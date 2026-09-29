package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AttachRequest
import io.github.noamcohen48.tap.api.v1.AttachResponse
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.Closing
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.ConnectResponse
import io.github.noamcohen48.tap.api.v1.DetachRequest
import io.github.noamcohen48.tap.api.v1.DetachResponse
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.DisconnectRequest
import io.github.noamcohen48.tap.api.v1.DisconnectResponse
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.api.v1.DriverLogRequest
import io.github.noamcohen48.tap.api.v1.DriverLogResponse
import io.github.noamcohen48.tap.api.v1.ExecuteRequest
import io.github.noamcohen48.tap.api.v1.ExecuteResponse
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.ForceStopRequest
import io.github.noamcohen48.tap.api.v1.ForceStopResponse
import io.github.noamcohen48.tap.api.v1.InstallRequest
import io.github.noamcohen48.tap.api.v1.InstallResponse
import io.github.noamcohen48.tap.api.v1.IsInstalledRequest
import io.github.noamcohen48.tap.api.v1.IsInstalledResponse
import io.github.noamcohen48.tap.api.v1.ObserveRequest
import io.github.noamcohen48.tap.api.v1.ObserveResponse
import io.github.noamcohen48.tap.api.v1.Observing
import io.github.noamcohen48.tap.api.v1.ScreenshotRequest
import io.github.noamcohen48.tap.api.v1.ScreenshotResponse
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The tap.v1 wire contract as the Kotlin client speaks it: ownership on every device call,
 * absent-means-default timeouts, the typed Observe events, streamed install and client-side
 * screenshot verification.
 */
