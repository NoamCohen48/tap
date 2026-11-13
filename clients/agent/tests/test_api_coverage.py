"""Fail on unclassified RPCs, operations, SDK APIs or CLI/MCP parameter drift."""
# pyright: reportMissingImports=false
from __future__ import annotations

import importlib
import inspect
import json
from pathlib import Path

import pytest
from tap_e2e import App, AppData, Device, Element, Selector, TapClient, TapConnection
from tap_e2e import _gen as pb
from tap_e2e.element import ElementWait
from tap_e2e.screen import Screen

from tap_agent import cli
from tap_agent.core import APP_ACTIONS, WAIT_STATES

from .test_reference import _generator

ROOT = Path(__file__).resolve().parents[3]
REGISTRY = json.loads((ROOT / "clients/agent/api-coverage.json").read_text())
SDK = (TapClient, TapConnection, Device, App, AppData, Element, ElementWait, Screen, Selector)


@pytest.fixture(scope="module")
def tools():
    return _generator().mcp_tools()


def _check_tools(names, tools):
    assert names and set(names) <= set(tools), names


def test_every_rpc_and_execute_operation_is_classified(tools):
    actual = set()
    for path in (ROOT / "contracts/proto").glob("*.proto"):
        module = importlib.import_module(f"tap_e2e._gen.{path.stem}_pb2")
        actual.update(f"{service.full_name}.{method.name}"
            for service in module.DESCRIPTOR.services_by_name.values() for method in service.methods)
    covered = set()
    for service, methods in REGISTRY["rpcs"].items():
        for name, via in methods.items():
            covered.add(f"{service}.{name}")
            _check_tools(via, tools)
    excluded = REGISTRY["excluded_rpcs"]
    assert set(excluded) == {
        "tap.v1.ClientConnectionService.Observe", "tap.v1.AppService.AwaitIdle",
        "tap.v1.WatchService.Watch", "tap.v1.WatchService.WatchVideo",
        "tap.v1.TouchIndicatorService.GetTouchIndicators",
        "tap.v1.TouchIndicatorService.SetTouchIndicators",
    }
    assert all(excluded.values()) and not covered.intersection(excluded)
    assert covered | set(excluded) == actual
    assert set(REGISTRY["operations"]) == {f.name for f in pb.Command.DESCRIPTOR.oneofs_by_name["op"].fields}
    for via in REGISTRY["operations"].values():
        _check_tools(via, tools)


@pytest.mark.parametrize("cls", SDK, ids=lambda cls: cls.__name__)
def test_operational_sdk_methods_and_properties_do_not_silently_drift(cls, tools):
    actual = {name for name, member in inspect.getmembers(cls)
        if not name.startswith("_") and (inspect.isroutine(member) or isinstance(member, property))}
    classified = []
    for row in REGISTRY["sdk"][cls.__name__]:
        classified.extend(row["methods"])
        if row["tools"]:
            _check_tools(row["tools"], tools)
        else:
            assert row.get("reason"), row
    assert len(classified) == len(set(classified)), "duplicate SDK classifications"
    assert set(classified) == actual


def test_cli_and_mcp_have_the_same_practical_parameter_surface(tools):
    gen = _generator()
    verbs = gen.subparsers(cli.parser())
    assert set(REGISTRY["sdk"]) == {cls.__name__ for cls in SDK}
    assert set(REGISTRY["cli_only_fields"]) <= set(verbs)
    for name, parser in verbs.items():
        tool_name = gen.MCP_NAMES.get(name, name)
        if tool_name is None:
            continue
        aliases = REGISTRY["cli_field_aliases"]["*"] | REGISTRY["cli_field_aliases"].get(name, {})
        cli_fields = {action.dest for action in parser._actions if action.dest != "help"}
        ignored = set(REGISTRY["cli_only_fields"].get(name, []))
        assert ignored <= cli_fields
        normalized = {aliases.get(field, field) for field in cli_fields - ignored}
        assert normalized == set(tools[tool_name].input_schema["properties"]), name
    assert set(tools["app"].input_schema["properties"]["action"]["enum"]) == set(APP_ACTIONS)
    assert set(tools["wait_for"].input_schema["properties"]["state"]["enum"]) == set(WAIT_STATES)


def test_mcp_command_budgets_match_protocol_bound(tools):
    for tool in tools.values():
        for name, field in tool.input_schema["properties"].items():
            if name == "timeout" or name.endswith("timeout_seconds"):
                branches = field.get("anyOf", [field])
                numeric = [branch for branch in branches if branch.get("type") in ("number", "integer")]
                assert len(numeric) == 1, (tool.name, name)
                assert numeric[0].get("maximum") == 120, (tool.name, name)
                assert numeric[0].get("minimum") == .001, (tool.name, name)
