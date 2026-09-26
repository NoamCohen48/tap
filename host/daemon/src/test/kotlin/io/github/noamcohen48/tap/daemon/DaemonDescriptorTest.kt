package io.github.noamcohen48.tap.daemon

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpc
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.server.FAILURE_TRAILER
import io.github.noamcohen48.tap.server.TokenAuthInterceptor
import io.grpc.Metadata
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonDescriptorTest {
    private val sample = DaemonDescriptor(41234, 777, DaemonDescriptor.newToken(), "0.1.0", "/opt/adb \"quoted\"")

    @Test
    fun `descriptor round trips through real JSON and is owner-only`() {
        val dir = Files.createTempDirectory("tap-descriptor")
        sample.write(dir.resolve(DaemonDescriptor.FILE_NAME))
        assertEquals(sample, DaemonDescriptor.read(dir))
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(DaemonDescriptor.FILE_NAME))),
        )
        assertEquals(listOf(DaemonDescriptor.FILE_NAME), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `tokens are 256-bit hex and unique`() {
        val a = DaemonDescriptor.newToken()
        assertTrue(Regex("[0-9a-f]{64}").matches(a))
        assertNotEquals(a, DaemonDescriptor.newToken())
    }

    @Test
    fun `malformed or tokenless descriptors read as absent`() {
        assertNull(DaemonDescriptor.parse("""{"port":1,"pid":2}"""))
        assertNull(DaemonDescriptor.parse("not json"))
        assertNull(DaemonDescriptor.read(Files.createTempDirectory("tap-empty")))
    }

    @Test
    fun `only the owning daemon deletes the descriptor`() {
        val dir = Files.createTempDirectory("tap-owned")
        sample.write(dir.resolve(DaemonDescriptor.FILE_NAME))
        DaemonDescriptor.deleteIfOwned(dir, DaemonDescriptor.newToken())
        assertNotNull(DaemonDescriptor.read(dir))
        DaemonDescriptor.deleteIfOwned(dir, sample.token)
        assertNull(DaemonDescriptor.read(dir))
    }

    @Test
    fun `the state dir lock is exclusive until released`() {
        val dir = Files.createTempDirectory("tap-lock")
        val first = assertNotNull(DaemonLock.tryAcquire(dir))
        assertNull(DaemonLock.tryAcquire(dir))
        first.close()
        assertNotNull(DaemonLock.tryAcquire(dir)).close()
    }

    @Test
    fun `calls without the right bearer token are UNAUTHENTICATED`() {
        val token = DaemonDescriptor.newToken()
        val service =
            object : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
                override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.newBuilder().setDaemonVersion("x").build()
            }
        val name = InProcessServerBuilder.generateName()
        val server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, TokenAuthInterceptor(token)))
                .build()
                .start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            fun info(header: String?): InfoResponse {
                val stub = ClientConnectionServiceGrpc.newBlockingStub(channel)
                val headers = Metadata().apply { header?.let { put(TokenAuthInterceptor.AUTHORIZATION, it) } }
                return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)).info(InfoRequest.getDefaultInstance())
            }
            assertEquals("x", info("Bearer $token").daemonVersion)
            for (bad in listOf(null, "Bearer ${DaemonDescriptor.newToken()}", token, "Bearer ")) {
                val error = assertFailsWith<StatusRuntimeException> { info(bad) }
                assertEquals(Status.Code.UNAUTHENTICATED, error.status.code)
                assertEquals(FailureReason.FAILURE_REASON_UNAUTHENTICATED, error.trailers?.get(FAILURE_TRAILER)?.reason)
            }
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }
}
