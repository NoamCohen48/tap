package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.DEVICE_PORT_RANGE
import io.github.noamcohen48.tap.host.JournalState
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.ok

/**
 * A driver session's whole life on a real device: startup walks past an occupied device port,
 * the forward is observable, the journal reaches `READY`, and a clean close lets the
 * instrumentation exit on its own with a passing result (`OK (1 test)`, no crash).
 */
@DeviceTest
class SessionLifecycleTest {
    @OnEachDevice
    fun `startup skips an occupied port and a clean close ends the instrumentation successfully`(serial: String) =
        deviceTest(serial) { device ->
            val occupiedPort = DEVICE_PORT_RANGE.first
            startPortOccupier(device, occupiedPort)
            val session =
                try {
                    device.startSession(connect = false)
                } finally {
                    device.forceStopFixture()
                    device.waitForPortState(occupiedPort, listening = false)
                }
            try {
                check(session.running.devicePort != occupiedPort) { "Driver did not retry after the occupied device port" }
                check(device.adb.forwards(serial).any { it.hostPort == session.hostPort && it.devicePort == session.running.devicePort }) {
                    "Created forwarding rule was not observable"
                }
                check(device.store.read()?.state == JournalState.ACTIVE)
                val client = session.connect()
                check(device.store.read()?.state == JournalState.READY)
                check(client.send(Requests.health()).ok)
            } catch (error: Throwable) {
                runCatching { session.cleanup() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
            val closed = session.cleanup()
            check(closed.state == JournalState.CLOSED) { "Clean close journaled ${closed.state}" }
            val process = session.running.process
            check(!process.isAlive && process.exitValue() == 0) { "Instrumentation did not exit successfully" }
            val output = session.output()
            check("OK (1 test)" in output && "Process crashed" !in output) { "Instrumentation reported a failure:\n${session.outputTail()}" }
        }

    private suspend fun startPortOccupier(
        device: DeviceHarness,
        port: Int,
    ) {
        try {
            device.launchFixture("PortOccupierActivity", "--ei", "port", port.toString())
            device.waitForPortState(port, listening = true)
        } catch (error: Throwable) {
            runCatching { device.forceStopFixture() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
