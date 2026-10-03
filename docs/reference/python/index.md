# Python client

Package `tap-e2e`, imported as `tap_e2e`. These pages are generated from the docstrings and
type hints of `clients/python/tap_e2e`; a type in a signature links to its own entry. The
[Guide](../../guide/selectors.md) explains each feature with examples.

```python
from tap_e2e import TapClient, res, res_id, text, desc, CONTAINS, DOWN, StabilitySignal
from tap_e2e import TapError, CommandError, WaitTimeoutError, AppLifecycleError, DeviceBusyError, ServerError
```

| Page | What is on it |
|---|---|
| [Device](device.md) | `Device`, one attached device; `Timeouts` |
| [Element](element.md) | `Element`, a lazy selector bound to a device; `ElementWait` |
| [Selectors](selectors.md) | `text`, `res`, `desc`, … and the `Selector` they build; match modes |
| [App](app.md) | `App`, from `device.app(package_name)` |
| [Screen](screen.md) | `Screen`, from `device.screen` |
| [Client and connections](client.md) | `TapClient`, `TapConnection`, the server's device list, `start_daemon` / `stop_daemon` |
| [Artifacts](artifacts.md) | `Screenshot`, `Hierarchy`, `DeviceInfo`, `DriverLog`, `Recording`, `Capture` |
| [Values](values.md) | `ElementSnapshot`, `AppProcess`, `PermissionPrompt`, `Toast`, `Notification`, `Long`, … |
| [Enums](enums.md) | `Direction`, `Orientation`, `PermissionChoice`, `StandardAction`, `StabilitySignal`, … |
| [Snapshots and the event log](snapshots.md) | `ScreenSnapshot`, `ScreenNode`, `EventLog`, `LoggedEvent` |
| [Errors](errors.md) | `TapError` and its subclasses; `ErrorCode`, `FailureReason`, `WaitReason` |
| [pytest plugin](pytest.md) | the fixtures and `TapConfig` |

## Protocol messages

::: tap_e2e.proto
    options:
      members: false
      show_root_heading: false
