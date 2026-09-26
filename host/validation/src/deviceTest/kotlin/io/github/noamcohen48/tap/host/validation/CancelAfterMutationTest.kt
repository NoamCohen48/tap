package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.observeProcess
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay

/**
 * A cancel arriving after the mutation gate: the driver holds a fault-button tap open for 3 s
 * after its click, the host cancels during the hold, and the awaited result must be the
 * definitive `ok` tap, not `CANCELLED`. The counter advances exactly once and the session stays
 * usable.
 */
@DeviceTest
class CancelAfterMutationTest {
    @OnEachDevice
    fun `a cancel after the mutation returns the definitive result`(serial: String) =
        deviceTest(serial) { device ->
            device.wakeAndDismissKeyguard()
            device.launchFixture("MainActivity")
            val fixtureProcess = observeProcess(device.adb, serial, FIXTURE_PACKAGE)
            val faultButton = Selectors.androidResource(FIXTURE_PACKAGE, "fault_button")
            device.withSession(TransportFaultPoint.CANCEL_AFTER_MUTATION) { session ->
                check(session.client.send(Commands.waitVisible(Selectors.text("Fault taps: 0")), timeoutMs = 10_000).ok) {
                    "Fault counter was not 0 before the cancel-after-mutation tap"
                }
                val tap = session.client.submit(Commands.tap(faultButton), timeoutMs = 15_000)
                val marker = "TAP_FAULT point=${TransportFaultPoint.CANCEL_AFTER_MUTATION.name} phase=MUTATED"
                check(session.awaitMarker(marker, 10_000)) { "Driver did not report the post-click hold" }
                check(tap.cancel()) { "In-flight tap was not cancellable at the host" }
                val cancelSentAt = System.nanoTime()
                val result = tap.await()
                val awaitedMs = (System.nanoTime() - cancelSentAt) / 1_000_000L
                check(result.ok && result.result.hasDone()) { "Cancel after mutation did not return the definitive tap result: $result" }
                check(session.client.send(Commands.waitVisible(Selectors.text("Fault taps: 1")), timeoutMs = 10_000).ok) {
                    "Cancelled-after-mutation tap did not take effect exactly once"
                }
                delay(500)
                check(session.client.execute(Commands.exists(Selectors.text("Fault taps: 1"))).bool) {
                    "Fault counter moved after the cancelled tap"
                }
                check(session.client.send(Requests.health()).ok) { "Session unusable after cancel-after-mutation" }
                check(observeProcess(device.adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT process changed during cancel scenario" }
                report("cancel-after-mutation", serial, "awaitedAfterCancelMs" to awaitedMs)
            }
        }
}
