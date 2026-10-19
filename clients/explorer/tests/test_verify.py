import json

import pytest

from tap_explorer.robots import build, write
from tap_explorer.verify import emulator_snapshot_reset, plans, verify

from test_robots import _run


class FakeApp:
    """Accepts every wait and records mutations; ``fail`` makes one selector's tap raise."""

    def __init__(self, log, fail=None):
        self.log, self.fail, self.current = log, fail, None

    def wait(self, selector, timeout):
        return self

    def element(self, selector):
        self.current = selector.render()
        return self

    def tap(self):
        if self.fail and self.fail in self.current:
            raise RuntimeError(f"no match for {self.current}")
        self.log.append(("tap", self.current))

    def set_text(self, value):
        self.log.append(("set_text", self.current, value))

    def __getattr__(self, name):  # one(), visible(), text_equals(...), checked(), ...
        return lambda *args: self


def test_plans_follow_observed_single_outcome_routes(tmp_path):
    run = _run(tmp_path)
    planned = plans(run, build(run))

    assert planned["start"] == "RequestsRobot"
    steps = planned["methods"]["YourNameRobot.tap_next_to_saved"]
    assert [(step["method"], step["argument"]) for step in steps] == [
        ("tap_start", None), ("tap_next_rejected", None), ("enter_name", "Ada"), ("tap_next_to_saved", None)]
    assert [step["method"] for step in planned["methods"]["SavedRobot.tap_home"]][-1] == "tap_home"


def test_verify_replays_each_method_after_a_reset_and_marks_the_draft(tmp_path):
    (tmp_path / "run").mkdir()
    run = _run(tmp_path / "run")
    out = tmp_path / "out"
    write(run, out)
    log, resets = [], []
    result = verify(run, out, open_app=lambda: FakeApp(log, fail='name: "home"'), reset=lambda: resets.append(1))

    statuses = {key: entry["status"] for key, entry in result["methods"].items()}
    assert statuses["YourNameRobot.tap_next_to_saved"] == "verified"
    assert statuses["SavedRobot.tap_home"] == "failed"
    assert "no match" in result["methods"]["SavedRobot.tap_home"]["error"]
    assert len(resets) == len(statuses)
    assert ("set_text", 'node { resource { name: "name" } }', "Ada") in log

    review = {"format": "tap-robots-review/1", "screens": {"YourNameRobot": {"methods": {"tap_next_to_saved": "submit"}}}}
    model = build(run, review, json.loads((out / "verification.json").read_text()))
    form = next(screen for screen in model["screens"] if screen["name"] == "YourNameRobot")
    assert next(m for m in form["methods"] if m["name"] == "submit")["replay"] == "verified"
    assert model["status"].startswith("draft; ") and "verified by replay" in model["status"]
    assert any(item["kind"] == "replay-failed" and item["method"] == "tap_home" for item in model["review"])


def test_snapshot_reset_refuses_physical_devices():
    with pytest.raises(ValueError):
        emulator_snapshot_reset("85e49002", "clean")
