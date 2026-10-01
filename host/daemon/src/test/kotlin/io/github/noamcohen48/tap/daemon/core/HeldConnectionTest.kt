package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpc
import io.github.noamcohen48.tap.api.v1.DriverLogRequest
import io.github.noamcohen48.tap.daemon.grpc.DeviceService
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.AppLifecycle
import io.github.noamcohen48.tap.host.DeviceSessionConfig
import io.github.noamcohen48.tap.host.DriverClient
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Held connections (`.docs/agent-surface.md`): no Observe stream, ended by idleness instead. */
class HeldConnectionTest {
    private class Session(
        override val serial: String,
    ) : DaemonDeviceSession {
        val closes = AtomicInteger()
        override val generation = 1L
        override val client: DriverClient get() = error("no client")

        override fun app(packageName: String): AppLifecycle = error("no app")

        override fun checkUsable() = Unit

        override suspend fun close(timeoutMs: Long) {
            closes.incrementAndGet()
        }
    }

    private val opened = mutableListOf<Session>()
    private val opener =
        object : DeviceSessionOpener {
            override suspend fun open(config: DeviceSessionConfig): DaemonDeviceSession = Session(config.serial).also { synchronized(opened) { opened += it } }
        }
    private val options = TapDaemon.AttachDeviceOptions(skipDriverInstall = true, defaultTimeoutMs = 5_000, leaseTimeoutMs = 0)

    private fun daemon(observeGraceMs: Long = OBSERVE_GRACE_MS) =
        TapDaemon(
            DaemonConfig(adb = object : Adb("fake-adb") {}, stateDir = Files.createTempDirectory("tap-held"), driver = null, log = {}),
            opener,
            observeGraceMs = observeGraceMs,
        )

    /** One call the way a gRPC servicer makes it: in its own job, which ends with the call. */
    private suspend fun <T> call(block: suspend () -> T): T = coroutineScope { block() }

    private suspend fun awaitGone(
        daemon: TapDaemon,
        id: String,
    ) = withTimeout(5_000) { while (daemon.clientConnectionExists(id)) delay(10) }

    @Test
    fun `a held connection outlives the observe grace and ends after its idle timeout with its devices`() =
        runBlocking {
            val daemon = daemon(observeGraceMs = 50)
            val held = daemon.connectClient("agent", holdIdleMs = 400)
            call { daemon.attachDevice(held.id, "serial-a", options) }
            delay(200)
            assertTrue(daemon.clientConnectionExists(held.id), "the observe grace must not reap a held connection")
            awaitGone(daemon, held.id)
            // The connection leaves the registry first; its devices close right after.
            withTimeout(5_000) { while (opened.single().closes.get() == 0) delay(10) }
            assertEquals(1, opened.single().closes.get())
        }

    @Test
    fun `every call naming a held connection restarts its idle clock`() =
        runBlocking {
            val daemon = daemon()
            val held = daemon.connectClient("agent", holdIdleMs = 300)
            val device = call { daemon.attachDevice(held.id, "serial-a", options) }
            repeat(6) {
                delay(150)
                call { daemon.attachedDevice(device.id, held.id) }
            }
            assertTrue(daemon.clientConnectionExists(held.id), "900 ms of calls every 150 ms keep a 300 ms hold alive")
            awaitGone(daemon, held.id)
        }

    @Test
    fun `a held connection is not idle while a call naming it is still running`() =
        runBlocking {
            val daemon = daemon()
            val held = daemon.connectClient("agent", holdIdleMs = 200)
            val device = call { daemon.attachDevice(held.id, "serial-a", options) }
            val release = CompletableDeferred<Unit>()
            val running =
                async(Dispatchers.Default) {
                    daemon.attachedDevice(device.id, held.id)
                    release.await()
                }
            delay(600)
            assertTrue(daemon.clientConnectionExists(held.id), "a running call keeps the connection")
            assertEquals(0L, daemon.connections().single().idleMs)
            release.complete(Unit)
            running.await()
            awaitGone(daemon, held.id)
        }

    @Test
    fun `held names are unique among held connections and free again once it ends`() =
        runBlocking {
            val daemon = daemon()
            val first = daemon.connectClient("agent", holdIdleMs = 60_000)
            assertFailsWith<HeldNameTakenException> { daemon.connectClient("agent", holdIdleMs = 60_000) }
            // Observed connections may share any name.
            daemon.connectClient("agent")
            daemon.disconnectClient(first.id, "release")
            daemon.connectClient("agent", holdIdleMs = 60_000)
            Unit
        }

    @Test
    fun `a held connection has no Observe stream`() {
        val daemon = daemon()
        val held = daemon.connectClient("agent", holdIdleMs = 60_000)
        assertFailsWith<HeldConnectionObserveException> { daemon.observeAcquire(held.id, Any()) {} }
    }

    @Test
    fun `connections lists each live connection with its hold and devices`() =
        runBlocking {
            val daemon = daemon()
            val held = daemon.connectClient("agent", holdIdleMs = 60_000)
            val device = call { daemon.attachDevice(held.id, "serial-a", options) }
            val observed = daemon.connectClient("test")
            val rows = daemon.connections().associateBy { it.id }
            assertEquals(60_000L, rows.getValue(held.id).holdIdleMs)
            assertEquals(listOf(device), rows.getValue(held.id).attachedDevices)
            assertNull(rows.getValue(observed.id).holdIdleMs)
            assertEquals(emptyList(), rows.getValue(observed.id).attachedDevices)
        }

    @Test
    fun `a gRPC call on a held connection ends with the call, so the connection still expires`() =
        runBlocking {
            val daemon = daemon()
            val held = daemon.connectClient("agent", holdIdleMs = 300)
            val device = call { daemon.attachDevice(held.id, "serial-a", options) }
            val name = InProcessServerBuilder.generateName()
            val server = InProcessServerBuilder.forName(name).addService(DeviceService(daemon)).build().start()
            val channel = InProcessChannelBuilder.forName(name).build()
            try {
                val stub = DeviceServiceGrpc.newBlockingStub(channel)
                val request = DriverLogRequest.newBuilder().setClientConnectionId(held.id).setAttachedDeviceId(device.id).build()
                repeat(4) {
                    delay(150)
                    stub.driverLog(request)
                }
                assertTrue(daemon.clientConnectionExists(held.id))
                awaitGone(daemon, held.id)
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }
}
