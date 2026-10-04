# Sessions to tests

`tap-agent export` prints the session's event log as JSON: every device call the session made,
in order, with its outcome. Refs appear as the selectors they stood for, so the log is directly
usable for a test in any language. `tap-agent` does not generate code; you (or your agent) do.

```json
{
  "format": "tap-events/1",
  "session": "agent",
  "exported_at": "2026-10-03T16:58:54Z",
  "dropped": 0,
  "events": [
    {"seq": 1, "at": "2026-10-03T16:58:20.486Z", "duration_ms": 1464, "serial": "emulator-5554",
     "ok": true,
     "app": {"operation": "cold_launch", "package_name": "com.android.settings", "timeout_ms": "30000"}},
    {"seq": 2, "at": "2026-10-03T16:58:30.165Z", "duration_ms": 92, "serial": "emulator-5554",
     "ok": true,
     "command": {"timeout_ms": "10000", "tap": {"selector": {"node": {"all_of": {"nodes": [
       {"resource": {"name": "search_action_bar"}},
       {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": "com.android.settings", "mode": "MATCH_EXACT"}}]}}}}}}
  ]
}
```

- `command`, `app`, `error` and `failure` are the `tap.v1` messages (`LoggedEvent` in the
  [gRPC reference](../reference/grpc.md)) in proto3 JSON with the `.proto` field names, so
  64-bit integers inside them (such as `timeout_ms`) are strings.
- Logged: every command except `device_info` / `dump_hierarchy`, and the app calls that change
  the device (install, uninstall, force-stop, clear-data, grant, launch, cold launch).
  Snapshots, screenshots and device lists are not.
- The log has the agent's failed attempts (`"ok": false`, with the `error`) and the waits
  `--settle` added (`wait_screen_stable`). A test keeps the steps that worked and the waits it
  needs.
- The server keeps the newest 2 000 events per session; `dropped` counts older ones it let go.
  Export before `release`: the log ends with the session.
- **Typed text is recorded verbatim, passwords included.** Treat an export file as a secret if
  the session typed one.

The [example session](session.md) becomes this test. The selectors that carry a `PROPERTY_PACKAGE_NAME` match
are that app's (`device.app(…)`), and the agent's `wait` becomes the test's wait. In Kotlin the
same test uses the same calls in camelCase ([Kotlin + JUnit 5](../sdk/kotlin.md)):

```python
from tap_e2e import res, text

def test_finds_dark_theme(tap_device):
    settings = tap_device.app("com.android.settings")
    search = tap_device.app("com.google.android.settings.intelligence")
    settings.cold_launch()
    settings.wait(res("search_action_bar")).one().tap()
    search.wait(res("open_search_view_edit_text")).one().set_text("dark")
    search.wait(text("Dark theme")).visible()
```

[Tap Studio](../studio/index.md) records the same kind of steps from a browser, if you would rather
click through a flow yourself.

## The pieces, from Python

The Python client exposes the same pieces directly: `client.connect(name, hold=...)`,
`client.resume(name)`, `device.screen_snapshot()`, `device.resolve_ref(ref)` and
`connection.event_log()`
([Held connections and screen snapshots](../sdk/python.md#held-connections-and-screen-snapshots)).
