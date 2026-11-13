#!/usr/bin/env node
import { createHash } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'

export const UPSTREAM_REVISION = 'bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b'
const ownRoot = dirname(fileURLToPath(import.meta.url))
const expected = new Map([
  ['packages/plugin/src/rpc-router.ts', '268f166358171a930f5ef1a0add7859352560ee223ce9b06d23a71310c73ce21'],
  ['packages/plugin/src/service.ts', '13b9a37efc8bab7f5ef014649ddc963de5d0234258a8f3d7150edaccdbda83b3'],
  ['packages/plugin/src/index.ts', 'ef42da0526e850d235c00922766a578eb0195ed35056b0cba336e58f565652d1'],
  ['packages/plugin/src/acp/gateway.ts', '24b68203abe656c1c7dd15b2ba658b7d07e4b10e1acb717b375173cb1ae20a6d'],
  ['packages/plugin/src/acp/adapters/cursor-process.ts', '6cfa67e6271dfc483dfcf7704fae6ce4c8e38409cec16ecd1a3c74aebe234fae'],
  ['packages/plugin/src/acp/adapters/session-models.ts', '4ccba95037cb0dff97f3a0cc083d49a0ae11ea7bec73a1ee87ef80f00760764b'],
  ['packages/plugin/scripts/build-bundles.mjs', 'e7e0a1d46e2783f22fbab0c76d160b72d25efdb399089b8b64c2617457b83e80'],
  ['packages/plugin/package.json', 'd55f6d5106f768377a7bdcc8bd38f85e764f22117d20a7b3728a3d0b52ce4f7c'],
])
const sha = bytes => createHash('sha256').update(bytes).digest('hex')
function replaceOnce(text, before, after) {
  if (text.split(before).length !== 2) throw new Error('Pinned upstream anchor missing or duplicated')
  return text.replace(before, after)
}

