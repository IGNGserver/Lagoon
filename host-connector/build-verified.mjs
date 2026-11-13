#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { cp, lstat, mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { applyOverlay, UPSTREAM_REVISION } from './apply-upstream.mjs';

const ownRoot = dirname(fileURLToPath(import.meta.url));
const usage = 'node host-connector/build-verified.mjs --upstream <pinned-source> --out <NEW-scratch-directory> --tools <scratch-node_modules> [--router-only]';
const inside = (root, path) => { const tail = relative(root, path); return tail === '' || (!tail.startsWith(`..${sep}`) && tail !== '..' && !isAbsolute(tail)); };
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
async function exists(path) {
  try { await lstat(path); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; }
}
async function canonical(path) {
  try { return await realpath(path); } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const parent = dirname(path);
    if (parent === path) throw error;
    return join(await canonical(parent), relative(parent, path));
  }
}
async function refuseGitOutput(path) {
  for (let cursor = path; ; cursor = dirname(cursor)) {
    if (await exists(join(cursor, '.git'))) throw new Error(`Refusing output in a Git checkout: ${path}`);
    if (cursor === dirname(cursor)) break;
  }
}
function options(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index++) {
    const name = argv[index];
    if (name === '--router-only') { result.routerOnly = true; continue; }
    if (!['--upstream', '--out', '--tools'].includes(name) || !argv[index + 1] || argv[index + 1].startsWith('--') || result[name.slice(2)]) throw new Error(usage);
    result[name.slice(2)] = argv[++index];
  }
  if (!result.upstream || !result.out || !result.tools) throw new Error(usage);
  return result;
}

