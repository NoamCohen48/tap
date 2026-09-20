# Python client

Package `tap-e2e`, importable as `tap`. Everything below is generated from the docstrings and
type hints of `clients/python/tap`.

```python
from tap import Service, res_id, text, desc, CONTAINS, DOWN, STABILITY_TREE
from tap import TapError, CommandError, WaitTimeoutError, AppLifecycleError, DeviceBusyError, ServiceError
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

## Service and connection

::: tap.service.Service

::: tap.service.Connection

## Errors

::: tap.errors

## pytest plugin

::: tap.pytest_plugin
    options:
      members:
        - TapConfig
        - tap_config
        - tap_service
        - tap_connection
        - tap_devices
        - tap_device
