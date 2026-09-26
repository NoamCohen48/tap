package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.ValidationApi
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Multi-device isolation: with a session live on every configured serial, dropping the first
 * device's transport leaves every other device's session healthy. Needs two or more serials.
 */
@DeviceTest
class DisconnectIsolationTest {
    @Test
    @OptIn(ValidationApi::class)
    fun `one device's transport loss does not disturb the others`() {
        val serials = Devices.configured
        assumeTrue(serials.size >= 2) { "Disconnect isolation needs at least two serials" }
        runBlocking(Dispatchers.IO) {
            val devices = mutableListOf<DeviceHarness>()
            val sessions = mutableListOf<FaultSession>()
            var failure: Throwable? = null
            try {
                serials.forEach { devices += DeviceHarness.open(it) }
                // Sequential, so every started session is tracked for cleanup.
                devices.forEach { sessions += it.startSession() }

                val disconnected = sessions.first()
                disconnected.client.validationTransport().disconnect()
                sessions.drop(1).zip(devices.drop(1)).map { (session, device) ->
                    async {
                        repeat(3) {
                            val health = session.client.send(Requests.health())
                            check(health.ok) { "Device ${device.serial} stopped after ${serials.first()} disconnected: $health" }
                        }
                        report("disconnect-isolation", device.serial, "disconnected" to serials.first())
                    }
                }.awaitAll()
            } catch (error: Throwable) {
                failure = error
                sessions.forEachIndexed { index, session ->
                    println("Driver output on ${devices[index].serial} before the failure:\n${session.outputTail()}")
                }
            } finally {
                sessions.forEach { session ->
                    runCatching { session.cleanup() }.exceptionOrNull()?.let { error ->
                        failure?.addSuppressed(error) ?: run { failure = error }
                    }
                }
                devices.forEach { it.close() }
            }
            failure?.let { throw it }
        }
    }
}
