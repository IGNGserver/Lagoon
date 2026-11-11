#!/usr/bin/env node
/** Test-only JSON-lines Host: real pinned router/bridge/policy/carrier; processless native business seam. */
import assert from 'node:assert/strict';
import { createInterface } from 'node:readline';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const SESSION = 'session-terminal-a';
export const DEVICE = 'trusted-device-a';
const shell = { path: '/fixture/bin/sh', args: ['-i'], name: 'fixture shell' };
const info = (id, request = {}) => ({ id, title: 'Session terminal', shell, cwd: '/fixture/workspace',
  cols: request.cols ?? 80, rows: request.rows ?? 24, state: 'running', exitCode: null });
const clone = value => JSON.parse(JSON.stringify(value));
const ok = value => value === undefined ? { ok: true } : { ok: true, value };

// Cancellation wakes blocked readers and drops queued frames; no terminal process is ever spawned.
function queue(signal) {
  let closed = false, wake;
  const values = [];
  const close = () => { closed = true; values.length = 0; wake?.(); };
  signal.addEventListener('abort', close, { once: true });
  if (signal.aborted) close();
  return { push(value) { if (!closed) { values.push(clone(value)); wake?.(); } }, close,
    async *[Symbol.asyncIterator]() {
      try {
        while (!closed) {
          if (values.length) yield values.shift();
          else await new Promise(done => { wake = done; });
        }
      } finally { signal.removeEventListener('abort', close); close(); }
    } };
}

