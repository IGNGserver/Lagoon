import test from 'node:test'
import assert from 'node:assert/strict'
import { pathToFileURL } from 'node:url'
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'

const artifact = process.env.LAGOON_AGY_GATEWAY
if (!artifact) throw new Error('Set LAGOON_AGY_GATEWAY to the pinned patched gateway bundle')
const { AcpRemoteGateway, CursorAcpClient, parseAgyElicitation, validateAgyElicitationResult, cancelAgyRequest } = await import(pathToFileURL(artifact))
const logger = Object.fromEntries(['debug', 'info', 'warn', 'error'].map(name => [name, () => {}]))
const form = {
  mode: 'form', message: 'Choose a strategy',
  requestedSchema: { type: 'object', properties: { strategy: { type: 'string', enum: ['safe', 'fast'] }, note: { type: 'string', maxLength: 8 } }, required: ['strategy'] },
}
async function until(check) {
  for (let i = 0; i < 200; i++) { if (check()) return; await new Promise(resolve => setTimeout(resolve, 5)) }
  throw new Error('Timed out waiting for gateway event')
}

test('form validation rejects extra, out-of-range and malformed values', () => {
  const schema = parseAgyElicitation(form)
  assert.equal(validateAgyElicitationResult(schema, { action: 'accept', content: { strategy: 'safe', note: 'ok' } }), true)
  for (const result of [
    { action: 'accept', content: { strategy: 'invented' } },
    { action: 'accept', content: { strategy: 'safe', note: 'too many characters' } },
    { action: 'accept', content: { strategy: 'safe', unexpected: 'x' } },
    { action: 'accept', content: {} },
    { action: 'accept', content: { strategy: 'safe' }, extra: true },
  ]) assert.equal(validateAgyElicitationResult(schema, result), false)
  assert.throws(() => parseAgyElicitation({ ...form, mode: 'url' }))
  assert.throws(() => parseAgyElicitation({ mode: 'form', requestedSchema: { type: 'object', properties: { n: { type: 'number' } } } }))
})

test('ACP cancellation returns the correct outcome for AGY requests', async () => {
  const calls = []
  const client = { respond: async (id, result) => calls.push([id, result]), respondError: async (...args) => calls.push(args) }
  await cancelAgyRequest({ backend: 'antigravity', method: 'session/request_permission', upstreamId: 1, client }, 'gone')
  await cancelAgyRequest({ backend: 'antigravity', method: 'elicitation/create', upstreamId: 2, client }, 'gone')
  assert.deepEqual(calls, [[1, { outcome: { outcome: 'cancelled' } }], [2, { action: 'cancel' }]])
})

test('gateway forwards AGY approval and form responses only to their owner', async () => {
  let inbound
  const replies = []
  const adapter = {
    start: async () => {}, isReady: () => true,
    call: async method => method === 'session/new' ? { sessionId: 'agy-session' } : {},
    respond: async (id, result) => replies.push({ id, result }),
    respondError: async (id, code) => replies.push({ id, code }),
    onInbound: handler => { inbound = handler; return () => {} }, onUnavailable: () => () => {}, close: async () => {},
  }
  const config = { enabled: true, backends: [{ id: 'antigravity', enabled: true, command: 'fixture', args: [] }] }
  const gateway = new AcpRemoteGateway(config, logger, () => adapter)
  await gateway.start()
  const frames = []
  const peer = gateway.createPeer({ connectionId: 'owner', peerDeviceId: 'one' }, async (name, data) => { if (name === 'agent.acp.frame') frames.push(data.frame) })
  gateway.createPeer({ connectionId: 'other', peerDeviceId: 'two' }, async () => {})
  try {
    await gateway.call('owner', { method: 'session/new', params: { backend: 'antigravity', cwd: process.cwd(), mcpServers: [] } })
    await peer.openStream({ streamId: 'stream-one', sessionId: 'agy-session' })
    inbound({ kind: 'request', id: 17, method: 'session/request_permission', params: { sessionId: 'agy-session', toolCall: { toolCallId: 'x' }, options: [
      { optionId: 'yes-17', kind: 'allow_once', name: 'Allow' }, { optionId: 'no-17', kind: 'reject_once', name: 'Deny' },
    ] } })
    await until(() => frames.some(frame => frame.method === 'session/request_permission'))
    const approval = frames.find(frame => frame.method === 'session/request_permission').params.requestHandle
    await assert.rejects(gateway.respond('other', { requestHandle: approval, decision: 'allow-once' }))
    await gateway.respond('owner', { requestHandle: approval, decision: 'allow-once' })
    assert.deepEqual(replies.pop(), { id: 17, result: { outcome: { outcome: 'selected', optionId: 'yes-17' } } })
    inbound({ kind: 'request', id: 18, method: 'elicitation/create', params: { sessionId: 'agy-session', ...form } })
    await until(() => frames.some(frame => frame.method === 'elicitation/create'))
    const question = frames.find(frame => frame.method === 'elicitation/create').params.requestHandle
    await assert.rejects(gateway.respond('owner', { requestHandle: question, decision: 'allow-once', result: { action: 'accept', content: { strategy: 'unknown' } } }))
    await gateway.respond('owner', { requestHandle: question, decision: 'allow-once', result: { action: 'accept', content: { strategy: 'safe' } } })
    assert.deepEqual(replies.pop(), { id: 18, result: { action: 'accept', content: { strategy: 'safe' } } })
  } finally { await gateway.close() }
})

