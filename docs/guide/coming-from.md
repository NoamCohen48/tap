# Coming from Maestro or Appium

!!! info "Examples are in Python"
    Kotlin has the same calls in camelCase (`cold_launch` → `coldLaunch`, `app.wait` →
    `app.await`), inside `tapTest { }`: see [Kotlin + JUnit 5](../sdk/kotlin.md).

Tap sits between the two: a *host-driven* runner with typed clients like Appium, with the
explicit-condition, no-magic philosophy of Maestro, and without a query language or element
handles. The rows below are the everyday equivalents; the notes call out where the behaviour is
deliberately different.

## Maestro

| Maestro | Tap | Note |
|---|---|---|
| `appId` + `launchApp` | `app = device.app("com.shop")`; `app.cold_launch()` | verified: new process identity, focused window |
| `launchApp: { clearState: true }` | `app.clear_data(); app.cold_launch()` | |
| `stopApp` | `app.force_stop()` | verified: no process left |
| `tapOn: "Text"` | `app.element(text("Text")).tap()` | Maestro tries text *or* id; Tap is explicit |
| `tapOn: { id: "x" }` | `app.element(res("x")).tap()` | |
| `tapOn: { text: "Add", index: 1 }` | `app.element(text("Add").at(1)).tap()` | without `at`, two matches are `AMBIGUOUS` |
| `tapOn: { point: "50%,50%" }` | — | no coordinates by design |
| `longPressOn` | `element.long_tap()` | |
| `inputText` | `element.set_text(v)` / `type_text(v)`; `device.type_text(v)` types into the current focus | neither reads the field back: assert with `app.wait(...).text_equals(v)` |
| `eraseText` | `element.clear_text()` | |
| `back` | `device.press_back()` | |
| `scroll` / `swipe` | `element.scroll(DOWN)` / `element.swipe(UP)` | always relative to an element |
| `scrollUntilVisible` | `container.scroll_until(target)` | a client-side loop of `exists` + `scroll`; gives up with `WaitTimeoutError` |
| `assertVisible` | `app.wait(sel).visible()` | Maestro's assert already waits; so does this |
| `assertNotVisible` | `app.wait(sel).gone()` | |
| `assertTrue` / `extendedWaitUntil` | `app.wait(sel).text_equals(...)`, `.enabled()`, `.count(n)`; `device.await_until(...)` for anything else | |
| `waitForAnimationToEnd` | `app.await_animation_end()` | pixel-based |
| (settle before each command) | `app.await_settled()` | Maestro settles implicitly; Tap only when asked |
| `runFlow` / `repeat` / `evalScript` | Python / Kotlin | the test language is the flow language |
| `takeScreenshot` | `device.screenshot()` | also automatic on failure |
| `openLink` | `app.open_link(uri)` | limited to the app unless `any_app=True` |
| `setLocation` | `device.set_location(lat, lon)` | a mock location, removed on detach |
| `addMedia` | `device.add_media(path)` | deleted on detach |

The two ideas that do not carry over: Maestro **retries and settles implicitly**, Tap does
neither; Maestro's **YAML** is replaced by ordinary code with a typed client, so loops,
fixtures, helpers and assertions come from pytest / JUnit.

## Appium (UiAutomator2 driver)

| Appium | Tap | Note |
|---|---|---|
| `AppiumDriver(url, caps)` | the `tap_device` fixture (JUnit: a `Device` parameter) | no server URL, no capabilities: the client finds the running server |
| `findElement(By.id("x"))` | `app.element(res("x"))` | returns a lazy element, never a handle |
| `findElement(AppiumBy.accessibilityId("x"))` | `app.element(desc("x"))` | |
| `findElement(AppiumBy.androidUIAutomator("…"))` | selector builders | no string queries |
| `findElement(By.xpath("…"))` | selector relations (`has_descendant`, `descendant`, `child`, `has_parent`) | no XPath by design |
| `findElements(...)` | `element.count()`, `element.at(n)` | |
| `element.click()` | `element.tap()` | exactly-one-match rule applies |
| `element.sendKeys(v)` | `element.set_text(v)` | |
| `element.clear()` | `element.clear_text()` | |
| `element.getText()` | `element.text()` | |
| `element.isDisplayed()` | `element.exists()` | |
| `element.getAttribute("checked")` | `element.is_checked()` / `element.snapshot()` | |
| `WebDriverWait(...).until(visibilityOf(...))` | `app.wait(sel).visible()` | polls on the device |
| `driver.pressKey(new KeyEvent(BACK))` | `device.press_back()` | |
| `driver.getPageSource()` | `device.dump_hierarchy()` | diagnostic only |
| `driver.getScreenshotAs(...)` | `device.screenshot()` | |
| `driver.activateApp` / `terminateApp` | `app.launch()` / `app.force_stop()` | verified |
| `driver.installApp` / `removeApp` | `app.install(path)` / `app.uninstall()` | |
| `driver.resetApp` | `app.clear_data()` | |
| `mobile: acceptAlert` | `device.choose_permission(...)` for the permission dialog; other dialogs through `device.screen.element(...)` | or skip the dialog with `app.grant_permission(...)` |
| `StaleElementReferenceException` | — | there are no references to go stale |
| implicit wait | — | none; write the wait |
| Grid / parallel sessions | `@pytest.mark.tap_devices("a", "b")`, per-device locks shared by every process | sessions opened in serial order, so no deadlocks |

Appium's strength is breadth (iOS, web, many drivers). Tap trades that for one platform done
deterministically: no handles, no XPath, no implicit waits, a closed error taxonomy with stable
details, and `INDETERMINATE` instead of a silent replay.
