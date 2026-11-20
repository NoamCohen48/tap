"""The generated command reference (scripts/gen_reference.py) covers every verb and MCP tool."""

from __future__ import annotations

import importlib.util
import re
from importlib.resources import files
from pathlib import Path

from tap_agent import cli
from tap_agent.mcp_server import INSTRUCTIONS

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "gen_reference.py"


def _generator():
    spec = importlib.util.spec_from_file_location("gen_reference", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_reference_covers_every_verb_and_mcp_tool():
    gen = _generator()
    verbs = gen.subparsers(cli.parser())
    tools = gen.mcp_tools()
    assert gen.check(verbs, tools) == []
    page = gen.render(verbs, tools)
    for verb in verbs:
        assert f"### `{verb}`" in page
    for name in tools:
        assert f"`{name}(" in page


def test_skill_event_option_list_matches_parser():
    skill = files("tap_agent").joinpath("SKILL.md").read_text(encoding="utf-8")
    listed = skill.split("`--events`/`--expect` are available on ", 1)[1].split(", not on", 1)[0]
    promised = set(re.findall(r"`([a-z-]+)`", listed))
    supported = set()
    for name, parser in _generator().subparsers(cli.parser()).items():
        options = {option for action in parser._actions for option in action.option_strings}
        if "--events" in options:
            assert {"--expect", "--expect-death"} <= options
            supported.add(name)
    assert promised == supported


def test_mcp_flow_uses_registered_tool_names():
    flow = INSTRUCTIONS.split("Flow:", 1)[1].split("Selectors search", 1)[0]
    calls = set(re.findall(r"\b([a-z_]+)\(", flow))
    assert {"attach", "app", "press_key"} <= calls
    assert calls <= _generator().mcp_tools().keys()


def test_reference_is_written(tmp_path):
    out = tmp_path / "agent.md"
    assert _generator().main(["gen_reference.py", str(out)]) == 0
    assert out.read_text(encoding="utf-8").startswith("# Tap agent command reference\n")
