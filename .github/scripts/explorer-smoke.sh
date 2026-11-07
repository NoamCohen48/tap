#!/usr/bin/env bash
# Explicit CI/operator orchestration, not client auto-start: reuse a running daemon or
# start one with TAP_BIN and stop only that daemon afterward. Requires a named serial.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TAP_BIN=${TAP_BIN:-$ROOT/host/daemon/build/install/tap/bin/tap}
SERIAL=${SERIAL:?set SERIAL explicitly}
APK=${APK:-$ROOT/samples/explorer-app/build/outputs/apk/debug/explorer-app-debug.apk}
OUT=${OUT:-$ROOT/build/explorer-smoke/run-$(date -u +%Y%m%dT%H%M%S)-$$}
EXPLORER=${TAP_EXPLORER:-tap-explorer}

started=
if ! "$TAP_BIN" status >/dev/null 2>&1; then
  "$TAP_BIN" start
  started=1
fi
cleanup() {
  if [ -n "$started" ]; then "$TAP_BIN" stop; fi
}
trap cleanup EXIT

"$EXPLORER" sample --serial "$SERIAL" --apk "$APK" --out "$OUT"
