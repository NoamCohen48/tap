# Elements

`Element` is a lazy selector bound to a device: nothing is looked up until an action or query
runs. State reads and actions require exactly one match; `exists()` and `count()` accept any
number. `element.wait()` returns an `ElementWait`. `app.elements(selector)` and
`device.screen.elements(selector)` return a read-only `Elements` collection for bulk values. Guide:
[Elements and text](../../guide/elements.md), [Gestures](../../guide/gestures.md),
[Waits](../../guide/waits.md).

::: tap_e2e.element.Element

::: tap_e2e.element.ElementWait

::: tap_e2e.elements.Elements
