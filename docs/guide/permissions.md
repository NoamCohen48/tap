# Permissions

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`grant_permission` → `grantPermission`,
    `await_permission_prompt` → `awaitPermissionPrompt`).
    [Kotlin + JUnit 5](../sdk/kotlin.md#permissions) shows this page's examples in Kotlin.

Two ways, in order of preference.

## Grant it before the prompt

| Method on `App` | Does | Verified by |
|---|---|---|
| `grant_permission(name)` | `pm grant` | `dumpsys package` lists it as granted |
| `revoke_permission(name)` | `pm revoke` (Android kills the app's process when a runtime permission is revoked) | `dumpsys package` no longer lists it as granted |
| `is_permission_granted(name)` | | whether it is granted now (after a grant, a revoke, `clear_data()` or a choice in the dialog) |

```python
app.grant_permission("android.permission.CAMERA")
app.cold_launch()
app.element(text("Take photo")).tap()      # no dialog, no timing
```

`clear_data()` revokes runtime permissions, so grant again after it.

## Answer the dialog

When the dialog itself is under test, `device.await_permission_prompt()` waits for it and
returns a `PermissionPrompt` with the `choices` it offers (`ALLOW`, `ALLOW_FOREGROUND_ONLY`,
`ALLOW_ONE_TIME`, `DENY`, …: which ones depends on the permission and the Android version), and
`device.choose_permission(choice)` presses one.

Buttons are found by the permission controller's resource ids, never by label or position, so
this works in any language. A choice the dialog does not offer is `NOT_FOUND` before any input;
no dialog within the timeout is a `WaitTimeoutError` with reason `NO_PERMISSION_PROMPT`.

```python
from tap_e2e import PermissionChoice

app.element(text("Take photo")).tap()
prompt = device.await_permission_prompt()
if PermissionChoice.ALLOW_FOREGROUND_ONLY in prompt.choices:
    device.choose_permission(PermissionChoice.ALLOW_FOREGROUND_ONLY)   # "While using the app"
else:
    device.choose_permission(PermissionChoice.ALLOW)
app.wait(text("Camera ready")).visible()
```

The location dialog of Android 12+ also asks how precise the location may be:
`prompt.accuracies` lists the `LocationAccuracy` radios it shows (`PRECISE`, `APPROXIMATE`;
empty on other dialogs), and `choose_permission(choice, accuracy)` selects one before pressing
the button. An accuracy the dialog does not offer is `NOT_FOUND` before any input.

```python
from tap_e2e import LocationAccuracy

device.choose_permission(PermissionChoice.ALLOW_FOREGROUND_ONLY, LocationAccuracy.APPROXIMATE)
app.wait(text("Approximate location")).visible()
```

For anything else in the dialog, its elements belong to `prompt.package_name` (the window's
package; Google builds name it `com.google.android.permissioncontroller`): reach them with
`device.app(prompt.package_name).element(...)` ([App or screen](app-or-screen.md)).
