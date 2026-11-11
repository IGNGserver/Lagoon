import test from 'node:test';
import assert from 'node:assert/strict';
import { createTerminalHost, SESSION, DEVICE } from './stdio-terminal-host.mjs';

// This is pinned Remote implementation integration, not an official-daemon/process acceptance test.
assert.ok(process.env.LAGOON_PATCHED_ROUTER, 'Build the verified entry and set LAGOON_PATCHED_ROUTER');
const bounded = { timeout: 10_000 };
let serial = 0;
async function fixture(t) {
  const waiting = new Set();
  const host = await createTerminalHost({ publish: async item => {
    for (const waiter of [...waiting]) if (waiter.predicate(item)) { waiting.delete(waiter); waiter.done(item); }
  } });
  t.after(() => host.close());
  const frame = predicate => {
    const existing = host.events.find(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise((done, reject) => {
      const waiter = { predicate, done: item => { clearTimeout(timer); done(item); } };
      const timer = setTimeout(() => { waiting.delete(waiter); reject(new Error('Expected upstream frame did not arrive')); }, 5000);
      waiting.add(waiter);
    });
  };
  const rpc = (method, params = {}) => host.dispatch({ id: `terminal-${++serial}`, method, params });
  const call = (endpoint, args) => rpc('harness.remote.call', { endpoint, payload: { args } });
  const create = (id = 'terminal-a') => call('terminal/create', { agentId: SESSION, request: { id, cols: 80, rows: 24 } });
  const open = (endpoint, streamId, args) => rpc('harness.remote.stream.open', { streamId, endpoint, payload: { args } });
  const follow = (streamId = 'follow-a', attachmentId = 'attachment-a', id = 'terminal-a') =>
    open('terminal/follow', streamId, { agentId: SESSION, id, attachmentId });
  const hold = (streamId = 'hold-a', id = 'terminal-a') => open('terminal/retain', streamId, { sessionId: SESSION, id });
  const input = (attachmentId = 'attachment-a', data = 'raw\r\n\u001b[31m界\u0003') =>
    call('terminal/write', { agentId: SESSION, id: 'terminal-a', attachmentId, data });
  const closeStream = streamId => rpc('harness.remote.stream.close', { streamId });
  const frames = () => host.events.filter(item => item.event === 'harness.remote.frame');
  return { ...host, rpc, call, create, open, follow, hold, input, closeStream, frames, frame };
}
const denied = (promise, code = 'PERMISSION_DENIED') => assert.rejects(promise, failure => failure.code === code);

test('actual router describes transport, Session native shapes and Policy-filtered terminal catalog', bounded, async t => {
  const f = await fixture(t);
  const description = await f.rpc('harness.transport.describe');
  assert.ok(description.capabilities.includes('harness.remote.v3'));
  assert.ok(description.capabilities.includes('harness.terminal.v1'));
  assert.ok(description.capabilities.includes('harness.remote.stream.transfer.v1'));
  const environment = await f.call('terminal/environment', { agentId: SESSION });
  assert.equal(environment.ok, true); assert.equal(environment.value.maxCols, 1000);
  assert.deepEqual(await f.call('terminal/list', { sessionId: SESSION }), { ok: true, value: [] });
  const created = await f.create(); assert.equal(created.ok, true);
  assert.deepEqual(created.value, { id: 'terminal-a', title: 'Session terminal',
    shell: { path: '/fixture/bin/sh', args: ['-i'], name: 'fixture shell' }, cwd: '/fixture/workspace',
    cols: 80, rows: 24, state: 'running', exitCode: null });
  const listed = await f.call('terminal/list', { sessionId: SESSION });
  assert.deepEqual(listed.value.map(row => row.id), ['terminal-a']);
  assert.equal(f.stats().nativeTerminals, 2); assert.equal(f.stats().creates, 1);
  assert.equal(DEVICE, 'trusted-device-a');
});

test('actual Host switch and shared-owner second-device Policy deny before native dispatch', bounded, async t => {
  const f = await fixture(t); await f.create(); const initial = f.stats().nativeRPCcount;
  const params = args => ({ endpoint: 'terminal/close', payload: { args } });
  await denied(f.rpc('fixture.secondDeviceProbe', { method: 'harness.remote.call',
    params: params({ agentId: SESSION, id: 'terminal-a', deviceId: DEVICE }) }));
  assert.equal(f.stats().nativeRPCcount, initial);
  await denied(f.open('terminal/retain', 'foreign', { sessionId: SESSION, id: 'external-readonly' }));
  await f.rpc('fixture.disable');
  for (const [endpoint, args] of [['terminal/list', { sessionId: SESSION }],
    ['terminal/create', { agentId: SESSION, request: { id: 'late' } }],
    ['terminal/close', { agentId: SESSION, id: 'terminal-a' }]]) await denied(f.call(endpoint, args), 'TERMINAL_DISABLED');
  assert.equal(f.stats().nativeRPCcount, initial); assert.equal(f.stats().closes, 0);
});

test('actual compatibility provider retains, attaches and consumes ACK before write/resize RPC resolves', bounded, async t => {
  const f = await fixture(t); await f.create(); await f.hold(); await f.follow();
  const snapshot = (await f.frame(item => item.data.value?.type === 'snapshot')).data.value;
  assert.equal(snapshot.sequence, 0); assert.equal(snapshot.writable, true);
  assert.equal(snapshot.info.controllerId, 'attachment-a');
  await f.rpc('fixture.manualAck');
  let writeResolved = false;
  const write = f.input().then(value => { writeResolved = true; return value; });
  await f.waitFor({ writes: 1, pendingAcks: 1 }); assert.equal(writeResolved, false);
  const output = (await f.frame(item => item.data.value?.type === 'output')).data.value;
  assert.equal(output.sequence, 1); assert.equal(output.data, 'native-output:raw\r\n\u001b[31m界\u0003');
  await f.rpc('fixture.ack'); assert.deepEqual(await write, { ok: true });
  let resizeResolved = false;
  const resize = f.call('terminal/resize', { agentId: SESSION, id: 'terminal-a', attachmentId: 'attachment-a', cols: 120, rows: 40 })
    .then(value => { resizeResolved = true; return value; });
  await f.waitFor({ resizes: 1, pendingAcks: 1 }); assert.equal(resizeResolved, false);
  await f.rpc('fixture.ack'); assert.deepEqual(await resize, { ok: true });
  assert.ok(f.frames().some(item => item.data.value?.type === 'state' && item.data.value.info.cols === 120));
  assert.equal(f.frames().some(item => item.data.value?.type === 'ack'), false, 'Provider ACK must not leak to Remote frame stream');
  assert.deepEqual(f.stats().inputs.map(value => [value.type, value.sequence]), [['write', 1], ['resize', 2]]);
});

test('new attachment invalidates old id; native read-only takeover cannot be bypassed or restored on detach', bounded, async t => {
  const f = await fixture(t); await f.create(); await f.hold(); await f.follow();
  await f.follow('follow-new', 'attachment-new');
  await f.waitFor({ activeFollows: 1 });
  await denied(f.input('attachment-a')); assert.equal(f.stats().writes, 0);
  assert.deepEqual(await f.input('attachment-new', 'once'), { ok: true });
  await f.rpc('fixture.externalAttach', { agentId: SESSION, id: 'terminal-a' });
  // Wait on published state rather than timing a native output queue.
  await f.waitFor({ writes: 1 });
  await new Promise(done => setImmediate(done));
  assert.ok(f.frames().some(item => item.data.value?.type === 'state' && item.data.value.writable === false));
  await denied(f.input('attachment-new')); assert.equal(f.stats().writes, 1);
  await f.closeStream('follow-new'); await f.waitFor({ activeFollows: 0 });
  await denied(f.input('attachment-a')); await denied(f.input('attachment-new'));
  await denied(f.open('terminal/attach', 'bypass', { agentId: SESSION, id: 'terminal-a' }), 'METHOD_NOT_ALLOWED');
});

test('follow/retain detach does not terminate; only explicit close releases actual Policy ownership', bounded, async t => {
  const f = await fixture(t); await f.create(); await f.hold(); await f.follow();
  assert.deepEqual(await f.closeStream('follow-a'), { closed: true, streamId: 'follow-a' });
  await f.waitFor({ activeFollows: 0, activeHolds: 1 }); assert.equal(f.stats().closes, 0);
  await f.closeStream('hold-a'); await f.waitFor({ activeHolds: 0 });
  assert.equal((await f.call('terminal/list', { sessionId: SESSION })).value[0].state, 'running');
  await f.call('terminal/close', { agentId: SESSION, id: 'terminal-a' });
  assert.equal(f.stats().closes, 1);
  assert.deepEqual(await f.call('terminal/list', { sessionId: SESSION }), { ok: true, value: [] });
  await denied(f.follow('after-close', 'after-close'));
});

test('closing pending attachment rejects unacknowledged input and never replays on reopen', bounded, async t => {
  const f = await fixture(t); await f.create(); await f.hold(); await f.follow(); await f.rpc('fixture.manualAck');
  const write = f.input('attachment-a', 'exactly-once');
  const refused = denied(write, 'TRANSPORT_CLOSED');
  await f.waitFor({ writes: 1, pendingAcks: 1 }); await f.closeStream('follow-a'); await refused;
  await f.waitFor({ activeFollows: 0 });
  await f.follow('reconnected', 'attachment-reconnected'); await f.waitFor({ activeFollows: 1 });
  await f.rpc('fixture.ack');
  assert.equal(f.stats().writes, 1); assert.equal(f.stats().closes, 0);
  assert.deepEqual(f.stats().inputs, [{ type: 'write', sequence: 1, data: 'exactly-once' }]);
  await denied(f.input('attachment-a', 'stale')); assert.equal(f.stats().writes, 1);
});
