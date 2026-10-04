"""The generated command reference (scripts/gen_reference.py) covers every verb and MCP tool."""

from __future__ import annotations

import importlib.util
from pathlib import Path

from tap_agent import cli

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


def test_reference_is_written(tmp_path):
    out = tmp_path / "agent.md"
    assert _generator().main(["gen_reference.py", str(out)]) == 0
    assert out.read_text(encoding="utf-8").startswith("# tap-agent command reference\n")
