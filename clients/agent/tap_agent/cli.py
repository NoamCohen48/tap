"""``tap-agent <verb>``: one agent step per invocation; the session lives in the daemon."""

from __future__ import annotations

import argparse
import os
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
    LOG_ACTIONS,
    LOG_LIMIT,
    NOTIFICATION_ACTIONS,
    PANELS,
    PERMISSION_CHOICES,
    PINCHES,
    ROTATIONS,
    SCREEN_ACTIONS,
    WAIT_STATES,
    Agent,
    AgentError,
    AppDiedStep,
    command_timeout,
)
from .events import KIND_NAMES as EVENT_KINDS
from .targets import SELECTOR_KEYS, UsageError, parse_duration

DESCRIPTION = f"""\
Drive an Android device through a running Tap daemon (`tap start`), one step per call.

  tap-agent devices
  tap-agent attach emulator-5554
  tap-agent app cold-launch com.example.app
  tap-agent app launch com.example.app .ui.ItemActivity --extra item:long=42
  tap-agent snapshot                 # one line per node: @e3  [Button] "Log in"  id=login
  tap-agent tap @e3 --settle         # act on a ref; --settle prints what changed
  tap-agent fill id=email me@example.com
  tap-agent tap @e7 --expect toast:saved   # the tap must show a toast containing "saved"
  tap-agent wait text=Welcome
  tap-agent release

A target is a ref from `snapshot` (@e3) or key=value terms joined by commas, all of which must
match: {SELECTOR_KEYS}. Exit codes: 0 ok, 1 the step failed, 2 usage, 3 no daemon, 4 the step ran
but a watched app crashed or stopped responding.
"""

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
    # Shared event options for verbs that support step observation.
    observe = argparse.ArgumentParser(add_help=False)
    observe.add_argument(
        "--events", action="append", default=[], metavar="KINDS",
        help=f"also print events observed during the step, of these kinds (comma list; {', '.join(EVENT_KINDS)}); not proof of causality or complete capture",
    )
    observe.add_argument(
        "--expect", dest="expect_events", action="append", default=[], metavar="KIND[:TEXT]",
        help="require this event to be observed (TEXT: part of it, any case), e.g. toast:saved; unrelated events may match; repeat for several",
    )
    observe.add_argument(
        "--event-timeout", type=_command_duration, default=None, metavar="DURATION",
        help="how long --expect waits after the step, at most 120s; default 10s",
    )
    expect = argparse.ArgumentParser(add_help=False, parents=[observe])
    expect.add_argument(
        "--expect-death", action="store_true",
        help="the step must make a watched app crash or stop responding (within 10 s after it); a command failing because of it is no failure",
    )
    # These verbs can also settle after input.
    settle = argparse.ArgumentParser(add_help=False, parents=[expect])
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
    p.add_argument("--driver-start", choices=("auto", "cold"), default="auto", help="cold: start fresh instrumentation; detach an existing attachment first")
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

    p = verb("log", "the session's history on a device: app log lines with the steps run, Tap's actions and markers; or write a marker", on_device)
    p.add_argument("action", choices=LOG_ACTIONS, nargs="?", default="read", help="read (default); mark NAME; redact REGEX; driver / evidence: save the driver's own log / the device's failure evidence to a file")
    p.add_argument("value", nargs="?", help="mark: the marker's name; redact: a regular expression whose matches are hidden in the whole log")
    start = p.add_mutually_exclusive_group()
    start.add_argument("--since", metavar="MARKER", help="from a marker: its name (the newest with it) or @CURSOR (as `log mark` prints it)")
    start.add_argument("--after", type=int, metavar="CURSOR", help="what came after this cursor (printed by an earlier read)")
    start.add_argument("--tail", type=int, metavar="N", help="the newest N entries that pass the filters")
    start.add_argument("--last-action", action="store_true", help="from the start of the newest Tap action")
    start.add_argument("--step", metavar="STEP", help="only one step: its number or id, from its first action to its last")
    p.add_argument("--until", metavar="MARKER", help="stop at a marker (a name or @CURSOR) instead of now")
    p.add_argument("--package", dest="packages", action="append", default=[], metavar="PACKAGE", help="only this app's lines (repeatable)")
    p.add_argument("--priority", help="lines at least this severe: verbose, debug, info, warn, error, fatal")
    p.add_argument("--tag", dest="tags", action="append", default=[], metavar="TAG", help="only lines with this tag (repeatable)")
    p.add_argument("--grep", metavar="REGEX", help="only lines whose message matches")
    p.add_argument("--no-actions", dest="actions", action="store_false", help="leave Tap's own actions out")
    p.add_argument("--driver-lines", action="store_true", help="keep the Tap driver's own logcat lines (UiAutomator lookups), left out by default")
    p.add_argument("--limit", type=int, default=LOG_LIMIT, help=f"at most this many entries, 1 to 5000 (default {LOG_LIMIT}): from a start the first ones (read on with --after), otherwise the newest")
    p.add_argument("--json", dest="as_json", action="store_true", help="the entries as JSON data instead of lines")

    p.add_argument("-o", "--out", help="driver / evidence: new local output file; never overwrite")

    p = verb("deaths", "app crashes and ANRs since the device was attached (watched apps; --all for every app)", on_device)
    p.add_argument("--package", help="only this app's")
    p.add_argument("--all", dest="all_apps", action="store_true", help="also apps the session did not start or target")
    p.add_argument("--trace", type=int, metavar="SEQ", help="print one death's full stack trace")
    p.add_argument("--json", dest="as_json", action="store_true", help="structured entries")
    p.add_argument(
        "--wait", action="store_true",
        help="block until a watched app dies (exit 4), the session ends or --timeout (exit 0); "
        "run it in the background to be told; keeps no idle session alive",
    )
    p.add_argument("--timeout", type=parse_duration, help="with --wait: give up after this long (default: until the session ends)")

    p = verb("media-record", "explicit bounded video/audio recording; save bytes privately on stop", on_device)
    p.add_argument("action", choices=("start", "stop"))
    p.add_argument("--no-video", action="store_false", dest="video", help="start: audio only")
    p.add_argument("--audio-source", choices=("output", "playback", "mic"), help="start: output mutes speakers; mic captures microphone")
    p.add_argument("--max-seconds", type=int, help="start: 1..30 with video, 1..60 audio only")
    p.add_argument("-o", "--out", help="stop: additional new output; a private canonical copy is retained")

    p = verb("snapshot", "print the screen, one node per line with its ref", on_device)
    level = p.add_mutually_exclusive_group()
    level.add_argument("-i", "--interactive", dest="level", action="store_const", const=render.INTERACTIVE, help="interactive controls with descendant context and labeled groups")
    level.add_argument("--all", dest="level", action="store_const", const=render.ALL, help="every node, layout containers too")
    p.add_argument("--candidates", action="store_true", help="complete structured SDK snapshot; incompatible with view filters")
    p.add_argument("--package", help="only windows rooted in this package")
    p.add_argument("--window", type=int, help="only this one-based window ordinal from the snapshot header")
    p.add_argument("--hide-keyboard", action="store_true", help="hide input-method windows; unknown types remain visible")
    p.add_argument("--hide-system-chrome", action="store_true", help="hide Android system-type windows, not system-owned apps")

    for name, help in (("inspect", "read one node's complete SDK state as JSON"), ("exists", "whether any node matches now"), ("count", "count matching nodes now")):
        p = verb(name, help, on_device)
        p.add_argument("target", help=TARGET_HELP)
        p.add_argument("--timeout", type=_command_duration, help="read budget, at most 120s")

    p = verb("elements", "read all matching nodes as a JSON array in one bulk read", on_device, budget)
    p.add_argument("target", help="written selector with optional pkg and relations; no refs, first or index")
    p.add_argument("--texts", action="store_true", help="return raw text values instead of element state; preserve nulls and empty strings")

    p = verb("tap", "tap a node", on_device, settle)
    p.add_argument("target", help=TARGET_HELP)
    kind = p.add_mutually_exclusive_group()
    kind.add_argument("--long", action="store_true", help="long-press instead")
    kind.add_argument("--double", action="store_true", help="double-tap instead")
    kind.add_argument("--outside-panel", action="store_true", help="tap outside the dialog, menu, drawer or sheet the target is in")
    p.add_argument("--allow-reach-below", action="store_true", help="with --outside-panel: send the touch even onto the screen under a panel that lets touches through")
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

    p = verb("toast", "wait for a toast, or read retained toast history", on_device)
    p.add_argument("text", nargs="?", help="the toast's text (omit for any toast)")
    p.add_argument("--contains", action="store_true", help="text is only part of the toast")
    p.add_argument("--package", help="only this package's toasts (default: any app's)")
    p.add_argument("--history", action="store_true", help="read retained toasts immediately, oldest first; never consumes")
    p.add_argument("--after-seq", type=int, default=0, help="history: exclusive sequence cursor (default 0)")
    p.add_argument("--stream", default="", help="history: stream ID from the previous read")
    p.add_argument("--json", dest="as_json", action="store_true", help="history: structured observations and cursor metadata")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="at most 120s; default 10s")

    p = verb("notification", "list the device's notifications, or wait for, open or dismiss one (read as data; the shade stays closed)", on_device, settle)
    p.add_argument("action", choices=NOTIFICATION_ACTIONS, nargs="?", default="list")
    p.add_argument("--title", help="the notification's title")
    p.add_argument("--text", help="the notification's text")
    p.add_argument("--contains", action="store_true", help="title/text are only part of it")
    p.add_argument("--package", help="only this package's notifications")
    p.add_argument("--button", help="open: press the action button with this title instead")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="command/wait budget, at most 120s; default 10s")

    p = verb("wait-event", "wait for the next device event of a kind (from now on) and print it", on_device)
    p.add_argument("kind", choices=EVENT_KINDS)
    p.add_argument("text", nargs="?", help="part of the event's text, any case: a toast's message, a window's title or class, a click's label, an edit's text")
    p.add_argument("--package", help="only this package's events (default: any app's)")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="at most 120s; default 10s")

    p = verb("watch", "print device events as they happen, one per line, until stopped (Ctrl-C), --for ends or the device is detached", on_device)
    p.add_argument("kinds", nargs="+", metavar="KIND", help=f"event kinds, each or comma lists: {', '.join(EVENT_KINDS)}")
    p.add_argument("--package", help="only this package's events (default: any app's)")
    p.add_argument("--for", dest="duration", type=_duration, default=None, metavar="DURATION", help="stop after this long (default: until stopped)")
    p.add_argument("--json", dest="as_json", action="store_true", help="one JSON object per line")

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

    p = verb("app", "app lifecycle: " + ", ".join(APP_ACTIONS), on_device, expect)
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

    p = verb("record", "protect a named range of history and device logs from rolling eviction", common)
    p.add_argument("action", choices=("start", "stop", "list", "export", "clean"))
    p.add_argument("name", nargs="?", help="required for start/stop/export/clean; list takes no name")
    p.add_argument("-L", dest="long", action="store_true", help="list only: include capture state, timestamps and shared segment disk usage")
    p.add_argument("--logs", action="store_true", help="export only: include retained device logs")
    p.add_argument("--json", dest="as_json", action="store_true", help="export only: JSON instead of readable text; stdout, never a file")
    p.add_argument("--details", action="store_true", help="export only: show retained redacted crash/ANR stack traces in text (JSON always includes them)")

    p = verb("history", "show retained activity, not a catalog; latest 200 steps by default", common)
    p.add_argument("--from", dest="start", metavar="STEP|TIME", help="inclusive stable step cursor or ISO timestamp with timezone; page forward")
    p.add_argument("--until", metavar="STEP|TIME", help="exclusive stable step cursor or ISO timestamp with timezone")
    p.add_argument("--last", metavar="DURATION", help="all retained activity in the last duration, e.g. 30s or 5m; excludes --from/--until")
    p.add_argument("--details", action="store_true", help="show retained redacted crash/ANR stack traces in text (JSON always includes them)")
    p.add_argument("--limit", type=int, default=200, metavar="N", help="maximum steps per page, 1..5000 (default: 200)")
    p.add_argument("--logs", action="store_true", help="include retained device logs for the full requested time range, even without steps")
    p.add_argument("--json", dest="as_json", action="store_true", help="JSON instead of readable text; stdout, never a file")

    verb("mcp", "run the MCP server on stdio (same sessions as the CLI)")
    verb("skill", "print the agent skill (SKILL.md)")
    return root


