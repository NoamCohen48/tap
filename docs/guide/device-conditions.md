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
| `set_soft_keyboard(enabled)` | Settings › Physical keyboard › Show on-screen keyboard: the soft keyboard shows while a hardware keyboard is connected. An emulator usually has one, and input methods that follow this setting then show no soft keyboard unless it is on, so a test of the keyboard (`keyboard_shown()`, `hide_keyboard()`) may need it; whether an input method follows it is up to the input method (Gboard on an API 34 emulator showed the keyboard either way). With no hardware keyboard it changes nothing | `soft_keyboard` (`None` when Android does not let Tap read it) |
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

## Temporary settings (Python)

Use `device.temporary_settings(...)` to change dark mode, font scale or animations for a
`with` block, then restore the previous values without detaching:

```python
with device.temporary_settings(dark_mode=True, font_scale=1.3):
    app.wait(text("Large text")).visible()
    with device.temporary_settings(font_scale=1.6):
        check_extra_large_text()
    # Font scale is 1.3 again.
# Previous theme and font scale restored; the device is still attached.
```

Pass at least one of `dark_mode`, `font_scale` and `animations`; omitted values and `None`
leave that setting unchanged. Each block saves values on entry, so nesting restores the
parent block's values. Changes to other settings are not restored by the block. Do not
change the same device settings concurrently.

Restoration runs even if setup partially fails or the block raises. Every attempted setting
is restored in reverse order. If cleanup alone fails, it raises `SettingsRestorationError`;
its `failures` contains `(setting_name, exception)` pairs. If setup or the block already
failed, that exception remains primary, with a `settings_restoration_error` attribute and a
traceback note on Python 3.11+ (an error log on Python 3.10). Attachment cleanup remains the
safety net if the device cannot be reached.

This helper restores the values exposed by `info()`, not the exact raw Android settings:
automatic theme mode becomes explicit light/dark, and animations are restored to all on
(scale 1) or all off (scale 0), not individual previous scales. Detach still restores the
original raw settings. Configuration changes can recreate activities on both entry and exit;
wait for the UI where needed.

## Waiting for the change to land

`info()` reports a change as soon as the call returns, and the restored values as soon as you
attach again. Android applies dark mode, font scale, density, the languages and bold text as
configuration changes that reach each running app a little later: its activities are recreated
unless it handles the change itself, so wait for what the test needs in the app after changing
one. Network switches take time to reach the app's connectivity
callbacks: wait for the app's own offline or online state. On some devices turning location on
also shows Google Play services' "improve location accuracy" prompt.

Animations stay as the device has them unless a test changes them. With them on,
`await_animation_end` takes longer; turning them off for the session is the usual first line of
a test that does not test animations.
