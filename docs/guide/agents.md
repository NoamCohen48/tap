# Coding agents: tap-agent

!!! warning "Experimental"
    `tap-agent`, the server features it is built on (held connections, screen snapshots with
    refs, the event log) and the `tap-events/1` export format are experimental: they may change
    in any release, including after 1.0, until this notice goes away.

`tap-agent` lets a coding agent (Claude Code, Cursor, …) drive a device through the same `tap`
server your tests use: read the screen, act on it, check the result, and hand you a log of what
it did. It is a CLI (one step per call) and an MCP server with the same steps as tools, both
built on the Python client.

## Install

It needs the `tap` server ([Getting started](getting-started.md#1-the-server)) and Python 3.10+.
Install the Python client first, then the agent tools, from the GitHub releases:

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.2/tap_e2e-0.0.2-py3-none-any.whl
pip install https://github.com/NoamCohen48/tap/releases/download/client-agent/v0.0.2/tap_agent-0.0.2-py3-none-any.whl
tap start
```

For Claude Code, register the MCP server:

```bash
claude mcp add tap -- tap-agent mcp
```

or give the agent the CLI and its instructions: `tap-agent skill` prints a `SKILL.md` that
teaches the flow below.

## The flow

```bash
tap-agent devices                                   # serials and whether they are free
tap-agent attach emulator-5554                      # hold the device
tap-agent app cold-launch com.example.app
tap-agent snapshot                                  # one line per node, with a ref
tap-agent tap @e12 --settle                         # act; --settle prints what changed
tap-agent fill id=email me@example.com
tap-agent wait text=Welcome
tap-agent export -o session.json                    # the session's device calls, as JSON
tap-agent release                                   # frees the device
```

- **Sessions** are *held connections* on the server: they outlive each CLI call, are shared by
  the CLI and the MCP server, and end on `release` or after 15 idle minutes. `--session NAME`
  (or `TAP_AGENT_SESSION`) keeps several apart.
- **Snapshots** print the visible screen, one node per line, grouped by window:

    ```
    # com.example.app
    @e3  [Button]  "Log in"  id=login_button
    @e5  [EditText]  hint="Email"  id=email  focused
    ```

    `-i` keeps only nodes you can act on. A ref (`@e3`) names a selector the server found to
    match exactly that node; it keeps naming the node while it stays on screen.
- **Targets** are a ref or `key=value` terms joined by commas: `id=`, `text=`, `text~=`
  (contains), `desc=`, `desc~=`, `hint=`, `class=`, `pkg=`, `index=`.
  A target matches anywhere on the screen; `pkg=` keeps it to one app's nodes, as
  `device.app(pkg).element(…)` does in a test. As in tests, every action needs exactly one
  match and fails before touching the device otherwise, and a tap whose point another window
  covers (a dialog, the shade) fails as `NOT_INTERACTABLE` / `OBSCURED` (a node covered
  completely is not found at all).
- **Apps**: `app <action> <package> [argument]`: `launch`, `cold-launch`, `stop`, `clear`,
  `install` (argument: the APK), `uninstall`, `grant` (argument: the permission), `running`.
- **System panels**: `panel notifications` / `panel quick-settings` (MCP `open_panel`) open the
  notification shade or quick settings; target their nodes with `pkg=com.android.systemui`, and
  `key back` closes them (twice from quick settings on newer Android). `key recents` opens the
  recent apps.
- **Gestures and device state**: `tap --double`, `fling`, `drag <target> <destination>`,
  `pinch <target> open|close`; `rotate portrait|landscape|…|auto` (detach restores the device's
  own setting); `screen [on|off|unlock]` (`unlock` never enters a PIN); `permission [choice]`
  lists or presses the runtime-permission dialog's buttons; `app foreground|background|open-link|revoke|granted
  <package> [URI|PERMISSION]`; `permission allow-foreground-only --accuracy approximate` picks
  Precise or Approximate in the location dialog first (Android 12+). MCP has the same tools
  (`fling`, `drag`, `pinch`, `rotate`, `screen`, `permission` with `accuracy`).
- **Accessibility actions and sliders**: `action <target>` lists the node's standard and custom
  accessibility actions, `action <target> expand` performs one and `action <target> Archive
  --custom` a custom one, as a screen reader would (no touch); `progress <target> 40` sets a
  slider in its own units (a value outside its range is refused). MCP `accessibility_action`,
  `set_progress`.
- **Device conditions and languages**: `condition` prints animations, dark mode, font scale and
  density, the network switches and the device languages; `condition animations off`,
  `condition dark-mode on` (Android 10+), `condition font-scale 1.3`, `condition density
  320|reset`, `condition airplane-mode|wifi|mobile-data on|off` (real switches, read back) and
  `condition locale fr-FR,en` change one until release, which restores the device's own values;
  `location 48.85 2.35 [--accuracy 10]` mocks the device location until release; `app locale <package> [fr-FR,en|system]` reads or sets the app's own languages
  (Android 13+). `condition stay-awake on` keeps the screen on while plugged in, and
  `condition high-contrast-text|color-inversion|bold-text on|off` (bold text Android 12+) set
  the accessibility display settings. MCP `condition`, `set_location` and `app` with `locale`.
- **Notifications and the activity on top**: `notification` lists the device's notifications
  (read as data; the shade stays closed), `notification await --title T [--text X] [--contains]
  [--package PKG]` waits for one, `notification open …` opens it as a tap does (`--button
  "Mark as read"` presses an action button) and `notification dismiss …` swipes it away (an
  ongoing one is refused). `activity` prints the activity on top (`package/class`). MCP
  `notification`, `foreground_activity`.
- **Keyboard, clipboard, toasts**: `keyboard [hide]`, `submit <target>` (the focused field's
  action key; tap the field first), `clipboard [text]`, `toast [text] [--contains] [--package PKG]`
  (any app's toast unless `--package`);
  MCP `keyboard`, `submit`, `clipboard`, `await_toast`.
- **Files and the gallery**: `push <local> <device-path>` copies a file to the device (its
  directory must exist; an existing file is never overwritten), `pull <device-path> [-o FILE]`
  copies one back and prints the local path, `media <local> [--name NAME]` adds a photo or video
  to the gallery (`Pictures/Tap`, `Movies/Tap`, indexed by the media scanner). The bytes stream
  through the server; what Tap created is removed on release. MCP `push_file`, `pull_file`,
  `add_media`.
- **`--settle`** after an action waits for the screen to stop changing and prints the
  difference (`+` added, `-` removed nodes), which is usually enough to pick the next step.
- **Evidence**: `screenshot` and `capture` (screenshot, hierarchy, device info, driver log)
  save files under `.tap/agent/` and print their paths.

Exit codes: `0` ok, `1` the step failed, `2` usage, `3` no server running.

## Turning a session into a test

`tap-agent export` prints the session's event log as JSON: every device call the session made,
in order, with its outcome. Refs appear as the selectors they stood for, so the log is directly
usable for a test in any language; `tap-agent` does not generate code, you (or your agent) do.

```json
{
  "format": "tap-events/1",
  "session": "agent",
  "exported_at": "2026-09-28T10:15:02Z",
  "dropped": 0,
  "events": [
    {"seq": 1, "at": "2026-09-28T10:14:40.020Z", "duration_ms": 1830, "serial": "emulator-5554",
     "ok": true,
     "app": {"operation": "cold_launch", "package_name": "com.example.app"}},
    {"seq": 2, "at": "2026-09-28T10:14:43.511Z", "duration_ms": 212, "serial": "emulator-5554",
     "ok": true,
     "command": {"timeout_ms": "10000", "tap": {"selector": {"node": {"resource": {"name": "login_button"}}}}}}
  ]
}
```

- `command`, `app`, `error` and `failure` are the `tap.v1` messages (`LoggedEvent` in the
  [gRPC reference](../reference/grpc.md)) in proto3 JSON with the `.proto` field names, so
  64-bit integers inside them (such as `timeout_ms`) are strings.
- Logged: every command except `device_info` / `dump_hierarchy`, and the app calls that change
  the device (install, uninstall, force-stop, clear-data, grant, launch, cold launch).
  Snapshots, screenshots and device lists are not.
- The server keeps the newest 2 000 events per session; `dropped` counts older ones it let go.
  Export before `release`: the log ends with the session.
- **Typed text is recorded verbatim, passwords included.** Treat an export file as a secret if
  the session typed one.

The Python client exposes the same pieces directly: `client.connect(name, hold=...)`,
`client.resume(name)`, `device.screen_snapshot()`, `device.resolve_ref(ref)` and
`connection.event_log()`.