test('loading an AGY session cannot interrupt an active prompt', async () => {
  let finish
  const pending = new Promise(resolve => { finish = resolve })
  const calls = []
  const adapter = {
    start: async () => {}, isReady: () => true,
    call: async method => {
      calls.push(method)
      if (method === 'session/new') return { sessionId: 'agy-session' }
      if (method === 'session/prompt') return pending
      return {}
    },
    respond: async () => {}, respondError: async () => {},
    onInbound: () => () => {}, onUnavailable: () => () => {}, close: async () => {},
  }
  const gateway = new AcpRemoteGateway({ enabled: true, backends: [{ id: 'antigravity', enabled: true, command: 'fixture', args: [] }] }, logger, () => adapter)
  await gateway.start()
  const peer = gateway.createPeer({ connectionId: 'owner', peerDeviceId: 'one' }, async () => {})
  try {
    await gateway.call('owner', { method: 'session/new', params: { backend: 'antigravity', cwd: process.cwd(), mcpServers: [] } })
    await peer.openStream({ streamId: 'stream-one', sessionId: 'agy-session' })
    const response = await gateway.call('owner', { method: 'session/prompt', params: { backend: 'antigravity', sessionId: 'agy-session', prompt: [{ type: 'text', text: 'hello' }] } })
    assert.equal(response.accepted, true)
    await assert.rejects(gateway.call('owner', { method: 'session/load', params: { backend: 'antigravity', sessionId: 'agy-session' } }),
      error => error.code === 'PROMPT_IN_PROGRESS')
    assert.equal(calls.includes('session/load'), false)
    finish({ stopReason: 'end_turn' })
    await until(() => !gateway.hasTurnInFlight('agy-session'))
  } finally { finish({ stopReason: 'cancelled' }); await gateway.close() }
})

test('native AGY load suppresses replay and fences prompts until the response', async () => {
  const child = new EventEmitter()
  Object.assign(child, { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), exitCode: null, killed: false,
    kill: () => { queueMicrotask(() => child.emit('exit', 0, 'SIGTERM')); return true } })
  const outbound = []
  let loadId
  const send = value => child.stdout.write(`${JSON.stringify(value)}\n`)
  child.stdin.on('data', chunk => {
    for (const line of String(chunk).trim().split('\n')) {
      const value = JSON.parse(line); outbound.push(value)
      if (value.method === 'session/load') {
        loadId = value.id
        for (let i = 0; i < 300; i++) send({ jsonrpc: '2.0', method: 'session/update', params: {
          sessionId: 'one', update: { sessionUpdate: 'agent_message_chunk', content: { type: 'text', text: String(i) } },
        } })
      } else if (value.id !== undefined) send({ jsonrpc: '2.0', id: value.id,
        result: value.method === 'initialize' ? { protocolVersion: 1 } : {} })
    }
  })
  const client = new CursorAcpClient('fixture', logger, () => child, { authenticationMethod: null, modelBackend: 'antigravity' })
  const inbound = []
  client.onInbound(value => inbound.push(value))
  try {
    await client.start()
    assert.deepEqual(outbound.map(value => value.method), ['initialize', 'session/list'])
    const loading = client.call('session/load', { sessionId: 'one', cwd: process.cwd(), mcpServers: [] })
    await until(() => loadId !== undefined)
    await assert.rejects(client.call('session/prompt', { sessionId: 'one', prompt: [] }), error => error.code === 'PROMPT_IN_PROGRESS')
    send({ jsonrpc: '2.0', id: loadId, result: { configOptions: [] } })
    await loading
    assert.equal(inbound.length, 0)
    send({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: 'one', update: { sessionUpdate: 'agent_message_chunk' } } })
    await until(() => inbound.length === 1)
    assert.equal(inbound[0].params.sessionId, 'one')
  } finally { await client.close() }
})
