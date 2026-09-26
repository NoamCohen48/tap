# Python client

Package `tap-e2e`, importable as `tap`. Everything below is generated from the docstrings and
type hints of `clients/python/tap`.

```python
from tap import TapServer, res_id, text, desc, CONTAINS, DOWN, STABILITY_TREE
from tap import TapError, CommandError, WaitTimeoutError, AppLifecycleError, DeviceBusyError, ServerError
```

## Device

::: tap.device.Device

::: tap.device.Timeouts

## Element and waits

::: tap.element.Element

::: tap.element.ElementWait

## Selectors

::: tap.selectors

## App lifecycle

::: tap.app.App

::: tap.app.ProcessIdentity

## Server and client connection

::: tap.server.TapServer

::: tap.server.ClientConnection

## Errors

::: tap.errors

## pytest plugin

::: tap.pytest_plugin
    options:
      members:
        - TapConfig
        - tap_config
        - tap_server
        - tap_client_connection
        - tap_devices
        - tap_device
