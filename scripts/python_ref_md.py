"""Turns the Python reference pages of the Markdown bundle into plain Markdown.

    python scripts/python_ref_md.py build/docs-md/reference/python

The site renders docs/reference/python/*.md with mkdocstrings (`::: tap_e2e.x.Y` blocks). The
Markdown bundle, and the .md copies scripts/llms_txt.py publishes next to each page, need the
API itself, so each block is replaced in place by lazydocs' Markdown for the same object: a
class, a function, a module, or a module's listed `members:` (`members: false` keeps only the
module docstring).
"""

from __future__ import annotations

import importlib
import inspect
import re
import sys
from pathlib import Path

from lazydocs import MarkdownGenerator

DIRECTIVE = re.compile(r"^::: (?P<path>[\w.]+)\n(?P<options>(?:    .*\n|\n(?=    ))*)", re.MULTILINE)
GENERATOR = MarkdownGenerator(src_base_url="https://github.com/NoamCohen48/tap/blob/main/")


def resolve(path: str):
    try:
        return importlib.import_module(path)
    except ModuleNotFoundError:
        module, _, name = path.rpartition(".")
        return getattr(importlib.import_module(module), name)


def render(obj, depth: int = 2) -> str:
    if inspect.ismodule(obj):
        return GENERATOR.module2md(obj, depth=depth)
    if inspect.isclass(obj):
        return GENERATOR.class2md(obj, depth=depth)
    return GENERATOR.func2md(obj, depth=depth)


def replace(match: re.Match) -> str:
    obj = resolve(match["path"])
    options = match["options"]
    members = re.findall(r"^\s+- (\w+)$", options, re.MULTILINE)
    if inspect.ismodule(obj) and "members: false" in options:
        return (inspect.getdoc(obj) or "") + "\n\n"
    if inspect.ismodule(obj) and members:
        return "".join(render(getattr(obj, name)) + "\n" for name in members)
    return render(obj) + "\n"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    for page in sorted(Path(argv[1]).glob("*.md")):
        text = page.read_text(encoding="utf-8")
        rendered = DIRECTIVE.sub(replace, text)
        rendered = re.sub(r"\n{3,}", "\n\n", rendered)
        page.write_text(rendered, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