export async function createTerminalHost({ bundle = process.env.LAGOON_PATCHED_ROUTER, publish = async () => {}, nativeGateway } = {}) {
  assert.ok(bundle, 'Set LAGOON_PATCHED_ROUTER to the verified upstream entry');
  const upstream = await import(pathToFileURL(resolve(bundle)));
  for (const name of ['RpcRouter', 'HarnessRemoteBridge', 'TerminalPolicy', 'TerminalCarrierCompat']) assert.equal(typeof upstream[name], 'function', name);
  const owners = new Map(), terminals = new Map(), holds = new Set(), follows = new Set(), pending = [];
  const tasks = new Set(), events = [], changes = new Set();
  const counts = { nativeRPCcount: 0, writes: 0, creates: 0, close: 0, attach: 0, resizes: 0 };
  let enabled = true, manualAck = false, serial = 0;
  const keyOf = args => `${args.agentId ?? args.sessionId}/${args.id}`;
  const changed = () => { for (const notify of changes) notify(); };
  const record = (sessionId, id, request) => {
    const value = { sessionId, info: info(id, request), screen: '', sequence: 0, writer: undefined };
    terminals.set(`${sessionId}/${id}`, value); return value;
  };
  record(SESSION, 'external-readonly'); // Native list includes this; only actual TerminalPolicy may filter it.
  const stats = () => ({ ...counts, closes: counts.close, activeHolds: holds.size, activeFollows: follows.size,
    pendingAcks: pending.filter(entry => !entry.signal.aborted).length, nativeTerminals: terminals.size,
    inputs: clone(inputs), nativeCalls: clone(nativeCalls) });
  const inputs = [], nativeCalls = [];
  async function waitFor(expected) {
    const matches = () => Object.entries(expected).every(([name, value]) => stats()[name] === value);
    if (!matches()) await new Promise((done, reject) => {
      const notify = () => { if (matches()) { cleanup(); done(); } };
      const timer = setTimeout(() => { cleanup(); reject(new Error('Fixture condition timeout')); }, 5_000);
      const cleanup = () => { clearTimeout(timer); changes.delete(notify); };
      changes.add(notify); notify();
    });
    return stats();
  }
  function acknowledge(entry) {
    if (!entry.signal.aborted) entry.output.push({ type: 'ack', sequence: entry.sequence });
  }
  const gateway = {
    supportsCarrier: true,
    failure: () => ({ code: 'FIXTURE_NATIVE_FAILED', message: 'Native fixture failed.', details: {} }),
    async invoke({ namespace, method, args, signal }) {
      const result = await this.dispatch(`${namespace}/${method}`, { args }, signal);
      assert.equal(result.ok, true); return result.value;
    },
    async dispatch(endpoint, payload, signal) {
      signal.throwIfAborted(); assert.deepEqual(Object.keys(payload), ['args']);
      const args = payload.args, sessionId = args.agentId ?? args.sessionId;
      assert.equal(typeof sessionId, 'string'); counts.nativeRPCcount++;
      nativeCalls.push({ endpoint, payload: clone(payload) });
      if (endpoint === 'terminal/environment') return ok({ cwd: '/fixture/workspace', scrollback: 1000,
        maxInputBytes: 65536, maxBufferedBytes: 262144, maxCols: 1000, maxRows: 1000 });
      if (endpoint === 'terminal/shells') return ok([shell]);
      if (endpoint === 'terminal/list') return ok([...terminals.values()].filter(row => row.sessionId === sessionId).map(row => clone(row.info)));
      if (endpoint === 'terminal/create') {
        assert.deepEqual(Object.keys(args).sort(), ['agentId', 'request']);
        const request = args.request; assert.equal(typeof request.id, 'string');
        const row = terminals.get(`${sessionId}/${request.id}`) ?? record(sessionId, request.id, request);
        counts.creates++; changed(); return ok(clone(row.info));
      }
      const row = terminals.get(keyOf(args)); assert.ok(row, 'Native terminal not found');
      if (endpoint === 'terminal/rename') { row.info.title = args.title; changed(); return ok(); }
      if (endpoint === 'terminal/close') {
        counts.close++; row.info.state = 'exited'; row.info.exitCode = 0;
        for (const follow of follows) if (follow.row === row) follow.output.push({ type: 'state', info: row.info, writable: false });
        changed(); return ok();
      }
      assert.fail('write/resize must use the actual duplex compatibility carrier');
    },
    async open(endpoint, payload, signal, uplink) {
      signal.throwIfAborted(); assert.deepEqual(Object.keys(payload), ['args']);
      const args = payload.args, row = terminals.get(keyOf(args)); assert.ok(row);
      const output = queue(signal), entry = { row, output, signal, number: ++serial };
      let collection;
      if (endpoint === 'terminal/retain') {
        assert.deepEqual(Object.keys(args).sort(), ['id', 'sessionId']); assert.equal(uplink, undefined);
        collection = holds; output.push({ type: 'retained' });
      } else {
        assert.equal(endpoint, 'terminal/attach'); assert.deepEqual(Object.keys(args).sort(), ['agentId', 'id']);
        assert.equal(typeof uplink?.[Symbol.asyncIterator], 'function'); collection = follows; counts.attach++;
        if (row.writer) row.writer.output.push({ type: 'state', info: row.info, writable: false });
        row.writer = entry;
        output.push({ type: 'snapshot', info: row.info, screen: row.screen, sequence: row.sequence, writable: true });
        const consume = (async () => {
          for await (const value of uplink) {
            if (signal.aborted) break;
            assert.deepEqual(Object.keys(value).sort(), value.type === 'write' ? ['data', 'sequence', 'type'] : ['cols', 'rows', 'sequence', 'type']);
            assert.equal(row.writer, entry); inputs.push(clone(value));
            if (value.type === 'write') {
              counts.writes++; const data = `native-output:${value.data}`; row.screen += data; row.sequence++;
              for (const follow of follows) if (follow.row === row) follow.output.push({ type: 'output', sequence: row.sequence, data });
            } else {
              assert.equal(value.type, 'resize'); counts.resizes++; row.info.cols = value.cols; row.info.rows = value.rows;
              for (const follow of follows) if (follow.row === row) follow.output.push({ type: 'state', info: row.info, writable: follow === row.writer });
            }
            const ack = { output, signal, sequence: value.sequence };
            if (manualAck) pending.push(ack); else acknowledge(ack);
            changed();
          }
        })();
        tasks.add(consume); consume.finally(() => tasks.delete(consume)).catch(() => output.close());
      }
      collection.add(entry); changed();
      const remove = () => { collection.delete(entry); if (row.writer === entry) row.writer = undefined; changed(); };
      signal.addEventListener('abort', remove, { once: true });
      return (async function* () {
        try { yield* output; }
        finally { signal.removeEventListener('abort', remove); remove(); }
      })();
    },
  };
  function makeRouter(deviceId) {
    const policy = new upstream.TerminalPolicy(() => enabled, deviceId, owners);
    const bridge = new upstream.HarnessRemoteBridge(nativeGateway ?? gateway, async (event, data) => {
      const item = { type: 'event', event, data }; events.push(clone(item)); await publish(item);
    }, undefined, '0.2.1-rc.5', policy);
    return new upstream.RpcRouter(128, undefined, undefined, bridge,
      () => ['harness.remote.v3', ...(enabled ? ['harness.terminal.v1'] : [])]);
  }
  const router = makeRouter(DEVICE), otherRouter = makeRouter('trusted-device-b');
  async function wire(target, id, method, params) {
    const request = upstream.createRpcRequest(method, params, String(id));
    const response = await target.handle(upstream.parseRemoteMessage(JSON.parse(JSON.stringify(request))));
    upstream.parseRemoteMessage(response); assert.equal(response.payload.requestId, String(id));
    if (response.type === 'rpc.error') throw Object.assign(new Error(response.payload.message), { code: response.payload.code });
    assert.equal(response.type, 'rpc.response'); return response.payload.result;
  }
  return { router, gateway, events, stats, waitFor,
    async dispatch({ id, method, params = {} }) {
      assert.ok(typeof id === 'string' || typeof id === 'number'); assert.equal(typeof method, 'string');
      if (method === 'fixture.createSession') {
        assert.ok(nativeGateway?.directory, 'Official provider required');
        return nativeGateway.dispatch('session/create', { args: { request: { cwd: nativeGateway.directory } } }, AbortSignal.timeout(15000));
      }
      if (method === 'fixture.stats') return stats();
      if (method === 'fixture.waitFor') return waitFor(params);
      if (method === 'fixture.disable' || method === 'fixture.disableSwitch') { enabled = false; return { enabled }; }
      if (method === 'fixture.manualAck') { manualAck = params.enabled !== false; return { manualAck }; }
      if (method === 'fixture.ack') {
        const entries = pending.splice(0); for (const entry of entries) acknowledge(entry); changed(); return { acknowledged: entries.length };
      }
      if (method === 'fixture.secondDeviceProbe') {
        return wire(otherRouter, id, params.method ?? 'harness.remote.call', params.params ?? params);
      }
      if (method === 'fixture.externalAttach') {
        const row = terminals.get(`${params.agentId}/${params.id}`); assert.ok(row);
        row.writer?.output.push({ type: 'state', info: row.info, writable: false }); row.writer = undefined;
        return { attached: true }; // Native takeover control, never an authorization shortcut.
      }
      assert.ok(['harness.transport.describe', 'harness.remote.call', 'harness.remote.stream.open', 'harness.remote.stream.close'].includes(method));
      return wire(router, id, method, params);
    },
    async close() { await Promise.all([router.closePeerStreams(), otherRouter.closePeerStreams()]); await Promise.allSettled([...tasks]); },
  };
}

