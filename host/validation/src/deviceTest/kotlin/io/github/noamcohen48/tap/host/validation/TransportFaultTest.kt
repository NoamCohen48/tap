package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.CommandTransportException
import io.github.noamcohen48.tap.host.JournalState
import io.github.noamcohen48.tap.host.TransmissionState
import io.github.noamcohen48.tap.host.observeProcess
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.label
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The driver drops the connection at a fault point around a tap. Whatever the point, the host
 * reports `INDETERMINATE` for the written mutation, the poisoned client refuses further work, the
 * tap is never replayed, and the AUT process survives recovery. A fresh session then proves the
 * tap happened exactly as often as the point implies (once only when it fell after the mutation).
 */
@DeviceTest
class TransportFaultTest {
    companion object {
        @JvmStatic
        fun faultCases(): List<Arguments> =
            Devices.configured.flatMap { serial ->
                listOf(
                    TransportFaultPoint.BEFORE_ACCEPTANCE,
                    TransportFaultPoint.AFTER_ACCEPTANCE,
                    TransportFaultPoint.AFTER_MUTATION,
                ).map { Arguments.of(serial, it) }
            }
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("faultCases")
    fun `transport loss at a fault point is INDETERMINATE and never replayed`(
        serial: String,
        point: TransportFaultPoint,
    ) = deviceTest(serial) { device ->
        device.wakeAndDismissKeyguard()
        device.launchFixture("MainActivity")
        val fixtureProcess = observeProcess(device.adb, serial, FIXTURE_PACKAGE)
        val faultButton = Selectors.androidResource(FIXTURE_PACKAGE, "fault_button")

        device.withSession(point) { session ->
            check(session.client.send(Commands.waitVisible(Selectors.text("Fault taps: 0")), timeoutMs = 10_000).ok) {
                "Fault counter changed before $point"
            }
            val failure = runCatching { session.client.send(Commands.tap(faultButton), timeoutMs = 10_000) }.exceptionOrNull()
            check(failure is CommandTransportException) { "$point did not lose transport: $failure" }
            check(failure.code == ErrorCode.ERR_INDETERMINATE) { "$point produced ${failure.code.label} instead of INDETERMINATE" }
            check(failure.transmissionState == TransmissionState.WRITTEN) {
                "$point failed in unexpected host transmission state ${failure.transmissionState}"
            }
            val poisonedFailure = runCatching { session.client.send(Requests.health()) }.exceptionOrNull()
            check(
                poisonedFailure is CommandTransportException &&
                    poisonedFailure.code == ErrorCode.ERR_TRANSPORT_LOST &&
                    poisonedFailure.transmissionState == TransmissionState.NOT_WRITTEN,
            ) { "Lost connection accepted another command: $poisonedFailure" }
            session.writeJournal(JournalState.BROKEN)
            session.cleanup()
            check("TAP_FAULT point=${point.name}" in session.output()) { "Missing driver fault marker for $point" }
            check(observeProcess(device.adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT process changed during $point recovery" }
        }

        val expectedTaps = if (point == TransportFaultPoint.AFTER_MUTATION) 1 else 0
        device.withSession { verification ->
            check(observeProcess(device.adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT process changed before verification" }
            val counter = Selectors.text("Fault taps: $expectedTaps")
            check(verification.client.send(Commands.waitVisible(counter), timeoutMs = 10_000).ok) {
                "Transport loss at $point did not leave exactly $expectedTaps tap(s)"
            }
            delay(500)
            check(verification.client.execute(Commands.exists(counter)).bool) { "Uncertain tap was replayed or completed late" }
            check(observeProcess(device.adb, serial, FIXTURE_PACKAGE) == fixtureProcess) { "AUT process changed during verification" }
        }
    }
}
