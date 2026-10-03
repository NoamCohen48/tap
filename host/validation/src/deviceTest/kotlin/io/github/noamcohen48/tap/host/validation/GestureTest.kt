package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.PinchDirection
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok

/**
 * The multi-step gestures reach the app as those gestures: a `GestureDetector` double tap, a
 * `startDragAndDrop` long-press drag that drops on its target, a `ScaleGestureDetector` pinch
 * both ways, and a fling that moves a list. A drag whose destination does not resolve is
 * `NOT_FOUND` before any input (the source never sees a long press).
 */
@DeviceTest
class GestureTest {
    @OnEachDevice
    fun `double tap, drag and drop, and pinch are recognised by the app`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("GestureActivity")
                fun id(name: String) = Selectors.androidResource(FIXTURE_PACKAGE, name)

                fun seen(text: String) = Selectors.text(text)
                check(client.send(Commands.waitVisible(id("double_tap_target")), timeoutMs = 10_000).ok) { "Gesture activity did not appear" }

                val doubleTap = client.send(Commands.doubleTap(id("double_tap_target")))
                check(doubleTap.ok) { "Double tap failed: $doubleTap" }
                check(client.send(Commands.waitVisible(seen("Double taps: 1")), timeoutMs = 5_000).ok) {
                    "The app did not see a double tap: ${client.execute(Commands.snapshot(id("double_tap_status"))).snapshot.text}"
                }

                val missing = client.send(Commands.drag(id("drag_source"), id("no_such_target")))
                check(!missing.ok && missing.errorCode == ErrorCode.ERR_NOT_FOUND) { "Drag onto nothing should be NOT_FOUND: $missing" }
                check(client.execute(Commands.snapshot(id("drag_status"))).snapshot.text == "Not dragged") {
                    "A drag refused before input must not have pressed the source"
                }
                val drag = client.send(Commands.drag(id("drag_source"), id("drop_target")), timeoutMs = 10_000)
                check(drag.ok) { "Drag failed: $drag" }
                check(client.send(Commands.waitVisible(seen("Dropped Card")), timeoutMs = 5_000).ok) {
                    "The drop target did not get the drop: ${client.execute(Commands.snapshot(id("drag_status"))).snapshot.text}"
                }

                val pinchOpen = client.send(Commands.pinch(id("pinch_target"), PinchDirection.PINCH_OPEN), timeoutMs = 10_000)
                check(pinchOpen.ok) { "Pinch open failed: $pinchOpen" }
                check(client.send(Commands.waitVisible(seen("Zoomed in")), timeoutMs = 5_000).ok) {
                    "Pinch open was not a zoom in: ${client.execute(Commands.snapshot(id("pinch_status"))).snapshot.text}"
                }
                val pinchClose = client.send(Commands.pinch(id("pinch_target"), PinchDirection.PINCH_CLOSE, percent = 60), timeoutMs = 10_000)
                check(pinchClose.ok) { "Pinch close failed: $pinchClose" }
                check(client.send(Commands.waitVisible(seen("Zoomed out")), timeoutMs = 5_000).ok) {
                    "Pinch close was not a zoom out: ${client.execute(Commands.snapshot(id("pinch_status"))).snapshot.text}"
                }
                report("gestures", serial, "double_tap" to "ok", "drag" to "ok", "pinch" to "ok")
            }
        }

    @OnEachDevice
    fun `a fling moves a list towards its end`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("ViewListActivity")
                val viewList = Selectors.androidResource(FIXTURE_PACKAGE, "view_list")
                check(client.send(Commands.waitVisible(Selectors.text("View item 1")), timeoutMs = 10_000).ok)
                val fling = client.send(Commands.fling(viewList, Direction.DIR_DOWN))
                check(fling.ok && fling.result.hasDone()) { "Fling failed: $fling" }
                check(client.send(Commands.waitGone(Selectors.text("View item 1")), timeoutMs = 5_000).ok) {
                    "A fling towards the end left the first item on screen"
                }
            }
        }
}
