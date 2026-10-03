# Device conditions

!!! info "Examples are in Python"
    Kotlin has the same methods in camelCase (`set_dark_mode` → `setDarkMode`), with named
    arguments where Python uses keywords. [Kotlin + JUnit 5](../sdk/kotlin.md#device-conditions)
    shows this page's examples in Kotlin.

Device-wide settings can be changed for the session. Each change is read back (a value the
device did not take fails with `ServerError`, reason `DEVICE_SETTING`), and what the device had
before the session's first change comes back on detach. `device.info()` reports the current
values.

| Call | Changes | Read back in `info()` |
|---|---|---|
| `set_animations(enabled)` | window, transition and animator scales, all 0 or all 1 | `animations_enabled` |
| `set_dark_mode(enabled)` | `cmd uimode night`; API 29+ (below: reason `UNSUPPORTED_API`). Some devices (Samsung's One UI) lock the day/night mode: there it fails with `DEVICE_SETTING` | `dark_mode` |
| `set_font_scale(scale)` | system font scale, 0.5 to 2.0 | `font_scale` |
| `set_density(dpi)` | display density override, 100 to 1000 dpi; `None` for the physical density | `density_dpi` |
| `set_network(airplane_mode=, wifi=, mobile_data=)` | the real switches (pass only the ones to change); API 29+. Airplane mode turns Wi-Fi off, as on a phone, unless the call also turns Wi-Fi on. A device reached over ADB on Wi-Fi refuses Wi-Fi off and airplane mode on: Tap would lose it | `airplane_mode`, `wifi_enabled`, `mobile_data_enabled` |
| `set_system_locales(tags)` | the device's languages (Settings › Languages), BCP-47 tags in preference order; every app that follows the system language sees it. Apps with their own language ([`app.set_locales`](app-lifecycle.md)) keep it | `system_locales` |
| `set_stay_awake(enabled)` | Developer options › Stay awake: the screen stays on while the device is plugged in (a device on ADB over USB is), so a long test does not find it off | `stay_awake` |
| `set_accessibility_display(high_contrast_text=, color_inversion=, bold_text=)` | Settings › Accessibility (Android 12+; below: reason `UNSUPPORTED_API`); pass only the ones to change. Screenshots are not inverted: the inversion happens on the way to the display | `high_contrast_text`, `color_inversion`, `bold_text` |
| `set_location(latitude, longitude, accuracy_m=, altitude_m=)` | a mock location: the GPS and network providers report this fix (re-sent every second, so an app that starts listening later gets it); call again to move it. The Tap driver app becomes the device's mock-location app and location is turned on if it was off; both come back on detach, which also removes the mock providers | the app's own location |

```python
device.set_animations(False)
device.set_dark_mode(True)
device.set_font_scale(1.3)
app.launch()
app.wait(text("Large text")).visible()
assert device.info().animations_enabled is False
```

```python
device.set_system_locales(["de-DE", "en-US"])
device.set_network(wifi=False, mobile_data=False)   # offline, without airplane mode
device.set_location(48.8584, 2.2945, accuracy_m=5)
app.grant_permission("android.permission.ACCESS_FINE_LOCATION")
```

## Waiting for the change to land

Android applies dark mode, font scale, density and the languages as configuration changes: a
running app's activities are recreated unless it handles the change itself, so wait for what
the test needs after changing one. Network switches take time to reach the app's connectivity
callbacks: wait for the app's own offline or online state. On some devices turning location on
also shows Google Play services' "improve location accuracy" prompt.

Animations stay as the device has them unless a test changes them. With them on,
`await_animation_end` takes longer; turning them off for the session is the usual first line of
a test that does not test animations.
