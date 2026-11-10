#!/usr/bin/env node
/**
 * Test-only stdio adapter. The Kotlin fake Host owns Noise; this process owns the real
 * NativeServicePeer/RpcRouter and an external, authenticated loopback OpenCode fixture.
 * Run from a scratch connector copy whose parent resolves ws, never by installing in source.
 */
import assert from 'node:assert/strict';
import http from 'node:http';
import { once, EventEmitter } from 'node:events';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';
import { createRequire } from 'node:module';

const configuredRouter = process.env.LAGOON_PATCHED_ROUTER || process.argv[2];
const module = await import(configuredRouter ? pathToFileURL(resolve(configuredRouter)) : '../native-services.mjs');
const { NativeServicePeer } = module;
const { WebSocketServer } = createRequire(import.meta.url)('ws');
const CHUNK = 512 * 1024;
const root = await mkdtemp(join(tmpdir(), 'lagoon-stdio-native-'));
const registrationPath = join(root, 'service.json');
const secret = 'stdio-fixture-password-never-forward';
const authorization = `Basic ${Buffer.from(`opencode:${secret}`).toString('base64')}`;
const sockets = new Set(), streams = new Set(), changes = new EventEmitter();
const requests = [], tasks = new Set();
const large = Buffer.from(Array.from({ length: CHUNK + 23 }, (_, index) => index % 251));
let title = 'Native fixture', renameCount = 0, largeMutations = 0, uploadCount = 0, revision = 0, stopping = false;
let peer, router, registration;
const session = () => ({ id: 'ses_native /one', title, location: { directory: '/repo' }, time: { created: 1, updated: 2 + renameCount } });
const paths = {
  '/api/project': { get: {} }, '/api/session': { get: {} },
  '/api/session/{sessionID}': { get: {}, patch: { requestBody: { content: { 'application/json': {
    schema: { type: 'object', properties: { title: { type: 'string' } }, required: ['title'] },
  } } } } },
  '/api/fs/read/*': { get: {} }, '/api/large': { get: {}, post: {} }, '/api/upload': { post: {} }, '/api/ws': { get: {} },
};
function json(res, value) { res.setHeader('content-type', 'application/json'); res.end(JSON.stringify(value)); }
const server = http.createServer(async (req, res) => {
  try {
    const path = new URL(req.url, 'http://127.0.0.1').pathname;
    requests.push({ method: req.method, path: req.url, authenticated: req.headers.authorization === authorization });
    if (path === '/api/info') { json(res, { pid: process.pid, version: '2.2.6' }); return; }
    if (req.headers.authorization !== authorization) { res.writeHead(401).end(); return; }
    if (path === '/api/project') { json(res, { data: [{ id: 'p_native', directory: '/repo', name: 'Native' }] }); return; }
    if (path === '/openapi.json') { json(res, { openapi: '3.1.0', paths }); return; }
    if (path === '/api/session') { json(res, { data: [session()], cursor: {} }); return; }
    if (path.startsWith('/api/session/')) {
      assert.equal(decodeURIComponent(path.slice('/api/session/'.length)), session().id);
      if (req.method === 'PATCH') {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        assert.deepEqual(Object.keys(body), ['title']); assert.equal(typeof body.title, 'string');
        title = body.title; renameCount++;
      }
      json(res, { data: session() }); return;
    }
    if (path === '/api/event') {
      streams.add(res); changes.emit('streams');
      res.once('close', () => { streams.delete(res); changes.emit('streams'); });
      res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8' }); res.flushHeaders();
      const payload = Buffer.from(`: fixture\r\nid: native-first\r\nevent: changed\r\ndata: ${JSON.stringify({
        type: 'session.updated', location: { directory: '/repo' }, data: { info: session(), marker: '界面' },
      })}\r\n\r\n`);
      // Split inside a UTF-8 codepoint to exercise Node's incremental SSE decoder, then Kotlin's JSON.
      const split = payload.indexOf(Buffer.from('界')) + 1;
      res.write(payload.subarray(0, split)); setImmediate(() => { if (!res.destroyed) res.write(payload.subarray(split)); });
      return;
    }
    if (path === '/api/upload') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks); uploadCount++;
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': body.length }); res.end(body); return;
    }
    if (path === '/api/large' || path === '/api/fs/read/large.bin') {
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      if (req.method === 'POST') { assert.deepEqual(Buffer.concat(chunks), Buffer.from([0, 1, 255])); largeMutations++; }
      res.writeHead(200, { 'content-type': 'application/octet-stream', 'content-length': large.length,
        'set-cookie': 'fixture=private', 'x-private': secret }); res.end(large); return;
    }
    res.writeHead(404).end();
  } catch { res.writeHead(500).end(); }
});
server.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
const wss = new WebSocketServer({ noServer: true });
server.on('upgrade', (req, socket, head) => {
  requests.push({ method: 'GET', path: req.url, authenticated: req.headers.authorization === authorization });
  if (req.url !== '/api/ws' || req.headers.authorization !== authorization) { socket.destroy(); return; }
  wss.handleUpgrade(req, socket, head, ws => {
    wss.emit('connection', ws); ws.send('early-native-frame'); ws.send(Buffer.from([0, 255]));
    ws.on('message', (bytes, binary) => ws.send(bytes, { binary }));
  });
});

