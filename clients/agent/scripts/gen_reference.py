"""Writes the tap-agent command reference from the CLI parser and the MCP server.

    python clients/agent/scripts/gen_reference.py docs/agent/commands.md

scripts/build-docs.sh runs it on every docs build, so the page never drifts from
`tap-agent --help`. The grouping and the group introductions are the only hand-written parts;
clients/agent/tests/test_reference.py fails when a verb or an MCP tool is missing from them.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

# argparse wraps usage lines to the terminal width; pin it so the output is the same everywhere.
os.environ["COLUMNS"] = "96"

import anyio  # noqa: E402

from tap_agent import cli  # noqa: E402
from tap_agent.mcp_server import create_server  # noqa: E402

# (title, introduction, verbs). Every CLI verb appears in exactly one group.
GROUPS: list[tuple[str, str, list[str]]] = [
    (
        "Sessions and devices",
        "A session is a held connection on the server: it outlives each CLI call, is shared by the "
        "CLI and the MCP server, and ends on `release` or after 15 idle minutes. `--session NAME` "
        "(or `TAP_AGENT_SESSION`) keeps several apart. A device is held by one session at a time, "
        "exactly as a test holds it.",
        ["devices", "attach", "sessions", "release"],
    ),
    (
        "Reading the screen",
        "`snapshot` is the main way to see the screen: text, one node per line, grouped by window, "
        "each with a ref (`@e12`) to act on. A ref names a selector the server found to match "
        "exactly that node, and keeps naming it while it stays on screen. Prefer it to "
        "`screenshot`, which an agent has to look at.",
        ["snapshot", "settle", "wait", "activity", "screenshot", "capture"],
    ),
    (
        "Acting on elements",
        "Every action takes a target and needs exactly one matching node: zero is `NOT_FOUND`, "
        "several is `AMBIGUOUS`, and both fail before anything touches the device. A tap whose "
        "point another window covers (a dialog, the shade) fails as `NOT_INTERACTABLE` / "
        "`OBSCURED`. With `--settle`, the command then waits for the screen to stop changing and "
        "prints what changed, which is usually enough to pick the next step.",
        ["tap", "fill", "type", "clear", "submit", "action", "progress", "keyboard"],
    ),
    (
        "Gestures",
        "Gestures run on one node, sized relative to it, never at screen coordinates.",
        ["scroll", "swipe", "fling", "drag", "pinch"],
    ),
    (
        "Keys, system panels and the display",
        "Target the shade's and quick settings' nodes with `pkg=com.android.systemui`; `key back` "
        "closes them (twice from quick settings on newer Android). A rotation is kept until "
        "release, which restores the device's own setting.",
        ["key", "panel", "rotate", "screen"],
    ),
    (
        "Apps and permissions",
        "`app <action> <package> [argument]` covers the app lifecycle. `permission` handles the "
        "runtime-permission dialog an app shows, whatever app shows it.",
        ["app", "permission"],
    ),
    (
        "Device conditions and location",
        "Everything changed here is restored on release: animations, dark mode, font scale, "
        "density, the network switches, the languages and the accessibility display settings, and "
        "the mocked location.",
        ["condition", "location"],
    ),
    (
        "Notifications, toasts and the clipboard",
        "Notifications are read as data: the shade stays closed. Toasts are caught when shown in "
        "the last 3.5 s or while waiting.",
        ["notification", "toast", "clipboard"],
    ),
    (
        "Files and the gallery",
        "The bytes stream through the server. What Tap created on the device is removed on "
        "release, and an existing file is never overwritten.",
        ["push", "pull", "media"],
    ),
    (
        "Turning a session into a test",
        "The event log lists every device call the session made, in order, with its outcome; refs "
        "appear as the selectors they stood for. See "
        "[Sessions to tests](export.md).",
        ["export"],
    ),
    (
        "Agent setup",
        "Register the MCP server with your agent, or give it the CLI with the skill text.",
        ["mcp", "skill"],
    ),
]

# CLI verb -> MCP tool, where the names differ. None: the verb has no MCP tool.
MCP_NAMES: dict[str, str | None] = {
    "type": "type_text",
    "action": "accessibility_action",
    "progress": "set_progress",
    "location": "set_location",
    "activity": "foreground_activity",
    "push": "push_file",
    "pull": "pull_file",
    "media": "add_media",
    "toast": "await_toast",
    "key": "press_key",
    "panel": "open_panel",
    "wait": "wait_for",
    "mcp": None,
    "skill": None,
}

# Options every on-device verb takes; documented once at the top instead of in every table.
COMMON_DESTS = {"help", "session", "device"}


def subparsers(root: argparse.ArgumentParser) -> dict[str, argparse.ArgumentParser]:
    for action in root._actions:
        if isinstance(action, argparse._SubParsersAction):
            return dict(action.choices)
    raise AssertionError("tap-agent has no verbs")


def mcp_tools() -> dict[str, object]:
    server = create_server(lambda name: None, default_session="agent")
    return {tool.name: tool for tool in anyio.run(server.list_tools)}


def mcp_name(verb: str) -> str | None:
    return MCP_NAMES.get(verb, verb)


def check(verbs: dict[str, argparse.ArgumentParser], tools: dict[str, object]) -> list[str]:
    """Problems that would make the page incomplete; empty when it covers everything."""
    problems = []
    grouped = [verb for _, _, names in GROUPS for verb in names]
    for verb in verbs:
        if grouped.count(verb) != 1:
            problems.append(f"verb {verb!r} is in {grouped.count(verb)} groups (want 1)")
    for verb in grouped:
        if verb not in verbs:
            problems.append(f"group lists {verb!r}, which is not a tap-agent verb")
    mapped = {mcp_name(verb) for verb in verbs} - {None}
    for name in tools:
        if name not in mapped:
            problems.append(f"MCP tool {name!r} has no CLI verb in MCP_NAMES")
    for name in mapped:
        if name not in tools:
            problems.append(f"MCP_NAMES maps a verb to {name!r}, which is not an MCP tool")
    return problems


def cell(text: str) -> str:
    return " ".join(text.replace("|", "\\|").split())


def sentence(text: str | None) -> str:
    """A parser description, capitalised and without its trailing period."""
    text = (text or "").strip().rstrip(".")
    return text[:1].upper() + text[1:]


def argument_name(action: argparse.Action) -> str:
    if not action.option_strings:
        return f"`{action.metavar or action.dest}`"
    names = ", ".join(action.option_strings)
    if action.nargs == 0:
        return f"`{names}`"
    metavar = action.metavar or action.dest.upper()
    return f"`{names} {metavar}`"


def argument_help(action: argparse.Action) -> str:
    parts = []
    if action.help and action.help != argparse.SUPPRESS:
        parts.append(action.help)
    if action.choices and not isinstance(action, argparse._SubParsersAction):
        parts.append("one of " + ", ".join(f"`{c}`" for c in action.choices))
    optional_positional = not action.option_strings and action.nargs == "?"
    if optional_positional:
        parts.append("optional")
    default = action.default
    if (
        default not in (None, False, [], argparse.SUPPRESS)
        and action.nargs != 0
        and "default" not in (action.help or "")
    ):
        parts.append(f"default `{default}`")
    if isinstance(action, argparse._AppendAction) and "repeat" not in (action.help or ""):
        parts.append("repeatable")
    return cell("; ".join(parts)) or "—"


def usage(parser: argparse.ArgumentParser) -> str:
    text = parser.format_usage().removeprefix("usage: ").strip()
    return text.replace(" [-h]", "")


def verb_section(verb: str, parser: argparse.ArgumentParser, tools: dict[str, object]) -> list[str]:
    lines = [f"### `{verb}`", "", sentence(parser.description) + ".", "", "```text", usage(parser), "```", ""]
    arguments = [a for a in parser._actions if a.dest not in COMMON_DESTS]
    arguments.sort(key=lambda a: bool(a.option_strings))  # positionals first, in their order
    if arguments:
        lines += ["| Argument | Meaning |", "|---|---|"]
        lines += [f"| {argument_name(a)} | {argument_help(a)} |" for a in arguments]
        lines.append("")
    name = mcp_name(verb)
    if name is None:
        lines += ["MCP: none (CLI only).", ""]
    else:
        tool = tools[name]
        params = [p for p in (tool.input_schema or {}).get("properties", {}) if p not in ("session",)]
        signature = f"`{name}({', '.join(params)})`"
        summary = cell((tool.description or "").strip().split("\n\n")[0])
        lines += [f"MCP: {signature}: {summary}", ""]
    return lines


def render(verbs: dict[str, argparse.ArgumentParser], tools: dict[str, object]) -> str:
    from tap_agent.targets import SELECTOR_KEYS

    out = [
        "# tap-agent command reference",
        "",
        '!!! warning "Experimental"',
        "    `tap-agent` and its export format may change in any release until this notice goes away.",
        "",
        "Every `tap-agent` verb, its arguments and the MCP tool that does the same. The page is",
        "generated from the CLI parser and the MCP server, so it matches `tap-agent <verb> --help`.",
        "The [tap-agent](index.md) pages explain the flow; this page is for looking",
        "things up.",
        "",
        "## Conventions",
        "",
        "| Term | Meaning |",
        "|---|---|",
        "| Target | A ref from `snapshot` (`@e12`), or `key=value` terms joined by commas, all of which "
        f"must match. Keys: {cell(SELECTOR_KEYS)}. `~=` means contains. A target matches anywhere on the "
        "screen; `pkg=` keeps it to one app's nodes. |",
        "| `-s`, `--session NAME` | The session to use (default `$TAP_AGENT_SESSION`, else `agent`). Every verb but `mcp` and `skill` takes it. |",
        "| `-d`, `--device SERIAL` | Needed only when the session holds several devices. Every verb that runs on a device takes it. |",
        "| `--settle` | After the step, wait for the screen to stop changing and print the difference (`+` added, `-` removed). |",
        "| Durations | `--timeout`, `--idle`, `--wait-for-device`: `500ms`, `10s`, `2m`, or a number of seconds. |",
        "| Files | `screenshot`, `capture` and `pull` write under `.tap/agent/` unless `-o` says otherwise, and print the path. |",
        "| Exit codes | `0` ok, `1` the step failed (the reason is on stderr), `2` usage, `3` no server running (`tap start`). |",
        "| MCP | Every tool also takes `session`; tools that run on a device take `device`. Actions take `settle`. |",
        "",
        "## At a glance",
        "",
        "| Command | What it does | MCP tool |",
        "|---|---|---|",
    ]
    for title, _, names in GROUPS:
        for verb in names:
            description = sentence(verbs[verb].description)
            tool = mcp_name(verb)
            out.append(f"| [`{verb}`](#{verb}) | {cell(description)} | {f'`{tool}`' if tool else '—'} |")
    out.append("")
    for title, intro, names in GROUPS:
        out += [f"## {title}", "", intro, ""]
        for verb in names:
            out += verb_section(verb, verbs[verb], tools)
    return "\n".join(out).rstrip() + "\n"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    verbs = subparsers(cli.parser())
    tools = mcp_tools()
    problems = check(verbs, tools)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    Path(argv[1]).write_text(render(verbs, tools), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
