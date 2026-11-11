import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { mkdtemp, writeFile, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { WebSocketServer } from 'ws';
import { NativeServicePeer, NativeServiceError, NATIVE_SERVICE_CAPABILITY } from '../native-services.mjs';

const CHUNK = 512 * 1024, prefix = 'harness.services.';
const errorCode = code => error => error instanceof NativeServiceError && error.code === code;
const encode = s => Buffer.from(s).toString('base64');
const hashCode = s => Buffer.from(s).toString('hex');
function eventSink() {
  const frames = [], waiters = [];
  return {
    frames,
    async publish(name, frame) {
      assert.equal(name, `${prefix}frame`); frames.push(frame);
      for (let i = waiters.length - 1; i >= 0; i--) if (waiters[i].predicate(frame)) { waiters[i].resolve(frame); waiters.splice(i, 1); }
    },
    wait(predicate) { const frame = frames.find(predicate); return frame ? Promise.resolve(frame) : new Promise(resolve => waiters.push({ predicate, resolve })); },
  };
}
async function fixture(t, options = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'lagoon-native-'));
  const path = join(dir, 'service.json'), password = 'private-registration-secret';
  const requests = [], sockets = new Set(), streams = new Set(), peers = [];
  let mutations = 0, infoPid = process.pid;
  const large = Buffer.alloc(CHUNK + 23, 0x61);
  const paths = {
    '/api/project': { get: {} }, '/api/echo': { post: {}, patch: {}, delete: {} },
    '/api/large': { post: {} }, '/api/chunked': { get: {} }, '/api/redirect': { get: {} },
    '/api/ws': { get: {} }, '/api/hang': { get: {} }, '/api/item/{id}': { get: {} },
    '/api/session/{id}/message': { post: {} }, '/api/fs/read/*': { get: {} },
    '/api/pty/{ptyID}/connect-token': { post: {} },
  };
  const server = http.createServer(async (req, res) => {
    requests.push({ path: req.url, method: req.method, headers: req.headers });
    if (req.url === '/api/info') {
      if (options.dripInfo && requests.length > 1) {
        res.writeHead(401, { 'content-length': CHUNK }); res.write('x');
        const interval = setInterval(() => res.write('x'), 100);
        res.once('close', () => clearInterval(interval)); return;
      }
      if (options.dripInfo && requests.length === 1) { res.setHeader('content-length', 0); res.writeHead(401).end(); return; }
      if (options.protectedInfo && req.headers.authorization !== `Basic ${encode(`opencode:${password}`)}`) {
        res.setHeader('content-length', 0); res.writeHead(401).end(); return;
      }
      res.setHeader('content-type', 'application/json'); res.end(JSON.stringify({ pid: infoPid, version: '2.0.0' })); return;
    }
    if (req.headers.authorization !== `Basic ${encode(`opencode:${password}`)}`) { res.writeHead(401).end(); return; }
    if (req.url === '/api/project') { res.end('[]'); return; }
    if (req.url === '/openapi.json') { res.end(JSON.stringify({ openapi: '3.1.0', paths })); return; }
    if (req.url === '/api/redirect') { res.writeHead(302, { location: 'http://192.0.2.1/private' }).end(); return; }
    if (req.url === '/api/large') { mutations++; res.end(large); return; }
    if (req.url === '/api/chunked') { res.write(Buffer.alloc(CHUNK, 0x62)); res.end(Buffer.alloc(40, 0x63)); return; }
    if (req.url === '/api/event') {
      streams.add(res); res.on('close', () => streams.delete(res));
      res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8' }); res.flushHeaders();
      if (options.autoSse !== false) {
        const payload = Buffer.from(':comment\r\nid: event-1\r\nevent: changed\r\ndata: 界面\r\ndata: second\r\n\r\n');
        const split = payload.indexOf(Buffer.from('界')) + 1;
        res.write(payload.subarray(0, split)); setImmediate(() => res.write(payload.subarray(split)));
      }
      return;
    }
    if (req.url === '/api/hang') { res.writeHead(200); res.flushHeaders(); return; }
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    if (req.url === '/api/echo' && req.method === 'POST') mutations++;
    res.setHeader('content-type', 'application/octet-stream');
    res.setHeader('set-cookie', 'session=must-not-leak'); res.setHeader('x-private', password);
    res.end(Buffer.concat(chunks).length ? Buffer.concat(chunks) : Buffer.from('ok'));
  });
  server.on('connection', socket => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)); });
  const wss = new WebSocketServer({ noServer: true });
  server.on('upgrade', (req, socket, head) => {
    requests.push({ path: req.url, method: 'GET', headers: req.headers });
    if (req.url !== '/api/ws' || req.headers.authorization !== `Basic ${encode(`opencode:${password}`)}`) { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, ws => {
      wss.emit('connection', ws); ws.send('early-server-frame'); ws.send(Buffer.from([0, 255]));
      ws.on('message', (data, binary) => ws.send(data, { binary }));
    });
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const registration = { url: `http://127.0.0.1:${server.address().port}`, pid: infoPid, version: '2.0.0', id: 'local-instance', password };
  await writeFile(path, JSON.stringify(registration));
  t.after(async () => {
    for (const peer of peers) peer.closeAll(); for (const stream of streams) stream.destroy();
    for (const ws of wss.clients) ws.terminate(); for (const socket of sockets) socket.destroy();
    await new Promise(resolve => wss.close(resolve)); await new Promise(resolve => server.close(resolve));
    // The exact temporary root created above is the only deletion target.
    assert.ok(dir.startsWith(join(tmpdir(), 'lagoon-native-'))); await rm(dir, { recursive: true, force: true });
  });
  const peer = (extra = {}) => { const sink = eventSink(); const value = new NativeServicePeer({ publish: sink.publish, registrationPaths: [path], ...extra }); peers.push(value); return { value, sink }; };
  return { dir, path, registration, requests, server, large, peer, streams, get mutations() { return mutations; }, set infoPid(n) { infoPid = n; } };
}
async function service(peer) { const d = await peer.describe(); assert.equal(d.completeness, 'complete'); assert.equal(d.services[0].available, true); return { serviceId: d.services[0].id, generation: d.services[0].generation }; }
const call = (peer, s, path, extra = {}) => peer.call(`${prefix}call`, { ...s, path, method: 'GET', maxResponseBytes: 2 * CHUNK, ...extra });
const openStream = (peer, s, streamId, kind, path) => peer.call(`${prefix}stream.open`, { ...s, streamId, kind, path });
const openUpload = (peer, s, totalBytes, extra = {}) => peer.call(`${prefix}transfer.open`, { ...s, path: '/api/echo', method: 'POST', maxResponseBytes: 64 * 1024 * 1024, totalBytes, ...extra });
async function upload(peer, s, bytes, extra = {}) {
  const opened = await openUpload(peer, s, bytes.length, extra); assert.equal(opened.chunkBytes, CHUNK);
  for (let index = 0; index * CHUNK < bytes.length; index++) {
    assert.deepEqual(await peer.call(`${prefix}transfer.chunk`, { transferId: opened.transferId, index, data: bytes.subarray(index * CHUNK, (index + 1) * CHUNK).toString('base64') }), { accepted: true });
  }
  return opened;
}

test('real authenticated HTTP methods and binary, stable path identity, safe errors and headers', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer } = f.peer(), s = await service(peer);
  assert.equal(NATIVE_SERVICE_CAPABILITY, 'harness.services.v1');
  for (const method of ['POST', 'PATCH', 'DELETE']) {
    const bytes = Buffer.from([0, 1, 255]);
    const result = await call(peer, s, '/api/echo', { method, body: bytes.toString('base64'), headers: { 'Content-Type': 'application/octet-stream', Accept: '*/*' } });
    assert.equal(result.status, 200); assert.deepEqual(Buffer.from(result.body, 'base64'), bytes);
    assert.equal(result.headers['set-cookie'], undefined); assert.equal(result.headers['x-private'], undefined);
  }
  const description = await peer.call(`${prefix}describe`, {});
  assert.equal(description.services[0].id, s.serviceId); assert.ok(!JSON.stringify(description).includes(f.path));
  assert.ok(!JSON.stringify(description).includes(f.registration.password));
  assert.equal((await call(peer, s, '/api/info')).status, 200);
  const beforeMetadataStreams = f.requests.length;
  for (const kind of ['sse', 'websocket']) for (const path of ['/api/info', '/api/info?q=x', '/openapi.json']) {
    await assert.rejects(openStream(peer, s, `metadata-${kind}-${hashCode(path)}`, kind, path), errorCode('ROUTE_DENIED'));
  }
  assert.equal(f.requests.length, beforeMetadataStreams, 'invalid stream route must not issue a credential-bearing request');
  assert.ok(f.requests.filter(r => r.path === '/api/info').every(r => r.headers.authorization === undefined));
  assert.ok(f.requests.filter(r => r.path !== '/api/info').every(r => r.headers.authorization === `Basic ${encode(`opencode:${f.registration.password}`)}`));
  for (const key of ['Authorization', 'Cookie', 'Host', 'X-Header']) await assert.rejects(call(peer, s, '/api/project', { headers: { [key]: 'private' } }), errorCode('INVALID_PARAMS'));
  await assert.rejects(call(peer, s, '/api/echo', { method: 'POST', body: 'not base64' }), error => {
    assert.ok(!error.message.includes('not base64')); return errorCode('INVALID_PARAMS')(error);
  });
});

