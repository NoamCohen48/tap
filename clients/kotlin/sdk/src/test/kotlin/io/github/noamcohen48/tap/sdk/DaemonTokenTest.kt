package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bearer-token discovery and propagation against an in-process server that requires it. */
class DaemonTokenTest {
    private val token = "ab".repeat(32)
    private val seen = CopyOnWriteArrayList<String?>()
    private lateinit var serverName: String
    private lateinit var server: io.grpc.Server

    private val authorization = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    private val requireToken =
        object : ServerInterceptor {
            override fun <Q : Any, A : Any> interceptCall(
                call: ServerCall<Q, A>,
                headers: Metadata,
                next: ServerCallHandler<Q, A>,
            ): ServerCall.Listener<Q> {
                val header = headers.get(authorization)
                seen.add(header)
                if (header != "Bearer $token") {
                    call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid bearer token"), Metadata())
                    return object : ServerCall.Listener<Q>() {}
                }
                return next.startCall(call, headers)
            }
        }

    private val info =
        object : ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineImplBase() {
            override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.newBuilder().setDaemonVersion("test").build()
        }

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(info, requireToken))
                .build()
                .start()
    }

    @AfterEach
    fun stop() {
        server.shutdownNow()
    }

    private fun channel() = InProcessChannelBuilder.forName(serverName).directExecutor().build()

    @Test
    fun `every call carries the bearer token`() =
        runBlocking {
            val client = TapClient("inprocess", channel(), token)
            try {
                assertEquals("test", client.info().daemonVersion)
                assertEquals(listOf("Bearer $token"), seen.toList())
            } finally {
                client.close()
            }
        }

    @Test
    fun `a missing token is a clear UNAUTHENTICATED error`() =
        runBlocking {
            val client = TapClient("inprocess", channel())
            try {
                val failure = assertFailsWith<ServerException> { client.info() }
                assertEquals("UNAUTHENTICATED", failure.status)
                assertTrue(failure.details.contains("wrong or missing daemon token"), failure.details)
            } finally {
                client.close()
            }
        }

    @Test
    fun `daemon json is parsed as JSON`() {
        val dir = Files.createTempDirectory("tap-descriptor")
        val file = dir.resolve("daemon.json")
        Files.writeString(file, """{ "pid": 7, "port" : 4242, "token": "$token", "daemonVersion": "1", "adb": "/x" }""")
        assertEquals(DaemonDiscovery.Descriptor(4242, token), DaemonDiscovery.readDescriptor(dir))
        Files.writeString(file, """{"port":4242}""")
        assertEquals(DaemonDiscovery.Descriptor(4242, null), DaemonDiscovery.readDescriptor(dir))
        Files.writeString(file, """{"port":"4242"}""")
        assertNull(DaemonDiscovery.readDescriptor(dir), "a non-numeric port is not a descriptor")
        Files.writeString(file, "not json")
        assertNull(DaemonDiscovery.readDescriptor(dir))
        Files.delete(file)
        assertNull(DaemonDiscovery.readDescriptor(dir))
    }

    @Test
    fun `discovery probes with the descriptor token`() =
        runBlocking {
            val deps = DaemonDiscovery.DiscoveryDeps(channelFactory = { channel() })
            val good = Files.createTempDirectory("tap-token-good")
            Files.writeString(good.resolve("daemon.json"), """{"port":1,"token":"$token"}""")
            assertEquals("127.0.0.1:1", DaemonDiscovery.running(good, deps))
            val wrong = Files.createTempDirectory("tap-token-wrong")
            Files.writeString(wrong.resolve("daemon.json"), """{"port":1,"token":"nope"}""")
            assertNull(DaemonDiscovery.running(wrong, deps), "a rejected token is not a live server")
            assertEquals(listOf("Bearer $token", "Bearer nope"), seen.toList())
        }
}