/** Never changes the input source, installs packages, or writes into a Git checkout. */
export async function buildVerified({ upstream, out, tools, routerOnly = false }) {
  const sourceInput = await realpath(resolve(upstream));
  const toolsRoot = await realpath(resolve(tools));
  const output = await canonical(resolve(out));
  await refuseGitOutput(output);
  if (await exists(output)) throw new Error(`Output must be a new directory: ${output}`);
  if (inside(sourceInput, output) || inside(output, sourceInput) || inside(toolsRoot, output) || inside(output, toolsRoot)) throw new Error('Output must not contain, or be inside, source input or tooling.');
  const toolRequire = createRequire(join(toolsRoot, '__lagoon_build__.cjs'));
  const esbuild = await import(pathToFileURL(toolRequire.resolve('esbuild')));
  const sourceRoot = join(output, 'source');
  const artifacts = join(output, 'artifacts');
  // Copy only build inputs; no dist, tests, Git metadata or node_modules from upstream.
  const files = ['tsconfig.base.json', 'LICENSE'];
  for (const name of ['plugin', 'protocol', 'client-core', 'crypto', 'webrtc']) {
    files.push(`packages/${name}/src`, `packages/${name}/package.json`);
  }
  files.push('packages/plugin/scripts/build-bundles.mjs');
  for (const path of files) {
    const stat = await lstat(join(sourceInput, path));
    if (stat.isSymbolicLink() || (!stat.isDirectory() && !stat.isFile())) throw new Error(`Unsupported upstream input: ${path}`);
  }
  await mkdir(output, { recursive: false });
  for (const path of files) {
    await mkdir(dirname(join(sourceRoot, path)), { recursive: true });
    await cp(join(sourceInput, path), join(sourceRoot, path), { recursive: true, dereference: false, errorOnExist: true, force: false, filter: async input => {
      if ((await lstat(input)).isSymbolicLink()) throw new Error(`Upstream source must not contain symlinks: ${input}`);
      return true;
    } });
  }
  const overlay = await applyOverlay(sourceRoot);
  await mkdir(artifacts);
  await writeFile(join(artifacts, 'UPSTREAM-LICENSE.txt'), await readFile(join(sourceRoot, 'LICENSE')));
  const alias = Object.fromEntries(['protocol', 'client-core', 'crypto', 'webrtc'].map(name => [`@dsh-remote/${name}`, join(sourceRoot, 'packages', name, 'src/index.ts')]));
  const common = {
    absWorkingDir: sourceRoot, bundle: true, platform: 'node', format: 'esm', target: 'node20',
    alias, nodePaths: [toolsRoot], tsconfigRaw: { compilerOptions: { target: 'ES2022', useDefineForClassFields: true } },
    sourcemap: true, metafile: true, logLevel: 'warning',
    external: ['@deepseek-ai/dsh-client-connection', '@roamhq/wrtc'],
    banner: { js: 'import { createRequire as __lagoonCreateRequire } from "node:module";\nconst require = __lagoonCreateRequire(import.meta.url);' },
  };
  const routerFile = join(artifacts, 'patched-router.mjs');
  const entries = [{ name: 'patched-router', outfile: routerFile, stdin: {
    contents: [
      "export { RpcRouter } from './packages/plugin/src/rpc-router.ts';",
      "export { HarnessRemoteBridge } from './packages/plugin/src/harness-remote-bridge.ts';",
      "export { TerminalPolicy } from './packages/plugin/src/terminal-policy.ts';",
      "export { TerminalCarrierCompat } from './packages/plugin/src/terminal-carrier-compat.ts';",
      "export { ConnectionController, ConnectionRejectedError } from './packages/plugin/src/connection-controller.ts';",
      "export { NativeServicePeer, NativeServiceError, NATIVE_SERVICE_CAPABILITY } from './packages/plugin/src/lagoon-native-services.mjs';",
      "export { DshChangesPeer, DshChangesError, DSH_CHANGES_CAPABILITY } from './packages/plugin/src/lagoon-dsh-changes.mjs';",
      "export { createRpcRequest, createEvent, parseRemoteMessage } from './packages/protocol/src/index.ts';",
    ].join('\n'), resolveDir: sourceRoot, sourcefile: 'verification-entry.ts', loader: 'ts',
  } }];
  if (!routerOnly) {
    entries.push({ name: 'host-runtime', outfile: join(artifacts, 'host-runtime.mjs'), entryPoints: [join(sourceRoot, 'packages/plugin/src/service.ts')] });
    entries.push({ name: 'plugin', outfile: join(artifacts, 'index.mjs'), entryPoints: [join(sourceRoot, 'packages/plugin/src/index.ts')] });
    entries.push({ name: 'agy-gateway', outfile: join(artifacts, 'agy-gateway.mjs'), stdin: {
      contents: [
        "export { AcpRemoteGateway } from './packages/plugin/src/acp/gateway.ts';",
        "export { CursorAcpClient } from './packages/plugin/src/acp/adapters/cursor-process.ts';",
        "export { parseAgyElicitation, validateAgyElicitationResult, cancelAgyRequest } from './packages/plugin/src/acp/lagoon-agy-elicitation.mjs';",
      ].join('\n'), resolveDir: sourceRoot, sourcefile: 'agy-verification-entry.ts', loader: 'ts',
    } });
  }
  const bundles = [];
  for (const { name, ...entry } of entries) {
    const result = await esbuild.build({ ...common, ...entry });
    await writeFile(join(artifacts, `${name}.meta.json`), `${JSON.stringify(result.metafile, null, 2)}\n`);
    const bytes = await readFile(entry.outfile);
    bundles.push({ name, path: relative(output, entry.outfile), bytes: bytes.length, sha256: sha256(bytes) });
  }
  const metadata = {
    upstreamRevision: UPSTREAM_REVISION, overlay, nodeVersion: process.version, esbuildVersion: esbuild.version,
    mode: routerOnly ? 'router-only' : 'full-plugin',
    externalRuntimeDependencies: ['@deepseek-ai/dsh-client-connection', '@roamhq/wrtc'], bundles,
  };
  await writeFile(join(output, 'build-manifest.json'), `${JSON.stringify(metadata, null, 2)}\n`);
  return { ...metadata, output, routerFile };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { console.log(JSON.stringify(await buildVerified(options(process.argv.slice(2))), null, 2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