test('PTY ticket header is exact and restricted to the native POST ticket route', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer } = f.peer(), s = await service(peer);
  const response = await call(peer, s, '/api/pty/pty_safe/connect-token?location%5Bdirectory%5D=%2Frepo',
    { method: 'POST', body: encode('{}'), headers: { 'X-OpenCode-Ticket': '1' } });
  assert.equal(response.status, 200);
  assert.equal(f.requests.at(-1).headers['x-opencode-ticket'], '1');
  const before = f.requests.length;
  for (const [path, method, value] of [['/api/echo', 'POST', '1'], ['/api/pty/pty_safe/connect-token', 'GET', '1'], ['/api/pty/pty_safe/connect-token', 'POST', 'secret']]) {
    await assert.rejects(call(peer, s, path, { method, headers: { 'x-opencode-ticket': value } }), errorCode('INVALID_PARAMS'));
  }
  assert.equal(f.requests.length, before);
});

test('protected identity authenticates only on the same socket owned by the registered PID', { timeout: 10_000, skip: process.platform !== 'linux' }, async t => {
  const f = await fixture(t, { protectedInfo: true, realPid: true }), { value: peer } = f.peer();
  const s = await service(peer);
  assert.ok(f.requests.some(r => r.path === '/api/info' && !r.headers.authorization));
  assert.ok(f.requests.some(r => r.path === '/api/info' && r.headers.authorization));
  const result = await call(peer, s, '/api/echo', { method: 'POST', body: encode('once') });
  assert.equal(result.status, 200); assert.equal(Buffer.from(result.body, 'base64').toString(), 'once');
  assert.equal(f.mutations, 1);
  await openStream(peer, s, 'owned-events', 'sse', '/api/event');
  await peer.call(`${prefix}stream.close`, { streamId: 'owned-events' });
});

