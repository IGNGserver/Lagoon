import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { DshChangesPeer, DshChangesError, DSH_CHANGES_CAPABILITY } from '../dsh-changes.mjs';
import { createChangesHost } from './stdio-changes-host.mjs';

const bounded = { timeout: 5_000 }, MAX = 2 * 1024 * 1024;
const summary = { kind: 'summary', sessionId: 'session-a', seq: 7 };
const diff = { ...summary, kind: 'diff', index: 0 };
const owner = () => ({ principal: { kind: 'owner' }, signal: new AbortController().signal });
const rejected = (promise, code) => assert.rejects(promise, error => error instanceof DshChangesError && error.code === code);
function fixture(response = () => Response.json({ files: [], total: 0 }), options = {}) {
  let factories = 0, reads = 0; const requests = [], trusted = options.peer ?? owner();
  const connection = { createSharedFetchHandler(channel) {
    factories++; assert.equal(channel, '/api');
    return { async fetch(request, peer) { reads++; requests.push(request); assert.equal(peer, trusted); return response(request); } };
  } };
  const peer = new DshChangesPeer({ readConnection: () => connection, peer: trusted, ...options });
  return { peer, connection, trusted, requests, stats: () => ({ factories, reads }) };
}

test('availability requires live Host owner + current connection and never probes a handler', bounded, async () => {
  const f = fixture();
  assert.equal(f.peer.available, true); assert.equal(f.peer.available, true); assert.deepEqual(f.stats(), { factories: 0, reads: 0 });
  const aborted = new AbortController(); aborted.abort();
  for (const peer of [undefined, null, {}, { principal: { kind: 'guest' } }, { principal: { kind: 'owner' }, signal: aborted.signal }]) {
    const value = new DshChangesPeer({ readConnection: () => f.connection, peer });
    assert.equal(value.available, false);
    await rejected(value.call('harness.changes.read', summary), peer?.signal?.aborted ? 'PEER_CLOSED' : 'FEATURE_NOT_SUPPORTED');
  }
  let current;
  const value = new DshChangesPeer({ readConnection: () => current, peer: owner() });
  assert.equal(value.available, false); current = f.connection; assert.equal(value.available, true);
  current = undefined; assert.equal(value.available, false);
  await rejected(value.call('harness.changes.read', summary), 'FEATURE_NOT_SUPPORTED');
  assert.equal(new DshChangesPeer({ readConnection: () => { throw new Error('secret'); }, peer: owner() }).available, false);
  assert.deepEqual(f.stats(), { factories: 0, reads: 0 });
  f.peer.closeAll(); assert.equal(f.peer.available, false);
});

test('only fixed internal GET routes encode selectors and pass exact Host-created Peer without credentials', bounded, async () => {
  const f = fixture(() => Response.json({ arbitrary: { official: true }, extra: 1 }));
  const sessionId = 'session /?&=#%界';
  assert.deepEqual(await f.peer.call('harness.changes.read', { ...summary, sessionId }),
    { status: 200, value: { arbitrary: { official: true }, extra: 1 } });
  await f.peer.call('harness.changes.read', { ...diff, sessionId, seq: Number.MAX_SAFE_INTEGER, index: 4999 });
  for (const [index, request] of f.requests.entries()) {
    const url = new URL(request.url); assert.equal(url.origin, 'http://127.0.0.1');
    assert.equal(url.pathname, index ? '/api/changes.diff' : '/api/changes.summary');
    assert.deepEqual([...url.searchParams.keys()], index ? ['sessionId', 'seq', 'index'] : ['sessionId', 'seq']);
    assert.equal(url.searchParams.get('sessionId'), sessionId);
    assert.equal(url.searchParams.get('seq'), index ? String(Number.MAX_SAFE_INTEGER) : '7');
    assert.equal(request.method, 'GET'); assert.equal(request.redirect, 'error'); assert.equal(request.body, null);
    assert.deepEqual([...request.headers], []);
  }
  f.peer.closeAll();
});

test('strict selectors reject URL/path/method/auth/peer fields, invalid integers and controls before dispatch', bounded, async () => {
  const f = fixture();
  const bad = [null, [], {}, { ...summary, kind: 'file' }, { ...summary, index: undefined },
    { ...diff, index: undefined }, { kind: 'diff', sessionId: 'a', seq: 1 },
    ...['url', 'path', 'method', 'headers', 'auth', 'peer', 'timeoutMs', 'endpoint'].map(key => ({ ...summary, [key]: 'private-secret' })),
    ...['', 'a'.repeat(513), 'nul\0', 'line\n', 'del\x7f', 'c1\x85'].map(sessionId => ({ ...summary, sessionId })),
    ...[0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1, '7'].map(seq => ({ ...summary, seq })),
    ...[-1, 5000, 0.5, Number.MAX_SAFE_INTEGER + 1, '0'].map(index => ({ ...diff, index })),
  ];
  for (const params of bad) await rejected(f.peer.call('harness.changes.read', params), 'INVALID_PARAMS');
  await rejected(f.peer.call('harness.changes.write', summary), 'METHOD_NOT_FOUND');
  assert.deepEqual(f.stats(), { factories: 0, reads: 0 });
  f.peer.closeAll();
});

