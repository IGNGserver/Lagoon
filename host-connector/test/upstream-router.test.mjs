import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

// Fail rather than silently testing a local mock or skipping the upstream integration.
assert.ok(process.env.LAGOON_PATCHED_ROUTER, 'Build first and set LAGOON_PATCHED_ROUTER to artifacts/patched-router.mjs');
const bundlePath = resolve(process.env.LAGOON_PATCHED_ROUTER);
const { RpcRouter, NativeServicePeer, ConnectionController, NATIVE_SERVICE_CAPABILITY, createRpcRequest, createEvent, parseRemoteMessage } = await import(pathToFileURL(bundlePath));
const require = createRequire(import.meta.url);
const { WebSocketServer } = require('ws');
const CHUNK = 512 * 1024;
const encode = value => Buffer.from(value).toString('base64');
let nextRequest = 0;
function sink() {
  const messages = [], waiters = [];
  return {
    messages,
    async send(message) {
      parseRemoteMessage(message); messages.push(message);
      for (let i = waiters.length - 1; i >= 0; i--) if (waiters[i].predicate(message)) { waiters[i].resolve(message); waiters.splice(i, 1); }
    },
    wait(predicate) { return messages.find(predicate) ? Promise.resolve(messages.find(predicate)) : new Promise(resolveWait => waiters.push({ predicate, resolve: resolveWait })); },
  };
}
function router(nativePeer, logs = []) {
  const logger = { info: (...args) => logs.push(args), warn: (...args) => logs.push(args) };
  return new RpcRouter(128, logger, undefined, undefined, () => ['existing.capability'], undefined, undefined, undefined, undefined, undefined, undefined, nativePeer);
}
async function request(value, method, params = {}) {
  const message = createRpcRequest(method, params, `router-test-${++nextRequest}`);
  // Exercise upstream's actual wire parser, including v1, id and timestamp.
  assert.equal(message.v, 1); assert.ok(message.timestamp > 0);
  const result = await value.handle(parseRemoteMessage(JSON.parse(JSON.stringify(message))));
  parseRemoteMessage(result); assert.equal(result.payload.requestId, message.id);
  return result;
}
async function success(value, method, params = {}) {
  const response = await request(value, method, params);
  assert.equal(response.type, 'rpc.response', JSON.stringify(response)); return response.payload.result;
}
async function error(value, method, params, code, message) {
  const response = await request(value, method, params);
  assert.equal(response.type, 'rpc.error'); assert.equal(response.payload.code, code);
  if (message) assert.equal(response.payload.message, message);
  return response;
}
async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'lagoon-router-'));
  const registrationPath = join(root, 'service.json');
  const secret = 'router-fixture-secret-do-not-forward', requests = [], sockets = new Set(), streams = new Set();
  const large = Buffer.alloc(CHUNK + 19, 0x61);
  let mutations = 0;
  const server = http.createServer(async (req, res) => {
    requests.push({ path: req.url, method: req.method, authorization: req.headers.authorization });
    if (req.url === '/api/info') { res.end(JSON.stringify({ pid: process.pid, version: '2.2.6' })); return; }
    if (req.headers.authorization !== `Basic ${encode(`opencode:${secret}`)}`) { res.writeHead(401).end(); return; }
    if (req.url === '/api/project') { res.end('[]'); return; }
    if (req.url === '/openapi.json') {
      res.end(JSON.stringify({ paths: { '/api/project': { get: {} }, '/api/mutate': { post: {} }, '/api/ws': { get: {} } } })); return;
    }
    if (req.url === '/api/event') {
      streams.add(res); res.once('close', () => streams.delete(res));
      res.writeHead(200, { 'content-type': 'text/event-stream' }); res.write('id: first\nevent: changed\ndata: native update\n\n'); return;
    }
    if (req.url === '/api/mutate') {
      for await (const _chunk of req) { /* drain mutation body without recording it */ }
      mutations++; res.setHeader('set-cookie', `secret=${secret}`); res.setHeader('x-secret', secret);
      res.end(large); return;
    }
    res.writeHead(404).end();
  });
  server.on('connection', socket => { sockets.add(socket); socket.once('close', () => sockets.delete(socket)); });
  const wss = new WebSocketServer({ noServer: true });
  server.on('upgrade', (req, socket, head) => {
    if (req.url !== '/api/ws' || req.headers.authorization !== `Basic ${encode(`opencode:${secret}`)}`) { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, ws => { wss.emit('connection', ws); ws.on('message', (bytes, binary) => ws.send(bytes, { binary })); });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const localUrl = `http://127.0.0.1:${server.address().port}`;
  await writeFile(registrationPath, JSON.stringify({ url: localUrl, pid: process.pid, version: '2.2.6', password: secret, id: 'local-fixture' }));
  const peers = [];
  t.after(async () => {
    for (const peer of peers) peer.closeAll();
    for (const res of streams) res.destroy();
    for (const ws of wss.clients) ws.terminate();
    for (const socket of sockets) socket.destroy();
    await new Promise(done => wss.close(done)); await new Promise(done => server.close(done));
    assert.ok(isAbsolute(root) && root.startsWith(join(tmpdir(), 'lagoon-router-')));
    await rm(root, { recursive: true, force: true });
  });
  function makePeer(send = sink().send) {
    const value = new NativeServicePeer({ registrationPaths: [registrationPath], publish: (event, data) => send(createEvent(event, data)) }); peers.push(value); return value;
  }
  async function describe(value) {
    const result = await success(value, 'harness.services.describe');
    assert.equal(result.completeness, 'complete'); assert.equal(result.services.length, 1); assert.equal(result.services[0].available, true);
    return { serviceId: result.services[0].id, generation: result.services[0].generation };
  }
  return { registrationPath, secret, localUrl, requests, large, makePeer, describe, server, get mutations() { return mutations; } };
}

test('actual patched RpcRouter negotiates capability only for opt-in native peers; legacy Host stays supported', async t => {
  const f = await fixture(t), legacy = router(), native = router(f.makePeer());
  const old = await success(legacy, 'harness.transport.describe');
  assert.deepEqual(old.capabilities, ['existing.capability', 'remote.history.compact.v1']);
  const enabled = await success(native, 'harness.transport.describe');
  assert.equal(enabled.capabilities.filter(value => value === NATIVE_SERVICE_CAPABILITY).length, 1);
  for (const method of ['describe', 'call', 'stream.open', 'stream.send', 'stream.close', 'transfer.open', 'transfer.chunk', 'transfer.commit', 'transfer.read', 'transfer.close']) {
    await error(legacy, `harness.services.${method}`, {}, 'FEATURE_NOT_SUPPORTED', 'Native services are not enabled on this Host.');
  }
  await error(native, 'harness.services.arbitrary', {}, 'METHOD_NOT_FOUND');
  await f.describe(native);
});

test('actual router maps native errors to static typed non-retryable RPC errors without credentials', async t => {
  const f = await fixture(t), logs = [], value = router(f.makePeer(), logs), service = await f.describe(value), wire = [];
  wire.push(await error(value, 'harness.services.call', { ...service, path: '/private-not-in-openapi', method: 'GET', maxResponseBytes: CHUNK }, 'ROUTE_DENIED', 'Native service route is not allowed'));
  wire.push(await error(value, 'harness.services.call', { ...service, path: '/api/mutate', method: 'POST', maxResponseBytes: CHUNK, headers: { Authorization: f.secret } }, 'INVALID_PARAMS', 'Invalid native service parameters'));
  wire.push(await error(value, 'harness.services.stream.send', { streamId: 'not-owned', type: 'text', data: f.secret }, 'NOT_FOUND', 'Native service handle not found'));
  wire.push(await error(value, 'harness.services.call', { ...service, generation: 'stale', path: '/api/project', method: 'GET', maxResponseBytes: CHUNK }, 'STALE_SERVICE', 'Native service registration changed'));
  for (const response of wire) { assert.equal(response.payload.retryable, false); assert.equal(response.payload.details, undefined); }
  const description = await success(value, 'harness.services.describe');
  for (const privateValue of [f.secret, encode(`opencode:${f.secret}`), f.registrationPath, f.localUrl]) {
    assert.ok(!JSON.stringify([wire, logs, description]).includes(privateValue), `private Host configuration appeared on wire/logs: ${privateValue}`);
  }
  assert.equal(f.requests.filter(req => req.path === '/api/mutate').length, 0);
});

test('actual router dispatches mutation once and serves sequential cached binary transfers', async t => {
  const f = await fixture(t), value = router(f.makePeer()), service = await f.describe(value);
  const response = await success(value, 'harness.services.call', { ...service, path: '/api/mutate', method: 'POST', body: encode('once'), maxResponseBytes: 2 * CHUNK });
  assert.equal(f.mutations, 1); assert.equal(response.totalBytes, f.large.length); assert.equal(response.body, undefined);
  assert.equal(response.headers['set-cookie'], undefined); assert.equal(response.headers['x-secret'], undefined);
  await error(value, 'harness.services.transfer.read', { transferId: response.transferId, index: 1 }, 'INVALID_PARAMS');
  const parts = [];
  for (let index = 0; index < 2; index++) {
    const chunk = await success(value, 'harness.services.transfer.read', { transferId: response.transferId, index });
    assert.equal(chunk.index, index); assert.equal(chunk.totalChunks, 2); parts.push(Buffer.from(chunk.data, 'base64'));
  }
  assert.deepEqual(Buffer.concat(parts), f.large); assert.equal(f.mutations, 1);
  assert.deepEqual(await success(value, 'harness.services.transfer.close', { transferId: response.transferId }), { closed: true });
  await error(value, 'harness.services.transfer.read', { transferId: response.transferId, index: 0 }, 'NOT_FOUND');
  assert.equal(f.mutations, 1);
});

test('actual router opens SSE and binary/text WebSocket streams, routes send/close, and closes only its native peer', { timeout: 10_000 }, async t => {
  const f = await fixture(t), events = sink(), peer = f.makePeer(events.send), value = router(peer), service = await f.describe(value);
  const sibling = router(f.makePeer()), siblingService = await f.describe(sibling);
  let closeCount = 0; const closeAll = peer.closeAll.bind(peer);
  peer.closeAll = () => { closeCount++; closeAll(); };
  await success(value, 'harness.services.stream.open', { ...service, streamId: 'sse', kind: 'sse', path: '/api/event' });
  const event = await events.wait(message => message.payload.data?.type === 'event');
  assert.equal(event.payload.event, 'harness.services.frame'); assert.equal(event.payload.data.data, 'native update');
  await success(value, 'harness.services.stream.open', { ...service, streamId: 'ws', kind: 'websocket', path: '/api/ws' });
  for (const type of ['text', 'bytes']) {
    const data = type === 'text' ? 'remote text' : encode(Buffer.from([0, 255, 1]));
    assert.deepEqual(await success(value, 'harness.services.stream.send', { streamId: 'ws', type, data }), { accepted: true });
    await events.wait(message => message.payload.data?.streamId === 'ws' && message.payload.data.type === type && message.payload.data.data === data);
  }
  assert.deepEqual(await success(value, 'harness.services.stream.close', { streamId: 'sse' }), { closed: true });
  await events.wait(message => message.payload.data?.streamId === 'sse' && message.payload.data.type === 'closed');
  await value.closePeerStreams(); assert.equal(closeCount, 1);
  await error(value, 'harness.services.describe', {}, 'PEER_CLOSED', 'Native service connection is closed');
  assert.equal((await success(sibling, 'harness.services.call', { ...siblingService, path: '/api/project', method: 'GET', maxResponseBytes: CHUNK })).status, 200);
  assert.equal(f.server.listening, true);
  const wire = JSON.stringify(events.messages);
  for (const privateValue of [f.secret, encode(`opencode:${f.secret}`), f.registrationPath, f.localUrl]) assert.ok(!wire.includes(privateValue));
});

function channel(overrides = {}) {
  const outgoing = sink(), closes = []; let receive;
  return { peerDeviceId: 'trusted-device', peerIdentityKey: 'trusted-key', mode: 'Relay', security: { protocol: 'Noise_IK_25519_ChaChaPoly_SHA256', connectionId: 'test-connection', membershipId: 'test-membership' },
    send: outgoing.send, close: async code => { closes.push(code); }, onMessage: callback => { receive = callback; return () => { receive = undefined; }; },
    deliver: message => { assert.ok(receive); receive(message); }, outgoing, closes, ...overrides };
}

test('actual ConnectionController rejects missing Noise/untrusted identities before peer creation and owns router teardown', { timeout: 10_000 }, async t => {
  const f = await fixture(t); let peersCreated = 0;
  const identities = { isTrusted: (id, key) => id === 'trusted-device' && key === 'trusted-key' };
  const controller = new ConnectionController(identities, (_context, send) => { peersCreated++; return router(f.makePeer(send)); });
  t.after(() => controller.close());
  for (const [bad, code] of [[channel({ security: {} }), 'SECURE_CHANNEL_FAILED'], [channel({ peerIdentityKey: 'untrusted-key' }), 'PEER_IDENTITY_MISMATCH']]) {
    await assert.rejects(controller.accept(bad), failure => failure.code === code);
    assert.deepEqual(bad.closes, [code]); assert.equal(peersCreated, 0); assert.equal(f.requests.length, 0);
  }
  const accepted = channel(); await controller.accept(accepted); assert.equal(peersCreated, 1);
  const describe = createRpcRequest('harness.services.describe', {}, 'controller-describe'); accepted.deliver(parseRemoteMessage(describe));
  const response = await accepted.outgoing.wait(message => message.payload.requestId === describe.id);
  assert.equal(response.type, 'rpc.response'); assert.equal(response.payload.result.completeness, 'complete');
  const service = response.payload.result.services[0];
  const stream = createRpcRequest('harness.services.stream.open', { serviceId: service.id, generation: service.generation, streamId: 'owned-stream', kind: 'sse', path: '/api/event' }, 'controller-open');
  accepted.deliver(parseRemoteMessage(stream)); await accepted.outgoing.wait(message => message.payload.requestId === stream.id);
  await accepted.outgoing.wait(message => message.payload.data?.streamId === 'owned-stream' && message.payload.data.type === 'event');
  assert.equal(await controller.closeConnection('test-connection'), true); assert.equal(controller.connectionCount(), 0); assert.equal(accepted.closes.length, 1);
  assert.equal(f.server.listening, true); assert.equal(await controller.closeConnection('test-connection'), false);
});
