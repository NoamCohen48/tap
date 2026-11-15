package io.github.noamcohen48.tap.host

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RotationReadbackTest {
    private val serial = "phone"
    private val saved =
        listOf(
            SavedState(StateKey.ACCELEROMETER_ROTATION, "0"),
            SavedState(StateKey.USER_ROTATION, "1"),
            SavedState(StateKey.DEVICE_STATE_ROTATION_LOCK, null),
        )

    private class RotationAdb : Adb("unused") {
        val values = mutableMapOf<String, String?>()
        val writes = mutableListOf<SavedState>()
        val observations = mutableListOf<List<String>>()
        var lastWrite = 0L
        var typedReads = 0
        var onObservation: ((List<String>) -> Map<String, String?>)? = null

        override suspend fun writeState(
            serial: String,
            key: String,
            value: String?,
        ) {
            writes += SavedState(key, value)
            values[key] = value
            lastWrite = System.nanoTime()
        }

        internal override suspend fun readRotationSettings(
            serial: String,
            keys: List<String>,
        ): Map<String, String?> {
            if (keys.isEmpty()) return emptyMap()
            observations += keys.toList()
            return onObservation?.invoke(keys) ?: keys.associateWith { values[it] }
        }

        override suspend fun readState(
            serial: String,
            key: String,
        ): String? {
            check(key !in StateKey.ROTATION) { "Rotation must use the batch reader" }
            typedReads++
            return values[key]
        }
    }

    @Test
    fun `locked restore keeps the original write ordering and full stability interval`() =
        runBlocking {
            val adb = RotationAdb()
            val started = System.nanoTime()
            adb.restoreState(serial, saved)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue(elapsed >= ROTATION_SETTLE_STABLE_MS, "Only waited $elapsed ms")
            assertEquals(listOf(saved.first()) + saved.asReversed(), adb.writes)
            assertTrue(adb.observations.size >= 2) // A settle observation and a separate final readback, even after scheduler delays.
            assertTrue(adb.observations.all { it == saved.map { entry -> entry.key } })
            assertEquals(0, adb.typedReads)
        }

    @Test
    fun `a late writer still rewrites every rotation setting and restarts the stable interval`() =
        runBlocking {
            val adb = RotationAdb()
            var changedOnce = false
            adb.onObservation = { keys ->
                if (!changedOnce) {
                    changedOnce = true
                    adb.values[StateKey.USER_ROTATION] = "3"
                }
                keys.associateWith { adb.values[it] }
            }
            adb.restoreState(serial, saved)
            val stableMs = (System.nanoTime() - adb.lastWrite) / 1_000_000
            assertTrue(stableMs >= ROTATION_SETTLE_STABLE_MS, "Stable for only $stableMs ms")
            assertEquals(listOf(saved.first()) + saved.asReversed() + saved.asReversed(), adb.writes)
            assertEquals("1", adb.values[StateKey.USER_ROTATION])
        }

    @Test
    fun `final verification is fresh and cannot reuse the successful settle snapshot`() =
        runBlocking {
            val adb = RotationAdb()
            var stableObserved = false
            adb.onObservation = { keys ->
                if (stableObserved) adb.values[StateKey.USER_ROTATION] = "2"
                if ((System.nanoTime() - adb.lastWrite) / 1_000_000 >= ROTATION_SETTLE_STABLE_MS) stableObserved = true
                keys.associateWith { adb.values[it] }
            }
            val error = assertFailsWith<DeviceSettingException> { adb.restoreState(serial, saved) }
            assertTrue(error.message.orEmpty().contains("expected 1"), error.message)
            assertTrue(stableObserved)
        }

    @Test
    fun `non rotation cleanup retains its typed reads and writes without a rotation batch`() =
        runBlocking {
            val adb = RotationAdb()
            val entry = SavedState(StateKey.FONT_SCALE, "1.0")
            adb.restoreState(serial, listOf(entry))
            assertEquals(listOf(entry), adb.writes)
            assertEquals(1, adb.typedReads)
            assertTrue(adb.observations.isEmpty())
        }

    @Test
    fun `read only matching distinguishes absent keys and changed values without writing`() =
        runBlocking {
            val adb = RotationAdb()
            adb.values.putAll(saved.associate { it.key to it.value })
            assertTrue(adb.rotationSettingsHold(serial, saved))
            adb.values[StateKey.USER_ROTATION] = "0"
            assertTrue(!adb.rotationSettingsHold(serial, saved))
            adb.onObservation = { emptyMap() }
            assertFailsWith<NoSuchElementException> { adb.rotationSettingsHold(serial, saved) }
            assertTrue(adb.writes.isEmpty())
        }
}