for (const protectedInfo of [true, false]) test(`stale PID cannot receive credentials even with ${protectedInfo ? 'protected' : 'spoofed public'} identity`, { timeout: 10_000, skip: process.platform !== 'linux' }, async t => {
  const f = await fixture(t, { protectedInfo }), { value: peer } = f.peer();
  f.infoPid = 1; // Attacker copies the stale registration's PID/version into its public response.
  await writeFile(f.path, JSON.stringify({ ...f.registration, pid: 1 }));
  const result = await peer.describe();
  assert.equal(result.completeness, 'partial'); assert.equal(result.services[0].available, false);
  assert.deepEqual(result.issues, [{ code: 'IDENTITY_UNVERIFIED' }]);
  assert.ok(f.requests.length > 0);
  assert.ok(f.requests.every(r => r.path === '/api/info' && r.headers.authorization === undefined));
  assert.equal(f.mutations, 0);
});

test('slow anonymous activation has an absolute proof deadline without credential disclosure', { timeout: 8_000, skip: process.platform !== 'linux' }, async t => {
  const f = await fixture(t, { dripInfo: true }), { value: peer } = f.peer();
  const started = Date.now(), result = await peer.describe();
  assert.ok(Date.now() - started < 7_000);
  assert.equal(result.services[0].available, false);
  assert.deepEqual(result.issues, [{ code: 'IDENTITY_UNVERIFIED' }]);
  assert.ok(f.requests.every(r => r.path === '/api/info' && r.headers.authorization === undefined));
  assert.equal(f.mutations, 0);
});

