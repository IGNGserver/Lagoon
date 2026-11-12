#!/usr/bin/env node
/** Build the pinned upstream Codex domain for the optional, isolated Noise interoperability test. */
import { createHash } from 'node:crypto';
import { copyFile, lstat, mkdir, readFile, realpath } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const EXPECTED_ARCHIVE = '9c7f2a773199f294f1edb97347ff142e43b7b3bf66f8949b2caf3e264ea240b8';
const ownRoot = dirname(fileURLToPath(import.meta.url));
const usage = 'node host-connector/test/build-codex-smoke.mjs --archive <bb33.tar.gz> --tools <node_modules> --out <new-scratch-dir>';
const options = {};
for (let i = 2; i < process.argv.length; i++) {
  const name = process.argv[i];
  if (!['--archive', '--tools', '--out'].includes(name) || !process.argv[i + 1] || options[name]) throw Error(usage);
  options[name] = process.argv[++i];
}
if (!options['--archive'] || !options['--tools'] || !options['--out']) throw Error(usage);
const scratch = await realpath(process.env.BUILD_SCRATCH_ROOT || join(process.env.HOME, '.build'));
const output = resolve(options['--out']);
const parent = await realpath(dirname(output));
const rel = relative(scratch, parent);
if (rel.startsWith(`..${sep}`) || rel === '..' || isAbsolute(rel)) throw Error('Output must be inside BUILD_SCRATCH_ROOT');
for (let current = parent; ; current = dirname(current)) {
  try { await lstat(join(current, '.git')); throw Error('Output cannot be inside a Git checkout'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (dirname(current) === current) break;
}
const archive = await realpath(options['--archive']);
const tools = await realpath(options['--tools']);
const digest = createHash('sha256').update(await readFile(archive)).digest('hex');
if (digest !== EXPECTED_ARCHIVE) throw Error('Pinned upstream archive SHA-256 mismatch');
await mkdir(output); // deliberately refuses an existing directory
const upstream = join(output, 'upstream');
await mkdir(upstream);
const extracted = spawnSync('tar', ['-xzf', archive, '-C', upstream, '--strip-components=1'], { stdio: 'inherit' });
if (extracted.error || extracted.status !== 0) throw extracted.error || Error('Unable to extract pinned upstream');
const require = createRequire(join(tools, '__lagoon_test__.cjs'));
const esbuild = await import(pathToFileURL(require.resolve('esbuild')));
const alias = Object.fromEntries(['protocol', 'client-core', 'crypto', 'webrtc'].map(name => [
  `@dsh-remote/${name}`, join(upstream, 'packages', name, 'src/index.ts'),
]));
await esbuild.build({
  absWorkingDir: upstream, bundle: true, platform: 'node', format: 'esm', target: 'node20', alias,
  nodePaths: [tools],
  banner: { js: 'import { createRequire as __testCreateRequire } from "node:module"; const require = __testCreateRequire(import.meta.url);' },
  stdin: {
    contents: [
      "export { CodexRemoteDomain } from './packages/plugin/src/codex/domain.ts';",
      "export { RpcRouter } from './packages/plugin/src/rpc-router.ts';",
      "export { createRpcRequest, parseRemoteMessage } from './packages/protocol/src/index.ts';",
    ].join('\n'), resolveDir: upstream, sourcefile: 'codex-remote-smoke-entry.ts', loader: 'ts',
  },
  outfile: join(output, 'codex-router.mjs'),
});
await copyFile(join(ownRoot, 'stdio-codex-host.mjs'), join(output, 'stdio-codex-host.mjs'));
console.log(`Pinned Codex smoke Host prepared in ${output}`);
