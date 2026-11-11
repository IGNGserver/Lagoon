# Lagoon native-service Host overlay

An opt-in source overlay for `ds-harness-remote` revision
`bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b`. It adds native OpenCode
HTTP, binary-transfer, SSE and WebSocket RPCs to the existing authenticated
Remote connection. It is not an ACP adapter, a separate public proxy, or a
mechanism to attach to a running Desktop DSH agent.

## Build in scratch only

Use an unmodified copy of that upstream revision and an existing scratch
`node_modules`. Node 20+ and esbuild are required. No command below installs,
publishes, deploys, or changes the input upstream tree.

```sh
node host-connector/build-verified.mjs \
  --upstream "$HOME/.build/Lagoon/unified-harness-connections/remote-upstream/ds-harness-remote-bb33f0a6eb676b61f40a9bbf7d365e8a5e93b81b" \
  --out "$HOME/.build/Lagoon/unified-harness-connections/connector-verified-NEW" \
  --tools "$HOME/.build/Lagoon/unified-harness-connections/connector-tools/node_modules"
```

The output must not exist. The script resolves symlinks and rejects outputs
under any ancestor containing `.git` (including worktrees), or overlapping the
upstream/tooling directories. It copies only source/build inputs into a new
scratch directory, calls [the checksum-gated overlay](<apply-upstream.mjs>), and
bundles the actual upstream router, Host runtime and plugin entry. The overlay
checks the pinned SHA-256 of its four upstream input files before changing the
copy; obtain the complete pinned source from a trusted checkout/archive.

Default outputs are `artifacts/patched-router.mjs`, `artifacts/host-runtime.mjs`,
`artifacts/index.mjs`, their source maps/metafiles, and `build-manifest.json`
with revision, tool versions, byte counts and bundle SHA-256. The upstream MIT
license is retained as `artifacts/UPSTREAM-LICENSE.txt` alongside the generated bundles. Runtime dependencies
are bundled, except the official shared `@deepseek-ai/dsh-client-connection`
peer and optional native `@roamhq/wrtc`, matching upstream's Host bundler policy.
Missing dependencies fail the build; esbuild transpilation is not a TypeScript
typecheck or an upstream packaging/release run.

For just router/connection integration, append `--router-only`. It requires
only esbuild, zod and ws in the scratch toolchain. The full build additionally
requires upstream runtime dependencies: `@deepseek-ai/schemastery`, `qrcode`,
`@xterm/headless`, `@xterm/addon-serialize`, `@lukeburns/clatterjs`,
`@noble/ciphers`, `@noble/curves`, `@noble/hashes`, and `werift`; use the pinned
upstream manifests/lockfile for versions. Never install into this repository.

The verifier toolchain has a committed [manifest](<toolchain/package.json>) and [lockfile](<toolchain/package-lock.json>). Copy both into a scratch tools directory and run `npm ci --prefix <scratch-tools> --ignore-scripts --no-audit --no-fund`; do not install here. CI downloads the exact upstream archive, verifies its SHA-256, builds the full plugin and runs these tests before Android verification.

## Integration test

```sh
NODE_PATH="$HOME/.build/Lagoon/unified-harness-connections/connector-tools/node_modules" \
LAGOON_PATCHED_ROUTER="$HOME/.build/Lagoon/unified-harness-connections/connector-verified-NEW/artifacts/patched-router.mjs" \
  node --test host-connector/test/upstream-router.test.mjs
```

[The router test](<test/upstream-router.test.mjs>) fails when the bundle path is
absent; it does not fall back to a mock or silently skip. It uses actual upstream
v1 Remote messages and actual loopback HTTP/SSE/WebSocket fixtures to check:

- native capability only when a peer is supplied; legacy Host reports
  `FEATURE_NOT_SUPPORTED` for all ten registered methods;
- static, typed, non-retryable errors and no registration credentials in
  responses, frames or router logs;
- one mutation followed by sequential cached binary chunks, without reissuing it;
- SSE and text/binary WebSocket open/send/close, router teardown calling native
  `closeAll`, and sibling connections/external service surviving;
- actual `ConnectionController` rejects missing Noise context and untrusted
  identities before creating a native peer, then routes and tears down an
  accepted connection.