test('PID/version mismatch unavailable; generation changes invalidate call, transfer and stream', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer } = f.peer(), s = await service(peer);
  const transfer = await call(peer, s, '/api/large', { method: 'POST' });
  await openStream(peer, s, 'stale-stream', 'sse', '/api/event');
  f.infoPid = 999;
  const beforeMismatch = f.requests.length;
  const wrong = await peer.describe(); assert.equal(wrong.completeness, 'partial'); assert.equal(wrong.services[0].available, false);
  assert.deepEqual(wrong.issues, [{ code: 'SERVICE_MISMATCH' }]);
  const mismatchRequests = f.requests.slice(beforeMismatch);
  assert.ok(mismatchRequests.length > 0);
  assert.ok(mismatchRequests.every(r => r.path === '/api/info' && r.headers.authorization === undefined));
  f.infoPid = f.registration.pid; await service(peer);
  await writeFile(f.path, JSON.stringify({ ...f.registration, id: 'replacement' }));
  const count = f.requests.length;
  await assert.rejects(call(peer, s, '/api/project'), errorCode('STALE_SERVICE'));
  await assert.rejects(peer.call(`${prefix}transfer.read`, { transferId: transfer.transferId, index: 0 }), errorCode('NOT_FOUND'));
  assert.deepEqual(await peer.call(`${prefix}stream.close`, { streamId: 'stale-stream' }), { closed: true });
  assert.equal(f.requests.length, count);
  const changed = await service(peer); assert.equal(changed.serviceId, s.serviceId); assert.notEqual(changed.generation, s.generation);
});

test('close overtakes pending registration and cannot resurrect a native stream', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer, sink } = f.peer(), s = await service(peer);
  const before = f.requests.length;
  // open reserves its caller id synchronously, before its first filesystem await.
  const opening = openStream(peer, s, 'pending-cancel', 'sse', '/api/event');
  await peer.call(`${prefix}stream.close`, { streamId: 'pending-cancel' });
  await assert.rejects(opening, errorCode('STREAM_FAILED'));
  assert.equal(f.requests.length, before);
  assert.deepEqual(sink.frames.filter(x => x.streamId === 'pending-cancel').map(x => x.type), ['closed']);
  await peer.call(`${prefix}stream.close`, { streamId: 'close-before-open' });
  await assert.rejects(openStream(peer, s, 'close-before-open', 'websocket', '/api/ws'), errorCode('STREAM_FAILED'));
  await openStream(peer, s, 'independent', 'sse', '/api/event');
  await sink.wait(x => x.streamId === 'independent' && x.type === 'event');
});

test('discovery and inbound frames retire old generations without requiring client writes', { timeout: 10_000 }, async t => {
  const f = await fixture(t), a = f.peer(), s = await service(a.value);
  await openStream(a.value, s, 'observe', 'sse', '/api/event');
  await a.sink.wait(x => x.streamId === 'observe' && x.type === 'event');
  await writeFile(f.path, JSON.stringify({ ...f.registration, id: 'generation-b' }));
  const next = await service(a.value);
  assert.notEqual(next.generation, s.generation);
  await a.sink.wait(x => x.streamId === 'observe' && x.type === 'closed');
  assert.ok(a.sink.frames.some(x => x.streamId === 'observe' && x.code === 'STALE_SERVICE'));
  await openStream(a.value, next, 'ingress', 'sse', '/api/event');
  await a.sink.wait(x => x.streamId === 'ingress' && x.type === 'event');
  const active = [...f.streams].find(x => !x.destroyed);
  await writeFile(f.path, JSON.stringify({ ...f.registration, id: 'generation-c' }));
  active.write('data: forbidden-old-generation\n\n');
  await a.sink.wait(x => x.streamId === 'ingress' && x.type === 'closed');
  assert.ok(!a.sink.frames.some(x => x.data === 'forbidden-old-generation'));
  for (const id of ['observe', 'ingress']) {
    const frames = a.sink.frames.filter(x => x.streamId === id);
    assert.deepEqual(frames.map(x => x.seq), frames.map((_, i) => i));
  }
});

