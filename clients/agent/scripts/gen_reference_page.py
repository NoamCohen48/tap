"""Writes the one-page tap-agent command reference (HTML) from the CLI parser and the MCP server.

    python clients/agent/scripts/gen_reference_page.py docs/agent/command-reference.html
    python clients/agent/scripts/gen_reference_page.py --fragment page.html

scripts/build-docs.sh runs it on every docs build, next to gen_reference.py (the same reference
as Markdown tables). Summaries, usage, options, MCP tools and the grouping come from the parser,
the MCP server and gen_reference.GROUPS; only the examples and notes (reference_examples.py)
are hand-written, and clients/agent/tests/test_reference_page.py checks that each still parses.
`--fragment` leaves out the document wrapper, for hosts that add their own.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import gen_reference as ref  # noqa: E402
from reference_examples import EXAMPLES, NOTES  # noqa: E402

from tap_agent import cli  # noqa: E402
from tap_agent.core import EXIT_APP_DIED, EXIT_FAILED, EXIT_NO_DAEMON, EXIT_OK, EXIT_USAGE  # noqa: E402

SITE = "https://noamcohen48.github.io/tap/"
TEMPLATE = HERE / "reference_page.html"
ROOT = HERE.parents[2]

# Options documented once under "Options on every step" instead of in every table.
SHARED_FLAGS = {"--events", "--expect", "--event-timeout", "--expect-death", "--settle"}
SHARED_USAGE = re.compile(
    r" ?\[(-s SESSION|-d DEVICE|--events KINDS|--expect KIND\[:TEXT\]|--event-timeout DURATION|--expect-death|--settle)\]"
)

EXIT_CODES = [
    (EXIT_OK, "done"),
    (EXIT_FAILED, "the step failed; the reason is on stderr"),
    (EXIT_USAGE, "the command line is wrong"),
    (EXIT_NO_DAEMON, "no Tap server is running (`tap start`)"),
    (EXIT_APP_DIED, "a watched app crashed or stopped responding during the step"),
]


def esc(text: str | None) -> str:
    return html.escape(text or "", quote=True)


def inline(text: str | None) -> str:
    """Escapes text and turns `code` spans into <code>."""
    parts = re.split(r"(`[^`]+`)", text or "")
    return "".join(f"<code>{esc(p[1:-1])}</code>" if p.startswith("`") and p.endswith("`") else esc(p) for p in parts)


def links(text: str) -> str:
    """Markdown links in a group introduction, made absolute against the site."""
    return re.sub(r"\[([^\]]+)\]\(([^)]+)\.md\)", lambda m: f'<a href="{SITE}agent/{m.group(2)}/">{m.group(1)}</a>', text)


def usage(parser: argparse.ArgumentParser) -> str:
    text = ref.usage(parser)
    lines = text.splitlines()
    joined = " ".join(line.strip() for line in lines)
    return SHARED_USAGE.sub("", joined)


def wrap_usage(text: str, width: int = 78) -> str:
    """Breaks a usage line between option groups, indenting the continuation lines."""
    words = re.findall(r"\[[^\[\]]*(?:\[[^\[\]]*\][^\[\]]*)*\]|\S+", text)
    lines, line = [], ""
    for word in words:
        if line and len(line) + 1 + len(word) > width:
            lines.append(line)
            line = "    " + word
        else:
            line = f"{line} {word}" if line else word
    lines.append(line)
    return "\n".join(lines)


def options(parser: argparse.ArgumentParser) -> tuple[str, bool]:
    """The options table, and whether the verb also takes the shared step options."""
    actions = [a for a in parser._actions if a.dest not in ref.COMMON_DESTS]
    shared = any(SHARED_FLAGS & set(a.option_strings) for a in actions)
    actions = [a for a in actions if not SHARED_FLAGS & set(a.option_strings)]
    actions.sort(key=lambda a: bool(a.option_strings))
    if not actions:
        return '<p class="none">No options of its own.</p>', shared
    rows = "".join(
        f"<tr><td>{inline(ref.argument_name(a))}</td><td>{inline(ref.argument_help(a).replace(chr(92) + '|', '|'))}</td></tr>"
        for a in actions
    )
    return f'<div class="tablewrap"><table><tbody>{rows}</tbody></table></div>', shared


def output_lines(text: str) -> str:
    out = []
    for line in text.split("\n"):
        kind = ("h" if line.startswith("# ") else "a" if line.startswith(("+ ", "✓")) else
                "d" if line.startswith(("- ", "error:")) else "")
        out.append(f'<span class="{kind}">{esc(line)}</span>' if kind else esc(line))
    return "\n".join(out)


def example(command: str, output: str) -> str:
    shown = command.removeprefix("tap-agent ")
    body = f'<pre class="term__out">{output_lines(output)}</pre>' if output else ""
    return (f'<figure class="term"><div class="term__in"><pre>tap-agent {esc(shown)}</pre>'
            f'<button type="button" class="copy" aria-label="Copy the command">Copy</button></div>{body}</figure>')


def mcp_line(verb: str, tools: dict) -> str:
    name = ref.mcp_name(verb)
    if name is None:
        return '<p class="mcp">MCP: none, CLI only.</p>'
    names = [name] + [extra for extra, under in ref.ALSO.items() if under == verb]
    calls = []
    for n in names:
        params = [p for p in (tools[n].input_schema or {}).get("properties", {}) if p != "session"]
        calls.append(f"<code>{esc(n)}({esc(', '.join(params))})</code>")
    return f'<p class="mcp">MCP: {", ".join(calls)}</p>'


def search_text(verb: str, parser: argparse.ArgumentParser) -> str:
    words = [verb, parser.description or ""]
    for action in parser._actions:
        words += action.option_strings
        if SHARED_FLAGS & set(action.option_strings) or action.dest in ref.COMMON_DESTS:
            continue  # their help is on most verbs: "crash" would match every step
        words.append(action.help or "")
        if action.choices and not isinstance(action, argparse._SubParsersAction):
            words += [str(c) for c in action.choices]
    words += NOTES.get(verb, [])
    return " ".join(" ".join(words).lower().split())


def command(verb: str, parser: argparse.ArgumentParser, tools: dict) -> str:
    table, shared = options(parser)
    also = ('<p class="also">Also takes the <a href="#common">options every step takes</a>: '
            "<code>--settle</code>, <code>--events</code>, <code>--expect</code>, <code>--expect-death</code>.</p>"
            if shared else "")
    examples = "".join(example(c, o) for c, o in EXAMPLES[verb])
    notes = "".join(f"<li>{inline(n)}</li>" for n in NOTES.get(verb, []))
    return (
        f'<section class="cmd" id="{esc(verb)}" data-search="{esc(search_text(verb, parser))}">'
        f'<h3><span class="prog">tap-agent</span> {esc(verb)} <a class="anchor" href="#{esc(verb)}" '
        f'aria-label="Link to {esc(verb)}">#</a></h3>'
        f'<p class="summary">{inline(ref.sentence(parser.description))}.</p>'
        f'<pre class="usage">{esc(wrap_usage(usage(parser)))}</pre>'
        f"{table}{also}{mcp_line(verb, tools)}"
        f'<h4>{"Examples" if len(EXAMPLES[verb]) > 1 else "Example"}</h4>{examples}'
        f'{f"<ul class=notes>{notes}</ul>" if notes else ""}'
        "</section>"
    )


def slug(title: str) -> str:
    return "group-" + re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")


def version() -> str:
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("tap.version="):
            return line.split("=", 1)[1].strip()
    return "unknown"


def render(verbs: dict[str, argparse.ArgumentParser], tools: dict) -> str:
    index, sections = [], []
    for title, intro, names in ref.GROUPS:
        gid = slug(title)
        items = "".join(f'<li><a href="#{esc(n)}">{esc(n)}</a></li>' for n in names)
        index.append(f'<div class="index__group" data-group="{gid}"><a href="#{gid}">{esc(title)}</a><ol>{items}</ol></div>')
        body = "".join(command(n, verbs[n], tools) for n in names)
        sections.append(f'<section class="group" id="{gid}"><h2>{esc(title)}</h2>'
                        f'<p class="blurb">{links(inline(intro))}</p>{body}</section>')
    codes = "".join(f"<li><b>{code}</b>{inline(meaning)}</li>" for code, meaning in EXIT_CODES)
    page = TEMPLATE.read_text(encoding="utf-8")
    for key, value in {
        "{{INDEX}}": "".join(index), "{{SECTIONS}}": "".join(sections), "{{EXIT_CODES}}": codes,
        "{{COUNT}}": str(len(verbs)), "{{SITE}}": SITE, "{{VERSION}}": esc(version()),
    }.items():
        page = page.replace(key, value)
    return page


def document(fragment: str) -> str:
    head, _, body = fragment.partition("</style>")
    return ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            f"{head}</style>\n</head>\n<body>\n{body.strip()}\n</body>\n</html>\n")


def problems(verbs: dict[str, argparse.ArgumentParser]) -> list[str]:
    found = [f"verb {v!r} has no example in reference_examples.py" for v in verbs if not EXAMPLES.get(v)]
    found += [f"reference_examples.py has examples for {v!r}, which is not a verb" for v in EXAMPLES if v not in verbs]
    found += [f"reference_examples.py has notes for {v!r}, which is not a verb" for v in NOTES if v not in verbs]
    return found


def main(argv: list[str]) -> int:
    args = argv[1:]
    fragment = "--fragment" in args
    args = [a for a in args if a != "--fragment"]
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    verbs = ref.subparsers(cli.parser())
    tools = ref.mcp_tools()
    issues = ref.check(verbs, tools) + problems(verbs)
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    page = render(verbs, tools)
    Path(args[0]).write_text(page if fragment else document(page), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
