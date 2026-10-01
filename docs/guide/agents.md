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
