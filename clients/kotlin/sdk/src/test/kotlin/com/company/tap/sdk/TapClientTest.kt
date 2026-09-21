package com.company.tap.sdk

import com.company.tap.api.v1.AppServiceGrpcKt
import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.CloseConnectionResponse
import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.CloseSessionResponse
import com.company.tap.api.v1.CommandResult
import com.company.tap.api.v1.ConnectionEvent
import com.company.tap.api.v1.ConnectionServiceGrpcKt
import com.company.tap.api.v1.DeviceServiceGrpcKt
import com.company.tap.api.v1.ExecuteRequest
import com.company.tap.api.v1.InfoRequest
import com.company.tap.api.v1.InfoResponse
import com.company.tap.api.v1.ListDevicesRequest
import com.company.tap.api.v1.ListDevicesResponse
import com.company.tap.api.v1.OpenConnectionRequest
import com.company.tap.api.v1.OpenConnectionResponse
import com.company.tap.api.v1.OpenSessionRequest
import com.company.tap.api.v1.OpenSessionResponse
import com.company.tap.api.v1.SessionServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * In-process grpc-kotlin fakes for the coroutine client: attach ownership/lifetime, close
 * ordering, cancellation propagation and the public suspend API shape. No devices involved.
 */
class TapClientTest {
    private lateinit var serverName: String
    private lateinit var fakeConnections: FakeConnections
    private lateinit var fakeSessions: FakeSessions
    private lateinit var fakeDevices: FakeDevices
    private lateinit var grpcServer: io.grpc.Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun start() {
        serverName = InProcessServerBuilder.generateName()
        fakeConnections = FakeConnections()
        fakeSessions = FakeSessions()
        fakeDevices = FakeDevices()
        grpcServer =
            InProcessServerBuilder
                .forName(serverName)
                .directExecutor()
                .addService(fakeConnections)
                .addService(fakeSessions)
                .addService(fakeDevices)
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
    fun `connect establishes attach before returning`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                assertTrue(connection.recentEvents.isNotEmpty(), "attach first event before connect returns")
                assertEquals(1, fakeConnections.attaches.get(), "exactly one Attach")
            } finally {
                connection.close()
            }
        }

    @Test
    fun `close sends Close before dropping attach`() =
        runBlocking {
            val connection = client().connect("test")
            connection.close()
            assertEquals(listOf("close", "attach-cancelled"), fakeConnections.order.toList())
        }

    @Test
    fun `dropped attach still lets close run`() =
        runBlocking {
            val connection = client().connect("test")
            // Server ends the stream after the first event; the client collection finishes,
            // but an explicit Close still works and the scope still cleans up exactly once.
            fakeConnections.failAttachCompletions.complete(Unit)
            withTimeout(5_000) {
                while (connection.recentEvents.size < 2) delay(10)
            }
            // Give the collection a moment to record its terminal cleanup.
            withTimeout(5_000) {
                while (!fakeConnections.order.contains("attach-cancelled")) delay(10)
            }
            connection.close()
            assertTrue(fakeConnections.order.contains("close"))
            assertEquals(1, fakeConnections.order.count { it == "attach-cancelled" })
        }

    @Test
    fun `cancelling execute cancels the grpc call`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                fakeSessions.hangExecute.complete(Unit)
                tapScope {
                    val device = connection.openDevice("emulator-5554", "com.test")
                    try {
                        val job = async { device.info() }
                        withTimeout(2_000) { fakeSessions.enteredExecute.await() }
                        job.cancelAndJoin()
                        assertTrue(job.isCancelled, "caller cancel cancels the in-flight Execute")
                        withTimeout(2_000) { fakeSessions.cancelledExecute.await() }
                    } finally {
                        // Release the fake server park, then close inside the same scope.
                        fakeSessions.hangExecuteResult.complete(CommandResult.getDefaultInstance())
                        device.close()
                    }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `sibling failure cancels the other device poll`() =
        runBlocking {
            val connection = client().connect("test")
            // Fresh flag per test (field is reused across tests in this class).
            val flag = CompletableDeferred<Unit>()
            try {
                val started = System.nanoTime()
                val failure =
                    assertFailsWith<AssertionError> {
                        tapScope {
                            // Deterministic structured-concurrency shape: one child parks in a
                            // cancellable wait (the stand-in for an in-flight device wait),
                            // the other fails fast; the scope must cancel the waiter.
                            kotlinx.coroutines.coroutineScope {
                                val waiter =
                                    async {
                                        try {
                                            awaitCancellation()
                                        } catch (cancelled: CancellationException) {
                                            flag.complete(Unit)
                                            throw cancelled
                                        }
                                    }
                                val boom =
                                    async {
                                        delay(50)
                                        throw AssertionError("sibling boom")
                                    }
                                boom.await()
                                waiter.await()
                            }
                        }
                    }
                assertEquals("sibling boom", failure.message)
                withTimeout(2_000) { flag.await() }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue(elapsedMs < 10_000, "cancelled promptly after sibling failure, took ${elapsedMs}ms")
            } finally {
                connection.close()
            }
        }

    @Test
    fun `device calls outside scope fail clearly`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
                // No TapContext here: direct call must fail, not hang.
                assertFailsWith<TapUsageException> { device.info() }
                // GlobalScope does not inherit the scope either.
                assertFailsWith<TapUsageException> {
                    withTimeout(2_000) {
                        kotlinx.coroutines.GlobalScope
                            .async { device.info() }
                            .await()
                    }
                }
                tapScope { device.close() }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `tapScope nesting fails`() =
        runBlocking {
            assertFailsWith<TapUsageException> {
                tapScope {
                    tapScope { }
                }
            }
        }

    @Test
    fun `awaitUntil is delay based and cancellable`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
                try {
                    val started = System.nanoTime()
                    val job =
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).async {
                            // Must run inside tapScope to pass the bound check; inherit via child.
                            kotlinx.coroutines.withContext(TapContext("test")) {
                                device.awaitUntil("never", timeout = 30.seconds, pollInterval = 50.milliseconds) { false }
                            }
                        }
                    delay(150)
                    job.cancelAndJoin()
                    assertTrue(job.isCancelled)
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue(elapsedMs < 5_000, "poll cancelled promptly, took ${elapsedMs}ms")
                } finally {
                    tapScope { device.close() }
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `quarantined close reports detail`() =
        runBlocking {
            fakeSessions.quarantineNextClose = "driver would not die"
            val connection = client().connect("test")
            try {
                val device = tapScope { connection.openDevice("emulator-5554", "com.test") }
                val detail = tapScope { device.closeAndReport() }
                assertEquals("driver would not die", detail)
            } finally {
                connection.close()
            }
        }

    // --- Fakes ----------------------------------------------------------------------------------

    private class FakeConnections : ConnectionServiceGrpcKt.ConnectionServiceCoroutineImplBase() {
        val attaches =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val order = CopyOnWriteArrayList<String>()
        val failAttachCompletions = CompletableDeferred<Unit>()

        override suspend fun open(request: OpenConnectionRequest): OpenConnectionResponse =
            OpenConnectionResponse.newBuilder().setConnectionId("conn-1").build()

        override fun attach(request: AttachRequest): Flow<ConnectionEvent> =
            flow {
                attaches.incrementAndGet()
                emit(ConnectionEvent.newBuilder().setMessage("hello").build())
                try {
                    // Park until the test signals server completion or the client cancels.
                    while (!failAttachCompletions.isCompleted) {
                        delay(10)
                    }
                    emit(ConnectionEvent.newBuilder().setMessage("bye").build())
                } finally {
                    order.add("attach-cancelled")
                }
            }

        override suspend fun close(request: CloseConnectionRequest): CloseConnectionResponse {
            order.add("close")
            return CloseConnectionResponse.getDefaultInstance()
        }

        override suspend fun info(request: InfoRequest): InfoResponse = InfoResponse.getDefaultInstance()
    }

    private class FakeSessions : SessionServiceGrpcKt.SessionServiceCoroutineImplBase() {
        val enteredExecute = CompletableDeferred<Unit>()
        val cancelledExecute = CompletableDeferred<Unit>()
        val hangExecute = CompletableDeferred<Unit>()
        val hangExecuteResult = CompletableDeferred<CommandResult>()
        var quarantineNextClose: String? = null
        val opens = CopyOnWriteArrayList<String>()
        val closes = CopyOnWriteArrayList<String>()

        override suspend fun open(request: OpenSessionRequest): OpenSessionResponse {
            opens.add(request.serial)
            return OpenSessionResponse
                .newBuilder()
                .setSessionId("sess-${request.serial}")
                .setSerial(request.serial)
                .setGeneration(1)
                .build()
        }

        override suspend fun execute(request: ExecuteRequest): CommandResult {
            if (hangExecute.isCompleted) {
                if (!enteredExecute.isCompleted) enteredExecute.complete(Unit)
                try {
                    // Park until the test releases; client cancel must interrupt this.
                    withTimeout(30_000) { hangExecuteResult.await() }
                    return hangExecuteResult.await()
                } catch (cancelled: CancellationException) {
                    if (!cancelledExecute.isCompleted) cancelledExecute.complete(Unit)
                    throw cancelled
                }
            }
            return CommandResult.getDefaultInstance()
        }

        override suspend fun close(request: CloseSessionRequest): CloseSessionResponse {
            closes.add(request.sessionId)
            val detail = quarantineNextClose?.also { quarantineNextClose = null }
            return CloseSessionResponse
                .newBuilder()
                .setClean(detail == null)
                .apply { detail?.let { setDetail(it) } }
                .build()
        }

        override suspend fun screenshot(request: com.company.tap.api.v1.ScreenshotRequest): com.company.tap.api.v1.ScreenshotResponse =
            com.company.tap.api.v1.ScreenshotResponse
                .getDefaultInstance()

        override suspend fun driverLog(request: com.company.tap.api.v1.DriverLogRequest): com.company.tap.api.v1.DriverLogResponse =
            com.company.tap.api.v1.DriverLogResponse
                .getDefaultInstance()
    }

    private class FakeDevices : DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
        override suspend fun listDevices(request: ListDevicesRequest): ListDevicesResponse = ListDevicesResponse.getDefaultInstance()
    }
}
