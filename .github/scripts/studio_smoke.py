#!/usr/bin/env python3
"""tap-studio end to end on one device, through the studio's own API (what the page calls).

    TAP_BIN=<tap> SERIAL=emulator-5554 python .github/scripts/studio_smoke.py

Installs the fixture app, starts `tap-studio --serial … --package …`, signs in with the launch
link, checks the page is served and frames arrive, records a cold launch, a tap, two text
assertions, a set text, a tap picked by index, and the notification shade opened and closed
with Back (the device rail's steps) with the checks that it covered the app and then did not, exports the recording, reopens it after
NewRecording and replays it. Then stops the studio with SIGTERM (the device must be released
at once) and replays the exported file through tap-e2e in a fresh connection.

Uses a running daemon, or starts one with $TAP_BIN and stops it afterwards. The exported file
and the studio's output go to $OUT (default build/studio-smoke).
"""

from __future__ import annotations

import http.client
import os
import pathlib
import re
import signal
import subprocess
import sys
import threading
import time

from tap_e2e import TapClient
from tap_e2e import proto as tap
from tap_e2e.selectors import res, text_matches
from tap_studio import steps
from tap_studio._gen import studio_pb2 as studio
from tap_studio._gen.studio_connect import StudioServiceClientSync
from tap_studio.recording import loads
from tap_studio.server import cookie_name

ROOT = pathlib.Path(__file__).resolve().parents[2]
TAP_BIN = os.environ.get("TAP_BIN", str(ROOT / "host/daemon/build/install/tap/bin/tap"))
SERIAL = os.environ.get("SERIAL", "emulator-5554")
APK = os.environ.get("TAP_FIXTURE_APK", str(ROOT / "fixture-app/build/outputs/apk/debug/fixture-app-debug.apk"))
OUT = pathlib.Path(os.environ.get("OUT", ROOT / "build/studio-smoke"))
STUDIO = os.environ.get("TAP_STUDIO", "tap-studio")
PACKAGE = "io.github.noamcohen48.tap.fixture"
STEPS = 10


def act(command: tap.Command) -> studio.Step:
    return studio.Step(action=studio.ActionStep(command=command))


def expect(selector, condition: int, value: str | None = None) -> studio.Step:
    return studio.Step(assertion=studio.AssertionStep(selector=selector.to_proto(), condition=condition, text=value))


def check(condition: bool, message: str) -> None:
    if not condition:
        sys.exit(f"studio smoke: {message}")


