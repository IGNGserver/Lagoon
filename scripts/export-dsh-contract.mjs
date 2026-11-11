#!/usr/bin/env node
/** Export request schemas from an installed, pinned official DSH release, not Lagoon guesses. */
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve, join } from 'node:path'
import { pathToFileURL } from 'node:url'

const [rootArg, outputArg, mode] = process.argv.slice(2)
if (!rootArg || !outputArg) throw new Error('Usage: node scripts/export-dsh-contract.mjs <official-dsh-package-root> <new-output.json> [--session-only|--goal-only|--schedule-only|--terminal-only]')
if (mode !== undefined && !['--session-only', '--goal-only', '--schedule-only', '--terminal-only'].includes(mode)) throw new Error('Unknown export mode')
const root = resolve(rootArg)
const packageInfo = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'))
const version = packageInfo.version
const commits = { '0.2.1-rc.5': 'b830ec950faba0a46dda564be3e2d854afed94f1', '0.2.2-rc.1': '66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db' }
if (!(version in commits)) throw new Error(`Unreviewed official DSH version: ${version}`)
const packages = {
  'dsh-api-session-controller': ['session/attachment', 'session/create', 'session/follow', 'session/page', 'session/prompt', 'session/projections', 'session/selectModel', 'session/updateQueue'],
  'dsh-commands': ['commands/execute', 'commands/list'],
  'dsh-user-questions': ['userQuestions/answer', 'userQuestions/attachWait'],
  'dsh-api-workspace-controller': ['workspace/archiveSession', 'workspace/unarchiveSession', 'workspace/pinSession', 'workspace/unpinSession'],
  'dsh-api-job-controller': ['job/list', 'job/follow', 'job/kill'],
  'dsh-api-workspace-files': ['workspaceFiles/readBytes', 'workspaceFiles/list', 'workspaceFiles/read'],
  'dsh-goal': ['goals/create', 'goals/edit', 'goals/get', 'goals/clear', 'goals/pause', 'goals/resume', 'goals/complete'],
  'dsh-schedule': ['schedule/catalog', 'schedule/list', 'schedule/history', 'schedule/delete', 'schedule/update'],
  'dsh-api-terminal-controller': ['terminal/environment', 'terminal/shells', 'terminal/list', 'terminal/create', 'terminal/retain', 'terminal/attach', 'terminal/rename', 'terminal/close'],
}
const sources = []
const methods = {}
for (const [name, selected] of Object.entries(packages)) {
  if (mode === '--session-only' && name !== 'dsh-api-session-controller') continue
  if (mode === '--goal-only' && name !== 'dsh-goal') continue
  if (mode === '--schedule-only' && name !== 'dsh-schedule') continue
  if (mode === '--terminal-only' && name !== 'dsh-api-terminal-controller') continue
  const dir = packageInfo.name === `@deepseek-ai/${name}` ? root : join(root, 'node_modules', '@deepseek-ai', name)
  const packageVersion = JSON.parse(await readFile(join(dir, 'package.json'), 'utf8')).version
  if (packageVersion !== version) throw new Error(`${name} version mismatch: ${packageVersion}`)
  const file = join(dir, 'lib', 'typert.host.js')
  const bytes = await readFile(file)
  const { TYPERT } = await import(pathToFileURL(file).href)
  if (!TYPERT?.invocations) throw new Error(`${name} has no generated invocation descriptors`)
  sources.push({ package: `@deepseek-ai/${name}`, version, generatedFile: 'lib/typert.host.js', sha256: createHash('sha256').update(bytes).digest('hex') })
  for (const endpoint of selected) {
    const descriptor = TYPERT.invocations.find(item => `${item.namespace}/${item.method}` === endpoint)
    if (!descriptor) throw new Error(`Official descriptor missing: ${endpoint}`)
    const properties = {}
    const required = []
    for (const parameter of descriptor.parameters) {
      const wire = parameter.wire ?? parameter.name
      const schema = parameter.strictCodec?.jsonSchema ?? parameter.codec?.jsonSchema
      if (!schema) throw new Error(`${endpoint}:${wire} has no published JSON schema`)
      properties[wire] = schema
      if (!parameter.optional) required.push(wire)
    }
    methods[endpoint] = {
      sourcePackage: `@deepseek-ai/${name}`,
      sourceLocation: descriptor.sourceLocation,
      // The official gateway wraps the named parameters in args.
      args: { type: 'object', properties, required, additionalProperties: false },
    }
    const result = descriptor.result?.spec?.jsonSchema
    if (descriptor.result?.spec?.jsonSchemaError) methods[endpoint].resultSchemaError = descriptor.result.spec.jsonSchemaError
    if (result && (name === 'dsh-goal' || name === 'dsh-schedule' || name === 'dsh-api-terminal-controller' || ['session/create', 'session/selectModel', 'userQuestions/attachWait', 'job/kill'].includes(endpoint))) methods[endpoint].result = result
  }
}
const result = { version: 1, upstream: { repository: 'https://github.com/deepseek-ai/deepseek-harness', tag: `dsh-v${version}`, commit: commits[version], version }, sources, methods }
// Exclusive create prevents an accidental fixture refresh without reviewing the old contract.
await writeFile(resolve(outputArg), `${JSON.stringify(result, null, 2)}\n`, { flag: 'wx' })
console.log(`Exported ${Object.keys(methods).length} official DSH method contracts`)
