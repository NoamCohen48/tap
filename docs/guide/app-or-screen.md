# App or screen

!!! info "Examples are in Python"
    Kotlin has the same calls (`app.wait` → `app.await`). [Kotlin + JUnit 5](../sdk/kotlin.md#app-or-screen)
    shows this page's examples in Kotlin.

Attaching a device names no app: one test can drive several apps (`device.app("a")`,
`device.app("b")`) and the system UI on the same device. Every lookup searches all the windows
on screen, and what you call `element` / `wait` on decides which of their nodes count:

- `device.app(pkg).element(sel)` / `.wait(sel)`: only nodes that belong to `pkg`. The package
  is one more predicate in the selector the device receives, so a dialog, a keyboard or a
  notification of another package can never be what a test of your app matches, whether or not
  it is focused.
- `device.screen.element(sel)` / `.wait(sel)`: nodes of any package: system dialogs, the
  status bar's panels, the launcher. Use it when you do not know, or do not care, which
  package owns what you are after.

```python
shop = device.app("com.shop")
shop.element(res("search")).set_text("socks")      # com.shop's search field only
device.screen.wait(text("Allow")).visible()        # whoever owns it
```

A permission dialog belongs to the permission controller, for instance:

```python
device.app("com.google.android.permissioncontroller").element(
    res_id("com.android.permissioncontroller", "permission_allow_button")
).tap()
```

For tests that just need the permission, prefer `app.grant_permission(...)`, which needs no
dialog at all, or [the permission dialog helpers](permissions.md) when the dialog is under test.

## Covered elements

Another window, such as a keyboard, a popup or another app's overlay, can hide a node you match:

- Covered completely, the node is not found: Android reports it as not visible, so an action
  fails with `NOT_FOUND` and `visible()` keeps waiting.
- Covered partly, it is found, but a gesture whose touch point (a tap's centre, a swipe's
  start) is under the other window fails with `NOT_INTERACTABLE` / `OBSCURED` before any input,
  instead of landing on whatever is on top.

Close the covering window (often `device.press_back()` or `device.hide_keyboard()`) or scroll
the node clear first.
