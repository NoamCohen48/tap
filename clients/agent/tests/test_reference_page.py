"""The one-page command reference (scripts/gen_reference_page.py): every verb, examples that parse."""

from __future__ import annotations

import importlib.util
import shlex
import sys
from pathlib import Path

import pytest

from tap_agent import cli

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"


def _generator():
    sys.path.insert(0, str(SCRIPTS))
    spec = importlib.util.spec_from_file_location("gen_reference_page", SCRIPTS / "gen_reference_page.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_every_verb_has_an_example_and_nothing_else_does():
    gen = _generator()
    assert gen.problems(gen.ref.subparsers(cli.parser())) == []


def test_every_example_parses_with_the_current_cli():
    gen = _generator()
    for verb, examples in gen.EXAMPLES.items():
        for line, _ in examples:
            argv = shlex.split(line)
            assert argv[0] == "tap-agent" and argv[1] == verb, line
            try:
                cli.parser().parse_args(argv[1:])
            except SystemExit:
                pytest.fail(f"example no longer parses: {line}")


def test_page_has_every_verb_and_no_shared_option_rows(tmp_path):
    gen = _generator()
    out = tmp_path / "page.html"
    assert gen.main(["gen_reference_page.py", str(out)]) == 0
    page = out.read_text(encoding="utf-8")
    assert page.startswith("<!doctype html>")
    for verb in gen.ref.subparsers(cli.parser()):
        assert f'<section class="cmd" id="{verb}"' in page
        assert f'<li><a href="#{verb}">{verb}</a></li>' in page
    assert "<td><code>--settle</code></td>" not in page
    assert "{{" not in page


def test_fragment_leaves_out_the_document_wrapper(tmp_path):
    out = tmp_path / "page.html"
    assert _generator().main(["gen_reference_page.py", "--fragment", str(out)]) == 0
    assert out.read_text(encoding="utf-8").startswith("<title>tap-agent commands</title>")