// Every stdout line is a protocol record. Errors never expose paths, stacks or native error text.
async function stdio() {
  const output = value => new Promise((done, reject) => process.stdout.write(`${JSON.stringify(value)}\n`, error => error ? reject(error) : done()));
  const official = process.env.LAGOON_STDIO_OFFICIAL_DSH_CONNECTION;
  const nativeGateway = official ? await (await import('./official-terminal-gateway.mjs')).officialTerminalGateway(official) : undefined;
  const host = await createTerminalHost({ bundle: process.env.LAGOON_PATCHED_ROUTER || process.argv[2], publish: output, nativeGateway });
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
        const safe = ['TERMINAL_DISABLED', 'PERMISSION_DENIED', 'METHOD_NOT_ALLOWED', 'INVALID_MESSAGE', 'TRANSPORT_CLOSED', 'RATE_LIMITED', 'REQUEST_CONFLICT', 'METHOD_NOT_FOUND'];
        await output({ type: 'error', id: request?.id ?? '', code: safe.includes(error.code) ? error.code : 'FIXTURE_FAILED',
          message: safe.includes(error.code) ? 'Host terminal request refused.' : 'Terminal stdio fixture failed.' });
      }
    })();
    tasks.add(task); task.finally(() => tasks.delete(task)).catch(() => { process.exitCode = 1; });
  });
  lines.once('close', () => { void stop(); });
  for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => { lines.close(); void stop(); });
  await output({ type: 'ready', router: true, terminal: true, official: Boolean(official) });
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await stdio().catch(() => { process.stderr.write('Terminal stdio fixture startup failed.\n'); process.exitCode = 1; });
}
