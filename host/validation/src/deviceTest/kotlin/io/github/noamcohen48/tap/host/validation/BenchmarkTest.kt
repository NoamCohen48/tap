package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.protocol.Commands

/**
 * The selector hot path against the diagnostic dump: 100 direct `EXISTS` lookups and 5
 * hierarchy dumps must all succeed; their average latencies go to the test output.
 */
@DeviceTest
class BenchmarkTest {
    @OnEachDevice
    fun `direct selector lookups and hierarchy dumps succeed`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val client = session.client
                device.openFixtureMain(client)

                val directStarted = System.nanoTime()
                repeat(100) { check(client.execute(Commands.exists(FIXTURE_MAIN_READY)).bool) { "Direct lookup missed" } }
                val directMs = (System.nanoTime() - directStarted) / 1_000_000.0 / 100

                val dumpStarted = System.nanoTime()
                repeat(5) { check(client.execute(Commands.dumpHierarchy()).text.isNotEmpty()) { "Empty hierarchy dump" } }
                val dumpMs = (System.nanoTime() - dumpStarted) / 1_000_000.0 / 5

                report("benchmark", serial, "directAvgMs" to directMs, "dumpAvgMs" to dumpMs)
            }
        }
}
