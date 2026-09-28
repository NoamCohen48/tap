# Coming from Maestro or Appium

Tap sits between the two: a *host-driven* runner with typed clients like Appium, with the
explicit-condition, no-magic philosophy of Maestro, and without a query language or element
handles. The rows below are the everyday equivalents; the notes call out where the behaviour is
deliberately different.

## Maestro

| Maestro | Tap (Kotlin) | Note |
|---|---|---|
| `launchApp` | `device.app().coldLaunch()` | verified: new process identity, focused window |
| `launchApp: { clearState: true }` | `app.clearData(); app.coldLaunch()` | |
| `stopApp` | `app.forceStop()` | verified: no process left |
| `tapOn: "Text"` | `device.element(text("Text")).tap()` | Maestro tries text *or* id; Tap is explicit |
| `tapOn: { id: "x" }` | `device.element(res("x")).tap()` | |
| `tapOn: { text: "Add", index: 1 }` | `device.element(text("Add").at(1)).tap()` | without `at`, two matches are `AMBIGUOUS` |
| `tapOn: { point: "50%,50%" }` | — | no coordinates by design |
| `longPressOn` | `element.longTap()` | |
| `inputText` | `element.setText(v)` / `typeText(v)`; `device.typeText(v)` types into the current focus | neither reads the field back: assert with `await(...).textEquals(v)` |
| `eraseText` | `element.clearText()` | |
| `back` | `device.pressBack()` | |
| `scroll` / `swipe` | `element.scroll(DOWN)` / `element.swipe(UP)` | always relative to an element |
| `scrollUntilVisible` | `container.scrollUntil(target)` | a client-side loop of `exists` + `scroll`; gives up with `WaitTimeoutException` |
| `assertVisible` | `device.await(sel).visible()` | Maestro's assert already waits; so does this |
| `assertNotVisible` | `device.await(sel).gone()` | |
| `assertTrue` / `extendedWaitUntil` | `device.await(sel).textEquals(...)`, `.enabled()`, `.count(n)`; `device.awaitUntil { … }` for anything else | |
| `waitForAnimationToEnd` | `device.awaitAnimationEnd()` | pixel-based |
| (settle before each command) | `device.awaitAppSettled()` | Maestro settles implicitly; Tap only when asked |
| `runFlow` / `repeat` / `evalScript` | Kotlin / Python | the test language is the flow language |
| `takeScreenshot` | `device.screenshot()` | also automatic on failure |
| `openLink`, `setLocation`, `addMedia` | — | not in scope yet |

The two ideas that do not carry over: Maestro **retries and settles implicitly**, Tap does
neither; Maestro's **YAML** is replaced by ordinary code with a typed client, so loops,
fixtures, helpers and assertions come from JUnit / pytest.

Kotlin note: every `tap()`/`setText()`/`await()` below is `suspend` inside `tapTest { ... }`
(`tapScope` in scripts); selector builders themselves are plain values.

## Appium (UiAutomator2 driver)

| Appium | Tap | Note |
|---|---|---|
| `AppiumDriver(url, caps)` | `@TapTest` + `Device` parameter / `tap_device` fixture | no server URL, no capabilities: the server is found or started |
| `findElement(By.id("x"))` | `device.element(res("x"))` | returns a lazy element, never a handle |
| `findElement(AppiumBy.accessibilityId("x"))` | `device.element(desc("x"))` | |
| `findElement(AppiumBy.androidUIAutomator("…"))` | selector DSL | no string queries |
| `findElement(By.xpath("…"))` | selector relations (`hasDescendant`, `descendant`, `child`, `hasParent`) | no XPath by design |
| `findElements(...)` | `element.count()`, `element.at(n)` | |
| `element.click()` | `element.tap()` | exactly-one-match rule applies |
| `element.sendKeys(v)` | `element.setText(v)` | verified |
| `element.clear()` | `element.clearText()` | |
| `element.getText()` | `element.text()` | |
| `element.isDisplayed()` | `element.exists()` | |
| `element.getAttribute("checked")` | `element.isChecked()` / `element.snapshot()` | |
| `WebDriverWait(...).until(visibilityOf(...))` | `device.await(sel).visible()` | polls on the device |
| `driver.pressKey(new KeyEvent(BACK))` | `device.pressBack()` | |
| `driver.getPageSource()` | `device.dumpHierarchy()` | diagnostic only |
| `driver.getScreenshotAs(...)` | `device.screenshot()` | |
| `driver.activateApp` / `terminateApp` | `app.launch()` / `app.forceStop()` | verified |
| `driver.installApp` / `removeApp` | `app.install(path)` / `app.uninstall()` | |
| `driver.resetApp` | `app.clearData()` | |
| `mobile: acceptAlert` | tap the dialog through `inAnyWindow()` / `inPackage(...)` or `app.grantPermission(...)` | |
| `StaleElementReferenceException` | — | there are no references to go stale |
| implicit wait | — | none; write the wait |
| Grid / parallel sessions | `@TapDevices("a", "b")`, per-device locks shared by every process | sessions opened in serial order, so no deadlocks |

Appium's strength is breadth (iOS, web, many drivers). Tap trades that for one platform done
deterministically: no handles, no XPath, no implicit waits, a closed error taxonomy with stable
details, and `INDETERMINATE` instead of a silent replay.
