package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.CommandTransportException
import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.detail
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.label
import java.util.concurrent.TimeUnit

/**
 * Watchdog poisoning end to end: the driver runs with a 3 s heartbeat timeout, the host sends
 * nothing after starting a long wait, and the driver must fail the wait with
 * `DRIVER_UNHEALTHY/HEARTBEAT_EXPIRED` (or drop the transport), report `TAP_POISONED`, and kill
 * its own process — the only scenario in which the driver, not the host, ends the instrumentation.
 */
@DeviceTest
class HeartbeatTest {
    @OnEachDevice
    fun `a silent host makes the driver poison and kill itself`(serial: String) =
        deviceTest(serial) { device ->
            val heartbeatTimeoutMs = 3_000L
            device.withSession(
                driverArguments = mapOf("tapHeartbeatTimeoutMs" to heartbeatTimeoutMs.toString()),
                hostHeartbeatIntervalMs = 0,
            ) { session ->
                val silentSince = System.nanoTime()
                val wait =
                    session.client.submit(
                        Commands.waitVisible(Selectors.text("tap-heartbeat-probe-never-visible")),
                        timeoutMs = 30_000,
                    )
                val outcome = runCatching { wait.await() }
                val elapsedMs = (System.nanoTime() - silentSince) / 1_000_000L
                val response = outcome.getOrNull()
                val transportError = outcome.exceptionOrNull()
                check(
                    (response != null && response.errorCode == ErrorCode.ERR_DRIVER_UNHEALTHY && response.detail == ErrorDetail.HEARTBEAT_EXPIRED) ||
                        (transportError is CommandTransportException && transportError.code == ErrorCode.ERR_TRANSPORT_LOST),
                ) { "Heartbeat expiry did not fail the running wait: response=$response error=$transportError" }
                check(elapsedMs >= heartbeatTimeoutMs) { "Driver poisoned before its heartbeat timeout ($elapsedMs ms)" }
                check(elapsedMs < 20_000) { "Heartbeat expiry took $elapsedMs ms" }
                check(session.awaitMarker("TAP_POISONED", 10_000)) { "Driver did not report TAP_POISONED after heartbeat expiry" }
                check(session.running.process.waitFor(15, TimeUnit.SECONDS)) { "Driver did not kill itself after heartbeat expiry" }
                device.awaitProcessAbsent(DRIVER_PACKAGE)
                report(
                    "heartbeat-expiry",
                    serial,
                    "failedAfterMs" to elapsedMs,
                    "terminal" to (response?.errorCode ?: (transportError as CommandTransportException).code).label,
                )
            }
        }
}
