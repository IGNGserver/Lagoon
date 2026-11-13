import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, realpath, stat } from 'node:fs/promises'
import { isAbsolute, join, relative, sep } from 'node:path'
import { pathToFileURL } from 'node:url'

const rootEnv = process.env.LAGOON_OFFICIAL_AGY_ROOT
const binaryEnv = process.env.LAGOON_OFFICIAL_AGY_BINARY
const enabled = rootEnv !== undefined || binaryEnv !== undefined

// Explicit opt-in: uses the Host user's existing AGY login and creates a real
// session in scratch. It never changes existing sessions or authorizes tools.
test('pinned gateway uses the real AGY CLI in a scratch workspace', { skip: !enabled, timeout: 240_000 }, async t => {
  assert.ok(rootEnv && binaryEnv && process.env.LAGOON_AGY_GATEWAY, 'Set the AGY root, binary and patched gateway bundle')
  const scratch = await realpath(process.env.BUILD_SCRATCH_ROOT ?? join(process.env.HOME, '.build'))
  const root = await realpath(rootEnv)
  const tail = relative(scratch, root)
  assert.ok(tail && tail !== '..' && !tail.startsWith(`..${sep}`) && !isAbsolute(tail), 'AGY test root must be inside scratch')
  const binary = await realpath(binaryEnv)
  assert.ok((await stat(binary)).isFile(), 'AGY binary must be a real file')
  const workspace = await mkdtemp(join(root, 'agy-host-'))
  const { AcpRemoteGateway } = await import(pathToFileURL(await realpath(process.env.LAGOON_AGY_GATEWAY)))
  const logger = Object.fromEntries(['debug', 'info', 'warn', 'error'].map(key => [key, () => {}]))
  const gateway = new AcpRemoteGateway({ enabled: true, backends: [{ id: 'antigravity', enabled: true, command: binary, args: ['acp'], cwd: workspace }] }, logger)
  let finish
  const completed = new Promise(resolve => { finish = resolve })
  const updates = []
  const peer = gateway.createPeer({ connectionId: 'lagoon-live-owner', peerDeviceId: 'lagoon-live-device' }, async (name, data) => {
    const frame = data?.frame
    if (name !== 'agent.acp.frame' || !frame) return
    if (['session/request_permission', 'elicitation/create'].includes(frame.method) && frame.params?.requestHandle)
      await gateway.respond('lagoon-live-owner', { requestHandle: frame.params.requestHandle, decision: 'cancel' })
    if (frame.method === 'session/update') {
      updates.push(frame.params?.update)
      if (['prompt_completed', 'prompt_failed'].includes(frame.params?.update?.sessionUpdate)) finish(frame.params.update)
    }
  })
  const call = (method, params) => gateway.call('lagoon-live-owner', { method, params: { ...params, backend: 'antigravity' } })
  let timer
  try {
    await gateway.start()
    assert.ok(gateway.availableBackends().includes('antigravity'), 'AGY must be installed and logged in as the Host user')
    const { sessionId } = await call('session/new', { cwd: workspace, mcpServers: [] })
    assert.ok(sessionId)
    await peer.openStream({ streamId: 'lagoon-live-stream', sessionId })
    const catalog = await call('dsh/sessionModels', { sessionId })
    assert.ok(Array.isArray(catalog.groups))
    const model = process.env.LAGOON_AGY_TEST_MODEL
    if (model) {
      assert.ok(catalog.groups.flatMap(group => group.models ?? []).some(item => item.id === model), 'Test model must be advertised')
      assert.equal((await call('dsh/selectModel', { sessionId, model })).selected?.model, model)
    }
    const accepted = await call('session/prompt', { sessionId, prompt: [{ type: 'text', text: 'Reply with exactly LAGOON_HOST_SMOKE_OK. Do not use tools or change files.' }] })
    assert.equal(accepted.accepted, true)
    const outcome = await Promise.race([completed, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('AGY prompt timed out')), 180_000) })])
    const text = updates.filter(update => update?.sessionUpdate === 'agent_message_chunk').map(update => update.content?.text ?? '').join('')
    assert.equal(outcome.sessionUpdate, 'prompt_completed')
    assert.equal(outcome.stopReason, 'end_turn')
    const limited = text.toLowerCase().includes('quota reached')
    assert.ok(text.includes('LAGOON_HOST_SMOKE_OK') || limited, 'AGY must return the marker or an explicit quota warning')
    if (limited) t.diagnostic('AGY account quota reached; normal model generation was not verified')
    const history = await call('dsh/sessionHistory', { sessionId: `acp:${sessionId}` })
    assert.ok(history.events?.some(item => item.event?.type === 'user/message' && JSON.stringify(item.event.data).includes('LAGOON_HOST_SMOKE_OK')))
  } finally {
    clearTimeout(timer)
    await gateway.close()
  }
})
