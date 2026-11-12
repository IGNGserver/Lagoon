#!/usr/bin/env node
/** Test-only stdio bridge to the pinned upstream router and its actual local App Server client. */
import { createInterface } from 'node:readline';
import { CodexRemoteDomain, RpcRouter, createRpcRequest, parseRemoteMessage } from './codex-router.mjs';

const binary = process.env.LAGOON_CODEX_E2E_BINARY;
if (!binary || !process.env.CODEX_HOME || !process.env.HOME) throw Error('Isolated Codex fixture environment is required');
const logger = { info() {}, warn() {}, error() {}, debug() {} };
const domain = new CodexRemoteDomain({ enabled: true, binary }, logger);
const output = value => new Promise((resolve, reject) => process.stdout.write(`${JSON.stringify(value)}\n`, error => error ? reject(error) : resolve()));
await domain.start();
if (!domain.isAvailable()) {
  await output({ type: 'unavailable', code: domain.status().error || 'CODEX_UNAVAILABLE' });
  await domain.close();
  process.exit(1);
}
const peer = domain.createPeer({ connectionId: 'lagoon-codex-smoke', peerDeviceId: 'fixture-device' },
  (event, data) => output({ type: 'event', event, data }));
const router = new RpcRouter(128, logger, undefined, undefined,
  () => ['codex.appserver.v1', 'codex.appserver.transfer.v1'], peer);
await output({ type: 'ready', host: 'bb33-codex-domain' });
const tasks = new Set();
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });
lines.on('line', line => {
  const task = (async () => {
    let request;
    try {
      request = JSON.parse(line);
      const response = await router.handle(parseRemoteMessage(createRpcRequest(request.method, request.params || {}, request.id)));
      if (response.type === 'rpc.error') await output({ type: 'error', id: request.id, code: response.payload.code });
      else await output({ type: 'response', id: request.id, result: response.payload.result });
    } catch (error) {
      await output({ type: 'error', id: request?.id || '', code: error.code || 'FIXTURE_FAILED' });
    }
  })();
  tasks.add(task); task.finally(() => tasks.delete(task));
});
lines.once('close', () => { void (async () => {
  await Promise.allSettled([...tasks]);
  await router.closePeerStreams();
  await domain.close();
})(); });
