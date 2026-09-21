package com.company.tap.junit5

import com.company.tap.api.v1.AttachRequest
import com.company.tap.api.v1.CloseConnectionRequest
import com.company.tap.api.v1.CloseConnectionResponse
import com.company.tap.api.v1.CloseSessionRequest
import com.company.tap.api.v1.CloseSessionResponse
import com.company.tap.api.v1.Command
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
import com.company.tap.sdk.KEYCODE_BACK
import com.company.tap.sdk.TapClient
import com.company.tap.sdk.TapUsageException
import com.company.tap.sdk.tapScope
import com.company.tap.sdk.text
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Extension contract without devices: `tapTest` binding/nesting, timeout cancellation of the
 * root job, structured sibling cancellation of an accepted in-flight `Execute`, teardown
 * closing every device while preserving the primary failure, and sorted-serial opens.
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
    fun `failing sibling cancels accepted in-flight Execute without replay`() =
        runBlocking {
            val connection = client().connect("test")
            try {
                val root = Job()
                bind(TestState(root, emptyMap(), emptyMap(), "sibling-remote"))
                try {
                    val started = System.nanoTime()
                    val failure =
                        assertFailsWith<AssertionError> {
                            tapTest {
                                val device = connection.openDevice("emulator-5554", "com.test")
                                try {
                                    // Accepted mutation before the scope: it must run exactly
                                    // once — the cancellation below must never replay it.
                                    device.pressKey(KEYCODE_BACK)
                                    coroutineScope {
                                        // UNDISPATCHED so the waiter reaches its suspension
                                        // before the sibling can fail; the sibling only fails
                                        // after the fake servicer signals the wait's Execute
                                        // was accepted server-side.
                                        async(start = CoroutineStart.UNDISPATCHED) {
                                            device.await(text("Never rendered ${System.nanoTime()}"), timeout = 20.seconds).visible()
                                        }
                                        async {
                                            withTimeout(5_000) { fakeSessions.enteredWait.await() }
                                            throw AssertionError("sibling boom")
                                        }
                                    }
                                } finally {
                                    device.close()
                                }
                            }
                        }
                    assertEquals("sibling boom", failure.message)
                    withTimeout(2_000) { fakeSessions.cancelledWait.await() }
                    assertEquals(1, fakeSessions.pressKeyExecutes.get(), "mutation executed exactly once, never replayed")
                    assertTrue(root.isCancelled, "failing tapTest cancels its root job")
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue(elapsedMs < 10_000, "accepted wait cancelled promptly, took ${elapsedMs}ms")
                } finally {
                    TapTestBinding.current.remove()
                    root.cancel()
                }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `device access outside tapTest fails clearly`(): Unit {
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
                        // An owned scope that does not inherit the test scope: suspension
                        // launched outside the bound scope is rejected, never left running
                        // beside the test. Cancelled in finally; never GlobalScope.
                        val probe = CoroutineScope(Job())
                        try {
                            assertFailsWith<TapUsageException> {
                                withTimeout(2_000) {
                                    probe.async { device.info() }.await()
                                }
                            }
                        } finally {
                            probe.coroutineContext[Job]?.cancel()
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
    fun `tapTest nested in a child coroutine fails`() {
        val root = Job()
        bind(TestState(root, emptyMap(), emptyMap(), "nested-child"))
        try {
            // The nesting flag travels with the coroutine context, so a child on another
            // thread (no ThreadLocal binding of its own) still reports "nested".
            val failure =
                assertFailsWith<TapUsageException> {
                    tapTest {
                        async(Dispatchers.Default) { tapTest { } }.await()
                    }
                }
            assertTrue(failure.message!!.contains("nested"))
        } finally {
            root.cancel()
            TapTestBinding.current.remove()
        }
    }

    @Test
    fun `duplicate pins fail before any open`() {
        val extension = TapExtension()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                extension.assignSerials(
                    listOf("left", "right"),
                    listOf("serial-aaa", "serial-bbb"),
                    mapOf("left" to "serial-aaa", "right" to "serial-aaa"),
                )
            }
        assertTrue(failure.message!!.contains("duplicate"), "pins name the offending serials: ${failure.message}")
        assertTrue(fakeSessions.opens.isEmpty(), "no session opened before the duplicate check")
    }

    @Test
    fun `duplicate assignment opens nothing`() =
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
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        tapScope {
                            extension.openAll(connection, mapOf("a" to "serial-aaa", "b" to "serial-aaa"), config)
                        }
                    }
                assertTrue(failure.message!!.contains("duplicate"))
                assertTrue(fakeSessions.opens.isEmpty(), "openAll validates before the first open")
            } finally {
                connection.close()
            }
        }

    @Test
    fun `interrupted tapTest keeps the thread usable and afterEach closes every device`() {
        // Exercises the real AfterEach path (artifacts + closeAll) on the same thread that
        // ran the interrupted test, like JUnit does: the interrupt must be consumed by
        // tapTest, or AfterEach's own runBlocking teardown aborts before closing devices.
        System.setProperty("tap.autPackage", "com.test")
        try {
            val connection = runBlocking { client().connect("test") }
            try {
                fakeSessions.quarantineSerial = "serial-bbb"
                val devices =
                    runBlocking(com.company.tap.sdk.TapContext("test:setup")) {
                        mapOf(
                            "a" to connection.openDevice("serial-aaa", "com.test"),
                            "b" to connection.openDevice("serial-bbb", "com.test"),
                        )
                    }
                val state = TestState(Job(), devices, mapOf("a" to "serial-aaa", "b" to "serial-bbb"), "interrupted-teardown")
                val extension = TapExtension()
                val backing = HashMap<Any, Any?>(mapOf("tap.devices" to state))
                val testMethod = TapTestBridgeTest::class.java.getDeclaredMethod("metadata-only test stays possible")
                val context = stubContext(stubStore(backing), TapTestBridgeTest::class.java, testMethod)
                var observed: Throwable? = null
                var flagAfterTest: Boolean? = null
                var afterEachError: Throwable? = null
                val thread =
                    Thread {
                        bind(state)
                        try {
                            tapTest { awaitCancellation() }
                        } catch (failure: Throwable) {
                            observed = failure
                            if (state.failure == null) state.failure = failure
                        } finally {
                            TapTestBinding.current.remove()
                        }
                        // tapTest must have consumed the interrupt: a set flag would abort
                        // AfterEach's runBlocking teardown on this same callback thread.
                        flagAfterTest = Thread.currentThread().isInterrupted
                        try {
                            extension.afterEach(context)
                        } catch (failure: Throwable) {
                            afterEachError = failure
                        }
                    }
                thread.start()
                Thread.sleep(300)
                thread.interrupt()
                thread.join(15_000)
                assertTrue(!thread.isAlive, "interrupted test plus AfterEach return")
                assertTrue(afterEachError == null, "AfterEach itself must not throw: $afterEachError")
                assertFalse(flagAfterTest ?: true, "tapTest consumes the interrupt flag")
                val primary = observed
                assertTrue(primary is CancellationException, "interruption stays the primary failure, was $primary")
                assertTrue(primary.cause is InterruptedException, "original interruption preserved as the cause")
                assertTrue(primary === state.failure, "stored primary is the observed failure")
                assertEquals(setOf("sess-serial-aaa", "sess-serial-bbb"), fakeSessions.closes.toSet(), "AfterEach closes every device")
                assertEquals(1, primary.suppressed.size, "quarantine error suppressed, not replacing")
                assertTrue(primary.suppressed[0].message!!.contains("serial-bbb"))
            } finally {
                runBlocking { connection.close() }
            }
        } finally {
            System.clearProperty("tap.autPackage")
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

    /** Minimal `ExtensionContext.Store` over a map (only `get`/`put`/`remove` are exercised). */
    private fun stubStore(backing: HashMap<Any, Any?>): ExtensionContext.Store =
        object : ExtensionContext.Store {
            @Suppress("UNCHECKED_CAST")
            override fun <V : Any?> get(key: Any?, requiredType: Class<V>?): V? = backing[key] as V?

            override fun get(key: Any?): Any? = backing[key]

            override fun put(key: Any?, value: Any?) {
                backing[key as Any] = value
            }

            override fun remove(key: Any?): Any? = backing.remove(key)

            @Suppress("UNCHECKED_CAST")
            override fun <V : Any?> remove(key: Any?, requiredType: Class<V>?): V? = backing.remove(key) as V?

            override fun <K : Any, V : Any> getOrComputeIfAbsent(
                key: K,
                defaultCreator: java.util.function.Function<K, V>,
            ): Any? = backing.getOrPut(key as Any) { defaultCreator.apply(key) as Any? }

            @Suppress("UNCHECKED_CAST")
            override fun <K : Any, V : Any> getOrComputeIfAbsent(
                key: K,
                defaultCreator: java.util.function.Function<K, V>,
                requiredType: Class<V>,
            ): V? = backing.getOrPut(key as Any) { defaultCreator.apply(key) as Any? } as V?
        }

    /** Minimal `ExtensionContext`: store, no execution exception, fixed class/method. */
    private fun stubContext(
        store: ExtensionContext.Store,
        testClass: Class<*>,
        testMethod: Method,
    ): ExtensionContext =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ExtensionContext::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getStore" -> store
                "getExecutionException" -> java.util.Optional.empty<Throwable>()
                "getRequiredTestMethod" -> testMethod
                "getRequiredTestClass" -> testClass
                "getDisplayName" -> "stub"
                "toString" -> "stub-context"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ExtensionContext

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

        // Sibling-cancellation support: wait-Execute entry/cancellation plus a count of
        // accepted mutation Executes (pressKey), proving exactly-once under cancellation.
        val enteredWait = CompletableDeferred<Unit>()
        val cancelledWait = CompletableDeferred<Unit>()
        val pressKeyExecutes =
            java.util.concurrent.atomic
                .AtomicInteger(0)

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
            if (request.command.opCase == Command.OpCase.WAIT_VISIBLE) {
                // An accepted in-flight remote wait: signal entry, then park until caller
                // (sibling-failure) cancellation arrives; the test asserts the server saw
                // the cancel. Never completes normally.
                if (!enteredWait.isCompleted) enteredWait.complete(Unit)
                try {
                    withTimeout(30_000) { awaitCancellation() }
                    error("unreachable")
                } catch (cancelled: CancellationException) {
                    if (!cancelledWait.isCompleted) cancelledWait.complete(Unit)
                    throw cancelled
                }
            }
            if (request.command.opCase == Command.OpCase.PRESS_KEY) pressKeyExecutes.incrementAndGet()
            return CommandResult.getDefaultInstance()
        }

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
