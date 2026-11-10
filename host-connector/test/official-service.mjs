import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { NativeServicePeer, NativeServiceError } from '../native-services.mjs';

// Caller owns an isolated, checksum-verified official OpenCode 2.2.6 daemon.
// LAGOON_OFFICIAL_OPENCODE_REGISTRATION=/absolute/path/service.json node --test test/official-service.mjs
// Never dump the registration, credentials, request headers, or daemon response bodies.
const registrationPath = process.env.LAGOON_OFFICIAL_OPENCODE_REGISTRATION;
const prefix = 'harness.services.';
const denied = error => error instanceof NativeServiceError && error.code === 'ROUTE_DENIED';

test('official 2.2.6 native companion discovery, authenticated reads, route policy and SSE', {
  skip: registrationPath === undefined ? 'Set LAGOON_OFFICIAL_OPENCODE_REGISTRATION to enable live official test' : false,
  timeout: 30_000,
}, async t => {
  assert.ok(isAbsolute(registrationPath), 'Official registration must be absolute');
  const metadata = await stat(registrationPath);
  assert.ok(metadata.isFile() && metadata.size <= 16 * 1024, 'Invalid official registration file');
  let registration;
  try { registration = JSON.parse(await readFile(registrationPath, 'utf8')); }
  catch { throw new Error('Invalid official registration'); }
  assert.ok(typeof registration.password === 'string' && registration.password.length > 0, 'Password-protected service required');
  assert.ok(/^http:\/\/(127\.0\.0\.1|localhost|\[::1\]):[1-9][0-9]{0,4}\/?$/.test(registration.url), 'Loopback registration required');
  const frames = [];
  const peer = new NativeServicePeer({
    registrationPaths: [registrationPath],
    publish: async (name, frame) => { assert.equal(name, `${prefix}frame`); frames.push(frame); },
  });
  t.after(() => peer.closeAll());
  // Actual release binary protects info as well; this is intentionally not the public-info fixture.
  for (const path of ['/api/info', '/api/project']) {
    const response = await fetch(`${registration.url.replace(/\/$/, '')}${path}`, { redirect: 'manual', signal: AbortSignal.timeout(10_000) });
    assert.equal(response.status, 401); await response.body?.cancel();
  }
  const description = await peer.describe();
  assert.equal(description.completeness, 'complete');
  assert.equal(description.services.length, 1);
  const service = description.services[0];
  assert.equal(service.available, true);
  assert.equal(service.protocol, 'opencode-native');
  assert.ok(typeof service.generation === 'string' && service.generation.length > 0);
  assert.ok(service.capabilities.includes('http') && service.capabilities.includes('sse'));
  assert.ok(!JSON.stringify(description).includes(registration.password), 'Discovery must not expose credentials');
  const binding = { serviceId: service.id, generation: service.generation };
  const call = (path, method = 'GET') => peer.call(`${prefix}call`, { ...binding, path, method, maxResponseBytes: 8 * 1024 * 1024 });
  const json = async path => {
    const response = await call(path);
    assert.equal(response.status, 200);
    if (response.body !== undefined) return JSON.parse(Buffer.from(response.body, 'base64').toString('utf8'));
    const chunks = [];
    try {
      for (let index = 0; index * 512 * 1024 < response.totalBytes; index++) {
        const chunk = await peer.call(`${prefix}transfer.read`, { transferId: response.transferId, index });
        chunks.push(Buffer.from(chunk.data, 'base64'));
      }
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } finally { await peer.call(`${prefix}transfer.close`, { transferId: response.transferId }); }
  };
  // Companion may authenticate info only after its local PID/socket ownership safety proof.
  const info = await json('/api/info');
  assert.equal(info.version, '2.2.6');
  assert.ok(info.pid === registration.pid, 'Official identity must match the registration');
  const projects = await json('/api/project');
  assert.ok(Array.isArray(projects) || Array.isArray(projects.data), 'Official project list shape');
  const schema = await json('/openapi.json');
  assert.ok(typeof schema.openapi === 'string' && typeof schema.paths === 'object');
  assert.ok(schema.paths['/api/project']?.get, 'Official schema must document protected projects');
  for (const [path, method] of [['/api/info', 'DELETE'], ['/api/project', 'TRACE'], ['/api/../project', 'GET']]) {
    const code = method === 'TRACE' ? 'INVALID_PARAMS' : 'ROUTE_DENIED';
    await assert.rejects(call(path, method), error => error instanceof NativeServiceError && error.code === code);
  }
  await assert.rejects(peer.call(`${prefix}stream.open`, { ...binding, streamId: 'metadata-denied', kind: 'sse', path: '/api/info' }), denied);
  const opened = await peer.call(`${prefix}stream.open`, { ...binding, streamId: 'official-events', kind: 'sse', path: '/api/event' });
  assert.equal(opened.streamId, 'official-events');
  assert.ok(frames.some(frame => frame.streamId === opened.streamId && frame.type === 'open'));
  assert.deepEqual(await peer.call(`${prefix}stream.close`, { streamId: opened.streamId }), { closed: true });
  assert.ok(frames.some(frame => frame.streamId === opened.streamId && frame.type === 'closed'));
  const again = await peer.describe();
  assert.equal(again.services[0]?.available, true, 'Closing SSE must leave caller-owned daemon running');
  assert.ok(again.services[0]?.generation === service.generation, 'Read-only operations must retain generation');
});
