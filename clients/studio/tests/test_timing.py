"""Integer recording budgets must not lose a millisecond through SDK float conversion."""
from __future__ import annotations

from google.protobuf.json_format import ParseDict
from tap_e2e import proto as tap

from tap_studio._gen import studio_pb2 as pb
from tap_studio.steps import prepare, run
from tap_studio.timing import seconds_from_ms


def test_all_supported_millisecond_values_survive_sdk_truncation():
    for milliseconds in range(1, 120001):
        assert int(seconds_from_ms(milliseconds) * 1000) == milliseconds


def test_recorded_action_and_precondition_preserve_1001_ms(daemon, device):
    selector = ParseDict({"node": {"resource": {"name": "field"}}}, tap.Selector())
    prepared = prepare(pb.Step(action=pb.ActionStep(command=tap.Command(tap=tap.Tap(selector=selector), timeout_ms=1001))), None, precondition_timeout_ms=1003)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert [c.timeout_ms for c in daemon.devices.commands] == [1003, 1001]


def test_quiet_period_and_wait_budget_preserve_1001_ms(daemon, device):
    prepared = prepare(pb.Step(app_wait=pb.AppWaitStep(command=tap.Command(wait_screen_stable=tap.WaitScreenStable(stable_for_ms=1001, signal=tap.STABILITY_ALL), timeout_ms=1003))), None)
    daemon.devices.commands.clear()
    run(device, prepared)
    assert daemon.devices.commands == [prepared.app_wait.command]