test('strict bounded registrations and default XDG discovery yield explicit partial issues', { timeout: 10_000 }, async t => {
  const f = await fixture(t);
  const badValues = [
    { ...f.registration, url: 'http://127.1:80' }, { ...f.registration, url: 'http://127.0.0.1.evil:80' },
    { ...f.registration, url: 'http://user:pass@127.0.0.1:80' }, { ...f.registration, url: 'http://127.0.0.1:80/?q=a' },
    { ...f.registration, url: 'http://127.0.0.1:80/\n' }, { ...f.registration, url: 'http://[::ffff:127.0.0.1]:80' },
    { ...f.registration, url: 'https://127.0.0.1:80' }, { ...f.registration, pid: 0 }, { ...f.registration, pid: '7251' },
    { ...f.registration, version: null }, { ...f.registration, extra: true },
  ];
  for (const value of badValues) {
    await writeFile(f.path, JSON.stringify(value)); const { value: peer } = f.peer();
    const d = await peer.describe(); assert.equal(d.completeness, 'partial'); assert.equal(d.services.length, 0); assert.equal(d.issues[0].code, 'REGISTRATION_INVALID');
  }
  await writeFile(f.path, '{"pid":1,"pid":2}'); assert.equal((await f.peer().value.describe()).issues[0].code, 'REGISTRATION_INVALID');
  await writeFile(f.path, ' '.repeat(16385)); assert.equal((await f.peer().value.describe()).issues[0].code, 'REGISTRATION_TOO_LARGE');
  const missing = f.peer({ registrationPaths: [join(f.dir, 'missing.json')] }).value;
  assert.deepEqual((await missing.describe()).issues, [{ code: 'REGISTRATION_MISSING' }]);
  await writeFile(f.path, JSON.stringify({ ...f.registration, url: f.registration.url.replace('127.0.0.1', 'localhost') })); await service(f.peer().value);
  const old = process.env.XDG_STATE_HOME; process.env.XDG_STATE_HOME = f.dir;
  t.after(() => { if (old === undefined) delete process.env.XDG_STATE_HOME; else process.env.XDG_STATE_HOME = old; });
  await mkdir(join(f.dir, 'opencode')); await writeFile(join(f.dir, 'opencode', 'service.json'), JSON.stringify(f.registration));
  await service(f.peer({ registrationPaths: undefined }).value);
});

test('OpenAPI allowlist, redirects, authority, traversal, double encoding and headers never escape loopback', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer } = f.peer(), s = await service(peer), before = f.requests.length;
  for (const path of ['http://example.com/x', '//example.com/x', '/api/../project', '/api/%2e%2e/project', '/api/%252e%252e/project', '/api/\\project', '/api/%2f%2fevil', '/api/project#x', '/api/project\n', '/api/session/x%2F..%2Fsecret/message', '/api/session/x%252f%252e%252e%252fsecret/message', '/api%2Fsession/x/message', '/api/fs/read/a%2Fb', '/api/not-in-schema'])
    await assert.rejects(call(peer, s, path), errorCode('ROUTE_DENIED'));
  await assert.rejects(call(peer, s, '/api/project', { method: 'DELETE' }), errorCode('ROUTE_DENIED'));
  assert.equal(f.requests.length, before);
  await assert.rejects(call(peer, s, '/api/redirect'), errorCode('REDIRECT_DENIED'));
  assert.equal(f.requests.at(-1).path, '/api/redirect');
  assert.equal((await call(peer, s, '/api/item/a?directory=%2Fhome')).status, 200);
  assert.equal((await call(peer, s, '/api/session/a%20b%2Fc/message', { method: 'POST', body: encode('native id') })).status, 200);
  assert.equal(f.requests.at(-1).path, '/api/session/a%20b%2Fc/message');
  assert.equal((await call(peer, s, '/api/fs/read/src/hello%20world.txt')).status, 200);
  assert.equal(f.requests.at(-1).path, '/api/fs/read/src/hello%20world.txt');
});

