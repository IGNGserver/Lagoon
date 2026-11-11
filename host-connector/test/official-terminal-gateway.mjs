/** Test-only official HTTP/mux adapter. Never starts a daemon, retries input or prints credentials. */
import { readFile } from 'node:fs/promises';
import { isAbsolute, resolve, sep } from 'node:path';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';

function queue() {
  const values = []; let done = false, error, wake;
  return { push(value) { if (!done) { values.push(value); wake?.(); } }, end(failure) { done = true; error = failure; wake?.(); },
    async *[Symbol.asyncIterator]() {
      while (true) {
        if (values.length) yield values.shift();
        else if (done) { if (error) throw error; break; }
        else await new Promise(resolveWait => { wake = resolveWait; });
      }
    } };
}
export async function officialTerminalGateway(connectionPath) {
  if (!isAbsolute(connectionPath)) throw new Error('Official connection must be absolute');
  const raw = await readFile(connectionPath); if (raw.length > 16384) throw new Error('Official connection too large');
  const connection = JSON.parse(raw), url = new URL(connection.url);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.port === '3080' || url.username || url.password || url.search || url.hash)
    throw new Error('Official provider must be an isolated HTTP loopback');
  const directory = resolve(connection.directory);
  if (!directory.startsWith(resolve(connectionPath, '..') + sep)) throw new Error('Official workspace must be isolated');
  const login = new URL(url); login.searchParams.set('token', connection.token);
  const auth = await fetch(login, { redirect: 'manual', headers: { accept: 'text/html,application/xhtml+xml' }, signal: AbortSignal.timeout(10000) });
  const cookie = auth.headers.getSetCookie().map(value => value.split(';')[0]).find(value => value.startsWith('dsh-auth-'));
  await auth.body?.cancel(); if (!cookie) throw new Error('Official authentication failed');
  const { WebSocket } = createRequire(import.meta.url)('ws');
  const streams = new Map(); let disposed = false;
  const socketUrl = new URL('/api/remote.mux', url); socketUrl.protocol = 'ws:';
  const socket = new WebSocket(socketUrl, { headers: { cookie }, handshakeTimeout: 10000 });
  socket.on('message', bytes => {
    try {
      const frame = JSON.parse(bytes.toString('utf8')), active = streams.get(frame.streamId);
      if (!active) return;
      if (frame.type === 'item') active.queue.push(frame.value);
      else if (frame.type === 'error' || frame.type === 'end') {
        streams.delete(frame.streamId); active.close();
        active.queue.end(frame.type === 'error' ? new Error('Official native stream refused') : undefined);
      }
    } catch { for (const active of streams.values()) active.queue.end(new Error('Official mux invalid')); }
  });
  const stop = () => { for (const active of streams.values()) { active.close(); active.queue.end(new Error('Official mux closed')); } streams.clear(); };
  socket.on('close', stop); socket.on('error', stop);
  await new Promise((done, reject) => { socket.once('open', done); socket.once('error', () => reject(new Error('Official mux failed'))); });
  const gateway = {
    supportsCarrier: true,
    directory,
    failure: () => ({ code: 'OFFICIAL_NATIVE_FAILED', message: 'Official native request failed.', details: {} }),
    async dispatch(endpoint, payload, signal) {
      signal.throwIfAborted(); if (disposed) throw new Error('Official provider disposed');
      const rpcId = randomUUID();
      const response = await fetch(new URL(`/api/${endpoint}`, url), { method: 'POST', redirect: 'manual',
        headers: { cookie, 'content-type': 'application/json' }, signal,
        body: JSON.stringify({ type: 'client-request', rpcId, method: endpoint, payload }) });
      if (!response.ok) throw new Error('Official native HTTP refused');
      const body = await response.json();
      if (body.type !== 'server-response' || body.rpcId !== rpcId || typeof body.result?.ok !== 'boolean') throw new Error('Official native response invalid');
      return body.result;
    },
    async invoke({ namespace, method, args, signal = AbortSignal.timeout(10000) }) {
      const result = await gateway.dispatch(`${namespace}/${method}`, { args }, signal);
      if (!result.ok) throw new Error('Official native invocation refused'); return result.value;
    },
    async open(endpoint, payload, signal, uplink) {
      signal.throwIfAborted(); if (disposed || socket.readyState !== WebSocket.OPEN) throw new Error('Official provider closed');
      const id = randomUUID(), output = queue();
      const close = () => { signal.removeEventListener('abort', cancel); };
      const cancel = () => {
        if (!streams.delete(id)) return;
        close(); if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'cancel', streamId: id }));
        output.end();
      };
      streams.set(id, { queue: output, close }); signal.addEventListener('abort', cancel, { once: true });
      socket.send(JSON.stringify({ type: 'open', streamId: id, endpoint, payload }));
      if (uplink) void (async () => {
        try { for await (const value of uplink) {
          if (!streams.has(id) || signal.aborted || socket.readyState !== WebSocket.OPEN) break;
          socket.send(JSON.stringify({ type: 'item', streamId: id, value }));
        } } catch { cancel(); }
      })();
      return (async function* () { try { yield* output; } finally { cancel(); } })();
    },
    async dispose() { if (disposed) return; disposed = true; stop(); socket.terminate(); },
  };
  return gateway;
}