/** Only operates on an explicitly supplied pinned source copy. No network, installs or deployment. */
export async function applyOverlay(upstream) {
  const root = resolve(upstream)
  const inputs = new Map()
  for (const [path, digest] of expected) {
    const bytes = await readFile(join(root, path))
    if (sha(bytes) !== digest) throw new Error(`Upstream ${UPSTREAM_REVISION} checksum mismatch: ${path}`)
    inputs.set(path, bytes.toString('utf8'))
  }
  // Read and validate every input and replacement anchor before mutating any upstream file.
  const native = await readFile(join(ownRoot, 'native-services.mjs'))
  const types = await readFile(join(ownRoot, 'native-services.d.mts'))
  const changes = await readFile(join(ownRoot, 'dsh-changes.mjs'))
  const changesTypes = await readFile(join(ownRoot, 'dsh-changes.d.mts'))
  const agyForm = await readFile(join(ownRoot, 'agy-elicitation.mjs'))
  const agyFormTypes = await readFile(join(ownRoot, 'agy-elicitation.d.mts'))
  let router = inputs.get('packages/plugin/src/rpc-router.ts')
  router = replaceOnce(router, "import type { PluginRouteBridge } from './plugin-route-bridge.js'",
    "import type { PluginRouteBridge } from './plugin-route-bridge.js'\nimport { NativeServicePeer, NativeServiceError, NATIVE_SERVICE_CAPABILITY } from './lagoon-native-services.mjs'\nimport { DshChangesPeer, DshChangesError, DSH_CHANGES_CAPABILITY } from './lagoon-dsh-changes.mjs'")
  const methods = ['describe','call','stream.open','stream.send','stream.close','transfer.open','transfer.chunk','transfer.commit','transfer.read','transfer.close']
  router = replaceOnce(router, "  'harness.transport.describe',",
    "  'harness.transport.describe',\n  'harness.changes.read',\n" + methods.map(method => `  'harness.services.${method}',`).join('\n'))
  router = replaceOnce(router, '    private readonly workspaceUpload?: WorkspaceUploadHost,\n  ) {}',
    '    private readonly workspaceUpload?: WorkspaceUploadHost,\n    private readonly nativeServices?: NativeServicePeer,\n    private readonly changes?: DshChangesPeer,\n  ) {}')
  router = replaceOnce(router, '      this.workspaceUpload?.closeAll(),',
    '      this.workspaceUpload?.closeAll(),\n      this.nativeServices?.closeAll(),\n      this.changes?.closeAll(),')
  router = replaceOnce(router, "          ...(this.harnessRemote === undefined ? [] : ['harness.remote.stream.transfer.v1']),",
    "          ...(this.harnessRemote === undefined ? [] : ['harness.remote.stream.transfer.v1']),\n          ...(this.nativeServices === undefined ? [] : [NATIVE_SERVICE_CAPABILITY]),\n          ...(this.changes?.available === true ? [DSH_CHANGES_CAPABILITY] : []),")
  router = replaceOnce(router, "      case 'harness.remote.call': return this.requireRemoteGateway().call(params)",
    "      case 'harness.changes.read':\n        if (this.changes === undefined) throw new DshChangesError('FEATURE_NOT_SUPPORTED')\n        return this.changes.call(method, params)\n" +
    methods.map(method => `      case 'harness.services.${method}':`).join('\n') +
    "\n        if (this.nativeServices === undefined) throw new RpcError('FEATURE_NOT_SUPPORTED', 'Native services are not enabled on this Host.')\n        return this.nativeServices.call(method, params)\n      case 'harness.remote.call': return this.requireRemoteGateway().call(params)")
  // Rejected extra fields must not trick upstream diagnostics into logging client paths/payloads.
  router = replaceOnce(router, '      const failedEndpoint = !request.payload.method.startsWith(\'workspace.upload.\')',
    "      const failedEndpoint = request.payload.method !== 'harness.changes.read'\n        && !request.payload.method.startsWith('workspace.upload.')")
  router = replaceOnce(router,
    'function errorResponse(requestId: string, error: unknown): RemoteMessage<RpcErrorPayload> {\n  const code = safeErrorCode(error)',
    'function errorResponse(requestId: string, error: unknown): RemoteMessage<RpcErrorPayload> {\n  if (error instanceof NativeServiceError || error instanceof DshChangesError) return createRpcError(requestId, error.code, error.message, undefined, false)\n  const code = safeErrorCode(error)')

  let service = inputs.get('packages/plugin/src/service.ts')
  service = replaceOnce(service, "import { RpcError } from './safe-error.js'",
    "import { RpcError } from './safe-error.js'\nimport { NativeServicePeer } from './lagoon-native-services.mjs'\nimport { DshChangesPeer, type DshChangesConnection } from './lagoon-dsh-changes.mjs'")
  service = replaceOnce(service, '    private readonly schedule?: ScheduleCreateService,\n  ) {',
    '    private readonly schedule?: ScheduleCreateService,\n    private readonly changesConnection?: () => DshChangesConnection | undefined,\n  ) {')
  service = replaceOnce(service,
    '      const pluginRoute = dispatcher === undefined ? undefined : new PluginRouteBridge(dispatcher, this.logger)',
    '      const pluginRoute = dispatcher === undefined ? undefined : new PluginRouteBridge(dispatcher, this.logger)\n' +
    "      const changes = new DshChangesPeer({ readConnection: this.changesConnection ?? (() => undefined), peer: peerGateway?.peer })\n" +
    "      // Explicit Host opt-in. Registration paths never come from Remote requests.\n" +
    "      const nativeServices = process.env.LAGOON_NATIVE_SERVICES === '1' ? new NativeServicePeer({\n" +
    "        publish: (event, data) => send(createEvent(event, data)),\n" +
    "        ...(process.env.LAGOON_NATIVE_REGISTRATIONS ? { registrationPaths: readNativeRegistrationPaths(process.env.LAGOON_NATIVE_REGISTRATIONS) } : {}),\n" +
    '      }) : undefined')
  service = replaceOnce(service, '        ), undefined, context.transportMode),\n      )',
    '        ), undefined, context.transportMode),\n        nativeServices,\n        changes,\n      )')
  service += `\n// Local Host configuration only; refuse malformed or oversized opt-in paths.\nfunction readNativeRegistrationPaths(raw: string): string[] {\n  const paths: unknown = JSON.parse(raw)\n  if (!Array.isArray(paths) || paths.length > 16 || paths.some(path => typeof path !== 'string' || path.length === 0 || path.length > 4096 || /[\\x00-\\x1f\\x7f]/.test(path))) {\n    throw new RpcError('INVALID_MESSAGE', 'Invalid native service registration configuration.')\n  }\n  return paths as string[]\n}\n`
  // AGY 1.5+ exposes official ACP. The pinned upstream AGY wrapper used stream-json and
  // did not forward individual approval replies; advertise a distinct capability only after
  // replacing that adapter, so an older Host cannot masquerade as this implementation.
  let gateway = inputs.get('packages/plugin/src/acp/gateway.ts')
  gateway = replaceOnce(gateway,
    "if ((targetBackend === 'claude' || targetBackend === 'hermes') && this.promptsInProgress.has(sessionId!))",
    "if ((targetBackend === 'claude' || targetBackend === 'hermes' || targetBackend === 'antigravity') && this.promptsInProgress.has(sessionId!))")
  gateway = replaceOnce(gateway, "import { randomUUID } from 'node:crypto'", "import { randomUUID } from 'node:crypto'\nimport { parseAgyElicitation, validateAgyElicitationResult, cancelAgyRequest, type AgyForm } from './lagoon-agy-elicitation.mjs'")
  gateway = replaceOnce(gateway, '  options: Array<{ kind: string; optionId: string }>\n}', '  options: Array<{ kind: string; optionId: string }>\n  formSchema?: AgyForm\n}')
  gateway = replaceOnce(gateway,
    "!['session/request_permission', 'cursor/create_plan', 'cursor/ask_question'].includes(message.method)",
    "!['session/request_permission', 'cursor/create_plan', 'cursor/ask_question', 'elicitation/create'].includes(message.method)\n" +
    "      || (message.method === 'elicitation/create' && backend !== 'antigravity')")
  gateway = replaceOnce(gateway, "    let options: PendingApproval['options'] = []\n    try {",
    "    let options: PendingApproval['options'] = []\n    let formSchema: AgyForm | undefined\n    try {\n" +
    "      if (message.method === 'elicitation/create') formSchema = parseAgyElicitation(message.params)")
  gateway = replaceOnce(gateway, '      expiresAt: Date.now() + APPROVAL_TTL_MS, backend, client, options,',
    '      expiresAt: Date.now() + APPROVAL_TTL_MS, backend, client, options, formSchema,')
  gateway = replaceOnce(gateway, "    if (params.decision === 'cancel') {\n      await acp.respondError(pending.upstreamId, -32800, 'Cancelled by Remote client.')",
    "    if (params.decision === 'cancel' && pending.method !== 'elicitation/create') {\n      await cancelAgyRequest(pending, 'Cancelled by Remote client.')")
  gateway = replaceOnce(gateway, "approval.client.respondError(approval.upstreamId, -32800, 'ACP session cancelled.')", "cancelAgyRequest(approval, 'ACP session cancelled.')")
  gateway = replaceOnce(gateway, "approval.client.respondError(approval.upstreamId, -32800, 'Remote peer disconnected.')", "cancelAgyRequest(approval, 'Remote peer disconnected.')")
  gateway = replaceOnce(gateway, "approval.client.respondError(approval.upstreamId, -32800, 'Cursor approval expired.')", "cancelAgyRequest(approval, 'ACP approval expired.')")
  gateway = replaceOnce(gateway, "await client.respondError(message.id, -32800, 'Remote approval delivery failed.')",
    "await cancelAgyRequest({ backend, method: message.method, upstreamId: message.id, client }, 'Remote approval delivery failed.')")
  gateway = replaceOnce(gateway, "      await client.respondError(message.id, -32602, 'The ACP permission options are invalid.')",
    "      if (message.method === 'elicitation/create') await client.respond(message.id, { action: 'decline' })\n" +
    "      else await client.respondError(message.id, -32602, 'The ACP permission options are invalid.')")
  gateway = replaceOnce(gateway, "  if (pending.method === 'session/request_permission') {\n    if (params.result !== undefined) throw invalid()",
    "  if (pending.method === 'elicitation/create') {\n" +
    "    if (params.decision === 'cancel' || params.decision === 'reject-once') {\n" +
    "      if (params.result !== undefined) throw invalid()\n" +
    "      return { action: params.decision === 'cancel' ? 'cancel' : 'decline' }\n" +
    "    }\n" +
    "    if (!pending.formSchema || !validateAgyElicitationResult(pending.formSchema, params.result)) throw invalid()\n" +
    "    return params.result\n" +
    "  }\n" +
    "  if (pending.method === 'session/request_permission') {\n    if (params.result !== undefined) throw invalid()")
  gateway = replaceOnce(gateway, "import { AntigravityAcpClient } from './adapters/antigravity-process.js'",
    "import { CursorAcpClient } from './adapters/cursor-process.js'")
  gateway = replaceOnce(gateway,
    "        return new AntigravityAcpClient(binary, targetLogger, undefined, { args: backend.args, cwd: backend.cwd })",
    "        const legacy = ['--input-format', 'stream-json', '--output-format', 'stream-json']\n" +
    "        const args = JSON.stringify(backend.args) === JSON.stringify(legacy) ? ['acp'] : backend.args\n" +
    "        if (args[0] !== 'acp') throw new Error('Configure Antigravity backend args for agy acp')\n" +
    "        return new CursorAcpClient(binary, targetLogger, undefined, { args, cwd: backend.cwd, authenticationMethod: null, modelBackend: 'antigravity' })")
  let acpProcess = inputs.get('packages/plugin/src/acp/adapters/cursor-process.ts')
  acpProcess = replaceOnce(acpProcess, "modelBackend?: 'cursor' | 'claude' | 'hermes'", "modelBackend?: 'cursor' | 'claude' | 'hermes' | 'antigravity'")
  acpProcess = replaceOnce(acpProcess, '  private readonly modelBusy = new Set<string>()',
    '  private readonly modelBusy = new Set<string>()\n  private readonly agyLoading = new Set<string>()')
  acpProcess = replaceOnce(acpProcess, "    if (!this.ready) throw new CursorAcpError('CURSOR_UNAVAILABLE', 'Cursor ACP is not ready.')",
    "    if (!this.ready) throw new CursorAcpError('CURSOR_UNAVAILABLE', 'Cursor ACP is not ready.')\n" +
    "    if (this.options.modelBackend === 'antigravity' && isRecord(params) && 'backend' in params) {\n" +
    "      params = { ...params }; delete (params as Record<string, unknown>).backend\n" +
    "    }")
  acpProcess = replaceOnce(acpProcess, "    const budget = timeoutMs\n      ?? (method === 'session/prompt' ? ACP_PROMPT_TIMEOUT_MS : ACP_REQUEST_TIMEOUT_MS)",
    "    if (backend === 'antigravity' && method === 'session/load' && id) {\n" +
    "      if (this.modelBusy.has(id)) throw new CursorAcpError('PROMPT_IN_PROGRESS', 'The ACP session is busy.')\n" +
    "      this.modelBusy.add(id); this.agyLoading.add(id)\n" +
    "      try {\n" +
    "        const result = await this.request(method, params, timeoutMs ?? ACP_REQUEST_TIMEOUT_MS)\n" +
    "        const returnedId = isRecord(result) && typeof result.sessionId === 'string' ? result.sessionId : undefined\n" +
    "        if (!this.ready || (returnedId && returnedId !== id)) throw new CursorAcpError('ACP_INVALID_SESSION', 'ACP returned an invalid session.')\n" +
    "        const state = new AcpSessionModelState(backend); state.update(result); this.modelStates.set(id, state)\n" +
    "        return result\n" +
    "      } finally { this.agyLoading.delete(id); this.modelBusy.delete(id) }\n" +
    "    }\n" +
    "    const budget = timeoutMs\n      ?? (method === 'session/prompt' ? ACP_PROMPT_TIMEOUT_MS : ACP_REQUEST_TIMEOUT_MS)")
  acpProcess = replaceOnce(acpProcess, "    for (const handler of this.inboundHandlers) handler(inbound)",
    "    // The app obtains history independently. Do not mix the load replay into its live stream.\n" +
    "    if (inbound.kind === 'notification' && inbound.method === 'session/update' && isRecord(params)\n" +
    "      && typeof params.sessionId === 'string' && this.agyLoading.has(params.sessionId)) return\n" +
    "    for (const handler of this.inboundHandlers) handler(inbound)")
  acpProcess = replaceOnce(acpProcess, "          terminal: false,\n        },",
    "          terminal: false,\n          ...(this.options.modelBackend === 'antigravity' ? { elicitation: { form: {} } } : {}),\n        },")
  acpProcess = replaceOnce(acpProcess, "      await this.request('initialize', {", "      const initialization = await this.request('initialize', {")
  acpProcess = replaceOnce(acpProcess, "      }, ACP_START_TIMEOUT_MS)\n      this.assertStarting(child)",
    "      }, ACP_START_TIMEOUT_MS)\n" +
    "      if (this.options.modelBackend === 'antigravity' && (!isRecord(initialization) || initialization.protocolVersion !== 1))\n" +
    "        throw new CursorAcpError('ACP_PROTOCOL_UNSUPPORTED', 'AGY ACP protocol version is unsupported.')\n" +
    "      this.assertStarting(child)")
  acpProcess = replaceOnce(acpProcess, "      this.assertStarting(child)\n      this.ready = true",
    "      this.assertStarting(child)\n" +
    "      // Listing requires the Host user's AGY credentials; readiness must not mean only that the binary exists.\n" +
    "      if (this.options.modelBackend === 'antigravity') await this.request('session/list', {}, ACP_START_TIMEOUT_MS)\n" +
    "      this.assertStarting(child)\n      this.ready = true")
  let acpModels = inputs.get('packages/plugin/src/acp/adapters/session-models.ts')
  acpModels = replaceOnce(acpModels, "private readonly backend: 'cursor' | 'claude' | 'opencode' | 'hermes'", "private readonly backend: 'cursor' | 'claude' | 'opencode' | 'hermes' | 'antigravity'")
  acpModels = replaceOnce(acpModels, "{ cursor: 'Cursor', claude: 'Claude', opencode: 'OpenCode', hermes: 'Hermes' }", "{ cursor: 'Cursor', claude: 'Claude', opencode: 'OpenCode', hermes: 'Hermes', antigravity: 'Antigravity' }")
  service = replaceOnce(service,
    "if (this.acp.enabledBackends().includes('antigravity')) capabilities.push('agent.acp.antigravity.models.v1', 'agent.acp.antigravity.model-load.v1')",
    "if (this.acp.enabledBackends().includes('antigravity')) capabilities.push('agent.acp.antigravity.models.v1', 'lagoon.antigravity.native-acp.v1')")

  let index = inputs.get('packages/plugin/src/index.ts')
  index = replaceOnce(index, "import { HostPluginRuntime } from './service.js'",
    "import { HostPluginRuntime } from './service.js'\nimport type { DshChangesConnection } from './lagoon-dsh-changes.mjs'")
  index = replaceOnce(index, '      schedule,\n    )',
    "      schedule,\n      () => ctx.get('connection') as DshChangesConnection | undefined,\n    )")
  await writeFile(join(root, 'packages/plugin/src/lagoon-native-services.mjs'), native)
  await writeFile(join(root, 'packages/plugin/src/lagoon-native-services.d.mts'), types)
  await writeFile(join(root, 'packages/plugin/src/lagoon-dsh-changes.mjs'), changes)
  await writeFile(join(root, 'packages/plugin/src/lagoon-dsh-changes.d.mts'), changesTypes)
  await writeFile(join(root, 'packages/plugin/src/acp/lagoon-agy-elicitation.mjs'), agyForm)
  await writeFile(join(root, 'packages/plugin/src/acp/lagoon-agy-elicitation.d.mts'), agyFormTypes)
  await writeFile(join(root, 'packages/plugin/src/rpc-router.ts'), router)
  await writeFile(join(root, 'packages/plugin/src/service.ts'), service)
  await writeFile(join(root, 'packages/plugin/src/index.ts'), index)
  await writeFile(join(root, 'packages/plugin/src/acp/gateway.ts'), gateway)
  await writeFile(join(root, 'packages/plugin/src/acp/adapters/cursor-process.ts'), acpProcess)
  await writeFile(join(root, 'packages/plugin/src/acp/adapters/session-models.ts'), acpModels)
  return { revision: UPSTREAM_REVISION, files: ['packages/plugin/src/rpc-router.ts', 'packages/plugin/src/service.ts', 'packages/plugin/src/index.ts', 'packages/plugin/src/acp/gateway.ts', 'packages/plugin/src/acp/adapters/cursor-process.ts', 'packages/plugin/src/acp/adapters/session-models.ts'], nativeSha256: sha(native), changesSha256: sha(changes) }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error('usage: node apply-upstream.mjs <pinned-upstream-root>')
  console.log(JSON.stringify(await applyOverlay(process.argv[2])))
}
