# Discovering supported operations

Call `device.capabilities()` after attaching to learn which operations Tap supports on that
device and what prerequisites remain. The server checks authenticated attachment facts and
host-tool presence. It sends no driver commands, changes no settings, grants no permissions
and starts no recording.

=== "Python"

    ```python
    import pytest
    from tap_e2e import Capability, CapabilityStatus

    support = device.capabilities()[Capability.APP_SET_LOCALES]
    if support.status is CapabilityStatus.UNSUPPORTED:
        pytest.skip(support.detail)
    device.app("com.example.app").set_locales(["fr-FR"])
    ```

=== "Kotlin"

    ```kotlin
    import io.github.noamcohen48.tap.sdk.Capability
    import io.github.noamcohen48.tap.sdk.CapabilityStatus
    import org.junit.jupiter.api.Assumptions.assumeTrue

    val support = device.capabilities()[Capability.APP_SET_LOCALES]
    assumeTrue(support.status == CapabilityStatus.SUPPORTED, support.detail)
    device.app("com.example.app").setLocales(listOf("fr-FR"))
    ```

Tap never skips a test automatically. Decide how your test should handle each status:

| Status | Meaning |
|---|---|
| `SUPPORTED` | Known device/host prerequisites are met |
| `CONDITIONAL` | A specific prerequisite remains, listed in `requirements` |
| `UNSUPPORTED` | A known blocker exists; inspect `reasons` and `required_api` / `requiredApi` |
| `UNKNOWN` | A required fact could not be established, or the server did not report the ID |

Support is **not a promise that an invocation will succeed**. Element matching, occlusion,
Android acceptance and transport errors are still checked during execution. Discovery does
not inspect the current screen or probe an operation by executing it.

## Examples of restrictions

- `KEYBOARD_IME_ACTION` requires API 30 and a target offering the keyboard action.
- `ACCESSIBILITY_PAGE_DOWN` requires API 29 and a target offering that action. Read the
  element snapshot's `actions` to check the target itself.
- `APP_SET_LOCALES` and `APP_GET_LOCALES` require API 33.
- `DEVICE_SET_ACCESSIBILITY_DISPLAY` covers high-contrast text and color inversion;
  `DEVICE_SET_BOLD_TEXT` is separate because it requires API 31.
- `NETWORK_DISABLE_WIFI` and `NETWORK_ENABLE_AIRPLANE_MODE` are unsupported over network
  ADB. Other network changes remain available with safe values.
- `RECORD_AUDIO_OUTPUT` and `RECORD_AUDIO_MIC` require API 30; `RECORD_AUDIO_PLAYBACK`
  requires API 33. All recording starts need scrcpy on the **daemon host**, not the client.
  A present executable does not prove compatible flags, encoders or capture access, so these
  entries remain conditional. Discovery does not launch scrcpy. Playback apps can exclude
  their audio; a silent recording is not proof the app was silent.
- `CLIPBOARD_READ` normally needs no manual setup: Tap adopts the shell's background-read
  permission on API 29 and later without changing the focused app or IME.

## Inspecting the report

`device.capabilities().entries` contains every catalog entry, including unsupported ones.
Each has `id`, `status`, `reasons`, optional minimum API, `requirements` and explanatory
`detail`. Branch on the typed fields, not the text. Unknown future IDs stay in `entries` as
`Capability.UNKNOWN`, with their numeric `raw_id` / `rawId` preserved; indexing a known ID
missing from a response returns `UNKNOWN`.

The catalog describes SDK primitives and meaningful variants. Helpers such as Home, sleep,
background and scroll-until compose those primitives. It is not a list of CLI command names.
Synchronization, watcher/event-log services, connection management and inventory are outside
this catalog. For package-specific private files, use the separate
[`app.data.capabilities()`](app-data.md) API.

## CLI and MCP

```sh
tap-agent capabilities -d emulator-5554
tap-agent capabilities --json -d emulator-5554
```

Both use the selected device in the held session. The read-only MCP `capabilities` tool
returns the same JSON as `--json`. No discovery call is captured as an action or wait in a
named recording. Call discovery again when host dependencies change; reports are snapshots,
not a permanent guarantee.