test('404 stays expired, authorization/conflict/internal failures are static typed errors, never empty success', bounded, async () => {
  for (const [status, code] of [[404, null], [401, 'NOT_AUTHORIZED'], [403, 'NOT_AUTHORIZED'],
    [409, 'RESOURCE_UNAVAILABLE'], [503, 'RESOURCE_UNAVAILABLE'], [500, 'INTERNAL'], [204, 'INTERNAL']]) {
    const f = fixture(() => new Response(status === 204 ? null : 'private credential/path payload', { status }));
    if (code) await rejected(f.peer.call('harness.changes.read', summary), code);
    else assert.deepEqual(await f.peer.call('harness.changes.read', summary), { status: 404 });
    f.peer.closeAll();
  }
  const f = fixture(() => { throw new Error('/private/path?auth=secret'); });
  await assert.rejects(f.peer.call('harness.changes.read', summary), error => {
    assert.equal(error.code, 'INTERNAL'); assert.equal(error.message, 'The Host could not read changes.');
    assert.equal(error.cause, undefined); assert.ok(!JSON.stringify(error).includes('secret')); return true;
  }); f.peer.closeAll();
});

test('response gate rejects redirects, wrong MIME, fatal UTF8, non-object/trailing JSON and both size bounds', bounded, async () => {
  let cancelled = 0;
  const oversized = () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(MAX)); controller.enqueue(new Uint8Array(1)); },
    cancel() { cancelled++; },
  }), { headers: { 'content-type': 'application/json' } });
  for (const [make, code] of [
    [() => Response.redirect('http://secret.invalid/private', 302), 'REDIRECT_DENIED'],
    [() => new Response('{}', { headers: { 'content-type': 'text/html' } }), 'INTERNAL'],
    [() => new Response(new Uint8Array([0x7b, 0xff, 0x7d]), { headers: { 'content-type': 'application/json' } }), 'INTERNAL'],
    ...['[]', 'null', 'true', '{} {}', '{"broken":'].map(text => [() => new Response(text, { headers: { 'content-type': 'application/json' } }), 'INTERNAL']),
    ...[String(MAX + 1), '-1', 'invalid'].map(length => [() => new Response('{}', { headers: { 'content-type': 'application/json', 'content-length': length } }), 'RESPONSE_TOO_LARGE']),
    [oversized, 'RESPONSE_TOO_LARGE'],
  ]) {
    const f = fixture(make); await rejected(f.peer.call('harness.changes.read', summary), code); f.peer.closeAll();
  }
  assert.equal(cancelled, 1);
  const exact = fixture(() => new Response(`{"v":"${'a'.repeat(MAX - 8)}"}`, { headers: { 'content-type': 'application/json; charset=utf-8', 'content-length': String(MAX) } }));
  assert.equal((await exact.peer.call('harness.changes.read', summary)).value.v.length, MAX - 8); exact.peer.closeAll();
});

test('four concurrent reads are bounded; close and owner abort cancel reads without closing sibling/Host', bounded, async () => {
  const signals = [], f = fixture(request => { signals.push(request.signal); return new Promise(() => {}); });
  const pending = Array.from({ length: 4 }, () => f.peer.call('harness.changes.read', summary));
  const outcomes = pending.map(promise => rejected(promise, 'PEER_CLOSED'));
  await rejected(f.peer.call('harness.changes.read', summary), 'RESOURCE_LIMIT');
  assert.equal(f.stats().reads, 4); f.peer.closeAll(); await Promise.all(outcomes);
  assert.equal(signals.every(signal => signal.aborted), true);
  assert.equal(f.trusted.signal.aborted, false);
  const sibling = fixture(); assert.equal((await sibling.peer.call('harness.changes.read', summary)).status, 200); sibling.peer.closeAll();
  const lifetime = new AbortController(), aborted = fixture(() => new Promise(() => {}), { peer: { principal: { kind: 'owner' }, signal: lifetime.signal } });
  const result = rejected(aborted.peer.call('harness.changes.read', summary), 'PEER_CLOSED'); lifetime.abort(); await result;
  assert.equal(aborted.peer.available, false); aborted.peer.closeAll();
});

