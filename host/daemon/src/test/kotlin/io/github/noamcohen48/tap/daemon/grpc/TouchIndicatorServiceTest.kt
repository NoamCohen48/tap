package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.GetTouchIndicatorsRequest
import io.github.noamcohen48.tap.api.v1.SetTouchIndicatorsRequest
import io.github.noamcohen48.tap.api.v1.TouchIndicatorServiceGrpcKt
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AdbDevice
import io.github.noamcohen48.tap.host.AdbDeviceState
import io.github.noamcohen48.tap.host.DeviceProperties
import io.github.noamcohen48.tap.host.TouchIndicatorState
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TouchIndicatorServiceTest {
    @Test
    fun `explicit settings need no connection and validate serial and optional switches`() =
        runBlocking {
            val writes = mutableListOf<Triple<String, Boolean?, Boolean?>>()
            val adb =
                object : Adb("fake-adb") {
                    override suspend fun deviceStates(timeoutMs: Long) =
                        listOf(
                            AdbDevice("online", AdbDeviceState.ONLINE, "device"),
                            AdbDevice("offline", AdbDeviceState.OFFLINE, "offline"),
                        )

                    override suspend fun deviceProperties(serial: String) = DeviceProperties(34, "test", "test", true)

                    override suspend fun touchIndicators(serial: String) = TouchIndicatorState(false, true)

                    override suspend fun setTouchIndicators(
                        serial: String,
                        showTaps: Boolean?,
                        pointerLocation: Boolean?,
                    ): TouchIndicatorState {
                        writes += Triple(serial, showTaps, pointerLocation)
                        return TouchIndicatorState(showTaps ?: false, pointerLocation ?: true)
                    }
                }
            val daemon = TapDaemon(DaemonConfig(adb, Files.createTempDirectory("tap-indicators"), driver = null, log = {}))
            val name = InProcessServerBuilder.generateName()
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(TouchIndicatorService(daemon))
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            val client = TouchIndicatorServiceGrpcKt.TouchIndicatorServiceCoroutineStub(channel)
            try {
                val initial = client.getTouchIndicators(GetTouchIndicatorsRequest.newBuilder().setSerial("online").build())
                assertTrue(initial.indicators.pointerLocation)
                assertTrue(writes.isEmpty())
                val changed =
                    client.setTouchIndicators(
                        SetTouchIndicatorsRequest
                            .newBuilder()
                            .setSerial("online")
                            .setShowTaps(true)
                            .build(),
                    )
                assertTrue(changed.indicators.showTaps && changed.indicators.pointerLocation)
                assertEquals(listOf<Triple<String, Boolean?, Boolean?>>(Triple("online", true, null)), writes)
                for (serial in listOf("", "missing", "offline")) {
                    val failure =
                        assertFailsWith<StatusException> {
                            client.setTouchIndicators(
                                SetTouchIndicatorsRequest
                                    .newBuilder()
                                    .setSerial(serial)
                                    .setShowTaps(true)
                                    .build(),
                            )
                        }
                    assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
                }
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    assertFailsWith<StatusException> {
                        client.setTouchIndicators(SetTouchIndicatorsRequest.newBuilder().setSerial("online").build())
                    }.status.code,
                )
                assertEquals(1, writes.size)
                assertTrue(daemon.connections().isEmpty())
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                daemon.close()
            }
            assertEquals(1, writes.size) // no automatic restore or hidden writes
        }
}
