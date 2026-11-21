#!/usr/bin/env bash
# tap-agent on one device: protect automatic activity, act by ref, stop/release, then export
# durable events/logs and ordinary history. No input is replayed to repair evidence.
# TAP_BIN=<tap> SERIAL=emulator-5554 .github/scripts/agent-smoke.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TAP_BIN=${TAP_BIN:-$ROOT/host/daemon/build/install/tap/bin/tap}
SERIAL=${SERIAL:-emulator-5554}
APK=${TAP_FIXTURE_APK:-$ROOT/fixture-app/build/outputs/apk/debug/fixture-app-debug.apk}
OUT=${OUT:-$ROOT/build/agent-smoke}
PACKAGE=io.github.noamcohen48.tap.fixture
AGENT=${TAP_AGENT:-tap-agent}
RECORDING=ci-smoke-$$
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
step record start "$RECORDING"
step history mark smoke
step tap "$ref" --settle
step wait "text=View tapped"
step record stop "$RECORDING"
step release
# Invoke directly: step's human-readable command echo must not corrupt JSON stdout.
"$AGENT" record export "$RECORDING" --logs --json > "$OUT/recording.json"
"$AGENT" history --json > "$OUT/history.json"

python3 - "$OUT/history.json" "$PACKAGE" "$OUT/recording.json" "$SERIAL" <<'PY'
import json
import sys

with open(sys.argv[3]) as source:
    recording = json.load(source)
assert recording["format"] == "tap-history/1", recording["format"]
steps = [(s["action"], s["outcome"]["status"]) for s in recording["steps"]]
print("recorded:", steps)
assert [a for a, _ in steps] == ["tap", "wait"], steps
assert steps[0][1] in ("succeeded", "post_settle_timeout") and steps[1][1] == "succeeded", steps
log = recording["log"][sys.argv[4]]["entries"]
assert any(e.get("marker") == "smoke" for e in log), log[:3]
taps = [e["action"] for e in log if e.get("action", {}).get("operation") == "tap"]
assert taps and all(a.get("step") == recording["steps"][0]["id"] for a in taps), taps
with open(sys.argv[1]) as source:
    history = json.load(source)
assert history["format"] == "tap-history/1"
events = history["events"]
calls = [e.get("app", {}).get("operation") or next(iter(k for k in e["command"] if k != "timeout_ms")) for e in events]
print("history:", calls)
assert calls[:2] == ["install", "cold_launch"], calls
tap = next(e for e in recording["events"] if "tap" in e.get("command", {}))
operands = tap["command"]["tap"]["selector"]["node"]["all_of"]["nodes"]
assert tap["ok"] and {"resource": {"name": "view_button"}} in operands, tap
assert {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": sys.argv[2], "mode": "MATCH_EXACT"}} in operands, tap
assert tap["attached_device_id"] and all(e["ok"] for e in events), events
PY
step record clean "$RECORDING"
