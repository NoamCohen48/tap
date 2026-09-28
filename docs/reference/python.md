# Python client

Package `tap-e2e`, importable as `tap`. Everything below is generated from the docstrings and
type hints of `clients/python/tap_e2e`.

```python
from tap_e2e import TapClient, res_id, text, desc, CONTAINS, DOWN, STABILITY_TREE
from tap_e2e import TapError, CommandError, WaitTimeoutError, AppLifecycleError, DeviceBusyError, ServerError
```

## Device

::: tap_e2e.device.Device

::: tap_e2e.device.Timeouts

## Element and waits

::: tap_e2e.element.Element

::: tap_e2e.element.ElementWait

## Selectors

::: tap_e2e.selectors

## App lifecycle

::: tap_e2e.app.App

::: tap_e2e.app.ProcessIdentity

## Server and client connection

::: tap_e2e.client.TapClient

::: tap_e2e.client.TapConnection

::: tap_e2e.client.Endpoint

## Errors

::: tap_e2e.errors

## pytest plugin

::: tap_e2e.pytest_plugin
    options:
      members:
        - TapConfig
        - tap_config
        - tap_client
        - tap_connection
        - tap_devices
        - tap_device
