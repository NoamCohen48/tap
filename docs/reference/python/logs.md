# Device log

`device.logs`: the device log since the attach, and the steps that tie your own record to it.
Guide: [Device logs](../../guide/logs.md).

::: tap_e2e.logs.DeviceLog

::: tap_e2e.logs.LogEntries

::: tap_e2e.logs.LogEntry

::: tap_e2e.logs.LogLine

::: tap_e2e.logs.LogAction

::: tap_e2e.logs.LogPriority

::: tap_e2e.logs.LogFollower

## Retained history and protected ranges

Available through `client.log_recordings`, including after the original connection ends.
`export(name)` and `history(original_connection_id)` return `tap-history/1` with durable RPC
events and optional logs, without invoking a CLI or writing files.

::: tap_e2e.history.HistoryExport

::: tap_e2e.log_recordings.LogRecordings

::: tap_e2e.log_recordings.LogRecording

::: tap_e2e.log_recordings.LogRecordingRange

::: tap_e2e.log_recordings.RetainedLog

::: tap_e2e.steps.step

::: tap_e2e.steps.current_step
