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
  let index = inputs.get('packages/plugin/src/index.ts')
  index = replaceOnce(index, "import { HostPluginRuntime } from './service.js'",
    "import { HostPluginRuntime } from './service.js'\nimport type { DshChangesConnection } from './lagoon-dsh-changes.mjs'")
  index = replaceOnce(index, '      schedule,\n    )',
    "      schedule,\n      () => ctx.get('connection') as DshChangesConnection | undefined,\n    )")
  await writeFile(join(root, 'packages/plugin/src/lagoon-native-services.mjs'), native)
  await writeFile(join(root, 'packages/plugin/src/lagoon-native-services.d.mts'), types)
  await writeFile(join(root, 'packages/plugin/src/lagoon-dsh-changes.mjs'), changes)
  await writeFile(join(root, 'packages/plugin/src/lagoon-dsh-changes.d.mts'), changesTypes)
  await writeFile(join(root, 'packages/plugin/src/rpc-router.ts'), router)
  await writeFile(join(root, 'packages/plugin/src/service.ts'), service)
  await writeFile(join(root, 'packages/plugin/src/index.ts'), index)
  return { revision: UPSTREAM_REVISION, files: ['packages/plugin/src/rpc-router.ts', 'packages/plugin/src/service.ts', 'packages/plugin/src/index.ts'], nativeSha256: sha(native), changesSha256: sha(changes) }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (!process.argv[2]) throw new Error('usage: node apply-upstream.mjs <pinned-upstream-root>')
  console.log(JSON.stringify(await applyOverlay(process.argv[2])))
}
