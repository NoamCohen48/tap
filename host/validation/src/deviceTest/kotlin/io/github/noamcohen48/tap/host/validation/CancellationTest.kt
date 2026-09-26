package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.delay

/**
 * The driver's reader, queue, executor and writer lanes are independent: a running wait is
 * cancelled while the executor is busy, queued work is cancelled without running, queue
 * residence consumes the request deadline, pings bypass the executor, and the session stays
 * reusable. Only non-mutating operations are used, so `CANCELLED` is always legal.
 */
@DeviceTest
class CancellationTest {
    @OnEachDevice
    fun `running and queued commands cancel promptly and the session stays usable`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                val absent = Selectors.text("tap-cancellation-probe-never-visible")

                val idlePingMs = client.ping()

                // Cancel a running wait: it must stop within the poll interval, not at its 30 s timeout.
                val running = client.submit(Commands.waitVisible(absent), timeoutMs = 30_000)
                delay(500)
                val busyPingMs = client.ping()
                check(running.cancel()) {
                    "Running wait was not cancellable: state=${running.transmissionState} done=${running.isDone} " +
                        "response=${running.responseOrNull}"
                }
                val cancelStarted = System.nanoTime()
                val cancelled = running.await()
                val cancelLatencyMs = (System.nanoTime() - cancelStarted) / 1_000_000L
                check(!cancelled.ok && cancelled.errorCode == ErrorCode.ERR_CANCELLED) { "Running wait was not cancelled: $cancelled" }
                check(cancelLatencyMs < 5_000) { "Cancellation took $cancelLatencyMs ms" }
                check(!running.cancel()) { "Terminal command accepted a second cancel" }

                // Cancel queued work: the second wait must terminate before the first one does, and
                // a short-deadline command queued behind a long one must expire without running.
                val first = client.submit(Commands.waitVisible(absent), timeoutMs = 30_000)
                val second = client.submit(Commands.waitVisible(absent), timeoutMs = 30_000)
                val expiring = client.submit(Requests.health(), timeoutMs = 200)
                check(second.cancel())
                val secondResult = second.await()
                check(!secondResult.ok && secondResult.errorCode == ErrorCode.ERR_CANCELLED) {
                    "Queued wait was not cancelled: $secondResult"
                }
                check(!first.isDone) { "First wait completed before it was cancelled" }
                delay(300)
                check(first.cancel())
                val firstResult = first.await()
                check(!firstResult.ok && firstResult.errorCode == ErrorCode.ERR_CANCELLED) { "First wait was not cancelled: $firstResult" }
                val expired = expiring.await()
                check(!expired.ok && expired.errorCode == ErrorCode.ERR_DEADLINE_EXCEEDED) {
                    "Queued command did not consume its deadline while waiting: $expired"
                }

                val health = client.send(Requests.health())
                check(health.ok) { "Session unusable after cancellation: $health" }
                report(
                    "cancellation",
                    serial,
                    "idlePingMs" to idlePingMs,
                    "busyPingMs" to busyPingMs,
                    "cancelLatencyMs" to cancelLatencyMs,
                )
            }
        }
}
