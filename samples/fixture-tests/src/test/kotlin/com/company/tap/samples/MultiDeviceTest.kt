package com.company.tap.samples

import com.company.tap.junit5.DeviceBarrier
import com.company.tap.junit5.Devices
import com.company.tap.junit5.TapDevices
import com.company.tap.junit5.TapTest
import com.company.tap.junit5.tapTest
import com.company.tap.sdk.res
import com.company.tap.sdk.text
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Two roles acquired all-or-none; the test is skipped (assumption) when `tap.serials` lists
 * fewer devices. Devices are independent sessions, so per-device work runs as sibling
 * coroutines and a failure on one cancels the other's in-flight work.
 */
@TapTest
class MultiDeviceTest {
    @Test
    @TapDevices("left", "right")
    fun drivesTwoDevicesConcurrently(devices: Devices) =
        tapTest {
            coroutineScope {
                listOf("left", "right")
                    .map { role ->
                        async {
                            val device = devices[role]
                            Fixture.launch(device)
                            device.element(res("view_button")).tap()
                            device.await(text("View tapped")).visible()
                            device.info().model
                        }
                    }.awaitAll()
                    .also { assertEquals(2, it.size) }
            }
        }

    /**
     * Structured sibling cancellation on real devices: the left device parks in a long
     * device-side wait while the right sibling fails fast. The scope must cancel the wait
     * promptly (far short of its 20 s deadline), and the mutation the left device performed
     * before the scope (one `fault_button` tap, counter `1`) must not have been replayed by
     * the cancellation. The [DeviceBarrier] is the genuine simultaneous phase: neither side
     * proceeds until both sessions are ready; the waiter additionally starts UNDISPATCHED so
     * it reaches the barrier (and then the remote wait) before the sibling can fail.
     *
     * What this proves on hardware is prompt cancellation plus no replay. That the failing
     * sibling cancels an already *accepted* remote `Execute` (rather than one still being
     * dispatched) is proven deterministically by the in-process
     * `TapTestBridgeTest` case, where the fake servicer signals `Execute` entry before the
     * sibling fails; here the 300 ms head start after the shared rendezvous is timing-shaped.
     */
    @Test
    @TapDevices("left", "right")
    fun siblingFailureCancelsWaitWithoutReplay(devices: Devices) =
        tapTest {
            val left = devices["left"]
            val right = devices["right"]
            Fixture.launch(left)
            Fixture.launch(right)

            left.element(res("fault_button")).tap()
            left.await(text("Fault taps: 1")).visible()

            val barrier = DeviceBarrier(2)
            val started = System.nanoTime()
            try {
                coroutineScope {
                    val waiting =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            barrier.await()
                            left.await(text("Never rendered ${System.nanoTime()}"), timeout = 20.seconds).visible()
                        }
                    val failing =
                        async {
                            barrier.await()
                            delay(300)
                            throw AssertionError("sibling boom")
                        }
                    awaitAll(waiting, failing)
                }
                fail("the failing sibling should have cancelled the scope")
            } catch (expected: AssertionError) {
                assertEquals("sibling boom", expected.message)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsedMs < 15_000, "in-flight wait cancelled promptly, took ${elapsedMs}ms")

            // No replay: the pre-scope mutation happened exactly once.
            assertEquals("Fault taps: 1", left.element(res("fault_status")).text())
            // The other session is still usable after its sibling was cancelled.
            right.element(res("view_button")).tap()
            right.await(text("View tapped")).visible()
        }
}
