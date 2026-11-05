#!/usr/bin/env bash
# End-to-end check of Lagoon's DSH Remote client against a real Hub + Host.
#
#   DSH_REMOTE_UPSTREAM=/path/to/ds-harness-remote \
#   DSH_PLUGIN_SPEC=ds-harness-remote@0.6.0 \
#   ./scripts/remote-e2e.sh
#
# Optional:
#   LAGOON_E2E_SESSION_SOURCE=/home/me/.dsh/sessions/--...--  copy one session into the host home
#   DSH_BIN=/path/to/dsh
#
# Requires: bash, node >= 22, npm, network for npm install / plugin install, a local DSH checkout
# (apps/server/src + packages/protocol/src) and the Android SDK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="${DSH_REMOTE_UPSTREAM:-$ROOT/../ds-harness-remote}"
DSH_BIN="${DSH_BIN:-dsh}"
PLUGIN_SPEC="${DSH_PLUGIN_SPEC:-ds-harness-remote@0.6.0}"
ACCOUNT="${LAGOON_E2E_ACCOUNT:-e2e@example.test}"
PASSWORD="${LAGOON_E2E_PASSWORD:-e2e-$(node -e 'console.log(require("crypto").randomBytes(12).toString("hex"))')}"

[[ -f "$UPSTREAM/apps/server/src/main.ts" ]] || {
  echo "missing upstream server sources: $UPSTREAM" >&2
  echo "set DSH_REMOTE_UPSTREAM to a ds-harness-remote checkout" >&2
  exit 2
}
command -v "$DSH_BIN" >/dev/null || { echo "dsh not found (set DSH_BIN)" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/lagoon-remote-e2e.XXXXXX")"
SERVER_PID=""
HOST_PID=""
cleanup() {
  [[ -n "$HOST_PID" ]] && kill "$HOST_PID" 2>/dev/null || true
  [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  if [[ "${LAGOON_E2E_KEEP:-0}" == 1 ]]; then echo "[e2e] kept $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT
log() { printf '[e2e] %s\n' "$*"; }
wait_for() { # file pattern seconds
  for _ in $(seq 1 $(( $3 * 4 ))); do grep -q "$2" "$1" 2>/dev/null && return 0; sleep 0.25; done
  echo "timed out waiting for '$2' in $1" >&2
  tail -30 "$1" >&2 || true
  return 1
}

log "building self-hosted Hub from $UPSTREAM"
mkdir -p "$WORK/server/src/server" "$WORK/server/src/protocol" "$WORK/dsh-home" "$WORK/work"
cp "$UPSTREAM"/apps/server/src/*.ts "$WORK/server/src/server/"
cp "$UPSTREAM"/packages/protocol/src/*.ts "$WORK/server/src/protocol/"
echo '{"name":"lagoon-e2e-server","private":true,"type":"module"}' > "$WORK/server/package.json"
(cd "$WORK/server" && npm install --silent --no-audit --no-fund --registry=https://registry.npmjs.org/ \
  ws@8.21.3 zod@4.6.7 esbuild@0.24.2 >/dev/null)
(cd "$WORK/server" && ./node_modules/.bin/esbuild src/server/main.ts --bundle --platform=node --format=esm \
  --target=node22 --alias:@dsh-remote/protocol=./src/protocol/index.ts --outfile=dist/main.js --log-level=warning \
  --banner:js="import { createRequire } from 'module'; const require = createRequire(import.meta.url);")

PORT="$(node -e 'const s=require("net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')"
SERVER_URL="http://127.0.0.1:$PORT"
(cd "$WORK/server" && DSH_SERVER_ACCOUNT="$ACCOUNT" DSH_SERVER_PASSWORD="$PASSWORD" DSH_SERVER_PUBLIC_URL="$SERVER_URL" \
  DSH_SERVER_PORT="$PORT" DSH_SERVER_HOST=127.0.0.1 DSH_SERVER_DATA_FILE="$WORK/server-data/state.json" \
  exec node dist/main.js >"$WORK/server.log" 2>&1) &
SERVER_PID=$!
wait_for "$WORK/server.log" "listening" 20
log "Hub at $SERVER_URL"

export DSH_HOME="$WORK/dsh-home"
# A local plugin checkout may declare optional native WebRTC deps; Relay-only E2E does not need them.
if [[ -d "${PLUGIN_SPEC#file:}" ]]; then
  mkdir -p "$WORK/plugin"
  cp -a "${PLUGIN_SPEC#file:}/." "$WORK/plugin/"
  node -e 'const fs=require("fs");const p=process.argv[1];const o=JSON.parse(fs.readFileSync(p));delete o.optionalDependencies;fs.writeFileSync(p,JSON.stringify(o,null,2))' "$WORK/plugin/package.json"
  PLUGIN_SPEC="file:$WORK/plugin"
fi
log "installing plugin $PLUGIN_SPEC into an isolated profile"
(cd "$WORK" && "$DSH_BIN" plugin --profile web add -w "$PLUGIN_SPEC" >"$WORK/plugin-install.log" 2>&1) || {
  tail -40 "$WORK/plugin-install.log" >&2
  exit 1
}
# `dsh plugin add` can leave package.json unchanged when it is interrupted; make the bundle explicit.
node - "$DSH_HOME" "$PLUGIN_SPEC" <<'NODE'
const fs = require('fs')
const [home, spec] = process.argv.slice(2)
const path = `${home}/profiles/web/package.json`
const file = JSON.parse(fs.readFileSync(path, 'utf8'))
file.dependencies = file.dependencies || {}
file.dependencies['ds-harness-remote'] = spec.startsWith('/') ? `file:${spec}` : spec
file.dsh = file.dsh || {}
file.dsh.profile = file.dsh.profile || {}
file.dsh.profile.bundles = [...new Set([...(file.dsh.profile.bundles || []), 'ds-harness-remote'])]
fs.writeFileSync(path, JSON.stringify(file, null, 2))
NODE
cat >"$DSH_HOME/profiles/web/cordis.patch.yml" <<EOF
- id: ds-harness-remote
  config:
    enabled: true
    role: host
    serverUrl: $SERVER_URL
    deviceName: e2e-host
EOF
node "$ROOT/scripts/remote-e2e/provision-host.mjs" "$SERVER_URL" "$ACCOUNT" "$PASSWORD" "$DSH_HOME" "e2e-host" >"$WORK/host.json"
cat "$WORK/host.json"

if [[ -n "${LAGOON_E2E_SESSION_SOURCE:-}" ]]; then
  log "copying session fixture"
  mkdir -p "$DSH_HOME/sessions"
  # A session directory lives inside its workspace directory; copy the whole workspace so the
  # Host's session index and workspace lookup stay consistent.
  if [[ -d "$LAGOON_E2E_SESSION_SOURCE" && -e "$LAGOON_E2E_SESSION_SOURCE/session.v4.jsonl.zstd" ]]; then
    cp -a "$(dirname "$LAGOON_E2E_SESSION_SOURCE")" "$DSH_HOME/sessions/" 2>/dev/null || true
  else
    cp -a "$LAGOON_E2E_SESSION_SOURCE" "$DSH_HOME/sessions/" 2>/dev/null || true
  fi
fi

(cd "$WORK/work" && exec env -u DEEPSEEK_API_KEY "$DSH_BIN" web --host 127.0.0.1 --port 0 --no-open >"$WORK/host.log" 2>&1) &
HOST_PID=$!
wait_for "$WORK/server.log" '"role":"host"' 90
log "Host online"
sleep 2

log "running the live probe"
cd "$ROOT"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" \
BUILD_SCRATCH_ROOT="${BUILD_SCRATCH_ROOT:-$HOME/.build-lagoon-remote}" \
LAGOON_REMOTE_BASE="$SERVER_URL" LAGOON_REMOTE_ACCOUNT="$ACCOUNT" LAGOON_REMOTE_PASSWORD="$PASSWORD" \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests '*RemoteLiveProbeTest*' --console=plain
log "ok"
