"""``tap-agent <verb>``: one agent step per invocation; the session lives in the daemon."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from importlib import resources

from . import render
from .core import EXIT_OK, EXIT_USAGE, PANELS, Agent, AgentError, APP_ACTIONS
from .targets import SELECTOR_KEYS, UsageError, parse_duration

DESCRIPTION = """\
Drive an Android device through a running Tap daemon (`tap start`), one step per call.

  tap-agent devices
  tap-agent attach emulator-5554
  tap-agent app cold-launch com.example.app
  tap-agent snapshot                 # one line per node: @e3  [Button] "Log in"  id=login
  tap-agent tap @e3 --settle         # act on a ref; --settle prints what changed
  tap-agent fill id=email me@example.com
  tap-agent wait text=Welcome
  tap-agent release

A target is a ref from `snapshot` (@e3) or key=value terms joined by commas, all of which must
match: {keys}. Exit codes: 0 ok, 1 the step failed, 2 usage, 3 no daemon.
""".format(keys=SELECTOR_KEYS)

TARGET_HELP = "a ref (@e3) or selector terms: id=login, text=Log in, desc=Close, class=Button, …"


def _duration(value: str) -> float:
    try:
        return parse_duration(value)
    except UsageError as error:
        raise argparse.ArgumentTypeError(str(error)) from None


def parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-s", "--session", help="session name (default: $TAP_AGENT_SESSION or 'agent')")
    on_device = argparse.ArgumentParser(add_help=False, parents=[common])
    on_device.add_argument("-d", "--device", help="serial; needed only when the session has several devices")
    settle = argparse.ArgumentParser(add_help=False)
    settle.add_argument("--settle", action="store_true", help="wait for the screen to settle, then print what changed")

    root = argparse.ArgumentParser(
        prog="tap-agent", description=DESCRIPTION, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    verbs = root.add_subparsers(dest="verb", required=True, metavar="<verb>")

    def verb(name: str, help: str, *parents: argparse.ArgumentParser) -> argparse.ArgumentParser:
        return verbs.add_parser(name, help=help, description=help, parents=list(parents))

    p = verb("attach", "attach a device to the session (created if needed)", common)
    p.add_argument("serial")
    p.add_argument("--idle", type=_duration, default=None, help="end the session after this long unused (default 15m)")
    p.add_argument("--wait-for-device", type=_duration, default=0, metavar="DURATION", help="wait this long for a device another session holds")

    verb("sessions", "list sessions with their devices", common)
    verb("release", "end the session and detach its devices", common)
    verb("devices", "list the devices ADB sees and who holds them", common)

    p = verb("snapshot", "print the screen, one node per line with its ref", on_device)
    level = p.add_mutually_exclusive_group()
    level.add_argument("-i", "--interactive", dest="level", action="store_const", const=render.INTERACTIVE, help="only nodes an action can target")
    level.add_argument("--all", dest="level", action="store_const", const=render.ALL, help="every node, layout containers too")

    p = verb("tap", "tap a node", on_device, settle)
    p.add_argument("target", help=TARGET_HELP)
    p.add_argument("--long", action="store_true", help="long-press instead")

    p = verb("fill", "replace a field's text (no key events)", on_device, settle)
    p.add_argument("target", help=TARGET_HELP)
    p.add_argument("text")

    p = verb("type", "type key events into the focused field", on_device, settle)
    p.add_argument("text")

    p = verb("clear", "clear a field's text", on_device, settle)
    p.add_argument("target", help=TARGET_HELP)

    for name, help in (
        ("scroll", "scroll a node towards a content edge (down reveals content below)"),
        ("swipe", "swipe across a node, the finger moving in the direction"),
    ):
        p = verb(name, help, on_device, settle)
        p.add_argument("target", help=TARGET_HELP)
        p.add_argument("direction", choices=("up", "down", "left", "right"))

    p = verb("key", "press a key: back, home, recents, enter, tab, delete, … or a key code", on_device, settle)
    p.add_argument("name")

    p = verb("panel", "open the notification shade or quick settings (key back closes it)", on_device, settle)
    p.add_argument("name", choices=PANELS)

    p = verb("wait", "wait until a target is visible (or gone, or exactly one node)", on_device)
    p.add_argument("target", help=TARGET_HELP)
    state = p.add_mutually_exclusive_group()
    state.add_argument("--gone", dest="state", action="store_const", const="gone")
    state.add_argument("--one", dest="state", action="store_const", const="one")
    p.add_argument("--timeout", type=_duration, default=10.0, help="default 10s")

    p = verb("settle", "wait for the screen to stop changing, then print what changed", on_device)
    p.add_argument("--full", action="store_true", help="also print unchanged nodes (=)")
    p.add_argument("--timeout", type=_duration, default=10.0, help="default 10s")

    p = verb("screenshot", "save a PNG and print its path", on_device)
    p.add_argument("-o", "--out", help="file (default .tap/agent/<serial>-<time>.png)")

    p = verb("capture", "save screenshot, hierarchy, device info and driver log", on_device)
    p.add_argument("-o", "--out", help="directory (default .tap/agent/capture-<serial>-<time>)")

    p = verb("app", "app lifecycle: " + ", ".join(APP_ACTIONS), on_device)
    p.add_argument("action", choices=APP_ACTIONS)
    p.add_argument("package")
    p.add_argument("argument", nargs="?", help="activity (launch), APK path (install) or permission (grant)")

    p = verb("export", "print the session's event log as JSON (every device call, in order)", common)
    p.add_argument("-o", "--out", help="write it to this file instead")

    verb("mcp", "run the MCP server on stdio (same sessions as the CLI)")
    verb("skill", "print the agent skill (SKILL.md)")
    return root


def run(args: argparse.Namespace, agent: Agent) -> str:
    v = args.verb
    device = getattr(args, "device", None)
    settle = getattr(args, "settle", False)
    if v == "attach":
        kwargs = {} if args.idle is None else {"idle": args.idle}
        return agent.attach(args.serial, wait_for_device=args.wait_for_device, **kwargs)
    if v == "sessions":
        return agent.sessions()
    if v == "release":
        return agent.release()
    if v == "devices":
        return agent.devices()
    if v == "snapshot":
        return agent.snapshot(device, args.level or render.DEFAULT)
    if v == "tap":
        return agent.tap(args.target, device, long=args.long, settle=settle)
    if v == "fill":
        return agent.fill(args.target, args.text, device, settle=settle)
    if v == "type":
        return agent.type(args.text, device, settle=settle)
    if v == "clear":
        return agent.clear(args.target, device, settle=settle)
    if v == "scroll":
        return agent.scroll(args.target, args.direction, device, settle=settle)
    if v == "swipe":
        return agent.swipe(args.target, args.direction, device, settle=settle)
    if v == "key":
        return agent.key(args.name, device, settle=settle)
    if v == "panel":
        return agent.panel(args.name, device, settle=settle)
    if v == "wait":
        return agent.wait(args.target, device, args.state or "visible", args.timeout)
    if v == "settle":
        return agent.settle(device, full=args.full, timeout=args.timeout)
    if v == "screenshot":
        return str(agent.screenshot(device, args.out))
    if v == "capture":
        return agent.capture(device, args.out)
    if v == "export":
        return agent.export(args.out)
    if v == "app":
        return agent.app(args.action, args.package, args.argument, device)
    raise AssertionError(v)


def skill_text() -> str:
    return resources.files("tap_agent").joinpath("SKILL.md").read_text(encoding="utf-8")


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    if args.verb == "mcp":
        from .mcp_server import serve

        serve()
        return EXIT_OK
    if args.verb == "skill":
        print(skill_text(), end="")
        return EXIT_OK
    agent = Agent(args.session)
    try:
        print(run(args, agent))
        return EXIT_OK
    except AgentError as error:
        print(f"error: {error}", file=sys.stderr)
        return error.exit_code
    except UsageError as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_USAGE
    finally:
        agent.close()