test('chunked oversized body aborts; large mutation runs once and only cached sequential chunks are read', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer } = f.peer(), s = await service(peer);
  await assert.rejects(call(peer, s, '/api/chunked', { maxResponseBytes: CHUNK }), errorCode('RESPONSE_TOO_LARGE'));
  const response = await call(peer, s, '/api/large', { method: 'POST', body: encode('mutation') });
  assert.equal(response.totalBytes, f.large.length); assert.equal(response.body, undefined); assert.equal(f.mutations, 1);
  const sibling = f.peer().value;
  await assert.rejects(sibling.call(`${prefix}transfer.read`, { transferId: response.transferId, index: 0 }), errorCode('NOT_FOUND'));
  await assert.rejects(peer.call(`${prefix}transfer.read`, { transferId: response.transferId, index: 1 }), errorCode('INVALID_PARAMS'));
  const chunks = [];
  for (let index = 0; index < 2; index++) {
    const chunk = await peer.call(`${prefix}transfer.read`, { transferId: response.transferId, index });
    assert.equal(chunk.totalChunks, 2); chunks.push(Buffer.from(chunk.data, 'base64'));
  }
  assert.deepEqual(Buffer.concat(chunks), f.large); assert.equal(f.mutations, 1);
  await peer.call(`${prefix}transfer.close`, { transferId: response.transferId });
  await assert.rejects(peer.call(`${prefix}transfer.read`, { transferId: response.transferId, index: 0 }), errorCode('NOT_FOUND'));
});

test('incoming transfer echoes large binary body, consumes commits once and enforces chunk protocol', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer } = f.peer(), s = await service(peer);
  const body = Buffer.alloc(9 * CHUNK + 71); for (let i = 0; i < body.length; i++) body[i] = i % 251;
  const opened = await upload(peer, s, body);
  const result = await peer.call(`${prefix}transfer.commit`, { transferId: opened.transferId });
  assert.equal(result.status, 200); assert.equal(result.totalBytes, body.length);
  const parts = [];
  for (let index = 0; index < Math.ceil(body.length / CHUNK); index++) {
    const response = await peer.call(`${prefix}transfer.read`, { transferId: result.transferId, index }); parts.push(Buffer.from(response.data, 'base64'));
  }
  assert.deepEqual(Buffer.concat(parts), body); assert.equal(f.mutations, 1);
  await assert.rejects(peer.call(`${prefix}transfer.commit`, { transferId: opened.transferId }), errorCode('NOT_FOUND'));
  assert.equal(f.mutations, 1);
  const incomplete = await openUpload(peer, s, 1);
  await assert.rejects(peer.call(`${prefix}transfer.commit`, { transferId: incomplete.transferId }), errorCode('INVALID_PARAMS'));
  await peer.call(`${prefix}transfer.close`, { transferId: incomplete.transferId });
  assert.equal(f.mutations, 1);
});

test('incoming transfer rejects malformed, unordered and over-budget chunks; cancellation and stale generation do not mutate', { timeout: 10_000 }, async t => {
  const f = await fixture(t), { value: peer } = f.peer(), s = await service(peer);
  const opened = await openUpload(peer, s, CHUNK + 1);
  await assert.rejects(peer.call(`${prefix}transfer.chunk`, { transferId: opened.transferId, index: 1, data: encode('x') }), errorCode('INVALID_PARAMS'));
  await assert.rejects(peer.call(`${prefix}transfer.chunk`, { transferId: opened.transferId, index: 0, data: encode('x') }), errorCode('INVALID_PARAMS'));
  await peer.call(`${prefix}transfer.close`, { transferId: opened.transferId });
  await assert.rejects(peer.call(`${prefix}transfer.chunk`, { transferId: opened.transferId, index: 0, data: encode('x') }), errorCode('NOT_FOUND'));
  await assert.rejects(openUpload(peer, s, 64 * 1024 * 1024 + 1), errorCode('RESOURCE_LIMIT'));
  const stale = await upload(peer, s, Buffer.from('no mutation'));
  await writeFile(f.path, JSON.stringify({ ...f.registration, id: 'new-generation' }));
  await assert.rejects(peer.call(`${prefix}transfer.commit`, { transferId: stale.transferId }), errorCode('STALE_SERVICE'));
  assert.equal(f.mutations, 0);
});

