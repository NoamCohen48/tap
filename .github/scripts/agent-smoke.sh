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
# The server keeps every session's history on this machine: read only what this run did.
STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
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
step history keep start "$RECORDING"
step history mark smoke
step tap "$ref" --settle
step wait "text=View tapped"
step history keep stop "$RECORDING"
step release
# Invoke directly: step's human-readable command echo must not corrupt JSON stdout.
"$AGENT" history --kept "$RECORDING" --logs --json > "$OUT/recording.json"
"$AGENT" history --from "$STARTED_AT" --json > "$OUT/history.json"

python3 - "$OUT/history.json" "$PACKAGE" "$OUT/recording.json" <<'PY'
import json
import sys

def calls(document):
    return [e["call"] for e in document["entries"] if e["kind"] == "call"]

with open(sys.argv[3]) as source:
    recording = json.load(source)
assert recording["format"] == "tap-history/2", recording["format"]
assert recording["kept"]["stopped_at"], recording["kept"]
steps = [(s["action"], s["outcome"]["status"]) for s in recording["steps"]]
print("recorded:", steps)
assert [a for a, _ in steps] == ["tap", "wait"], steps
assert steps[0][1] in ("succeeded", "post_settle_timeout") and steps[1][1] == "succeeded", steps
entries = recording["entries"]
assert any(e.get("marker") == "smoke" for e in entries), entries[:3]
taps = [e["action"] for e in entries if e["kind"] == "action" and e["action"]["operation"] == "tap"]
assert taps and all(a.get("step") == recording["steps"][0]["id"] for a in taps), taps
with open(sys.argv[1]) as source:
    history = json.load(source)
assert history["format"] == "tap-history/2"
made = calls(history)
names = [c.get("app", {}).get("operation") or next(iter(k for k in c.get("command", {}) if k != "timeout_ms"), "?") for c in made]
print("history:", names)
assert names[:2] == ["install", "cold_launch"], names
tap = next(c for c in calls(recording) if "tap" in c.get("command", {}))
operands = tap["command"]["tap"]["selector"]["node"]["all_of"]["nodes"]
assert tap["ok"] and {"resource": {"name": "view_button"}} in operands, tap
assert {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": sys.argv[2], "mode": "MATCH_EXACT"}} in operands, tap
assert tap["attached_device_id"] and all(c["ok"] for c in made), made
PY
step history keep clean "$RECORDING"
