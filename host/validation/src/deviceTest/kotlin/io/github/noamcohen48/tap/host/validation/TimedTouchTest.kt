package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.ok

@DeviceTest
class TimedTouchTest {
    @OnEachDevice
    fun `counted taps and timed holds deliver exact counts and refuse unsafe input`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.wakeAndDismissKeyguard()
                device.launchFixture("TimedTouchActivity")

                fun id(name: String) = Selectors.androidResource(FIXTURE_PACKAGE, name)

                suspend fun count(expected: Int) {
                    check(client.send(Commands.waitVisible(Selectors.text("Taps: $expected")), timeoutMs = 5_000).ok)
                }
                check(client.send(Commands.waitVisible(id("timed_touch_target")), timeoutMs = 10_000).ok)
                val target = id("timed_touch_target")
                check(client.send(Commands.multiTap(target, 3), timeoutMs = 5_000).ok)
                count(3)
                check(client.send(Commands.multiTap(target, 7, 200), timeoutMs = 5_000).ok)
                count(10)
                check(client.send(Commands.hold(target, 1_200), timeoutMs = 5_000).ok)
                count(11)
                val held =
                    client
                        .execute(Commands.snapshot(id("touch_duration")))
                        .snapshot.text
                        .removePrefix("Held: ")
                        .removeSuffix(" ms")
                        .toLong()
                check(held in 1_200..3_000) { "The app observed a $held ms press, expected about 1200 ms" }
                val tooLong = client.send(Commands.hold(target, 5_000), timeoutMs = 500)
                check(tooLong.errorCode == ErrorCode.ERR_DEADLINE_EXCEEDED) { "$tooLong" }
                val tooMany = client.send(Commands.multiTap(target, 10), timeoutMs = 500)
                check(tooMany.errorCode == ErrorCode.ERR_DEADLINE_EXCEEDED) { "$tooMany" }
                val ambiguous = client.send(Commands.multiTap(Selectors.text("Touch me"), 3))
                check(ambiguous.errorCode == ErrorCode.ERR_AMBIGUOUS) { "$ambiguous" }
                val missing = client.send(Commands.hold(id("missing"), 1_000))
                check(missing.errorCode == ErrorCode.ERR_NOT_FOUND) { "$missing" }
                count(11)
                // Rejected sequences never leave a finger down or poison the next command.
                check(client.send(Commands.tap(target)).ok)
                count(12)
                report("timed-touch", serial, "counted_taps" to "ok", "held_ms" to held, "refused_before_input" to "ok")
            }
        }
}
