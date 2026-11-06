package io.github.noamcohen48.tap.samples

import io.github.noamcohen48.tap.junit5.TapTest
import io.github.noamcohen48.tap.junit5.tapTest
import io.github.noamcohen48.tap.sdk.AppDataException
import io.github.noamcohen48.tap.sdk.Device
import io.github.noamcohen48.tap.sdk.text
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TapTest
class AppDataTest {
    @Test
    fun stoppedPrivateFilesAndWalBundleRoundTrip(device: Device) =
        tapTest {
            val app = Fixture.launch(device)
            app.forceStop()
            app.launch(".PrivateDataActivity", extras = mapOf("seed" to true))
            app.await(text("database value: 41")).visible()
            assertTrue(app.data.capabilities().available)
            assertEquals("APP_RUNNING", assertFailsWith<AppDataException> { app.data.list() }.detail)
            app.forceStop()
            assertContentEquals(ByteArray(1024) { it.toByte() }, app.data.read("files/binary.bin"))
            assertTrue(
                app.data
                    .list("files")
                    .first { it.name == "link" }
                    .isSymlink,
            )
            assertEquals("SYMLINK", assertFailsWith<AppDataException> { app.data.read("files/link") }.detail)
            val path = "files/é ' \$literal.bin"
            val payload = byteArrayOf(0, 1, -1, 0, 42)
            app.data.write(path, payload)
            assertContentEquals(payload, app.data.read(path))
            assertEquals("EXISTS", assertFailsWith<AppDataException> { app.data.write(path, payload) }.detail)
            app.data.delete(path)
            assertFalse(app.isRunning())
            val bundle = app.data.exportDatabase("tap-debug.db")
            assertTrue(bundle.database.isNotEmpty())
            assertTrue(bundle.wal?.isNotEmpty() == true, "Force-stop must leave committed WAL for this fixture")
            app.clearData()
            app.data.importDatabase(bundle)
            assertFalse(app.isRunning())
            assertTrue(app.data.list("databases").none { it.name.endsWith("-shm") })
            app.launch(".PrivateDataActivity")
            app.await(text("database value: 41")).visible()
            app.forceStop()
            assertEquals("EXISTS", assertFailsWith<AppDataException> { app.data.importDatabase(bundle) }.detail)
            app.data.importDatabase(bundle, overwrite = true)
            app.launch(".PrivateDataActivity")
            app.await(text("database value: 41")).visible()
        }
}