This validates the patched router/connection seam, not a physical Android-to-
production-Host end-to-end connection. [Peer-focused tests](<test/native-services.test.mjs>)
cover additional registration, allowlist, expiry and resource-limit cases.

## Real official-service acceptance (explicit opt-in)

[The official-service test](<test/official-service.mjs>) reads a caller-owned, isolated OpenCode **2.2.6** registration. It never starts/stops a daemon, sends a model prompt, or writes its registration. Run against a scratch copy with the locked tooling dependencies available:

```sh
LAGOON_OFFICIAL_OPENCODE_REGISTRATION=/absolute/private/scratch/state/opencode/service.json \
  node --test test/official-service.mjs
```

The actual release binary protects `/api/info` despite the OpenAPI public declaration. Before **every** credential-bearing HTTP/WebSocket request, the connector proves the registered PID owns the server side of that exact established TCP socket using Linux `/proc` inode/tuple evidence, then authenticates on the same socket. A bounded anonymous HTTP activation handles deferred-accept listeners. Connection/activation/proof have a five-second absolute deadline; no business mutation is retried. Public identity JSON alone is never sufficient evidence.

**Platform limit:** this ownership proof currently supports Linux only. Other OSes, restricted `/proc`, mismatched PID, malformed/closing/oversized anonymous responses and unsupported framing fail closed with `IDENTITY_UNVERIFIED`; they do not silently send the registration password. macOS/Windows native Host support is not claimed.

Android JVM opt-ins are `LAGOON_OFFICIAL_OPENCODE_REGISTRATION`, `LAGOON_OFFICIAL_DSH_CONNECTION` (private JSON `{url,token}` for a fresh standalone official DSH WebHost), and `LAGOON_OFFICIAL_SSH_CONNECTION` (scratch-confined pinned SSH descriptor). Unset variables skip only those explicit live tests; provided invalid configurations fail. With the staged stdio Host and actual patched Router enabled, the encrypted native test also targets the official daemon. These tests do not replace public-Hub, production-Host, Desktop-attachment or Android-device acceptance.

## Remote DSH terminal verification

The verification entry also exports the pinned `HarnessRemoteBridge`, `TerminalPolicy` and
`TerminalCarrierCompat`, without changing their behavior. [Terminal policy tests](<test/upstream-terminal.test.mjs>)
exercise Host enablement, device ownership, attachment identity, provider-ACK waits and detach-only
cleanup against those actual implementations. They use a processless native provider, not an
invented bridge.

[The terminal stdio Host](<test/stdio-terminal-host.mjs>) supports Kotlin Noise integration through
the same router. Its optional [official HTTP/mux adapter](<test/official-terminal-gateway.mjs>) reads
`LAGOON_STDIO_OFFICIAL_DSH_CONNECTION` only for an explicitly supplied isolated official WebHost.
The JVM opt-in is `LAGOON_OFFICIAL_REMOTE_DSH_CONNECTION`, together with `LAGOON_TERMINAL_STDIO_HOST`
and `LAGOON_PATCHED_ROUTER`. The test creates its own Session/terminal, sends a fixed `printf` probe,
and explicitly closes the terminal; it never sends a model prompt or touches the user's WebHost.
No public-Hub or Android-device acceptance is implied.

## Local Host opt-in

Set these on the Host process that loads the patched plugin:

```sh
LAGOON_NATIVE_SERVICES=1
# Optional JSON array of absolute Host-local registration paths:
LAGOON_NATIVE_REGISTRATIONS='["/absolute/private/state/opencode/service.json"]'
```

Without the path override, discovery reads
`$XDG_STATE_HOME/opencode/service.json`, or
`$HOME/.local/state/opencode/service.json`. Registration contents (including
passwords and loopback addresses) stay local. Native simple PTY uses the instance's documented
HTTP/WS routes; `x-opencode-ticket: 1` is accepted only on its POST connect-token route.
The returned short-lived, single-use ticket is not a long-lived Host credential. Attachment
cancellation never deletes the PTY; process creation and termination require explicit UI actions. Do not pass registration paths or
credentials through Remote RPC parameters. Each accepted, trusted connection owns
its own peer and handles; disconnect only cancels that peer's operations, never
stops the externally running native service. See [the protocol](<../docs/NATIVE_SERVICE_PROTOCOL.md>)
for the wire contract and limits.