# Verbs after which no app deaths are reported: they need no device or session (record, export
# and the inventory work without one), end the session, or list deaths themselves.
UNREPORTED = frozenset({"watch", "attach", "sessions", "release", "devices", "unquarantine", "server-info", "record", "history", "deaths"})


def run(args: argparse.Namespace, agent: Agent) -> str:
    v = args.verb
    device = getattr(args, "device", None)
    settle = getattr(args, "settle", False)
    if v == "attach":
        kwargs = {} if args.idle is None else {"idle": args.idle}
        return agent.attach(
            args.serial, wait_for_device=args.wait_for_device, inspect_quarantined=args.inspect_quarantined,
            driver_start=args.driver_start, **kwargs
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
    if v == "log":
        return agent.log(
            args.action, args.value, device, since=args.since, after=args.after, tail=args.tail,
            last_action=args.last_action, packages=args.packages, priority=args.priority, tags=args.tags,
            grep=args.grep, actions=args.actions, limit=args.limit, step=args.step, until=args.until, as_json=args.as_json, out=args.out,
            driver_lines=args.driver_lines,
        )
    if v == "deaths":
        if args.wait:
            if args.trace is not None or args.as_json:
                raise UsageError("--wait does not take --trace or --json")
            return agent.wait_for_death(device, package=args.package, all_apps=args.all_apps, timeout=args.timeout)
        if args.timeout is not None:
            raise UsageError("--timeout needs --wait")
        return agent.deaths(device, package=args.package, all_apps=args.all_apps, trace=args.trace, as_json=args.as_json)
    if v == "media-record":
        return agent.recording(args.action, device, video=args.video, audio_source=args.audio_source, max_seconds=args.max_seconds, out=args.out)
    if v == "snapshot":
        return agent.snapshot(device, args.level or render.DEFAULT, candidates=args.candidates,
                              package=args.package, window=args.window, hide_keyboard=args.hide_keyboard,
                              hide_system_chrome=args.hide_system_chrome)
    if v in ("inspect", "exists", "count"):
        return getattr(agent, v)(args.target, device, args.timeout)
    if v == "elements":
        return agent.elements(args.target, device, args.timeout, texts=args.texts)
    if v == "tap":
        return agent.tap(args.target, device, long=args.long, settle=settle, double=args.double,
                         outside_panel=args.outside_panel, allow_reach_below=args.allow_reach_below,
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
        return agent.toast(args.text, device, contains=args.contains, package=args.package, timeout=args.timeout,
                           history=args.history, after_seq=args.after_seq, stream=args.stream, as_json=args.as_json)
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
    if v == "wait-event":
        return agent.wait_event(args.kind, args.text, device, package=args.package, timeout=args.timeout)
    if v == "settle":
        return agent.settle(device, full=args.full, timeout=args.timeout, package=args.package, stable_for=args.stable_for, signal=args.signal)
    if v == "screenshot":
        return str(agent.screenshot(device, args.out))
    if v == "capture":
        return agent.capture(device, args.out)
    if v == "record":
        return agent.record(args.action, args.name, long=args.long, logs=args.logs, as_json=args.as_json, details=args.details)
    if v == "history":
        return agent.history(start=args.start, until=args.until, last=args.last, limit=args.limit, logs=args.logs, as_json=args.as_json, details=args.details)
    if v == "app-data":
        return agent.app_data(args.action, args.package, args.path, args.local, device, overwrite=args.overwrite)
    if v == "app":
        return agent.app(args.action, args.package, args.argument, device, any_app=args.any_app, extras=args.extra, timeout=args.timeout)
    raise AssertionError(v)


def _observed(args: argparse.Namespace, agent: Agent) -> str:
    """The step, with the device events it causes when it was asked for them."""
    return agent.observing_events(
        lambda: run(args, agent), device=getattr(args, "device", None), events=getattr(args, "events", ()),
        expect=getattr(args, "expect_events", ()), timeout=getattr(args, "event_timeout", None),
    )


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
        if args.verb == "watch":
            for line in agent.watch(args.kinds, getattr(args, "device", None), package=args.package, duration=args.duration, as_json=args.as_json):
                print(line, flush=True)
            return EXIT_OK
        if args.verb in UNREPORTED:
            print(run(args, agent))
        else:
            print(agent.reporting_deaths(lambda: _observed(args, agent), expect=getattr(args, "expect_death", False)))
        sys.stdout.flush()
        return EXIT_OK
    except KeyboardInterrupt:
        if args.verb == "watch":
            return EXIT_OK  # the way to stop a watch without --for
        raise
    except BrokenPipeError:
        # The reader went away (`log | head`): not an error of ours. Point stdout at nothing so
        # the interpreter's own flush at exit does not fail again.
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
        return EXIT_OK
    except AppDiedStep as died:
        # The step ran: its output goes where a step's output goes, the exit code says the app died.
        print(died)
        return died.exit_code
    except AgentError as error:
        print(f"error: {error}", file=sys.stderr)
        return error.exit_code
    except UsageError as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_USAGE
    finally:
        agent.close()