test('SSE incremental UTF8 CRLF/multiline and WebSocket early text/binary, send and cancellation are isolated', { timeout: 10_000 }, async t => {
  const f = await fixture(t), a = f.peer(), b = f.peer(), sa = await service(a.value), sb = await service(b.value);
  await openStream(a.value, sa, 'sse', 'sse', '/api/event');
  const event = await a.sink.wait(x => x.type === 'event');
  assert.deepEqual(event, { streamId: 'sse', seq: 1, type: 'event', id: 'event-1', event: 'changed', data: '界面\nsecond' });
  await openStream(a.value, sa, 'ws', 'websocket', '/api/ws');
  await a.sink.wait(x => x.streamId === 'ws' && x.type === 'bytes');
  assert.equal(a.sink.frames.find(x => x.streamId === 'ws' && x.type === 'text').data, 'early-server-frame');
  const earlyBytes = a.sink.frames.find(x => x.streamId === 'ws' && x.type === 'bytes'); assert.deepEqual(Buffer.from(earlyBytes.data, 'base64'), Buffer.from([0, 255]));
  await a.value.call(`${prefix}stream.send`, { streamId: 'ws', type: 'text', data: 'exactly once' });
  await a.sink.wait(x => x.type === 'text' && x.data === 'exactly once');
  await a.value.call(`${prefix}stream.send`, { streamId: 'ws', type: 'bytes', data: encode('binary echo') });
  await a.sink.wait(x => x.type === 'bytes' && x.data === encode('binary echo'));
  await openStream(b.value, sb, 'sibling', 'websocket', '/api/ws');
  await assert.rejects(b.value.call(`${prefix}stream.send`, { streamId: 'ws', type: 'text', data: 'wrong peer' }), errorCode('NOT_FOUND'));
  await a.value.call(`${prefix}stream.close`, { streamId: 'sse' });
  await a.value.call(`${prefix}stream.close`, { streamId: 'sse' });
  await a.value.call(`${prefix}stream.send`, { streamId: 'ws', type: 'close', code: 1000, reason: 'done' });
  await a.sink.wait(x => x.streamId === 'ws' && x.type === 'closed');
  a.value.closeAll();
  await b.value.call(`${prefix}stream.send`, { streamId: 'sibling', type: 'text', data: 'still alive' });
  await b.sink.wait(x => x.data === 'still alive');
  for (const id of ['sse', 'ws']) assert.deepEqual(a.sink.frames.filter(x => x.streamId === id).map(x => x.seq), a.sink.frames.filter(x => x.streamId === id).map((_, index) => index));
  assert.equal((await call(b.value, sb, '/api/project')).status, 200);
});

test('resource limits, transfer idle expiry, SSE backpressure and closeAll abort only owned operations', { timeout: 10_000 }, async t => {
  const f = await fixture(t, { autoSse: false }); let now = 1000;
  const a = f.peer({ now: () => now }), s = await service(a.value);
  for (let i = 0; i < 4; i++) await call(a.value, s, '/api/large', { method: 'POST' });
  await assert.rejects(call(a.value, s, '/api/large', { method: 'POST' }), errorCode('RESOURCE_LIMIT'));
  assert.equal(f.mutations, 4); now += 60_001;
  await call(a.value, s, '/api/large', { method: 'POST' }); assert.equal(f.mutations, 5);
  for (let i = 0; i < 16; i++) await openStream(a.value, s, `stream-${i}`, 'sse', '/api/event');
  await assert.rejects(openStream(a.value, s, 'overflow', 'sse', '/api/event'), errorCode('RESOURCE_LIMIT'));
  a.value.closeAll();
  const sink = eventSink(); let release, firstEvent;
  const seenEvent = new Promise(resolve => { firstEvent = resolve; });
  const blocked = f.peer({ publish: async (name, frame) => {
    await sink.publish(name, frame);
    if (frame.type === 'event' && frame.data === 'one') { firstEvent(); await new Promise(resolve => { release = resolve; }); }
  } });
  const sb = await service(blocked.value); await openStream(blocked.value, sb, 'backpressure', 'sse', '/api/event');
  const response = [...f.streams].at(-1); response.write('data: one\n\ndata: two\n\n'); await seenEvent;
  assert.equal(sink.frames.filter(x => x.type === 'event').length, 1); release();
  await sink.wait(x => x.type === 'event' && x.data === 'two');
  const pending = call(blocked.value, sb, '/api/hang');
  // Headers arrive before close; no process or service signal is needed for cancellation.
  const requested = new Promise(resolve => f.server.on('request', req => { if (req.url === '/api/hang') resolve(); }));
  await requested; blocked.value.closeAll();
  await assert.rejects(pending, errorCode('PEER_CLOSED'));
  const sibling = f.peer().value, ss = await service(sibling);
  assert.equal((await call(sibling, ss, '/api/project')).status, 200);
});
