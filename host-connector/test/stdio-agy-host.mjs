#!/usr/bin/env node
/** Explicitly opted-in test bridge to the pinned ACP gateway and the Host user's AGY CLI. */
import { createInterface } from 'node:readline'
import { realpath } from 'node:fs/promises'
import { isAbsolute, relative, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

const binary = process.env.LAGOON_OFFICIAL_AGY_BINARY
const root = process.env.LAGOON_OFFICIAL_AGY_ROOT
const artifact = process.env.LAGOON_AGY_GATEWAY
if (!binary || !root || !artifact || !process.env.BUILD_SCRATCH_ROOT) throw new Error('Isolated AGY fixture environment is required')
const scratch = await realpath(process.env.BUILD_SCRATCH_ROOT)
const cwd = await realpath(root)
const tail = relative(scratch, cwd)
if (!tail || tail === '..' || tail.startsWith(`..${sep}`) || isAbsolute(tail)) throw new Error('AGY test root must be inside scratch')
const { AcpRemoteGateway } = await import(pathToFileURL(await realpath(artifact)))
const logger = Object.fromEntries(['debug', 'info', 'warn', 'error'].map(name => [name, () => {}]))
const gateway = new AcpRemoteGateway({ enabled: true, backends: [{ id: 'antigravity', enabled: true,
  command: await realpath(binary), args: ['acp'], cwd }] }, logger)
const output = value => new Promise((resolve, reject) => process.stdout.write(`${JSON.stringify(value)}\n`, error => error ? reject(error) : resolve()))
await gateway.start()
if (!gateway.availableBackends().includes('antigravity')) {
  await output({ type: 'unavailable', code: gateway.status().error ?? 'AGY_UNAVAILABLE' })
  await gateway.close()
  process.exit(1)
}
const peer = gateway.createPeer({ connectionId: 'lagoon-agy-smoke', peerDeviceId: 'fixture-device' },
  (event, data) => output({ type: 'event', event, data }))
await output({ type: 'ready', host: 'bb33-agy-domain' })
const methods = {
  'agent.acp.call': params => peer.call(params),
  'agent.acp.respond': params => peer.respond(params),
  'agent.acp.stream.open': params => peer.openStream(params),
  'agent.acp.stream.close': params => peer.closeStream(params),
  'agent.acp.transfer.open': params => peer.openTransfer(params),
  'agent.acp.transfer.chunk': params => peer.appendTransfer(params),
  'agent.acp.transfer.commit': params => peer.commitTransfer(params),
  'agent.acp.transfer.read': params => peer.readTransfer(params),
  'agent.acp.transfer.close': params => peer.closeTransfer(params),
}
const tasks = new Set()
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })
lines.on('line', line => {
  const task = (async () => {
    let request
    try {
      request = JSON.parse(line)
      const invoke = methods[request.method]
      if (!invoke) throw new Error('method not supported')
      const result = await invoke(request.params ?? {})
      await output({ type: 'response', id: request.id, result: result ?? null })
    } catch (error) { await output({ type: 'error', id: request?.id ?? '', code: error.code ?? 'AGY_FIXTURE_FAILED' }) }
  })()
  tasks.add(task); task.finally(() => tasks.delete(task))
})
lines.once('close', () => { void (async () => { await Promise.allSettled([...tasks]); await gateway.close() })() })
