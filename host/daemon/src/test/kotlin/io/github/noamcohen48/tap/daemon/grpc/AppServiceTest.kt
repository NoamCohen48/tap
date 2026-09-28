package io.github.noamcohen48.tap.daemon.grpc

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.InstallHeader
import io.github.noamcohen48.tap.api.v1.InstallRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The streaming Install upload is validated before anything reaches ADB, and never leaks its spool file. */
class AppServiceTest {
    private val stateDir = Files.createTempDirectory("tap-app-service")
    private val daemon = TapDaemon(DaemonConfig(adb = object : Adb("fake-adb") {}, stateDir = stateDir, driver = null, log = {}))
    private val name = InProcessServerBuilder.generateName()
    private val server = InProcessServerBuilder.forName(name).addService(AppService(daemon)).build().start()
    private val channel = InProcessChannelBuilder.forName(name).build()
    private val stub = AppServiceGrpcKt.AppServiceCoroutineStub(channel)

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun header(size: Long) =
        InstallRequest
            .newBuilder()
            .setHeader(
                InstallHeader
                    .newBuilder()
                    .setApp(AppTarget.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setPackageName("com.test"))
                    .setSizeBytes(size),
            ).build()

    private fun chunk(bytes: Int) = InstallRequest.newBuilder().setChunk(ByteString.copyFrom(ByteArray(bytes))).build()

    private fun rejected(vararg parts: InstallRequest): Status.Code =
        runBlocking { assertFailsWith<StatusException> { stub.install(flowOf(*parts)) }.status.code }

    @Test
    fun `malformed uploads are INVALID_ARGUMENT and leave no spool file`() {
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(chunk(4)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(4), chunk(3)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(4), chunk(5)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(4), header(4)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(0)))
        assertTrue(stateDir.resolve("uploads").listDirectoryEntries().isEmpty())
    }

    @Test
    fun `a complete upload for a device the caller does not have is NOT_FOUND`() {
        assertEquals(Status.Code.NOT_FOUND, rejected(header(4), chunk(4)))
        assertTrue(stateDir.resolve("uploads").listDirectoryEntries().isEmpty())
    }
}
