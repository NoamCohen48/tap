package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.AppLifecycleException
import io.github.noamcohen48.tap.sdk.CommandException
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.DeviceInfo
import io.github.noamcohen48.tap.sdk.ErrorCode
import io.github.noamcohen48.tap.sdk.FailureReason
import io.github.noamcohen48.tap.sdk.ForegroundActivity
import io.github.noamcohen48.tap.sdk.LocationAccuracy
import io.github.noamcohen48.tap.sdk.MatchMode
import io.github.noamcohen48.tap.sdk.Orientation
import io.github.noamcohen48.tap.sdk.PermissionChoice
import io.github.noamcohen48.tap.sdk.ServerException
import io.github.noamcohen48.tap.sdk.StandardAction
import io.github.noamcohen48.tap.sdk.Toast
import io.github.noamcohen48.tap.sdk.res
import io.github.noamcohen48.tap.sdk.text
import io.github.noamcohen48.tap.sdk.textContains
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@TapTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceActionsTest {
    /**
     * A deep link opens inside the app (and only the app, unless `anyApp`); Home sends the app
     * away and `App.foreground` brings it back where it was: the link screen, not a new launcher
     * activity on top, which is what a launch would add.
     */
    @Test
    @Order(1)
    fun deepLinkThenBackgroundAndForegroundReturnsToIt(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            app.forceStop()
            app.foreground()
            app.await(res("view_button")).visible()

            val activity = app.openLink("tapfixture://link/orders/42")
            assertEquals("${Fixture.PACKAGE}/.LinkActivity", activity)
            app.await(text("Link: tapfixture://link/orders/42")).visible()
            // No activity of the package handles this host: refused, not sent to a browser.
            assertFailsSuspend<AppLifecycleException> { app.openLink("tapfixture://nowhere/1") }

            app.background()
            device.awaitUntil("the app in the background", observe = { device.info().currentPackage }) {
                device.info().currentPackage != Fixture.PACKAGE
            }
            app.foreground()
            app.await(text("Link: tapfixture://link/orders/42")).visible()
        }
    }

    /** Each gesture is recognised by the app as that gesture (see the fixture's GestureActivity). */
    @Test
    @Order(2)
    fun gesturesReachTheAppAsGestures(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device, ".GestureActivity")
            app.element(res("double_tap_target")).doubleTap()
            app.await(text("Double taps: 1")).visible()
            app.element(res("drag_source")).dragTo(res("drop_target"))
            app.await(text("Dropped Card")).visible()
            app.element(res("pinch_target")).pinchOpen()
            app.await(text("Zoomed in")).visible()
            app.element(res("pinch_target")).pinchClose()
            app.await(text("Zoomed out")).visible()
        }
    }

    /** The runtime-permission dialog is read and answered by choice, not by label. */
    @Test
    @Order(3)
    fun permissionPromptIsAnsweredByChoice(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            app.clearData() // resets the permission, so the dialog shows on every run
            app.launch(".PermissionActivity")
            app.element(res("request_camera_permission")).tap()
            val prompt = device.awaitPermissionPrompt()
            val allow =
                if (PermissionChoice.ALLOW_FOREGROUND_ONLY in prompt.choices) PermissionChoice.ALLOW_FOREGROUND_ONLY else PermissionChoice.ALLOW
            assertTrue(allow in prompt.choices && PermissionChoice.DENY in prompt.choices, "offered: $prompt")
            device.choosePermission(allow)
            app.await(text("Camera granted")).visible()
        }
    }

    /** Sleep and wake are reported by `info()`; an insecure keyguard is dismissed. */
    @Test
    @Order(4)
    fun sleepWakeAndDismissTheKeyguard(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            assumeFalse(device.info().keyguardSecure, "the device has a secure lock screen")
            device.sleep()
            device.awaitUntil("the screen off") { !device.info().screenOn }
            device.wake()
            device.awaitUntil("the screen on") { device.info().screenOn }
            device.dismissKeyguard()
            device.awaitUntil("the keyguard gone", observe = { device.info().toString() }) { !device.info().keyguardLocked }
            app.await(res("view_button")).visible()
        }
    }

    /** A rotation holds for the test; the next test finds the device as it was before. */
    @Test
    @Order(5)
    fun rotationHoldsForTheTest(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            val before = device.info()
            rotationBefore[device.serial] = before
            val turned = if (before.orientation == Orientation.PORTRAIT) Orientation.LANDSCAPE else Orientation.PORTRAIT
            device.setOrientation(turned)
            device.awaitUntil("the display in $turned", observe = { device.info().toString() }) { device.info().orientation == turned }
        }
    }

    @Test
    @Order(6)
    fun rotationIsRestoredAfterTheTest(device: Device): Unit {
        tapTest {
            val before = rotationBefore[device.serial] ?: return@tapTest
            // With auto-rotate on, the sensor (how the phone lies) picks the rotation, so only
            // the setting itself can be compared; with it off, the frozen rotation comes back.
            val expected = if (before.autoRotate) "auto-rotate back on" else "the display back at ${before.displayRotation}"
            device.awaitUntil(expected, observe = { device.info().toString() }) {
                val now = device.info()
                now.autoRotate == before.autoRotate && (before.autoRotate || now.displayRotation == before.displayRotation)
            }
        }
    }

    /** Launch extras reach the app with their types: `getLongExtra` sees the `Long`, `getFloatExtra` the `Float`. */
    @Test
    @Order(7)
    fun launchExtrasReachTheAppTyped(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            app.launch(".FormActivity", extras = mapOf("query" to "red shoes", "flag" to true, "count" to 3, "id" to 9_000_000_000L, "ratio" to 0.5f))
            app.await(text("Extras: query=red shoes flag=true count=3 id=9000000000 ratio=0.5")).visible()
        }
    }

    /** A revoked permission is gone for the app (Android stops its process; it starts again denied). */
    @Test
    @Order(8)
    fun revokedPermissionIsDeniedToTheApp(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            app.grantPermission("android.permission.CAMERA")
            assertTrue(app.isPermissionGranted("android.permission.CAMERA"))
            app.launch(".FormActivity")
            app.await(text("Camera: granted")).visible()
            app.revokePermission("android.permission.CAMERA")
            assertFalse(app.isPermissionGranted("android.permission.CAMERA"))
            app.coldLaunch(".FormActivity")
            app.await(text("Camera: denied")).visible()
        }
    }

    /**
     * The keyboard shows for a focused field and hides; its action key runs the field's own
     * action (API 30+); the clipboard goes both ways while the app has focus; the app's toast is seen.
     */
    @Test
    @Order(9)
    fun keyboardClipboardAndToast(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device, ".FormActivity")
            val field = app.element(res("search_field"))
            field.tap()
            device.awaitUntil("the keyboard shown") { device.keyboardShown() }
            field.setText("shoes")
            if (device.info().apiLevel >= 30) {
                field.imeAction()
                app.await(text("Searched: shoes")).visible()
            }
            device.hideKeyboard()
            device.awaitUntil("the keyboard hidden") { !device.keyboardShown() }

            device.setClipboard("tap clip")
            app.element(res("paste_button")).tap()
            app.await(text("Pasted: tap clip")).visible()
            app.element(res("copy_button")).tap()
            app.await(text("Copied: shoes")).visible()
            assertEquals("shoes", device.clipboard())

            app.element(res("toast_button")).tap()
            assertEquals(Toast("Saved 1", Fixture.PACKAGE), app.awaitToast("Saved 1"))
        }
    }

    /**
     * Animations, dark mode, font scale, density and (API 33+) the app's language hold for the
     * test, are read back in `info()` and reach the app's configuration.
     */
    @Test
    @Order(10)
    fun conditionsHoldForTheTest(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            val before = device.info()
            conditionsBefore[device.serial] = before
            val perAppLocales = before.apiLevel >= 33
            if (perAppLocales) localesBefore[device.serial] = app.locales()
            val density = if (before.densityDpi == 320) 360 else 320
            device.setAnimations(false)
            // Some devices lock the day/night mode (Samsung's One UI): the change is refused, never faked.
            val dark =
                try {
                    device.setDarkMode(!before.darkMode)
                    !before.darkMode
                } catch (refused: ServerException) {
                    assertEquals(FailureReason.DEVICE_SETTING, refused.reason, refused.message)
                    assertTrue("locks the day/night mode" in refused.details, refused.details)
                    before.darkMode
                }
            device.setFontScale(1.3f)
            device.setDensity(density)
            if (perAppLocales) {
                app.setLocales(listOf("fr-fr"))
                assertEquals(listOf("fr-FR"), app.locales())
            } else {
                val refused = assertFailsSuspend<ServerException> { app.setLocales(listOf("fr-FR")) }
                assertEquals(FailureReason.UNSUPPORTED_API, refused.reason)
            }
            val now = device.info()
            assertEquals(listOf(false, dark, 1.3f, density), listOf(now.animationsEnabled, now.darkMode, now.fontScale, now.densityDpi))

            app.launch(".FormActivity")
            app.await(textContains("night=$dark ")).visible()
            app.await(textContains(" fontScale=1.3 density=$density animators=false")).visible()
            if (perAppLocales) app.await(textContains(" locale=fr-FR ")).visible()
        }
    }

    /** Detach put every condition back: the next test finds the device (and app) as before. */
    @Test
    @Order(11)
    fun conditionsAreRestoredAfterTheTest(device: Device): Unit {
        tapTest {
            val before = conditionsBefore[device.serial] ?: return@tapTest
            val now = device.info()
            assertEquals(
                listOf(before.animationsEnabled, before.darkMode, before.fontScale, before.densityDpi),
                listOf(now.animationsEnabled, now.darkMode, now.fontScale, now.densityDpi),
            )
            localesBefore[device.serial]?.let { assertEquals(it, device.app(Fixture.PACKAGE).locales()) }
        }
    }

    /**
     * Named actions run as a screen reader runs them: standard and the app's own custom ones; an
     * action the node does not offer is refused before input, and a slider takes a value in its
     * range and refuses one outside it.
     */
    @Test
    @Order(12)
    fun accessibilityActionsAndSliderProgress(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device, ".ControlsActivity")
            val header = app.element(res("details_header"))
            assertTrue(StandardAction.EXPAND in header.snapshot().actions)
            header.performAction(StandardAction.EXPAND)
            app.await(text("Details (expanded)")).visible()
            val refused = assertFailsSuspend<CommandException> { header.performAction(StandardAction.DISMISS) }
            assertEquals(ErrorCode.ACTION_REJECTED to "ACTION_NOT_OFFERED", refused.code to refused.detail)

            val card = app.element(res("message_card"))
            assertTrue(card.snapshot().customActions.containsAll(listOf("Archive", "Mark unread")))
            card.performCustomAction("Archive")
            app.await(text("Archived")).visible()

            val slider = app.element(res("volume_slider"))
            assertEquals(100f, slider.snapshot().range?.max)
            slider.setProgress(55f)
            app.await(text("Volume: 55")).visible()
            assertEquals("OUT_OF_RANGE", assertFailsSuspend<CommandException> { slider.setProgress(150f) }.detail)
        }
    }

    /** API 31+: the location dialog offers Precise / Approximate, and the choice reaches the app. */
    @Test
    @Order(13)
    fun locationPromptTakesApproximate(device: Device): Unit {
        tapTest {
            assumeTrue(device.info().apiLevel >= 31, "the location accuracy choice is API 31+")
            val app = Fixture.launch(device)
            app.clearData()
            app.launch(".PermissionActivity")
            app.element(res("request_location_permission")).tap()
            val prompt = device.awaitPermissionPrompt()
            assertEquals(setOf(LocationAccuracy.PRECISE, LocationAccuracy.APPROXIMATE), prompt.accuracies.toSet())
            device.choosePermission(PermissionChoice.ALLOW_FOREGROUND_ONLY, LocationAccuracy.APPROXIMATE)
            app.await(text("Location approximate")).visible()
        }
    }

    /**
     * The device language reaches an app that follows the system, a mocked fix reaches the app's
     * gps listener, a pushed file pulls back and added media is in the app's gallery query.
     */
    @Test
    @Order(14)
    fun localeLocationFilesAndMediaHoldForTheTest(device: Device): Unit {
        tapTest {
            val app = Fixture.launch(device)
            val before = device.info()
            localeBefore[device.serial] = before.systemLocales
            val target = if (before.systemLocales.firstOrNull() == "de-DE") "fr-FR" else "de-DE"
            device.setSystemLocales(target, "en-US")
            assertEquals(listOf(target, "en-US"), device.info().systemLocales.take(2))
            app.launch(".FormActivity")
            app.await(textContains(" locale=$target ")).visible()

            app.grantPermission("android.permission.ACCESS_FINE_LOCATION")
            app.launch(".PermissionActivity")
            device.setLocation(48.8584, 2.2945, accuracyM = 3f)
            app.element(res("read_location")).tap()
            app.await(text("At 48.85840, 2.29450"), 15.seconds).visible()

            val payload = ByteArray(3 shl 20) { (it % 251).toByte() }
            device.pushFile(PUSHED, payload)
            assertContentEquals(payload, device.pullFile(PUSHED))
            val refused = assertFailsSuspend<ServerException> { device.pushFile("/system/build.prop", byteArrayOf(1)) }
            assertEquals(FailureReason.DEVICE_FILE, refused.reason)

            assertEquals("/sdcard/Pictures/Tap/$PHOTO", device.addMedia(PHOTO, Fixture.PNG))
            app.grantPermission(if (before.apiLevel >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE")
            app.launch(".PermissionActivity")
            app.element(res("read_gallery")).tap()
            app.await(textContains(PHOTO)).visible()
        }
    }

    /** Detach put the language back and removed the pushed file and the media. */
    @Test
    @Order(15)
    fun localeFilesAndMediaAreRestoredAfterTheTest(device: Device): Unit {
        tapTest {
            val before = localeBefore[device.serial] ?: return@tapTest
            assertEquals(before, device.info().systemLocales)
            assertEquals(FailureReason.DEVICE_FILE, assertFailsSuspend<ServerException> { device.pullFile(PUSHED) }.reason)
            val app = Fixture.launch(device, ".PermissionActivity")
            app.element(res("read_gallery")).tap()
            val shown = app.await(res("gallery_value").andText("Gallery", MatchMode.CONTAINS)).visible().text().orEmpty()
            assertFalse(PHOTO in shown, shown)
        }
    }

    /**
     * Stay awake and the accessibility display settings read back while the test holds them; the
     * fixture's notifications are read as data, a button and the notification itself open the
     * app's link screen (the auto-cancel one goes away), an ongoing one cannot be dismissed, and
     * a match of both is refused as ambiguous before anything is sent.
     */
    @Test
    @Order(16)
    fun displaySettingsAndNotificationsHoldForTheTest(device: Device): Unit {
        tapTest {
            val before = device.info()
            displayBefore[device.serial] = before
            device.setStayAwake(!before.stayAwake)
            val bold = before.apiLevel >= 31
            device.setAccessibilityDisplay(highContrastText = true, colorInversion = true, boldText = if (bold) true else null)
            val held = device.info()
            assertEquals(!before.stayAwake, held.stayAwake)
            assertEquals(listOf(true, true, bold), listOf(held.highContrastText, held.colorInversion, held.boldText))
            // Stay awake off lets the screen time out at once on a device left idle: the rest needs it on.
            device.setStayAwake(true)
            device.wake()
            device.dismissKeyguard()

            val app = Fixture.launch(device, ".FormActivity")
            if (before.apiLevel >= 33) app.grantPermission("android.permission.POST_NOTIFICATIONS")
            app.await(res("notify_button")).visible().tap()
            val message = app.awaitNotification("New message")
            assertEquals("from Ada" to listOf("Mark as read"), message.text to message.actions)
            assertTrue(message.clearable)
            val mine = device.notifications().filter { it.packageName == Fixture.PACKAGE }
            assertEquals(setOf("New message", "Syncing"), mine.map { it.title }.toSet())
            assertFalse(mine.single { it.title == "Syncing" }.clearable)

            val ongoing = assertFailsSuspend<CommandException> { device.dismissNotification("Syncing", packageName = Fixture.PACKAGE) }
            assertEquals(ErrorCode.ACTION_REJECTED to "NOT_CLEARABLE", ongoing.code to ongoing.detail)
            assertEquals(ErrorCode.AMBIGUOUS, assertFailsSuspend<CommandException> { device.openNotification(packageName = Fixture.PACKAGE) }.code)

            device.openNotification("New message", packageName = Fixture.PACKAGE, action = "Mark as read")
            app.await(text("Link: tapfixture://link/read")).visible()
            device.openNotification("New message", packageName = Fixture.PACKAGE)
            app.await(text("Link: tapfixture://link/notification")).visible()
            assertEquals(ForegroundActivity(Fixture.PACKAGE, "${Fixture.PACKAGE}.LinkActivity"), device.foregroundActivity())
            device.awaitUntil("the opened notification auto-cancelled", observe = { device.notifications().joinToString { it.title.orEmpty() } }) {
                device.notifications().none { it.packageName == Fixture.PACKAGE && it.title == "New message" }
            }

            app.coldLaunch(".FormActivity") // the task's top is the link screen now
            app.await(res("notify_button")).visible().tap()
            app.awaitNotification("New message")
            device.dismissNotification("New message", packageName = Fixture.PACKAGE)
            device.awaitUntil("the notification dismissed", observe = { device.notifications().joinToString { it.title.orEmpty() } }) {
                device.notifications().none { it.packageName == Fixture.PACKAGE && it.title == "New message" }
            }
            app.forceStop()
        }
    }

    /** Detach put stay awake and the accessibility display settings back. */
    @Test
    @Order(17)
    fun displaySettingsAreRestoredAfterTheTest(device: Device): Unit {
        tapTest {
            val before = displayBefore[device.serial] ?: return@tapTest
            val now = device.info()
            assertEquals(
                listOf(before.stayAwake, before.highContrastText, before.colorInversion, before.boldText),
                listOf(now.stayAwake, now.highContrastText, now.colorInversion, now.boldText),
            )
        }
    }

    private companion object {
        val displayBefore = ConcurrentHashMap<String, DeviceInfo>()
        const val PUSHED = "/data/local/tmp/tap-sample-pushed.bin"
        const val PHOTO = "tap-sample.png"
        val localeBefore = ConcurrentHashMap<String, List<String>>()
        val rotationBefore = ConcurrentHashMap<String, DeviceInfo>()
        val conditionsBefore = ConcurrentHashMap<String, DeviceInfo>()
        val localesBefore = ConcurrentHashMap<String, List<String>>()
    }
}
