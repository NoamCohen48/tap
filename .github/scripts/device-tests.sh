#!/usr/bin/env bash
# The CI device lane, run inside reactivecircus/android-emulator-runner. That action runs each
# line of its `script` as a separate `sh -c`, so exports and line continuations do not carry
# over; everything lives here instead.
#
# One serial (emulator-5554), so the two-device tests are skipped. Each suite starts the daemon
# (tap.manageDaemon / TAP_MANAGE_DAEMON) and stops it after its last test; the agent smoke
# starts and stops its own.
set -euo pipefail

SERIAL=emulator-5554
adb -s "$SERIAL" wait-for-device
# A 2-vCPU swiftshader emulator throws "Process system isn't responding" ANR dialogs (package
# android) over the AUT, which then never owns the focused window.
adb -s "$SERIAL" shell settings put global hide_error_dialogs 1
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$SERIAL" shell settings put global "$scale" 0
done
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
adb -s "$SERIAL" shell wm dismiss-keyguard
adb -s "$SERIAL" shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' || true

echo "::group::Kotlin sample suite"
./gradlew :samples:fixture-tests:test -Ptap.serials="$SERIAL"
echo "::endgroup::"

export TAP_BIN="$PWD/host/daemon/build/install/tap/bin/tap"

echo "::group::Python sample suite"
TAP_MANAGE_DAEMON=1 TAP_SERIALS="$SERIAL" python -m pytest -q clients/python/tests
echo "::endgroup::"

echo "::group::tap-agent smoke"
SERIAL="$SERIAL" .github/scripts/agent-smoke.sh
echo "::endgroup::"
