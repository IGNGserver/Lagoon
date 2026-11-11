import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { DshChangesPeer } from '../dsh-changes.mjs';

// Opt-in source-runtime contract: actual official authentication and route handlers, fake domain data.
// No daemon/model/filesystem mutation or credentials are needed to exercise these native handlers.
const root = process.env.LAGOON_OFFICIAL_DSH_PACKAGE_ROOT;
test('fixed official Connection authentication and deliverables handlers serve only exact changes GETs', {
  skip: root === undefined ? 'Set LAGOON_OFFICIAL_DSH_PACKAGE_ROOT for installed official route runtime' : false,
  timeout: 10_000,
}, async () => {
  const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
  assert.equal(pkg.version, '0.2.1-rc.5');
  const base = join(root, 'node_modules', '@deepseek-ai');
  const { HostConnectionService } = await import(pathToFileURL(join(base, 'dsh-client-connection/lib/index.js')));
  const { apply } = await import(pathToFileURL(join(base, 'dsh-client-ui-deliverables/lib/index.js')));
  const routes = new Map();
  const summary = { turn: 1, files: [{ path: '/host/private/path', display: 'file.txt', added: 1, deleted: 1 }], total: 1, added: 1, deleted: 1 };
  const diff = { path: '/host/private/path', display: 'file.txt', kind: 'text', before: true, after: true, coarse: false,
    hunks: [{ oldStart: 1, oldLines: 1, newStart: 1, newLines: 1, lines: ['-old', '+new'] }] };
  const calls = [];
  apply({
    connection: { fetch: { register(route) { routes.set(route.path, { ...route, methods: new Set(route.methods) }); } } },
    effect() {},
    systemPrompt: { section() {}, getSectionOrder() { return 1; } },
    workspaceChanges: {
      summary(sessionId, seq) { calls.push(['summary', sessionId, seq]); return sessionId === 'owned' && seq === 7 ? summary : undefined; },
      async diff(sessionId, seq, index, signal) { signal.throwIfAborted(); calls.push(['diff', sessionId, seq, index]); return sessionId === 'owned' && seq === 7 && index === 0 ? diff : undefined; },
    },
  });
  const host = { fetchRoutes: routes, interceptors: new Map() };
  const connection = { createSharedFetchHandler: channel => HostConnectionService.prototype.createSharedFetchHandler.call(host, channel) };
  const lifetime = new AbortController();
  const owner = { principal: { kind: 'owner' }, signal: lifetime.signal };
  const untrusted = { principal: { kind: 'remote-device' }, signal: lifetime.signal };
  const handler = connection.createSharedFetchHandler('/api');
  const denied = await handler.fetch(new Request('http://127.0.0.1/api/changes.summary?sessionId=owned&seq=7'), untrusted);
  assert.equal(denied.status, 403); assert.equal(calls.length, 0);
  const peer = new DshChangesPeer({ readConnection: () => connection, peer: owner });
  try {
    assert.equal(peer.available, true);
    assert.deepEqual(await peer.call('harness.changes.read', { kind: 'summary', sessionId: 'owned', seq: 7 }), { status: 200, value: summary });
    assert.deepEqual(await peer.call('harness.changes.read', { kind: 'diff', sessionId: 'owned', seq: 7, index: 0 }), { status: 200, value: diff });
    assert.deepEqual(await peer.call('harness.changes.read', { kind: 'diff', sessionId: 'owned', seq: 7, index: 1 }), { status: 404 });
    assert.deepEqual(await peer.call('harness.changes.read', { kind: 'summary', sessionId: 'expired', seq: 7 }), { status: 404 });
    assert.equal(new DshChangesPeer({ readConnection: () => connection, peer: untrusted }).available, false);
    assert.deepEqual(calls, [['summary', 'owned', 7], ['diff', 'owned', 7, 0], ['diff', 'owned', 7, 1], ['summary', 'expired', 7]]);
    lifetime.abort(); assert.equal(peer.available, false);
  } finally { peer.closeAll(); }
});