class WireContractTest {
    private lateinit var serverName: String
    private lateinit var grpcServer: io.grpc.Server
    private lateinit var channel: ManagedChannel
    private val connections = Connections()
    private val devices = Devices()
    private val apps = Apps()

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(connections)
                .addService(devices)
                .addService(apps)
                .build()
                .start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        grpcServer.shutdownNow()
    }

    private fun client() = TapClient("inprocess:$serverName", channel)

    @Test
    fun `every call on an attached device names the owning connection`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    device.info()
                    device.screenshot()
                    device.driverLog()
                    device.app().isInstalled()
                    device.app().forceStop()
                    device.detach()
                }
            } finally {
                connection.close()
            }
            assertEquals(listOf("execute", "screenshot", "driverLog", "detach"), devices.owners.map { it.first })
            assertEquals(listOf("isInstalled", "forceStop"), apps.owners.map { it.first })
            assertTrue((devices.owners + apps.owners).all { it.second == "conn-1" }, "owners: ${devices.owners + apps.owners}")
        }

    @Test
    fun `attach sends no lease timeout unless the caller waits`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    connection.attachDevice("a", "com.test").detach()
                    connection.attachDevice("b", "com.test", options = DeviceOptions(waitForDevice = 5.seconds)).detach()
                }
                assertFalse(devices.attaches[0].hasLeaseTimeoutMs(), "absent = fail at once")
                assertEquals(5_000L, devices.attaches[1].leaseTimeoutMs)
                assertTrue(devices.attaches.all { it.clientConnectionId == "conn-1" && it.hasDefaultTimeoutMs() })
            } finally {
                connection.close()
            }
        }

    @Test
    fun `a closing event makes the connection unusable with the daemon's reason`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                connections.closeWith.complete("reaped: no heartbeat")
                withTimeout(5_000) { while (connection.isUsable) kotlinx.coroutines.delay(10) }
                val failure = assertFailsWith<ServerException> { tapScope { connection.attachDevice("a", "com.test") } }
                assertTrue(failure.message!!.contains("reaped: no heartbeat"), failure.message)
                assertEquals(listOf("observing", "closing: reaped: no heartbeat"), connection.recentEvents)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `a closing first event fails connect`() =
        runBlocking {
            connections.closeFirst = "daemon shutting down"
            val failure = assertFailsWith<TapException> { client().connect("test") }
            assertTrue(failure.message!!.contains("daemon shutting down"), failure.message)
        }

    @Test
    fun `PERMISSION_DENIED maps to a foreign-connection error`() =
        runBlocking {
            devices.denyExecute = true
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    val failure = assertFailsWith<ServerException> { device.info() }
                    assertEquals("PERMISSION_DENIED", failure.status)
                    assertTrue(failure.message!!.contains("another client connection"), failure.message)
                    device.detach()
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `install streams a header then 1 MiB chunks of the local APK`() =
        runBlocking {
            val apk = Files.createTempFile("tap-test", ".apk")
            val bytes = ByteArray(INSTALL_CHUNK_BYTES * 2 + 123) { (it % 251).toByte() }
            apk.writeBytes(bytes)
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    device.app().install(apk, timeout = 9.seconds)
                    device.detach()
                }
                val parts = apps.installParts
                assertTrue(parts.first().hasHeader())
                val header = parts.first().header
                assertEquals(bytes.size.toLong(), header.sizeBytes)
                assertEquals(9_000L, header.timeoutMs)
                assertEquals("conn-1", header.app.clientConnectionId)
                assertEquals("com.test", header.app.packageName)
                val chunks = parts.drop(1).map { it.chunk }
                assertEquals(listOf(INSTALL_CHUNK_BYTES, INSTALL_CHUNK_BYTES, 123), chunks.map { it.size() })
                assertTrue(bytes.contentEquals(chunks.fold(ByteString.EMPTY, ByteString::concat).toByteArray()))
            } finally {
                connection.close()
                Files.deleteIfExists(apk)
            }
        }

    @Test
    fun `screenshot bytes are checked against the returned sha256`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    assertTrue(byteArrayOf(1, 2, 3).contentEquals(device.screenshot().bytes))
                    devices.corruptScreenshot = true
                    val failure = assertFailsWith<TapException> { device.screenshot() }
                    assertTrue(failure.message!!.contains("checksum"), failure.message)
                    device.detach()
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `audio start and stop preserve ownership and verify checksum`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                tapScope {
                    val device = connection.attachDevice("emulator-5554", "com.test")
                    device.startAudioRecording("playback", 12)
                    assertEquals("conn-1", devices.audioStart?.clientConnectionId)
                    assertEquals("playback", devices.audioStart?.source)
                    assertEquals(12, devices.audioStart?.maxSeconds)
                    assertEquals("opus", device.stopAudioRecording().extension)
                    assertEquals("conn-1", devices.audioStop?.clientConnectionId)
                    devices.corruptAudio = true
                    assertFailsWith<IllegalStateException> { device.stopAudioRecording() }
                    device.detach()
                }
            } finally {
                connection.close()
            }
        }

    // --- Fakes ----------------------------------------------------------------------------------

    private class Connections : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
        val closeWith = CompletableDeferred<String>()

        @Volatile var closeFirst: String? = null

        override suspend fun connect(request: ConnectRequest): ConnectResponse =
            ConnectResponse.newBuilder().setClientConnectionId("conn-1").build()

        override fun observe(request: ObserveRequest): Flow<ObserveResponse> =
            flow {
                closeFirst?.let {
                    emit(closing(it))
                    return@flow
                }
                emit(ObserveResponse.newBuilder().setObserving(Observing.newBuilder().setClientConnectionId(request.clientConnectionId)).build())
                val reason = withTimeout(30_000) { closeWith.await() }
                emit(closing(reason))
                // The daemon completes the stream normally after `closing`.
            }

        private fun closing(reason: String) = ObserveResponse.newBuilder().setClosing(Closing.newBuilder().setReason(reason)).build()

        override suspend fun disconnect(request: DisconnectRequest): DisconnectResponse = DisconnectResponse.getDefaultInstance()
    }

    private class Devices : DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
        val owners = CopyOnWriteArrayList<Pair<String, String>>()
        val attaches = CopyOnWriteArrayList<AttachRequest>()

        @Volatile var denyExecute = false

        @Volatile var corruptScreenshot = false
        @Volatile var corruptAudio = false
        @Volatile var audioStart: io.github.noamcohen48.tap.api.v1.StartAudioRecordingRequest? = null
        @Volatile var audioStop: io.github.noamcohen48.tap.api.v1.StopAudioRecordingRequest? = null

        override suspend fun startAudioRecording(
            request: io.github.noamcohen48.tap.api.v1.StartAudioRecordingRequest,
        ): io.github.noamcohen48.tap.api.v1.StartAudioRecordingResponse {
            audioStart = request
            return io.github.noamcohen48.tap.api.v1.StartAudioRecordingResponse.getDefaultInstance()
        }

        override suspend fun stopAudioRecording(
            request: io.github.noamcohen48.tap.api.v1.StopAudioRecordingRequest,
        ): io.github.noamcohen48.tap.api.v1.StopAudioRecordingResponse {
            audioStop = request
            val bytes = byteArrayOf(1, 2, 3)
            return io.github.noamcohen48.tap.api.v1.StopAudioRecordingResponse.newBuilder()
                .setOpus(ByteString.copyFrom(bytes))
                .setSha256(if (corruptAudio) "bad" else sha256Hex(bytes))
                .build()
        }

        override suspend fun attach(request: AttachRequest): AttachResponse {
            attaches.add(request)
            return AttachResponse
                .newBuilder()
                .setAttachedDeviceId("attached-${request.serial}")
                .setSerial(request.serial)
                .setGeneration(1)
                .build()
        }

        override suspend fun execute(request: ExecuteRequest): ExecuteResponse {
            owners.add("execute" to request.clientConnectionId)
            if (denyExecute) throw daemonFailure(Status.PERMISSION_DENIED, FailureReason.FAILURE_REASON_NOT_OWNER, "not your device")
            return ExecuteResponse
                .newBuilder()
                .setResult(CommandResult.newBuilder().setDone(Done.getDefaultInstance()))
                .build()
        }

        override suspend fun screenshot(request: ScreenshotRequest): ScreenshotResponse {
            owners.add("screenshot" to request.clientConnectionId)
            val png = byteArrayOf(1, 2, 3)
            val sent = if (corruptScreenshot) byteArrayOf(1, 2, 4) else png
            return ScreenshotResponse
                .newBuilder()
                .setPng(ByteString.copyFrom(sent))
                .setSha256(sha256Hex(png))
                .build()
        }

        override suspend fun driverLog(request: DriverLogRequest): DriverLogResponse {
            owners.add("driverLog" to request.clientConnectionId)
            return DriverLogResponse.getDefaultInstance()
        }

        override suspend fun detach(request: DetachRequest): DetachResponse {
            owners.add("detach" to request.clientConnectionId)
            return DetachResponse.newBuilder().setClean(true).build()
        }
    }

    private class Apps : AppServiceGrpcKt.AppServiceCoroutineImplBase() {
        val owners = CopyOnWriteArrayList<Pair<String, String>>()
        val installParts = CopyOnWriteArrayList<InstallRequest>()

        override suspend fun isInstalled(request: IsInstalledRequest): IsInstalledResponse {
            owners.add("isInstalled" to request.app.clientConnectionId)
            return IsInstalledResponse.newBuilder().setInstalled(true).build()
        }

        override suspend fun forceStop(request: ForceStopRequest): ForceStopResponse {
            owners.add("forceStop" to request.app.clientConnectionId)
            return ForceStopResponse.getDefaultInstance()
        }

        override suspend fun install(requests: Flow<InstallRequest>): InstallResponse {
            requests.collect { installParts.add(it) }
            return InstallResponse.getDefaultInstance()
        }
    }
}