test('absolute deadline covers uncooperative headers and a stalled/slow body, cancelling in-flight readers', bounded, async () => {
  const header = fixture(() => new Promise(() => {}), { timeoutMs: 50 });
  const start = performance.now(); await rejected(header.peer.call('harness.changes.read', summary), 'TIMEOUT');
  assert.ok(performance.now() - start < 1000); assert.equal(header.requests[0].signal.aborted, true); header.peer.closeAll();
  let cancelled = 0;
  const body = fixture(() => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode('{')); }, cancel() { cancelled++; },
  }), { headers: { 'content-type': 'application/json' } }), { timeoutMs: 50 });
  await rejected(body.peer.call('harness.changes.read', summary), 'TIMEOUT');
  assert.equal(cancelled, 1); body.peer.closeAll();
  assert.throws(() => fixture(undefined, { timeoutMs: 10001 }), error => error.code === 'INVALID_PARAMS');
});

test('actual patched Router negotiates only available changes, delegates reads/errors and owns teardown; stdio seam supplies snapshot', bounded, async t => {
  assert.ok(process.env.LAGOON_PATCHED_ROUTER, 'Build and set LAGOON_PATCHED_ROUTER for actual router integration');
  const upstream = await import(pathToFileURL(resolve(process.env.LAGOON_PATCHED_ROUTER)));
  assert.equal(upstream.DSH_CHANGES_CAPABILITY, DSH_CHANGES_CAPABILITY);
  const logs = [], logger = { info: (...v) => logs.push(v), warn: (...v) => logs.push(v) };
  const f = fixture();
  // Use bundled class: instanceof in actual Router must not accept a different module's error class.
  let live = true, routeStatus = 200;
  const changes = new upstream.DshChangesPeer({ readConnection: () => live ? { createSharedFetchHandler: () => ({ fetch: async () => new Response('{"native":true}', { status: routeStatus, headers: { 'content-type': 'application/json' } }) }) } : undefined, peer: owner() });
  const router = new upstream.RpcRouter(128, logger, undefined, undefined, () => ['legacy'], undefined, undefined, undefined, undefined, undefined, undefined, undefined, changes);
  const legacy = new upstream.RpcRouter(128, logger, undefined, undefined, () => ['legacy']);
  let serial = 0;
  const wire = async (target, method, params = {}) => {
    const response = await target.handle(upstream.parseRemoteMessage(upstream.createRpcRequest(method, params, `changes-${++serial}`)));
    upstream.parseRemoteMessage(response); return response;
  };
  assert.ok((await wire(router, 'harness.transport.describe')).payload.result.capabilities.includes(DSH_CHANGES_CAPABILITY));
  assert.ok(!(await wire(legacy, 'harness.transport.describe')).payload.result.capabilities.includes(DSH_CHANGES_CAPABILITY));
  assert.equal((await wire(legacy, 'harness.changes.read', summary)).payload.code, 'FEATURE_NOT_SUPPORTED');
  assert.deepEqual((await wire(router, 'harness.changes.read', summary)).payload.result, { status: 200, value: { native: true } });
  for (const [params, status, code] of [[{ ...summary, endpoint: 'private-secret-path' }, 200, 'INVALID_PARAMS'], [summary, 403, 'NOT_AUTHORIZED'], [summary, 500, 'INTERNAL']]) {
    routeStatus = status; const response = await wire(router, 'harness.changes.read', params);
    assert.equal(response.type, 'rpc.error'); assert.equal(response.payload.code, code); assert.equal(response.payload.retryable, false); assert.equal(response.payload.details, undefined);
  }
  assert.ok(!JSON.stringify(logs).includes('private-secret-path'));
  live = false; assert.ok(!(await wire(router, 'harness.transport.describe')).payload.result.capabilities.includes(DSH_CHANGES_CAPABILITY));
  assert.equal((await wire(router, 'harness.changes.read', summary)).payload.code, 'FEATURE_NOT_SUPPORTED');
  await router.closePeerStreams(); assert.equal(changes.available, false); f.peer.closeAll();
  const host = await createChangesHost(); t.after(() => host.close());
  const call = (method, params = {}) => host.dispatch({ id: `fixture-${++serial}`, method, params });
  await call('harness.remote.stream.open', { streamId: 'snapshot', endpoint: 'session/follow', payload: { args: { sessionId: 's' } } });
  assert.equal(host.events.find(item => item.data.value?.type === 'snapshot').data.value.records[0].event.seq, 7);
  assert.equal((await call('harness.changes.read', summary)).value.files[0].path, 'src/main.kt');
  assert.equal((await call('harness.changes.read', diff)).value.hunks[0].lines[1], '+new');
  assert.equal(host.stats().readCount, 2); await call('fixture.disable');
  assert.ok(!(await call('harness.transport.describe')).capabilities.includes(DSH_CHANGES_CAPABILITY)); assert.equal(host.stats().readCount, 2);
});
