#!/usr/bin/env node
/** Processless fixture: actual pinned RpcRouter + changes peer, fake official Connection and history. */
import assert from 'node:assert/strict';
import { createInterface } from 'node:readline';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const SESSION = 'session-changes-a';
const SUMMARY = { turn: 1, files: [{ path: 'src/main.kt', display: 'src/main.kt', added: 1, deleted: 1 }], total: 1, added: 1, deleted: 1 };
const DIFF = { path: 'src/main.kt', display: 'src/main.kt', kind: 'text', before: true, after: true, coarse: false,
  hunks: [{ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['-old', '+new'] }] };
const clone = value => JSON.parse(JSON.stringify(value));

export async function createChangesHost({ bundle = process.env.LAGOON_PATCHED_ROUTER, publish = async () => {} } = {}) {
  assert.ok(bundle, 'Set LAGOON_PATCHED_ROUTER to the verified upstream entry');
  const upstream = await import(pathToFileURL(resolve(bundle)));
  for (const name of ['RpcRouter', 'DshChangesPeer', 'DshChangesError']) assert.equal(typeof upstream[name], 'function', name);
  const lifetime = new AbortController(), peer = { principal: { kind: 'owner' }, signal: lifetime.signal };
  const streams = new Map(), events = [], paths = [];
  let enabled = true, readCount = 0, factories = 0, status = 200;
  const connection = { createSharedFetchHandler(channel) {
    assert.equal(channel, '/api'); factories++;
    return { async fetch(request, suppliedPeer) {
      assert.equal(suppliedPeer, peer); assert.equal(request.method, 'GET'); assert.equal(request.redirect, 'error');
      assert.deepEqual([...request.headers], []);
      const url = new URL(request.url); assert.equal(url.origin, 'http://127.0.0.1');
      assert.ok(['/api/changes.summary', '/api/changes.diff'].includes(url.pathname));
      assert.equal(url.searchParams.get('seq'), '7'); assert.ok(url.searchParams.get('sessionId'));
      assert.deepEqual([...url.searchParams.keys()], url.pathname.endsWith('.diff') ? ['sessionId', 'seq', 'index'] : ['sessionId', 'seq']);
      if (url.pathname.endsWith('.diff')) assert.equal(url.searchParams.get('index'), '0');
      readCount++; paths.push(url.pathname);
      if (status !== 200) return new Response(null, { status });
      return Response.json(clone(url.pathname.endsWith('.summary') ? SUMMARY : DIFF));
    } };
  } };
  const changes = new upstream.DshChangesPeer({ readConnection: () => enabled ? connection : undefined, peer });
  const emit = async (event, data) => { const item = { type: 'event', event, data }; events.push(clone(item)); await publish(item); };
  // Only this session/history seam is fake. Changes reads below always pass through the actual router.
  const history = {
    async call(params) {
      if (params.endpoint === 'session/get') return { ok: true, value: { id: params.payload?.args?.sessionId ?? SESSION, cwd: '/repo', title: 'Changes fixture' } };
      return { ok: true, value: {} };
    },
    async openStream(params) {
      assert.equal(typeof params.streamId, 'string');
      assert.ok(['session/follow', '$events'].includes(params.endpoint)); streams.set(params.streamId, true);
      if (params.endpoint === 'session/follow') {
        const sessionId = params.payload?.args?.request?.address?.sessionId ?? params.payload?.args?.sessionId ?? SESSION;
        await emit('harness.remote.frame', { streamId: params.streamId, hasValue: true, value: {
          type: 'snapshot', header: { version: 4, id: sessionId, cwd: '/repo', title: 'Changes fixture', createdAt: 1 },
          cursor: 7, hasMore: false,
          records: [{ type: 'event', event: { seq: 7, type: 'workspace/changes', time: 1, data: { turn: 1 } } }],
        } });
      } else await emit('harness.remote.frame', { streamId: params.streamId, hasValue: true, value: { type: 'ready' } });
      return { streamId: params.streamId };
    },
    async closeStream(params) { streams.delete(params.streamId); return { closed: true, streamId: params.streamId }; },
    closeAll() { streams.clear(); },
  };
  const router = new upstream.RpcRouter(128, undefined, undefined, history, () => ['harness.remote.v3'],
    undefined, undefined, undefined, undefined, undefined, undefined, undefined, changes);
  const stats = () => ({ readCount, factories, paths: [...paths], enabled, activeStreams: streams.size });
  return { router, changes, events, stats,
    async dispatch({ id, method, params = {} }) {
      assert.ok(typeof id === 'string' || typeof id === 'number'); assert.equal(typeof method, 'string');
      if (method === 'fixture.stats') return stats();
      if (method === 'fixture.disable') { enabled = false; return { enabled }; }
      if (method === 'fixture.status') { assert.ok([200, 401, 403, 404, 409, 500].includes(params.status)); status = params.status; return { status }; }
      assert.ok(['harness.transport.describe', 'harness.changes.read', 'harness.remote.call', 'harness.remote.stream.open', 'harness.remote.stream.close', 'remote.history.configure'].includes(method));
      const response = await router.handle(upstream.parseRemoteMessage(JSON.parse(JSON.stringify(upstream.createRpcRequest(method, params, String(id))))));
      upstream.parseRemoteMessage(response); assert.equal(response.payload.requestId, String(id));
      if (response.type === 'rpc.error') throw Object.assign(new Error(response.payload.message), { code: response.payload.code });
      assert.equal(response.type, 'rpc.response'); return response.payload.result;
    },
    async close() { await router.closePeerStreams(); lifetime.abort(); },
  };
}

async function stdio() {
  const output = value => new Promise((done, reject) => process.stdout.write(`${JSON.stringify(value)}\n`, error => error ? reject(error) : done()));
  const host = await createChangesHost({ bundle: process.env.LAGOON_PATCHED_ROUTER || process.argv[2], publish: output });
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity }), tasks = new Set();
  let stopping = false;
  const stop = async () => { if (stopping) return; stopping = true; await host.close(); await Promise.allSettled([...tasks]); };
  lines.on('line', line => {
    if (stopping) return;
    const task = (async () => {
      let request;
      try {
        assert.ok(line.length <= 256 * 1024); request = JSON.parse(line);
        await output({ type: 'response', id: request.id, result: await host.dispatch(request) });
      } catch (error) {
        const safe = ['INVALID_PARAMS', 'METHOD_NOT_FOUND', 'FEATURE_NOT_SUPPORTED', 'NOT_AUTHORIZED', 'RESOURCE_UNAVAILABLE', 'INTERNAL', 'REDIRECT_DENIED', 'RESPONSE_TOO_LARGE', 'RESOURCE_LIMIT', 'TIMEOUT', 'PEER_CLOSED'];
        await output({ type: 'error', id: request?.id ?? '', code: safe.includes(error.code) ? error.code : 'FIXTURE_FAILED', message: 'Host changes request refused.' });
      }
    })();
    tasks.add(task); task.finally(() => tasks.delete(task)).catch(() => { process.exitCode = 1; });
  });
  lines.once('close', () => { void stop(); });
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => { lines.close(); void stop(); });
  await output({ type: 'ready', router: true, changes: true });
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await stdio().catch(() => { process.stderr.write('Changes stdio fixture startup failed.\n'); process.exitCode = 1; });
}
