"""The shared client conformance table (contracts/conformance/client-conformance.json): the
Kotlin client's unit suite loads the same file, so both bindings keep the same defaults and map
daemon failures to the same exception kinds."""

from __future__ import annotations

import inspect
import json
import pathlib

import grpc
import pytest  # type: ignore[import-not-found]

from tap_e2e import (
    AppLifecycleError,
    DeviceBusyError,
    DeviceQuarantinedError,
    ServerError,
    WaitTimeoutError,
)
from tap_e2e import _gen as pb
from tap_e2e.app import App
from tap_e2e.device import Timeouts
from tap_e2e.pytest_plugin import DEFAULT_ACQUIRE_TIMEOUT
from tap_e2e.client import _map_rpc_error

TABLE = json.loads(
    (
        pathlib.Path(__file__).resolve().parents[4]
        / "contracts"
        / "conformance"
        / "client-conformance.json"
    ).read_text()
)

RAISES = {
    "wait_timeout": WaitTimeoutError,
    "device_busy": DeviceBusyError,
    "device_quarantined": DeviceQuarantinedError,
    "app_lifecycle": AppLifecycleError,
    "server": ServerError,
}


class _Status(grpc.RpcError):
    def __init__(self, case: dict):
        self.case = case

    def code(self) -> grpc.StatusCode:
        return grpc.StatusCode[self.case["status"]]

    def details(self) -> str:
        return self.case["details"]

    def trailing_metadata(self):
        if "reason" not in self.case:
            return ()
        failure = pb.Failure(
            reason=pb.FailureReason.Value(self.case["reason"]),
            serial=self.case.get("serial", ""),
            waited_ms=self.case.get("waited_ms", 0),
        )
        return (("tap-failure-bin", failure.SerializeToString()),)


def test_defaults_match_the_table():
    defaults = TABLE["defaults"]
    timeouts = Timeouts()
    assert timeouts.action * 1000 == defaults["action_timeout_ms"]
    assert timeouts.wait * 1000 == defaults["wait_timeout_ms"]
    assert timeouts.lifecycle * 1000 == defaults["lifecycle_timeout_ms"]
    stable_for = inspect.signature(App.await_idle).parameters["stable_for"].default
    assert round(stable_for * 1000) == defaults["idle_stable_ms"]
    assert DEFAULT_ACQUIRE_TIMEOUT * 1000 == defaults["acquire_timeout_ms"]


@pytest.mark.parametrize(
    "case", TABLE["failures"], ids=lambda c: f"{c['status']}-{c.get('reason', 'none')}"
)
def test_failures_map_by_reason(case):
    error = _map_rpc_error(_Status(case), serial="fallback")
    assert type(error) is RAISES[case["raises"]]
    if isinstance(error, ServerError):
        assert error.code == case["status"]
        assert error.reason == pb.FailureReason.Value(
            case.get("reason", "FAILURE_REASON_UNSPECIFIED")
        )
    if isinstance(error, WaitTimeoutError):
        assert error.serial == case["serial"]
        assert error.elapsed_ms == case["waited_ms"]
    if isinstance(error, DeviceQuarantinedError):
        assert error.serial == case["serial"]
