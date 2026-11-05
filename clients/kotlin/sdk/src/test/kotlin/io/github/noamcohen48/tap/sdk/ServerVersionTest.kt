package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.ConnectRequest
import io.github.noamcohen48.tap.api.v1.ConnectResponse
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.TAP_API_VERSION
import io.github.noamcohen48.tap.api.v1.TAP_API_COMPATIBILITY
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerVersionTest {
    @Test
    fun `a server at least as new as the client's stubs is compatible`() {
        assertNull(ServerVersion.incompatibility("0.0.2", "0.0.2"))
        assertNull(ServerVersion.incompatibility("0.0.3", "0.0.2"))
        assertNull(ServerVersion.incompatibility("0.1.0", "0.0.9"))
        assertNull(ServerVersion.incompatibility("1.0.0-rc1", "0.9.9"))
    }

    @Test
    fun `an older server is refused with both versions`() {
        val reason = assertNotNull(ServerVersion.incompatibility("0.0.1", "0.0.2"))
        assertTrue("0.0.1" in reason && "0.0.2" in reason, reason)
        assertNotNull(ServerVersion.incompatibility("0.9.0", "0.10.0"))
        assertNotNull(ServerVersion.incompatibility("1.2.3", "2.0.0"))
    }

    @Test
    fun `a version that is not major-minor-patch is not checked`() {
        assertNull(ServerVersion.incompatibility("test", "0.0.2"))
        assertNull(ServerVersion.incompatibility("", "0.0.2"))
    }

    @Test
    fun `connect refuses an older server before opening a connection`(): Unit =
        runBlocking {
            val connects = AtomicInteger()
            var epoch = TAP_API_COMPATIBILITY
            var version = "0.0.0"
            val name = InProcessServerBuilder.generateName()
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(
                        object : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
                            override suspend fun info(request: InfoRequest): InfoResponse =
                                InfoResponse.newBuilder().setDaemonVersion(version).setApiCompatibility(epoch).build()

                            override suspend fun connect(request: ConnectRequest): ConnectResponse {
                                connects.incrementAndGet()
                                return ConnectResponse.newBuilder().setClientConnectionId("c1").build()
                            }
                        },
                    ).build()
                    .start()
            val client = TapClient("inprocess", InProcessChannelBuilder.forName(name).directExecutor().build())
            try {
                val failure = assertFailsWith<TapException> { client.connect("test") }
                assertTrue(TAP_API_VERSION in failure.message.orEmpty(), failure.message)
                version = "99.0.0"
                for (incompatible in listOf(0, TAP_API_COMPATIBILITY + 1)) {
                    epoch = incompatible
                    val mismatch = assertFailsWith<TapException> { client.connect("test") }
                    assertTrue("API compatibility $epoch" in mismatch.message.orEmpty(), mismatch.message)
                }
                assertNull(ServerVersion.compatibility(TAP_API_COMPATIBILITY))
                assertEquals(0, connects.get())
            } finally {
                client.close()
                server.shutdownNow()
            }
        }
}
