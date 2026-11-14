"""``tap-agent <verb>``: one agent step per invocation; the session lives in the daemon."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from importlib import resources

from . import render
from .core import (
    ACCURACIES,
    APP_ACTIONS,
    APP_DATA_ACTIONS,
    CONDITIONS,
    EXIT_OK,
    EXIT_USAGE,
    KEYBOARD_ACTIONS,
    NOTIFICATION_ACTIONS,
    PANELS,
    PERMISSION_CHOICES,
    PINCHES,
    ROTATIONS,
    SCREEN_ACTIONS,
    WAIT_STATES,
    Agent,
    AgentError,
    command_timeout,
)
from .targets import SELECTOR_KEYS, UsageError, parse_duration

DESCRIPTION = """\
Drive an Android device through a running Tap daemon (`tap start`), one step per call.

  tap-agent devices
  tap-agent attach emulator-5554
  tap-agent app cold-launch com.example.app
  tap-agent app launch com.example.app .ui.ItemActivity --extra item:long=42
  tap-agent snapshot                 # one line per node: @e3  [Button] "Log in"  id=login
  tap-agent tap @e3 --settle         # act on a ref; --settle prints what changed
  tap-agent fill id=email me@example.com
  tap-agent wait text=Welcome
  tap-agent release

A target is a ref from `snapshot` (@e3) or key=value terms joined by commas, all of which must
match: {keys}. Exit codes: 0 ok, 1 the step failed, 2 usage, 3 no daemon.
""".format(keys=SELECTOR_KEYS)

TARGET_HELP = "a ref (@e3), comma-joined selector terms, or a JSON selector (all/any, relations, flags, text modes)"


def _duration(value: str) -> float:
    try:
        return parse_duration(value)
    except UsageError as error:
        raise argparse.ArgumentTypeError(str(error)) from None


def _command_duration(value: str) -> float:
    try:
        seconds = parse_duration(value)
        command_timeout(seconds)
        return seconds
    except (UsageError, AgentError) as error:
        raise argparse.ArgumentTypeError(str(error)) from None


def parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-s", "--session", help="session name (default: $TAP_AGENT_SESSION or 'agent')")
    on_device = argparse.ArgumentParser(add_help=False, parents=[common])
    on_device.add_argument("-d", "--device", help="serial; needed only when the session has several devices")
    settle = argparse.ArgumentParser(add_help=False)
    settle.add_argument("--settle", action="store_true", help="wait for the screen to settle, then print what changed")

    budget = argparse.ArgumentParser(add_help=False)
    budget.add_argument("--timeout", type=_command_duration, help="command budget, at most 120s")

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
    p.add_argument(
        "--quarantined",
        dest="inspect_quarantined",
        action="store_true",
        help="attach a quarantined device to inspect and fix it (it stays quarantined until unquarantine)",
    )

    verb("sessions", "list sessions with their devices", common)
    verb("release", "end the session and detach its devices", common)
    verb("devices", "list devices, properties and who holds them", common)
    p = verb("unquarantine", "put a checked quarantined device back in service (retries its cleanup first)", common)
    p.add_argument("serial")
    verb("detach", "detach one device without ending its session", on_device)
    verb("server-info", "daemon version, compatibility and configuration as JSON", common)
    verb("device-info", "complete current device information as JSON", on_device)
    p = verb("capabilities", "read SDK operation support and prerequisites; no device changes", on_device)
    p.add_argument("--json", dest="as_json", action="store_true", help="structured capability entries")

    p = verb("logs", "save device/driver logs or diagnostic hierarchy privately", on_device)
    p.add_argument("kind", choices=("device", "driver", "hierarchy"), nargs="?", default="device")
    p.add_argument("-o", "--out", help="new local output file; never overwrite")

    p = verb("record", "start/stop/list/clean named action-and-wait recordings", common)
    p.add_argument("action", choices=("start", "stop", "list", "clean"), nargs="?", default="list")
    p.add_argument("name", nargs="?", help="recording name; required for start, optional for stop")
    p.add_argument("--all", dest="all_records", action="store_true", help="clean only: explicitly delete all stopped recordings")

    p = verb("media-record", "explicit bounded video/audio recording; save bytes privately on stop", on_device)
    p.add_argument("action", choices=("start", "stop"))
    p.add_argument("--no-video", action="store_false", dest="video", help="start: audio only")
    p.add_argument("--audio-source", choices=("output", "playback", "mic"), help="start: output mutes speakers; mic captures microphone")
    p.add_argument("--max-seconds", type=int, help="start: 1..30 with video, 1..60 audio only")
    p.add_argument("-o", "--out", help="stop: additional new output; a private canonical copy is retained")

    p = verb("snapshot", "print the screen, one node per line with its ref", on_device)
    level = p.add_mutually_exclusive_group()
    level.add_argument("-i", "--interactive", dest="level", action="store_const", const=render.INTERACTIVE, help="only nodes an action can target")
    level.add_argument("--all", dest="level", action="store_const", const=render.ALL, help="every node, layout containers too")
    p.add_argument("--candidates", action="store_true", help="structured SDK snapshot with selector candidates instead of rendered lines")

    for name, help in (("inspect", "read one node's complete SDK state as JSON"), ("exists", "whether any node matches now"), ("count", "count matching nodes now")):
        p = verb(name, help, on_device)
        p.add_argument("target", help=TARGET_HELP)
        p.add_argument("--timeout", type=_command_duration, help="read budget, at most 120s")

    p = verb("tap", "tap a node", on_device, settle)
    p.add_argument("target", help=TARGET_HELP)
    kind = p.add_mutually_exclusive_group()
    kind.add_argument("--long", action="store_true", help="long-press instead")
    kind.add_argument("--double", action="store_true", help="double-tap instead")
    p.add_argument("--count", type=int, default=1, help="tap this many times, 1..100")
    p.add_argument("--interval", type=_duration, default=0.1, help="release-to-press gap for counted taps, 40ms..1s (default 100ms)")
    p.add_argument("--duration", type=_duration, help="hold the finger down for this duration, 1ms..30s (implies --long)")
    p.add_argument("--timeout", type=_command_duration, help="command budget, at most 120s; must cover the whole gesture")

    p = verb("fill", "replace a field's text (no key events)", on_device, settle, budget)
    p.add_argument("target", help=TARGET_HELP)
    p.add_argument("text")
    p.add_argument("--sensitive", action="store_true", help="a secret: the session log and export never hold the text")

    p = verb("type", "type into current focus or a target with a focus wait", on_device, settle)
    p.add_argument("text")
    p.add_argument("--target", help=TARGET_HELP)
    p.add_argument("--no-await-focus", action="store_false", dest="await_focus", help="with a target: explicitly skip its SDK focus wait")
    p.add_argument("--timeout", type=_command_duration, help="per tap/type budget, at most 120s (not an overall deadline)")
    p.add_argument("--focus-timeout", type=_command_duration, help="target focus wait budget; SDK default 10s, at most 120s")
    p.add_argument("--sensitive", action="store_true", help="a secret: the session log and export never hold the text")

    p = verb("clear", "clear a field's text", on_device, settle, budget)
    p.add_argument("target", help=TARGET_HELP)

    p = verb("submit", "run a focused field's keyboard action (Search, Go, Send, Done, …); API 30+", on_device, settle, budget)
    p.add_argument("target", help=TARGET_HELP)

    p = verb("action", "list a node's accessibility actions, or perform one as a screen reader does (no touch)", on_device, settle, budget)
    p.add_argument("target", help=TARGET_HELP)
    p.add_argument("name", nargs="?", help="a standard action (expand, collapse, dismiss, scroll-forward, ...) or, with --custom, a label")
    p.add_argument("--custom", action="store_true", help="NAME is a custom action's label (e.g. 'Archive')")

    p = verb("progress", "set a slider (SeekBar, Slider, RatingBar) to a value in its own units", on_device, settle, budget)
    p.add_argument("target", help=TARGET_HELP)
    p.add_argument("value", type=float)

    p = verb("keyboard", "whether a soft keyboard shows, or hide it", on_device, settle)
    p.add_argument("action", choices=KEYBOARD_ACTIONS, nargs="?", default="state")

    p = verb("location", "mock the device location until release", on_device)
    p.add_argument("latitude", type=float, help="-90..90")
    p.add_argument("longitude", type=float, help="-180..180")
    p.add_argument("--accuracy", type=float, help="in meters (default 5)")
    p.add_argument("--altitude", type=float, help="in meters")

    verb("activity", "print the activity on top of the screen (package/class)", on_device)

    p = verb("push", "copy a local file to the device until release", on_device)
    p.add_argument("local", help="the file on this machine")
    p.add_argument("device_path", help="absolute path on the device; its directory must exist (e.g. /sdcard/Download/a.pdf)")

    p = verb("pull", "copy a device file here and print the local path", on_device)
    p.add_argument("device_path", help="absolute path of a regular file on the device")
    p.add_argument("-o", "--out", help="file (default .tap/agent/<serial>-<time>-<name>)")

    p = verb("media", "add a photo or video to the device gallery until release", on_device)
    p.add_argument("local", help="the image or video on this machine (jpg, png, gif, webp, heic, bmp, mp4, 3gp, webm, mkv, mov)")
    p.add_argument("--name", help="file name on the device (default the local name)")

    p = verb("clipboard", "print the device clipboard, or set it", on_device)
    p.add_argument("text", nargs="?", help="put this on the clipboard (omit to print it)")

    for name, help in (
        ("scroll", "scroll a node towards a content edge (down reveals content below)"),
        ("swipe", "swipe across a node, the finger moving in the direction"),
        ("fling", "fling a node towards a content edge, as for scroll; content may keep moving"),
    ):
        p = verb(name, help, on_device, settle)
        p.add_argument("target", help=TARGET_HELP)
        p.add_argument("direction", choices=("up", "down", "left", "right"))
        p.add_argument("--timeout", type=_command_duration, help="command budget, at most 120s")
        if name != "fling":
            p.add_argument("--percent", type=int, default=80, help="distance across the node, 1..100")

    p = verb("scroll-until", "bounded SDK scroll until a descendant target exists", on_device)
    p.add_argument("target", help="scroll container: " + TARGET_HELP)
    p.add_argument("destination", help="descendant to find: " + TARGET_HELP)
    p.add_argument("--direction", choices=("up", "down", "left", "right"), default="down")
    p.add_argument("--max-scrolls", type=int, default=20, help="1..1000; also bounded by the SDK deadline")
    p.add_argument("--percent", type=int, default=80, help="scroll distance, 1..100")
    p.add_argument("--timeout", type=_command_duration, help="overall SDK loop budget, at most 120s")

    p = verb("drag", "long-press a node, move it onto another node and drop it", on_device, settle)
    p.add_argument("target", help=TARGET_HELP)
    p.add_argument("destination", help="where to drop it: " + TARGET_HELP)
    p.add_argument("--timeout", type=_command_duration, help="command budget, at most 120s")

    p = verb("pinch", "pinch a node open (fingers apart, zoom in) or closed", on_device, settle)
    p.add_argument("target", help=TARGET_HELP)
    p.add_argument("how", choices=PINCHES)
    p.add_argument("--percent", type=int, default=80, help="how far across the node, 1..100 (default 80)")
    p.add_argument("--timeout", type=_command_duration, help="command budget, at most 120s")

    p = verb("key", "press a key: back, home, recents, enter, tab, delete, … or a key code", on_device, settle)
    p.add_argument("name")

    p = verb("panel", "open the notification shade or quick settings (key back closes it)", on_device, settle)
    p.add_argument("name", choices=PANELS)

    p = verb("rotate", "rotate the display and keep it there (auto: back to the sensor)", on_device, settle)
    p.add_argument("how", choices=ROTATIONS)

    p = verb("screen", "screen state, or turn it on/off, or wake it and dismiss a keyguard without a PIN", on_device, settle)
    p.add_argument("action", choices=SCREEN_ACTIONS, nargs="?", default="state")

    p = verb("condition", "device conditions (animations, dark mode, font scale, density, network switches, languages, stay awake, accessibility display): print or change until release", on_device)
    p.add_argument("name", choices=CONDITIONS, nargs="?", help="omit to print all")
    p.add_argument("value", nargs="?", help="animations/dark-mode/airplane-mode/wifi/mobile-data/stay-awake/high-contrast-text/color-inversion/bold-text: on|off; font-scale: 0.5..2.0; density: dpi or reset; locale: comma-separated BCP-47 tags, e.g. fr-FR,en (omit to print)")

    p = verb("permission", "list the permission dialog's buttons, or press one", on_device, settle)
    p.add_argument("choice", choices=PERMISSION_CHOICES, nargs="?", help="the button to press (omit to list them)")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="dialog wait/action budget, at most 120s (default 10s)")
    p.add_argument("--accuracy", choices=ACCURACIES, help="pick Precise or Approximate first (location dialog, Android 12+)")

    p = verb("toast", "wait for a toast (shown in the last 3.5s or coming) and print it", on_device)
    p.add_argument("text", nargs="?", help="the toast's text (omit for any toast)")
    p.add_argument("--contains", action="store_true", help="text is only part of the toast")
    p.add_argument("--package", help="only this package's toasts (default: any app's)")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="at most 120s; default 10s")

    p = verb("notification", "list the device's notifications, or wait for, open or dismiss one (read as data; the shade stays closed)", on_device, settle)
    p.add_argument("action", choices=NOTIFICATION_ACTIONS, nargs="?", default="list")
    p.add_argument("--title", help="the notification's title")
    p.add_argument("--text", help="the notification's text")
    p.add_argument("--contains", action="store_true", help="title/text are only part of it")
    p.add_argument("--package", help="only this package's notifications")
    p.add_argument("--button", help="open: press the action button with this title instead")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="command/wait budget, at most 120s; default 10s")

    p = verb("wait", "wait until a target is visible (or gone, or exactly one node)", on_device)
    p.add_argument("target", help=TARGET_HELP)
    state = p.add_mutually_exclusive_group()
    state.add_argument("--gone", dest="state", action="store_const", const="gone")
    state.add_argument("--one", dest="state", action="store_const", const="one")
    state.add_argument("--state", choices=WAIT_STATES, help="visibility, state, text, or count condition")
    state.add_argument(
        "--until", metavar="CONDITION",
        help="until the target's node satisfies CONDITION, in the target grammar: enabled=true,text=Pay or a JSON selector",
    )
    p.add_argument("--value", help="expected text or count for --state text/text-contains/count")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="at most 120s; default 10s")

    p = verb("settle", "wait for the screen to stop changing, then print what changed", on_device)
    p.add_argument("--full", action="store_true", help="also print unchanged nodes (=)")
    p.add_argument("--package", help="explicit package; otherwise use the focused app")
    p.add_argument("--stable-for", type=_duration, default=0.5, help="quiet period, 1ms..30s, no greater than timeout")
    p.add_argument("--signal", choices=("tree", "pixels", "all"), default="tree", help="what must stop changing")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="at most 120s; default 10s")

    p = verb("screenshot", "save a PNG and print its path", on_device)
    p.add_argument("-o", "--out", help="file (default .tap/agent/<serial>-<time>.png)")

    p = verb("capture", "save screenshot, hierarchy, device info and driver log", on_device)
    p.add_argument("-o", "--out", help="directory (default .tap/agent/capture-<serial>-<time>)")

    p = verb("app", "app lifecycle: " + ", ".join(APP_ACTIONS), on_device)
    p.add_argument("action", choices=APP_ACTIONS)
    p.add_argument("package", help="the app's package name, e.g. com.example.app")
    p.add_argument("argument", nargs="?", help="activity (launch), URI (open-link), APK path (install), permission (grant, revoke, granted) or languages (locale: fr-FR,en or system)")
    p.add_argument("--any-app", action="store_true", help="open-link: let any app handle the link, not only this one")
    p.add_argument("--timeout", type=_command_duration, help="supported lifecycle/visibility/process actions: at most 120s")
    p.add_argument(
        "--extra",
        action="append",
        default=[],
        metavar="KEY[:TYPE]=VALUE",
        help="launch/cold-launch: an intent extra; TYPE is string (default), int, long, float or bool; repeat for more",
    )

    p = verb("app-data", "debuggable-app private files and SQLite bundles; stop the app explicitly first", on_device)
    p.add_argument("action", choices=APP_DATA_ACTIONS)
    p.add_argument("package", help="the debuggable app's package name")
    p.add_argument("path", nargs="?", default="", help="relative private path, or database basename; list defaults to root")
    p.add_argument("local", nargs="?", help="read/write: local file; export-db/import-db: local bundle directory")
    p.add_argument("--overwrite", action="store_true", help="write/import-db: explicitly replace existing remote data")

    p = verb("history", "raw session event history as JSON; export before release", common)
    p.add_argument("-o", "--out", help="write to a new private file instead")
    p.add_argument("--after-seq", type=int, default=0, help="only events with larger sequence numbers")

    p = verb("export", "export a named action-and-wait recording, even after release", common)
    p.add_argument("name", nargs="?", help="default: this session's active recording, otherwise its latest archive")
    p.add_argument("-o", "--out", help="write to a new 0600 file instead of stdout")

    verb("mcp", "run the MCP server on stdio (same sessions as the CLI)")
    verb("skill", "print the agent skill (SKILL.md)")
    return root


def run(args: argparse.Namespace, agent: Agent) -> str:
    v = args.verb
    device = getattr(args, "device", None)
    settle = getattr(args, "settle", False)
    if v == "attach":
        kwargs = {} if args.idle is None else {"idle": args.idle}
        return agent.attach(
            args.serial, wait_for_device=args.wait_for_device, inspect_quarantined=args.inspect_quarantined, **kwargs
        )
    if v == "sessions":
        return agent.sessions()
    if v == "release":
        return agent.release()
    if v == "devices":
        return agent.devices()
    if v == "unquarantine":
        return agent.unquarantine(args.serial)
    if v == "detach":
        return agent.detach(device)
    if v == "server-info":
        return agent.server_info()
    if v == "device-info":
        return agent.device_info(device)
    if v == "capabilities":
        return agent.capabilities(device, as_json=args.as_json)
    if v == "logs":
        return agent.logs(args.kind, device, args.out)
    if v == "record":
        return agent.record(args.action, args.name, all_records=args.all_records)
    if v == "media-record":
        return agent.recording(args.action, device, video=args.video, audio_source=args.audio_source, max_seconds=args.max_seconds, out=args.out)
    if v == "snapshot":
        return agent.snapshot(device, args.level or render.DEFAULT, candidates=args.candidates)
    if v in ("inspect", "exists", "count"):
        return getattr(agent, v)(args.target, device, args.timeout)
    if v == "tap":
        return agent.tap(args.target, device, long=args.long, settle=settle, double=args.double,
                         count=args.count, interval=args.interval, duration=args.duration, timeout=args.timeout)
    if v == "fill":
        return agent.fill(args.target, args.text, device, settle=settle, sensitive=args.sensitive, timeout=args.timeout)
    if v == "type":
        return agent.type(args.text, device, settle=settle, sensitive=args.sensitive, target=args.target, await_focus=args.await_focus, timeout=args.timeout, focus_timeout=args.focus_timeout)
    if v == "clear":
        return agent.clear(args.target, device, settle=settle, timeout=args.timeout)
    if v == "submit":
        return agent.submit(args.target, device, settle=settle, timeout=args.timeout)
    if v == "action":
        return agent.action(args.target, args.name, device, custom=args.custom, settle=settle, timeout=args.timeout)
    if v == "progress":
        return agent.progress(args.target, args.value, device, settle=settle, timeout=args.timeout)
    if v == "keyboard":
        return agent.keyboard(args.action, device, settle=settle)
    if v == "location":
        return agent.location(args.latitude, args.longitude, args.accuracy, device, altitude=args.altitude)
    if v == "activity":
        return agent.activity(device)
    if v == "push":
        return agent.push(args.local, args.device_path, device)
    if v == "pull":
        return agent.pull(args.device_path, args.out, device)
    if v == "media":
        return agent.media(args.local, args.name, device)
    if v == "clipboard":
        return agent.clipboard(args.text, device)
    if v == "notification":
        return agent.notification(
            args.action, args.title, args.text, device, contains=args.contains, package=args.package,
            button=args.button, timeout=args.timeout, settle=settle,
        )
    if v == "toast":
        return agent.toast(args.text, device, contains=args.contains, package=args.package, timeout=args.timeout)
    if v == "scroll":
        return agent.scroll(args.target, args.direction, device, settle=settle, percent=args.percent, timeout=args.timeout)
    if v == "swipe":
        return agent.swipe(args.target, args.direction, device, settle=settle, percent=args.percent, timeout=args.timeout)
    if v == "fling":
        return agent.fling(args.target, args.direction, device, settle=settle, timeout=args.timeout)
    if v == "scroll-until":
        return agent.scroll_until(args.target, args.destination, args.direction, device, max_scrolls=args.max_scrolls, percent=args.percent, timeout=args.timeout)
    if v == "drag":
        return agent.drag(args.target, args.destination, device, settle=settle, timeout=args.timeout)
    if v == "pinch":
        return agent.pinch(args.target, args.how, args.percent, device, settle=settle, timeout=args.timeout)
    if v == "key":
        return agent.key(args.name, device, settle=settle)
    if v == "panel":
        return agent.panel(args.name, device, settle=settle)
    if v == "rotate":
        return agent.rotate(args.how, device, settle=settle)
    if v == "condition":
        return agent.condition(args.name, args.value, device)
    if v == "screen":
        return agent.screen(args.action, device, settle=settle)
    if v == "permission":
        return agent.permission(args.choice, device, args.timeout, settle=settle, accuracy=args.accuracy)
    if v == "wait":
        return agent.wait(args.target, device, args.state or "visible", args.timeout, value=args.value, until=args.until)
    if v == "settle":
        return agent.settle(device, full=args.full, timeout=args.timeout, package=args.package, stable_for=args.stable_for, signal=args.signal)
    if v == "screenshot":
        return str(agent.screenshot(device, args.out))
    if v == "capture":
        return agent.capture(device, args.out)
    if v == "history":
        return agent.history(args.out, after_seq=args.after_seq)
    if v == "export":
        return agent.export(args.name, args.out)
    if v == "app-data":
        return agent.app_data(args.action, args.package, args.path, args.local, device, overwrite=args.overwrite)
    if v == "app":
        return agent.app(args.action, args.package, args.argument, device, any_app=args.any_app, extras=args.extra, timeout=args.timeout)
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
