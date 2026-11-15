package io.github.noamcohen48.tap.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DriverRuntimeCacheTest {
    @TempDir lateinit var root: Path
    private val serial = "phone"

    private fun record() =
        SessionJournal(
            state = JournalState.CLOSED,
            serial = serial,
            bootId = "boot",
            sessionId = "old",
            generation = 7,
            devicePort = DEVICE_PORT,
            hostPort = 41321,
            driverPid = 4242,
            driverStartToken = "token",
            driverInstanceId = "instance",
            runtimeIdle = true,
        )

    private fun store() = SessionJournalStore(root, serial)

    private fun running() =
        RunningInstrumentation(
            FakeProcess(exitDelayMs = FakeProcess.NEVER),
            StringBuilder(),
            CompletableDeferred(Unit),
            DEVICE_PORT,
            "instance",
            CoroutineScope(SupervisorJob()),
        )

    private class RuntimeAdb : Adb() {
        var present = true
        var token = "token"
        var stopped = 0
        var forward = true
        var failStop: Exception? = null
        val rotation = StateKey.ROTATION.zip(listOf("1", "0", null)).toMap().toMutableMap()

        override suspend fun readState(serial: String, key: String): String? = rotation.getValue(key)

        internal override suspend fun runtimeObservation(serial: String, pid: Int, rotationKeys: List<String>) =
            RuntimeObservation(rotationKeys.map { SavedState(it, readState(serial, it)) }, processStat(serial, pid))

        override suspend fun bootId(serial: String) = "boot"

        override suspend fun processIds(
            serial: String,
            packageName: String,
        ) = if (present) listOf(4242) else emptyList()

        override suspend fun processStat(
            serial: String,
            pid: Int,
            timeoutMs: Long,
        ) = if (present) ProcessStat.Live(token) else ProcessStat.Gone

        override suspend fun forwards(serial: String) = if (forward) listOf(Forwarding(serial, 41321, DEVICE_PORT)) else emptyList()

        override suspend fun removeForward(
            serial: String,
            hostPort: Int,
        ) {
            forward = false
        }

        override suspend fun releaseDriverNotificationListener(serial: String) = Unit

        override suspend fun forceStop(
            serial: String,
            packageName: String,
        ) {
            stopped++
            failStop?.let { throw it }
            present = false
        }
    }

    @Test
    fun `only a clean fully identified empty journal can retain a runtime`() {
        val valid = record()
        store().write(valid)
        assertEquals(valid, store().read())
        for (bad in listOf(
            valid.copy(state = JournalState.READY),
            valid.copy(hostPort = null),
            valid.copy(driverInstanceId = null),
            valid.copy(savedState = listOf(SavedState(StateKey.FONT_SCALE, "1.0"))),
        )) {
            assertFailsWith<IllegalArgumentException> { store().write(bad) }
        }
        assertFalse("secret" in Files.readString(store().file))
    }

    @Test
    fun `a verified idle runtime is clean to doctor but a foreign process is not`() =
        runBlocking {
            store().write(record())
            val adb = RuntimeAdb()
            assertSame(DeviceDoctor.Outcome.Clean, DeviceDoctor.check(adb, root, serial, fix = false))
            assertSame(DeviceDoctor.Outcome.Clean, DeviceDoctor.check(adb, root, serial, fix = true))
            assertEquals(0, adb.stopped)
            adb.token = "new-process"
            assertIs<DeviceDoctor.Outcome.Findings>(DeviceDoctor.check(adb, root, serial, fix = false))
            assertEquals(0, adb.stopped)
        }

    @Test
    fun `boot pid instance forward and expiry mismatches all refuse reuse`() =
        runBlocking {
            val adb = RuntimeAdb()
            val cache = DriverRuntimeCache(adb, root, 60_000)
            val old = record()
            store().write(old)
            val entry =
                IdleDriverRuntime(old, running(), cache.nextSession(old.generation), System.nanoTime() + 60_000_000_000L, emptyList())
            assertTrue(cache.offer(entry))
            try {
                assertTrue(cache.verify(entry, old, "boot"))
                assertFalse(cache.verify(entry, old, "after-reboot"))
                assertFalse(cache.verify(entry, old.copy(driverInstanceId = "foreign"), "boot"))
                assertFalse(cache.verify(entry, old.copy(hostPort = 41322), "boot"))
                assertFalse(
                    cache.verify(IdleDriverRuntime(old, entry.running, entry.next, System.nanoTime() - 1, emptyList()), old, "boot"),
                )
                adb.token = "reused-pid"
                assertFalse(cache.verify(entry, old, "boot"))
                adb.token = "token"
                adb.present = false
                assertFalse(cache.verify(entry, old, "boot"))
                adb.present = true
                adb.forward = false
                assertFalse(cache.verify(entry, old, "boot"))
                assertEquals(0, adb.stopped)
            } finally {
                adb.token = "token"
                adb.present = true
                adb.forward = true
                cache.close()
            }
        }

    @Test
    fun `batched rotation observations still reject each changed baseline before reuse`() =
        runBlocking {
            val adb = RuntimeAdb()
            val cache = DriverRuntimeCache(adb, root, 60_000)
            val old = record()
            val baseline = StateKey.ROTATION.map { SavedState(it, adb.rotation[it]) }
            val entry = IdleDriverRuntime(old, running(), cache.nextSession(old.generation), System.nanoTime() + 60_000_000_000L, baseline)
            try {
                assertTrue(cache.verify(entry, old, "boot"))
                for (saved in baseline) {
                    adb.rotation[saved.key] = "changed"
                    assertFalse(cache.verify(entry, old, "boot"), saved.key)
                    adb.rotation[saved.key] = saved.value
                }
                assertTrue(cache.verify(entry, old, "boot"))
                assertEquals(0, adb.stopped)
            } finally {
                entry.running.process.destroyForcibly()
                cache.close()
            }
        }

    @Test
    fun `eviction uses the remaining absolute lifetime rather than restarting its timer`() =
        runBlocking {
            val adb = RuntimeAdb()
            val cache = DriverRuntimeCache(adb, root, 60_000)
            val old = record()
            store().write(old)
            val entry =
                IdleDriverRuntime(old, running(), cache.nextSession(old.generation), System.nanoTime() + 1_000_000_000L, emptyList())
            try {
                assertTrue(cache.offer(entry))
                withTimeout(4_000) {
                    while (store().read()?.runtimeIdle != false) delay(10)
                }
                assertEquals(1, adb.stopped)
                assertFalse(adb.forward)
                assertFalse(entry.running.process.isAlive)
            } finally {
                cache.close()
            }
        }

    @Test
    fun `stale eviction never changes a newer generation or stops its process`() =
        runBlocking {
            val adb = RuntimeAdb()
            val cache = DriverRuntimeCache(adb, root, 60_000)
            val old = record()
            store().write(old)
            val entry =
                IdleDriverRuntime(old, running(), cache.nextSession(old.generation), System.nanoTime() + 60_000_000_000L, emptyList())
            assertTrue(cache.verify(entry, old, "boot"))
            assertTrue(cache.offer(entry))
            val newer = old.copy(state = JournalState.READY, generation = 8, sessionId = "new", runtimeIdle = false)
            store().write(newer)
            cache.close()
            assertEquals(0, adb.stopped)
            assertTrue(adb.forward)
            assertEquals(newer, store().read())
            assertFalse(entry.running.process.isAlive)
        }

    @Test
    fun `eviction respects an active serial lock and clears the exact forward once free`() =
        runBlocking {
            val adb = RuntimeAdb()
            val cache = DriverRuntimeCache(adb, root, 60_000)
            val old = record()
            store().write(old)
            val entry =
                IdleDriverRuntime(old, running(), cache.nextSession(old.generation), System.nanoTime() + 60_000_000_000L, emptyList())
            assertTrue(cache.offer(entry))
            store().acquireLease(0).use { cache.close() }
            assertEquals(0, adb.stopped)
            assertTrue(entry.running.process.isAlive)
            cache.close()
            assertEquals(1, adb.stopped)
            assertFalse(adb.forward)
            assertEquals(false, store().read()?.runtimeIdle)
            assertFalse(entry.running.process.isAlive)
        }

    @Test
    fun `unproven eviction is quarantined rather than made reusable`() =
        runBlocking {
            val adb = RuntimeAdb().apply { failStop = AdbReapUncertainException("unreaped", listOf("adb", "-s", serial), serial) }
            val cache = DriverRuntimeCache(adb, root, 60_000)
            val old = record()
            store().write(old)
            val entry =
                IdleDriverRuntime(old, running(), cache.nextSession(old.generation), System.nanoTime() + 60_000_000_000L, emptyList())
            try {
                assertTrue(cache.offer(entry))
                cache.close()
                assertEquals(JournalState.QUARANTINED, store().read()?.state)
                assertEquals(false, store().read()?.runtimeIdle)
            } finally {
                entry.running.process.destroyForcibly()
                entry.running.drainScope.coroutineContext[kotlinx.coroutines.Job]
                    ?.cancel()
            }
        }
}
