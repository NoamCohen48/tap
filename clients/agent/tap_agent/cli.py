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
    DEFAULT_WAIT,
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


def _whole_seconds(text: str) -> int:
    """A duration in whole seconds (``20s``, ``1m``), for the recording limit the server takes."""
    seconds = _duration(text)
    if not float(seconds).is_integer():
        raise argparse.ArgumentTypeError(f"{text!r} is not a whole number of seconds")
    return int(seconds)


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
    p = verb("devices", "list devices, properties and who holds them; or unquarantine SERIAL: put a checked quarantined device back in service (retries its cleanup first)", common)
    p.add_argument("action", nargs="?", choices=("unquarantine",), help="omit to list")
    p.add_argument("serial", nargs="?", help="unquarantine: the device's serial")
    verb("detach", "detach one device without ending its session", on_device)
    p = verb("server-info", "daemon version, compatibility and configuration", common)
    p.add_argument("--json", dest="as_json", action="store_true", help="structured JSON instead of text")
    p = verb("device-info", "the whole device state: display, screen and keyguard, keyboard, conditions, top activity", on_device)
    p.add_argument("--json", dest="as_json", action="store_true", help="structured JSON instead of text")
    p = verb("capabilities", "read SDK operation support and prerequisites; no device changes", on_device)
    p.add_argument("--json", dest="as_json", action="store_true", help="structured capability entries")

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
    p.add_argument("--max", type=_whole_seconds, metavar="DURATION", help="start: stop after this long, whole seconds: 1s..30s with video, 1s..60s audio only")
    p.add_argument("-o", "--out", help="stop: additional new output; a private canonical copy is retained")

    p = verb("snapshot", "print the screen, one node per line with its ref", on_device)
    level = p.add_mutually_exclusive_group()
    level.add_argument("-i", "--interactive", dest="level", action="store_const", const=render.INTERACTIVE, help="interactive controls with descendant context and labeled groups")
    level.add_argument("--all", dest="level", action="store_const", const=render.ALL, help="every node, layout containers too")
    p.add_argument("--json", dest="as_json", action="store_true", help="the complete structured snapshot with selector candidates; no view level or filter")
    p.add_argument("--package", help="only windows rooted in this package")
    p.add_argument("--window", type=int, help="only this one-based window ordinal from the snapshot header")
    p.add_argument("--keyboard", action="store_true", help="show the keyboard window's nodes (by default it is one header line with a count)")
    p.add_argument("--hide-keyboard", action="store_true", help="hide input-method windows, header included; unknown types remain visible")
    p.add_argument("--hide-system-chrome", action="store_true", help="hide Android system-type windows, not system-owned apps")

    for name, help in (("inspect", "read one node's complete state"), ("exists", "whether any node matches now"), ("count", "count matching nodes now")):
        p = verb(name, help, on_device)
        p.add_argument("target", help=TARGET_HELP)
        p.add_argument("--timeout", type=_command_duration, help="read budget, at most 120s")
        p.add_argument("--json", dest="as_json", action="store_true", help="structured JSON instead of text")

    p = verb("elements", "read all matching nodes in one bulk read, one line each", on_device, budget)
    p.add_argument("--json", dest="as_json", action="store_true", help="the SDK snapshots (or texts) as a JSON array")
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

    p = verb("type", "type into the focused field, or into TARGET after tapping it and waiting for its focus", on_device, settle)
    p.add_argument("text")
    p.add_argument("target", nargs="?", help="optional: " + TARGET_HELP)
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
    p.add_argument("action", choices=KEYBOARD_ACTIONS, nargs="?", help="omit to read whether a soft keyboard shows")

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
        ("swipe", "swipe across a node, the finger moving towards --finger"),
        ("fling", "fling a node towards a content edge, as for scroll; content may keep moving"),
    ):
        p = verb(name, help, on_device, settle)
        p.add_argument("target", help=TARGET_HELP)
        if name == "swipe":
            p.add_argument("--finger", required=True, choices=("up", "down", "left", "right"), help="where the finger moves (up reveals what is below)")
        else:
            p.add_argument("direction", choices=("up", "down", "left", "right"), help="the content to reveal (down: what is below; the finger moves up)")
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

    p = verb("key", "press a key: any Android key name (back, home, enter, volume-up, media-play-pause, …) or a key code", on_device, settle)
    p.add_argument("name", nargs="?", help="a key name (`--list` prints them all; digit keys are keycode-0 .. keycode-9) or a numeric key code")
    p.add_argument("--list", dest="list_keys", action="store_true", help="print every key name with its code (no device needed)")

    p = verb("panel", "open the notification shade or quick settings (key back closes it)", on_device, settle)
    p.add_argument("name", choices=PANELS)

    p = verb("rotate", "rotate the display and keep it there (auto: back to the sensor)", on_device, settle)
    p.add_argument("how", choices=ROTATIONS)

    p = verb("screen", "screen state, or turn it on/off, or wake it and dismiss a keyguard without a PIN", on_device, settle)
    p.add_argument("action", choices=SCREEN_ACTIONS, nargs="?", help="omit to read the screen and keyguard state")

    p = verb("condition", "device conditions (animations, dark mode, font scale, density, network switches, languages, stay awake, accessibility display): print or change until release", on_device)
    p.add_argument("name", choices=CONDITIONS, nargs="?", help="omit to print all")
    p.add_argument("value", nargs="?", help="animations/dark-mode/airplane-mode/wifi/mobile-data/stay-awake/high-contrast-text/color-inversion/bold-text: on|off; font-scale: 0.5..2.0; density: dpi or reset; locale: comma-separated BCP-47 tags, e.g. fr-FR,en (omit to print)")

    p = verb("permission", "list the permission dialog's buttons, or press one", on_device, settle)
    p.add_argument("choice", choices=PERMISSION_CHOICES, nargs="?", help="the button to press (omit to list them)")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="dialog wait/action budget, at most 120s (default 10s)")
    p.add_argument("--accuracy", choices=ACCURACIES, help="pick Precise or Approximate first (location dialog, Android 12+)")

    p = verb("toast", "read retained toasts (oldest first, never consumes), or --wait for one", on_device)
    p.add_argument("text", nargs="?", help="the toast's text (omit for any toast)")
    p.add_argument("--contains", action="store_true", help="text is only part of the toast")
    p.add_argument("--package", help="only this package's toasts (default: any app's)")
    p.add_argument("--wait", action="store_true", help="wait for a matching toast (one shown in the last 3.5 s counts)")
    p.add_argument("--after-seq", type=int, default=0, help="read: exclusive sequence cursor (default 0)")
    p.add_argument("--stream", default="", help="read: stream ID from the previous read")
    p.add_argument("--json", dest="as_json", action="store_true", help="read: structured observations and cursor metadata")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="with --wait: at most 120s; default 10s")

    p = verb("notification", "list the device's notifications, --wait for one, or open or dismiss one (read as data; the shade stays closed)", on_device, settle)
    p.add_argument("action", choices=NOTIFICATION_ACTIONS, nargs="?", help="omit to list (or --wait)")
    p.add_argument("--wait", action="store_true", help="wait until one matches and print it")
    p.add_argument("--title", help="the notification's title")
    p.add_argument("--text", help="the notification's text")
    p.add_argument("--contains", action="store_true", help="title/text are only part of it")
    p.add_argument("--package", help="only this package's notifications")
    p.add_argument("--button", help="open: press the action button with this title instead")
    p.add_argument("--timeout", type=_command_duration, default=10.0, help="command/wait budget, at most 120s; default 10s")

    p = verb("watch", "print device events as they happen, one per line, until stopped (Ctrl-C), --for ends or the device is detached; or --until-first", on_device)
    p.add_argument("kinds", nargs="+", metavar="KIND", help=f"event kinds, each or comma lists: {', '.join(EVENT_KINDS)}")
    p.add_argument("--package", help="only this package's events (default: any app's)")
    p.add_argument("--for", dest="duration", type=_duration, default=None, metavar="DURATION", help="stop after this long (default: until stopped)")
    p.add_argument("--json", dest="as_json", action="store_true", help="one JSON object per line")
    p.add_argument("--until-first", action="store_true", help="wait for the first event of one KIND from now on, print it and stop (exit 1 on --timeout)")
    p.add_argument("--text", help="with --until-first: part of the event's text, any case: a toast's message, a window's title or class, a click's label, an edit's text")
    p.add_argument("--timeout", type=_command_duration, default=None, help="with --until-first: at most 120s; default 10s")

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

    p = verb("capture", "save one evidence bundle (the screen, hierarchy, device/driver logs and activity/window state now, and the device's history as text and JSON); or list, show or clean the bundles taken", on_device)
    p.add_argument("action", nargs="?", metavar="NAME|list|show|clean", help="a NAME labels the new bundle; list, show NAME|ID or clean NAME|ID manage the ones taken")
    p.add_argument("name", nargs="?", metavar="NAME|ID", help="show/clean: the bundle's name, or its id (as `capture list -L` shows it)")
    p.add_argument("-o", "--out", help="new private directory, never overwrite (default .tap/agent/capture-<serial>[-NAME]-<time>)")
    since = p.add_mutually_exclusive_group()
    since.add_argument("--last", metavar="DURATION", help="taking: only the last DURATION of history (default: all that is kept); list: bundles taken in it")
    since.add_argument("--from", dest="start", metavar="TIME", help="taking: history from this ISO time with a time zone; list: bundles taken since")
    p.add_argument("-L", dest="long", action="store_true", help="list: id, folder, parts and failed parts")
    p.add_argument("--older-than", metavar="DURATION", help="clean: every bundle taken longer ago than DURATION")
    p.add_argument("--all", dest="all_captures", action="store_true", help="clean: every bundle")
    p.add_argument("--json", dest="as_json", action="store_true", help="list/show: structured JSON instead of text")

    p = verb("app", "app lifecycle: " + ", ".join(APP_ACTIONS) + " (visible waits until the app owns the focused window)", on_device, expect)
    p.add_argument("action", choices=APP_ACTIONS)
    p.add_argument("package", help="the app's package name, e.g. com.example.app")
    p.add_argument("argument", nargs="?", help="activity (launch), URI (open-link), APK path (install), permission (grant, revoke, granted) or languages (locale: fr-FR,en or system)")
    p.add_argument("--any-app", action="store_true", help="open-link: let any app handle the link, not only this one")
    p.add_argument("--json", dest="as_json", action="store_true", help="reads (granted, running, locale, installed, process): the value as JSON")
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

    p = verb("history", "what happened: calls, crashes, ANRs and (with --logs) device logs, with this agent's steps; or mark/redact held devices, or keep a named range past retention", common)
    p.add_argument("action", choices=("read", "mark", "redact", "keep"), nargs="?", default="read")
    p.add_argument("value", nargs="?", help="mark NAME, redact REGEX, or keep start|stop|list|clean")
    p.add_argument("name", nargs="?", help="keep start/stop/clean: the kept range's name (or its id, as `history keep list -L` shows it)")
    p.add_argument("-L", dest="long", action="store_true", help="keep list: state, id, connection, times and disk use")
    p.add_argument("-d", "--device", dest="history_devices", action="append", default=[], metavar="SERIAL", help="only these devices (repeatable); for mark/redact, these held devices")
    p.add_argument("--kept", metavar="NAME", help="only a kept range (`history keep start`), also after its session ended")
    start = p.add_argument_group("where to start (one; default: the newest entries)")
    start.add_argument("--from", dest="start", metavar="TIME", help="from this ISO time with a time zone, e.g. 2026-11-21T12:00:00Z")
    start.add_argument("--last", metavar="DURATION", help="everything in the last DURATION, e.g. 30s or 5m")
    start.add_argument("--since-marker", metavar="NAME", help="from the newest marker NAME (`history mark`)")
    start.add_argument("--step", metavar="STEP", help="one step, by the cursor or id history prints, with its device-log span")
    start.add_argument("--last-action", action="store_true", help="from the start of the newest step")
    start.add_argument("--after", metavar="POSITION", help="the next page: the position a previous history printed after next: or older:")
    p.add_argument("--until", metavar="TIME", help="up to this ISO time (excluded)")
    p.add_argument("--until-marker", metavar="NAME", help="through the newest marker NAME")
    p.add_argument("--logs", action="store_true", help="add the device logs (any log filter below implies it)")
    p.add_argument("--package", dest="packages", action="append", default=[], help="only this app's log lines (repeatable)")
    p.add_argument("--priority", help="log lines at least this severe: verbose, debug, info, warn, error, fatal")
    p.add_argument("--tag", dest="tags", action="append", default=[], help="only log lines with this tag (repeatable)")
    p.add_argument("--grep", metavar="REGEX", help="only log lines whose message matches (Java regex)")
    p.add_argument("--no-actions", dest="actions", action="store_false", help="leave Tap's own action entries out of the logs")
    p.add_argument("--driver-lines", action="store_true", help="keep the Tap driver's own logcat lines")
    p.add_argument("--details", action="store_true", help="show crash and ANR stack traces")
    p.add_argument("--limit", type=int, default=200, metavar="N", help="at most N server entries per page, 1 to 5000 (default 200); a step's calls print as its one line")
    p.add_argument("--json", dest="as_json", action="store_true", help="JSON (tap-history/2) instead of text, on stdout")

    verb("mcp", "run the MCP server on stdio (same sessions as the CLI)")
    verb("skill", "print the agent skill (SKILL.md)")
    return root


