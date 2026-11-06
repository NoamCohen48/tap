package io.github.noamcohen48.tap.daemon.grpc

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AppDataHeader
import io.github.noamcohen48.tap.api.v1.AppDataPart
import io.github.noamcohen48.tap.api.v1.AppDataServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.GetCapabilitiesRequest
import io.github.noamcohen48.tap.api.v1.ReadFileRequest
import io.github.noamcohen48.tap.api.v1.WriteFileRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AppDataException
import io.github.noamcohen48.tap.host.AppPrivateData
import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.host.DRIVER_TEST_PACKAGE
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppDataServiceTest {
    private val state = Files.createTempDirectory("tap-private-data-test")
    private val calls = AtomicInteger()
    private val adb =
        object : Adb("fake-adb") {
            override suspend fun execResult(
                serial: String,
                vararg arguments: String,
                timeoutMs: Long,
            ): Result {
                calls.incrementAndGet()
                error("Unauthorised request reached ADB")
            }
        }
    private val daemon = TapDaemon(DaemonConfig(adb, state, driver = null, log = {}))
    private val name = InProcessServerBuilder.generateName()
    private val server =
        InProcessServerBuilder
            .forName(name)
            .addService(AppDataService(daemon))
            .build()
            .start()
    private val channel = InProcessChannelBuilder.forName(name).build()
    private val stub = AppDataServiceGrpcKt.AppDataServiceCoroutineStub(channel)
    private val target =
        AppTarget
            .newBuilder()
            .setClientConnectionId(
                "missing",
            ).setAttachedDeviceId("missing")
            .setPackageName("com.example.fixture")
            .build()

    @AfterTest fun close() {
        channel.shutdownNow()
        server.shutdownNow()
        Files.walk(state).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun header(
        path: String = "files/a",
        size: Long = 0,
    ) = AppDataPart
        .newBuilder()
        .setHeader(
            AppDataHeader
                .newBuilder()
                .setApp(target)
                .setPath(path)
                .setSizeBytes(size),
        ).build()

    private fun rejected(vararg parts: AppDataPart): Status.Code =
        runBlocking {
            assertFailsWith<StatusException> {
                stub.writeFile(
                    flowOf(*parts).map { WriteFileRequest.newBuilder().setPart(it).build() },
                )
            }.status.code
        }

    @Test fun malformedHeadersAndPathsNeverReachAdbAndLeaveNoPrivateSpools() {
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected())
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(AppDataPart.getDefaultInstance()))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(AppDataPart.newBuilder().setChunk(ByteString.copyFromUtf8("secret")).build()))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(size = -1)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(size = AppPrivateData.MAX_BYTES + 1)))
        for (path in listOf("", "/data/a", "../a", "a/../b", "a//b", "a\u0000b", "a/.tap-private-stage-1")) {
            assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(path)))
        }
        assertEquals(0, calls.get())
        assertTrue(state.resolve("uploads").listDirectoryEntries().isEmpty())
    }

    @Test fun anUnownedTargetIsRejectedBeforeAcceptingPrivateDataOrReadingAdb() =
        runBlocking {
            assertEquals(
                Status.Code.NOT_FOUND,
                rejected(header(size = 3), AppDataPart.newBuilder().setChunk(ByteString.copyFromUtf8("abc")).build()),
            )
            val error =
                assertFailsWith<StatusException> {
                    stub
                        .readFile(
                            ReadFileRequest
                                .newBuilder()
                                .setApp(target)
                                .setPath("files/a")
                                .build(),
                        ).toList()
                }
            assertEquals(Status.Code.NOT_FOUND, error.status.code)
            assertEquals(0, calls.get())
            assertTrue(state.resolve("uploads").listDirectoryEntries().isEmpty())
        }

    @Test fun bothDriverPackagesAreProtectedEvenForCapabilityProbes() =
        runBlocking {
            for (pkg in listOf(DRIVER_PACKAGE, DRIVER_TEST_PACKAGE)) {
                val error =
                    assertFailsWith<StatusException> {
                        stub.getCapabilities(GetCapabilitiesRequest.newBuilder().setApp(target.toBuilder().setPackageName(pkg)).build())
                    }
                assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
            }
            assertEquals(0, calls.get())
        }

    @Test fun privateDataErrorsKeepAStableReasonAndDetailWithoutPayload() {
        val error = AppDataException("private access refused", "APP_RUNNING", "serial").toStatus()
        assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
        val failure = error.trailers!!.get(FAILURE_TRAILER)!!
        assertEquals(FailureReason.FAILURE_REASON_APP_DATA, failure.reason)
        assertEquals("APP_RUNNING", failure.detail)
        assertEquals("serial", failure.serial)
    }
}
