package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.DeviceLogsRequest
import io.github.noamcohen48.tap.host.Adb
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceLogsServiceTest {
    @Test
    fun `a stuck dumpsys leaves the other logs inside the caller's deadline`(): Unit =
        runBlocking {
            val adb =
                object : Adb("fake-adb") {
                    override suspend fun logcat(
                        serial: String,
                        buffers: List<String>,
                        sinceEpochSeconds: Long?,
                        tags: List<String>,
                    ): String = "line\n"

                    override suspend fun dumpsys(
                        serial: String,
                        vararg arguments: String,
                    ): String = awaitCancellation()
                }
            AttachedHarness.with(adb) {
                val logs =
                    devices.withDeadlineAfter(2, TimeUnit.SECONDS).deviceLogs(
                        DeviceLogsRequest
                            .newBuilder()
                            .setClientConnectionId(connectionId)
                            .setAttachedDeviceId(attached.id)
                            .build(),
                    )
                assertEquals("line\n", logs.logcat)
                assertTrue(logs.windows.startsWith("<unavailable: not read within"), logs.windows)
            }
        }
}