// Every stdout line is a protocol message, never logging. Callback completion includes write flush.
function output(value) {
  return new Promise((done, reject) => process.stdout.write(`${JSON.stringify(value)}\n`, error => error ? reject(error) : done()));
}
function stats() { return { requests: [...requests], renameCount, largeMutations, uploadCount, activeSse: streams.size, title }; }
async function waitForStreams(count) {
  if (streams.size === count) return stats();
  await new Promise((done, reject) => {
    const changed = () => { if (streams.size === count) { cleanup(); done(); } };
    const timer = setTimeout(() => { cleanup(); reject(new Error('Fixture stream close timeout')); }, 5_000);
    const cleanup = () => { clearTimeout(timer); changes.off('streams', changed); };
    changes.on('streams', changed); changed();
  });
  return stats();
}
async function dispatch(message) {
  const { id, method, params = {} } = message;
  assert.equal(typeof id, 'string'); assert.equal(typeof method, 'string');
  if (method === 'fixture.stats') return stats();
  if (method === 'fixture.waitForStreams') return waitForStreams(params.count);
  if (method === 'fixture.rotate') {
    registration = { ...registration, id: `replacement-${++revision}` };
    await writeFile(registrationPath, JSON.stringify(registration), { mode: 0o600 }); return { rotated: true };
  }
  // Keep fixture controls off the Remote wire; bridge only actual native protocol methods.
  assert.ok(method.startsWith('harness.services.'));
  if (!router) return peer.call(method, params);
  const request = module.createRpcRequest(method, params, id);
  const response = await router.handle(module.parseRemoteMessage(JSON.parse(JSON.stringify(request))));
  module.parseRemoteMessage(response); assert.equal(response.payload.requestId, id);
  if (response.type === 'rpc.error') {
    const error = new Error(response.payload.message); error.code = response.payload.code; throw error;
  }
  assert.equal(response.type, 'rpc.response'); return response.payload.result;
}
async function cleanup() {
  peer?.closeAll();
  for (const stream of streams) stream.destroy();
  for (const ws of wss.clients) ws.terminate();
  for (const socket of sockets) socket.destroy();
  await new Promise(done => wss.close(done));
  await new Promise(done => server.close(done));
  // Delete only the exact temp root allocated above, never a caller-provided path.
  assert.equal(dirname(root), resolve(tmpdir())); assert.ok(root.startsWith(join(tmpdir(), 'lagoon-stdio-native-')));
  await rm(root, { recursive: true, force: true });
}
async function stop() {
  if (stopping) return; stopping = true;
  try { await cleanup(); await Promise.allSettled([...tasks]); }
  catch { process.exitCode = 1; }
}
try {
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  registration = { url: `http://127.0.0.1:${server.address().port}`, pid: process.pid, version: '2.2.6', id: 'stdio-fixture', password: secret };
  await writeFile(registrationPath, JSON.stringify(registration), { mode: 0o600 });
  peer = new NativeServicePeer({ registrationPaths: [registrationPath], publish: (event, data) => output({ type: 'event', event, data }) });
  if (configuredRouter) {
    const logger = { info() {}, warn() {}, debug() {}, error() {} };
    router = new module.RpcRouter(128, logger, undefined, undefined, () => [], undefined, undefined, undefined, undefined, undefined, undefined, peer);
  }
  await output({ type: 'ready', router: Boolean(router) });
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
  lines.on('line', line => {
    if (stopping) return;
    const task = (async () => {
      let request;
      try {
        assert.ok(line.length <= 2 * 1024 * 1024); request = JSON.parse(line);
        await output({ type: 'response', id: request.id, result: await dispatch(request) });
      } catch (error) {
        await output({ type: 'error', id: request?.id ?? '', code: error.code || 'FIXTURE_FAILED', message: error.code ? error.message : 'Native stdio fixture failed' });
      }
    })();
    tasks.add(task); task.finally(() => tasks.delete(task)).catch(() => { process.exitCode = 1; });
  });
  lines.once('close', () => { void stop(); });
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => { lines.close(); void stop(); });
} catch (error) {
  // No registration paths, local URLs, auth headers or credentials are included in protocol errors.
  process.stderr.write(`Native stdio fixture startup failed: ${error?.code || error?.name || 'Error'}\n`);
  await stop(); process.exitCode = 1;
}
