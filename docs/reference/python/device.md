# Device

`Device` is one attached device: keys, the screen, rotation, device conditions, files,
notifications, screenshots and waits of your own. `device.app(...)` and `device.screen` find
elements. Guide: [Keys, screen and rotation](../../guide/device-control.md),
[Device conditions](../../guide/device-conditions.md).

::: tap_e2e.device.Device
    options:
      filters:
        - "!^_"
        - "!^(owner_connection|attached_device_id|client)$"

::: tap_e2e.device.Timeouts