# Verbs after which no app deaths are reported: they need no device or session (record, export
# and the inventory work without one), end the session, or list deaths themselves.
UNREPORTED = frozenset({"watch", "attach", "sessions", "release", "devices", "server-info", "history", "deaths"})


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
        if args.action is None:  # argparse gives a lone word to action, so serial is None here
            return agent.devices()
        if args.serial is None:
            raise UsageError("devices unquarantine needs the device's SERIAL")
        return agent.unquarantine(args.serial)
    if v == "detach":
        return agent.detach(device)
    if v == "server-info":
        return agent.server_info(as_json=args.as_json)
    if v == "device-info":
        return agent.device_info(device, as_json=args.as_json)
    if v == "capabilities":
        return agent.capabilities(device, as_json=args.as_json)
    if v == "deaths":
        if args.wait:
            if args.trace is not None or args.as_json:
                raise UsageError("--wait does not take --trace or --json")
            return agent.wait_for_death(device, package=args.package, all_apps=args.all_apps, timeout=args.timeout)
        if args.timeout is not None:
            raise UsageError("--timeout needs --wait")
        return agent.deaths(device, package=args.package, all_apps=args.all_apps, trace=args.trace, as_json=args.as_json)
    if v == "media-record":
        return agent.recording(args.action, device, video=args.video, audio_source=args.audio_source, max_seconds=args.max, out=args.out)
    if v == "snapshot":
        return agent.snapshot(device, args.level or render.DEFAULT, as_json=args.as_json,
                              package=args.package, window=args.window, hide_keyboard=args.hide_keyboard, keyboard=args.keyboard,
                              hide_system_chrome=args.hide_system_chrome)
    if v in ("inspect", "exists", "count"):
        return getattr(agent, v)(args.target, device, args.timeout, as_json=args.as_json)
    if v == "elements":
        return agent.elements(args.target, device, args.timeout, texts=args.texts, as_json=args.as_json)
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
            button=args.button, timeout=args.timeout, settle=settle, wait=args.wait,
        )
    if v == "toast":
        return agent.toast(args.text, device, contains=args.contains, package=args.package, timeout=args.timeout,
                           wait=args.wait, after_seq=args.after_seq, stream=args.stream, as_json=args.as_json)
    if v == "scroll":
        return agent.scroll(args.target, args.direction, device, settle=settle, percent=args.percent, timeout=args.timeout)
    if v == "swipe":
        return agent.swipe(args.target, args.finger, device, settle=settle, percent=args.percent, timeout=args.timeout)
    if v == "fling":
        return agent.fling(args.target, args.direction, device, settle=settle, timeout=args.timeout)
    if v == "scroll-until":
        return agent.scroll_until(args.target, args.destination, args.direction, device, max_scrolls=args.max_scrolls, percent=args.percent, timeout=args.timeout)
    if v == "drag":
        return agent.drag(args.target, args.destination, device, settle=settle, timeout=args.timeout)
    if v == "pinch":
        return agent.pinch(args.target, args.how, args.percent, device, settle=settle, timeout=args.timeout)
    if v == "key":
        return agent.key(args.name, device, settle=settle, list_keys=args.list_keys)
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
    if v == "watch":  # --until-first; a plain watch streams from main
        if args.duration is not None or args.as_json:
            raise UsageError("--until-first prints one event: it takes no --for or --json")
        return agent.watch_first(args.kinds, args.text, device, package=args.package,
                                 timeout=DEFAULT_WAIT if args.timeout is None else args.timeout)
    if v == "settle":
        return agent.settle(device, full=args.full, timeout=args.timeout, package=args.package, stable_for=args.stable_for, signal=args.signal)
    if v == "screenshot":
        return str(agent.screenshot(device, args.out))
    if v == "capture":
        return agent.capture(args.action, args.name, device, args.out, last=args.last, start=args.start, long=args.long,
            older_than=args.older_than, all_captures=args.all_captures, as_json=args.as_json)
    if v == "history":
        return agent.history(args.action, args.value, start=args.start, until=args.until, last=args.last, limit=args.limit,
            logs=args.logs, as_json=args.as_json, details=args.details, devices=args.history_devices, packages=args.packages,
            priority=args.priority, tags=args.tags, grep=args.grep, actions=args.actions, driver_lines=args.driver_lines,
            since_marker=args.since_marker, until_marker=args.until_marker, step=args.step, last_action=args.last_action,
            kept=args.kept, after=args.after, name=args.name, long=args.long)
    if v == "app-data":
        return agent.app_data(args.action, args.package, args.path, args.local, device, overwrite=args.overwrite)
    if v == "app":
        return agent.app(args.action, args.package, args.argument, device, any_app=args.any_app, extras=args.extra, timeout=args.timeout, as_json=args.as_json)
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
        if args.verb == "watch" and not args.until_first:
            if args.text is not None or args.timeout is not None:
                raise UsageError("--text and --timeout go with --until-first")
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
        # The reader went away (`history | head`): not an error of ours. Point stdout at nothing so
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
