package com.company.tap.junit5

import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.CloseConnectionResponse
import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.CloseSessionResponse
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpcKt
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.OpenConnectionResponse
import com.company.tap.api.v1.OpenSessionRequest
import com.company.tap.api.v1.OpenSessionResponse
import com.company.tap.api.v1.SessionServiceGrpcKt
import com.company.tap.sdk.Connection
import com.company.tap.sdk.Device
import com.company.tap.sdk.TapClient
import com.company.tap.sdk.TapUsageException
import com.company.tap.sdk.tapScope
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Extension contract without devices: `tapTest` binding/nesting, timeout cancellation of the
 * root job, structured sibling cancellation of a fake in-flight RPC, teardown closing every
 * device while preserving the primary failure, and sorted-serial opens.
 */
class TapTestBridgeTest {
    private lateinit var serverName: String
    private lateinit var fakeConnections: FakeConnections
    private lateinit var fakeSessions: FakeSessions
    private lateinit var grpcServer: io.grpc.Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        fakeConnections = FakeConnections()
        fakeSessions = FakeSessions()
        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(fakeConnections)
                .addService(fakeSessions)
                .addService(FakeDevices())
                .build()
                .start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow()
        grpcServer.shutdownNow()
        TapTestBinding.current.remove()
        TapTestBinding.inTapTest.remove()
    }

    private fun client() = TapClient("inprocess:$serverName", channel)

    private fun bind(state: TestState) = TapTestBinding.current.set(state)

    @Test
    fun `tapTest without binding fails clearly`() {
        TapTestBinding.current.remove()
        val failure = assertFailsWith<TapUsageException> { tapTest { } }
        assertTrue(failure.message!!.contains("@TapTest"))
    }

    @Test
    fun `nested tapTest fails clearly`() {
        val root = Job()
        bind(TestState(root, emptyMap(), emptyMap(), "nested"))
        try {
            val failure =
                assertFailsWith<TapUsageException> {
                    tapTest {
                        tapTest { }
                    }
                }
            assertTrue(failure.message!!.contains("nested"))
        } finally {
            root.cancel()
            TapTestBinding.current.remove()
        }
    }

    @Test
    fun `tapTest failure cancels the root job`() {
        val root = Job()
        bind(TestState(root, emptyMap(), emptyMap(), "failing"))
        try {
            assertFailsWith<AssertionError> {
                tapTest { throw AssertionError("boom") }
            }
            assertTrue(root.isCancelled, "failing tapTest cancels its root job")
        } finally {
            TapTestBinding.current.remove()
        }
    }

    @Test
    fun `thread interruption cancels the root job`() {
        val root = Job()
        val state = TestState(root, emptyMap(), emptyMap(), "interrupted")
        var observed: Throwable? = null
        val thread =
            Thread {
                bind(state)
                try {
                    tapTest { awaitCancellation() }
                } catch (failure: Throwable) {
                    observed = failure
                } finally {
                    TapTestBinding.current.remove()
                }
            }
        thread.start()
        // Let the runBlocking park, then interrupt like JUnit @Timeout does.
        Thread.sleep(300)
        thread.interrupt()
        thread.join(5_000)
        assertTrue(!thread.isAlive, "interrupted tapTest returns")
        assertTrue(root.isCancelled, "interruption cancels the root job")
    }

    @Test
    fun `failing sibling cancels an in-flight fake RPC`() =
        runBlocking {
            val root = Job()
            bind(TestState(root, emptyMap(), emptyMap(), "siblings"))
            try {
                val parkedCancelled = CompletableDeferred<Unit>()
                val started = System.nanoTime()
                val failure =
                    assertFailsWith<AssertionError> {
                        tapTest {
                            coroutineScope {
                                launch {
                                    try {
                                        awaitCancellation()
                                    } catch (cancelled: CancellationException) {
                                        parkedCancelled.complete(Unit)
                                        throw cancelled
                                    }
                                }
                                launch {
                                    delay(50)
                                    throw AssertionError("sibling boom")
                                }
                            }
                        }
                    }
                assertEquals("sibling boom", failure.message)
                withTimeout(2_000) { parkedCancelled.await() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 10_000, "waiter cancelled promptly, took ${elapsedMs}ms")
            } finally {
                TapTestBinding.current.remove()
                root.cancel()
            }
        }

    @Test
    fun `device access outside tapTest fails clearly`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
                try {
                    // Bound to a test method, but outside tapTest: no TapContext marker.
                    val root = Job()
                    bind(TestState(root, mapOf("device" to device), mapOf("device" to "emulator-5554"), "outside"))
                    try {
                        assertFailsWith<TapUsageException> { runBlocking { device.info() } }
                        assertFailsWith<TapUsageException> {
                            withTimeout(2_000) {
                                kotlinx.coroutines.GlobalScope
                                    .async { device.info() }
                                    .await()
                            }
                        }
                    } finally {
                        TapTestBinding.current.remove()
                        root.cancel()
                    }
                } finally {
                    tapScope { device.close() }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `metadata-only test stays possible`() {
        // No devices, no tapTest: pure computation through the bridge is untouched. The point
        // is that binding/nesting rules do not forbid non-device tests; device calls are what
        // fail outside tapTest (covered above).
        val root = Job()
        bind(TestState(root, emptyMap(), emptyMap(), "meta"))
        try {
            val result = tapTest { 40 + 2 }
            assertEquals(42, result)
        } finally {
            TapTestBinding.current.remove()
            root.cancel()
        }
    }

    @Test
    fun `roles open in sorted serial order`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                val extension = TapExtension()
                val config =
                    TapConfig(
                        serials = emptyList(),
                        autPackage = "com.test",
                        artifactsDir = Path.of("build/tap-test-artifacts"),
                        acquireTimeout = kotlin.time.Duration.ZERO,
                        pinnedRoles = emptyMap(),
                    )
                val devices =
                    tapScope {
                        // Roles declared z-then-a, serials offered high-then-low: opens must take
                        // the locks low-first (sorted serial order) while keeping role mapping.
                        extension.openAll(
                            connection,
                            mapOf("z-role" to "serial-zzz", "a-role" to "serial-aaa"),
                            config,
                        )
                    }
                try {
                    assertEquals(listOf("serial-aaa", "serial-zzz"), fakeSessions.opens.toList())
                    assertEquals("serial-zzz", devices.getValue("z-role").serial)
                    assertEquals("serial-aaa", devices.getValue("a-role").serial)
                } finally {
                    tapScope {
                        devices.values.forEach { it.close() }
                    }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `teardown closes every device and preserves the primary failure`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                fakeSessions.quarantineSerial = "serial-bbb"
                val devices =
                    tapScope {
                        mapOf(
                            "a" to connection.openDevice("serial-aaa", "com.test"),
                            "b" to connection.openDevice("serial-bbb", "com.test"),
                        )
                    }
                val state = TestState(Job(), devices, mapOf("a" to "serial-aaa", "b" to "serial-bbb"), "teardown")
                val extension = TapExtension()
                val closeErrors = extension.closeAll(state)
                // Both sessions were asked to close, even though one quarantines.
                assertEquals(setOf("sess-serial-aaa", "sess-serial-bbb"), fakeSessions.closes.toSet())
                assertEquals(1, closeErrors.size, "quarantined close surfaces exactly one error")
                // Primary failure is preserved with cleanup suppressed into it.
                val primary = AssertionError("primary")
                closeErrors.forEach(primary::addSuppressed)
                assertEquals(1, primary.suppressed.size)
                assertTrue(primary.suppressed[0].message!!.contains("serial-bbb"))
            } finally {
                connection.close()
            }
        }

    @Test
    fun `assignSerials pins and keeps declaration order`() {
        val extension = TapExtension()
        val assignment =
            extension.assignSerials(
                listOf("sender", "receiver"),
                listOf("emulator-5554", "85e49002"),
                mapOf("receiver" to "85e49002"),
            )
        assertEquals("emulator-5554", assignment.getValue("sender"))
        assertEquals("85e49002", assignment.getValue("receiver"))
    }

    // --- Fakes ----------------------------------------------------------------------------------

    private class FakeConnections : ConnectionServiceGrpcKt.ConnectionServiceCoroutineImplBase() {
        override suspend fun open(request: OpenConnectionRequest): OpenConnectionResponse =
            OpenConnectionResponse.newBuilder().setConnectionId("conn-1").build()

        override fun attach(request: AttachRequest): Flow<ConnectionEvent> =
            flow {
                emit(ConnectionEvent.newBuilder().setMessage("hello").build())
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    throw CancellationException("attach dropped")
                }
            }

        override suspend fun close(request: CloseConnectionRequest): CloseConnectionResponse = CloseConnectionResponse.getDefaultInstance()

        override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.getDefaultInstance()
    }

    private class FakeSessions : SessionServiceGrpcKt.SessionServiceCoroutineImplBase() {
        val opens = CopyOnWriteArrayList<String>()
        val closes = CopyOnWriteArrayList<String>()
        var quarantineSerial: String? = null

        override suspend fun open(request: OpenSessionRequest): OpenSessionResponse {
            opens.add(request.serial)
            return OpenSessionResponse
                .newBuilder()
                .setSessionId("sess-${request.serial}")
                .setSerial(request.serial)
                .setGeneration(1)
                .build()
        }

        override suspend fun execute(request: ExecuteRequest): CommandResult = CommandResult.getDefaultInstance()

        override suspend fun close(request: CloseSessionRequest): CloseSessionResponse {
            closes.add(request.sessionId)
            val quarantined = request.sessionId.endsWith(quarantineSerial ?: "@@none@@")
            return CloseSessionResponse
                .newBuilder()
                .setClean(!quarantined)
                .apply { if (quarantined) setDetail("serial-bbb quarantined: driver would not die") }
                .build()
        }

        override suspend fun screenshot(request: com.company.tap.api.v1.ScreenshotRequest): com.company.tap.api.v1.ScreenshotResponse =
            com.company.tap.api.v1.ScreenshotResponse
                .getDefaultInstance()

        override suspend fun driverLog(request: com.company.tap.api.v1.DriverLogRequest): com.company.tap.api.v1.DriverLogResponse =
            com.company.tap.api.v1.DriverLogResponse
                .getDefaultInstance()
    }

    private class FakeDevices : com.company.tap.api.v1.DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
        override suspend fun listDevices(request: com.company.tap.api.v1.ListDevicesRequest): com.company.tap.api.v1.ListDevicesResponse =
            com.company.tap.api.v1.ListDevicesResponse
                .getDefaultInstance()
    }
}
