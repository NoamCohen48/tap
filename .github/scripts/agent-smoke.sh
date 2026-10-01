#!/usr/bin/env bash
# tap-agent end to end on one device: attach, install and cold-launch the fixture app, snapshot,
# tap by ref with --settle, wait, export the session log and check it, release.
#
#   TAP_BIN=<tap> SERIAL=emulator-5554 .github/scripts/agent-smoke.sh
#
# Uses a running daemon, or starts one with $TAP_BIN and stops it afterwards. Output (the
# exported JSON) goes to $OUT (default build/agent-smoke).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TAP_BIN=${TAP_BIN:-$ROOT/host/daemon/build/install/tap/bin/tap}
SERIAL=${SERIAL:-emulator-5554}
APK=${TAP_FIXTURE_APK:-$ROOT/fixture-app/build/outputs/apk/debug/fixture-app-debug.apk}
OUT=${OUT:-$ROOT/build/agent-smoke}
PACKAGE=io.github.noamcohen48.tap.fixture
AGENT=${TAP_AGENT:-tap-agent}
export TAP_AGENT_SESSION=ci-smoke TAP_AGENT_DIR=$OUT

mkdir -p "$OUT"
started=
if ! "$TAP_BIN" status >/dev/null 2>&1; then
  "$TAP_BIN" start
  started=1
fi
cleanup() {
  "$AGENT" release >/dev/null 2>&1 || true
  if [ -n "$started" ]; then "$TAP_BIN" stop; fi
}
trap cleanup EXIT

step() { echo "\$ tap-agent $*"; "$AGENT" "$@"; }

step attach "$SERIAL"
step app install "$PACKAGE" "$APK"
step app cold-launch "$PACKAGE"
step snapshot -i | tee "$OUT/snapshot.txt"
ref=$(sed -n 's/^\(@e[0-9]*\) .*id=view_button.*/\1/p' "$OUT/snapshot.txt" | head -1)
[ -n "$ref" ] || { echo "no view_button in the snapshot"; exit 1; }
step tap "$ref" --settle
step wait "text=View tapped"
step export -o "$OUT/export.json"

python3 - "$OUT/export.json" "$PACKAGE" <<'EOF'
import json, sys
doc = json.load(open(sys.argv[1]))
assert doc["format"] == "tap-events/1", doc["format"]
calls = [e.get("app", {}).get("operation") or next(iter(k for k in e["command"] if k != "timeout_ms")) for e in doc["events"]]
print("exported:", calls)
assert calls[:2] == ["install", "cold_launch"], calls
tap = next(e for e in doc["events"] if "command" in e and "tap" in e["command"])
# The ref was logged as the selector it stood for: the id, bound to the app's package.
operands = tap["command"]["tap"]["selector"]["node"]["all_of"]["nodes"]
assert tap["ok"] and {"resource": {"name": "view_button"}} in operands, tap
assert {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": sys.argv[2], "mode": "MATCH_EXACT"}} in operands, tap
assert all(e["ok"] for e in doc["events"]), doc["events"]
EOF
step release