class Studio:
    """A `tap-studio` process and a signed-in Connect client for it."""

    def __init__(self) -> None:
        self.log = (OUT / "studio.log").open("w")
        self.process = subprocess.Popen(
            [STUDIO, "--no-open", "--serial", SERIAL, "--package", PACKAGE],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        self.lines: list[str] = []
        self.ready = threading.Event()
        threading.Thread(target=self._read, daemon=True).start()
        check(self.ready.wait(300), "tap-studio did not attach within 300 s:\n" + "".join(self.lines))
        link = next(line.split()[1] for line in self.lines if line.startswith("Open "))
        match = re.fullmatch(r"http://127\.0\.0\.1:(\d+)/login\?t=(.+)", link)
        check(match is not None, f"unexpected link {link!r}")
        self.port, token = int(match.group(1)), match.group(2)
        self.headers = {"cookie": f"{cookie_name(token)}={token}"}
        self.client = StudioServiceClientSync(f"http://127.0.0.1:{self.port}")
        self.token = token

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            self.lines.append(line)
            self.log.write(line)
            self.log.flush()
            if line.startswith("Attached ") or "could not attach" in line:
                self.ready.set()

    def get(self, path: str) -> http.client.HTTPResponse:
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        connection.request("GET", path)
        return connection.getresponse()

    def perform(self, request: studio.Step) -> studio.Step:
        response = self.client.perform(studio.PerformRequest(step=request), headers=self.headers, timeout_ms=60_000)
        check(response.recorded, f"not recorded: {response.message or response.step}")
        return response.step

    def stop(self) -> float:
        started = time.monotonic()
        self.process.send_signal(signal.SIGTERM)
        self.process.wait(30)
        return time.monotonic() - started


def install_fixture() -> None:
    with TapClient.create() as client, client.connect("studio-smoke-setup") as connection:
        connection.attach_device(SERIAL, PACKAGE).app(PACKAGE).install(APK)


def attached_to(serial: str) -> list[str]:
    with TapClient.create() as client:
        return [c.name for c in client.connections() if any(d.serial == serial for d in c.attached_devices)]


def run() -> None:
    install_fixture()
    s = Studio()
    try:
        check(s.get("/").status == 401, "the page must need the launch cookie")
        login = s.get(f"/login?t={s.token}")
        check(login.status == 303 and "httponly" in login.getheader("set-cookie", "").lower(), "login")
        session = s.client.get_session(studio.GetSessionRequest(), headers=s.headers).session
        check(session.device.serial == SERIAL, f"not attached: {session}")

        # Frames: the first one carries the screenshot and a snapshot with selector candidates.
        frames = s.client.frames(studio.FramesRequest(), headers=s.headers, timeout_ms=60_000)
        frame = next(iter(frames))
        frames.close()
        check(frame.png.startswith(b"\x89PNG") and frame.width > 0, "frame without a picture")
        check(any(n.candidates for n in frame.nodes), "frame without selector candidates")
        print(f"frame {frame.sequence}: {frame.width}x{frame.height}, {len(frame.nodes)} nodes")

        s.perform(studio.Step(app=tap.AppCall(operation="cold_launch", package_name=PACKAGE)))
        s.perform(act(tap.Command(tap=tap.Tap(selector=res("view_button").to_proto()))))
        s.perform(expect(res("view_status"), studio.CONDITION_TEXT_EQUALS, "View tapped"))
        s.perform(act(tap.Command(set_text=tap.SetText(selector=res("view_input").to_proto(), text="wool $5"))))
        right = act(tap.Command(tap=tap.Tap(selector=text_matches("(?i)ambiguous tap").at(1).to_proto())))
        picked = s.perform(right)
        check(not picked.action.wait.wait_visible.exactly_one, "a picked selector must wait for any match")
        s.perform(expect(res("ambiguous_status"), studio.CONDITION_TEXT_CONTAINS, "right=1"))
        # The shade covers the app (its nodes are gone) before Back, and Back gives it back: a
        # replay sends the steps back to back, and a Back sent while the shade is still opening
        # can be ignored.
        s.perform(act(tap.Command(open_system_panel=tap.OpenSystemPanel(panel=tap.SYSTEM_PANEL_NOTIFICATIONS))))
        s.perform(expect(res("view_button"), studio.CONDITION_GONE))
        s.perform(act(tap.Command(press_key=tap.PressKey(key_code=4))))
        s.perform(expect(res("view_button"), studio.CONDITION_VISIBLE))

        document = s.client.get_recording(studio.GetRecordingRequest(), headers=s.headers).document
        (OUT / "flow.tap-recording.json").write_text(document)
        check(len(loads(document).steps) == STEPS, f"the export has {STEPS} steps")

        s.client.new_recording(studio.NewRecordingRequest(), headers=s.headers)
        opened = s.client.open_recording(studio.OpenRecordingRequest(document=document), headers=s.headers)
        check(len(opened.recording.steps) == STEPS, f"reopened with {STEPS} steps")
        events = list(s.client.replay(studio.ReplayRequest(), headers=s.headers, timeout_ms=180_000))
        outcomes = [e for e in events if e.HasField("outcome")]
        for e in outcomes:
            print(f"replayed {e.step_id}: {'ok' if not e.message else e.message} ({e.outcome.duration_ms} ms)")
        check(len(outcomes) == STEPS and not any(e.message for e in outcomes), "the replay passed every step")
    finally:
        took = s.stop()
    print(f"tap-studio stopped {took:.1f} s after SIGTERM")
    check(not attached_to(SERIAL), f"{SERIAL} still attached after the studio stopped: {attached_to(SERIAL)}")

    # The exported file, replayed by another client in a fresh connection.
    recording = loads((OUT / "flow.tap-recording.json").read_text())
    with TapClient.create() as client, client.connect("studio-smoke-replay") as connection:
        device = connection.attach_device(SERIAL, recording.aut_package)
        for recorded in recording.steps:
            steps.run(device, recorded)
    print("the exported recording replays through tap-e2e")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    started = subprocess.run([TAP_BIN, "status"], capture_output=True).returncode != 0
    if started:
        subprocess.run([TAP_BIN, "start"], check=True)
    try:
        run()
    finally:
        if started:
            subprocess.run([TAP_BIN, "stop"], check=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
