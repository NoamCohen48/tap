package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.DRIVER_APK_BUILD_ID
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DriverApksTest {
    @Test
    fun `builds differing only in the test APK extract to different directories`() {
        val stateDir = Files.createTempDirectory("tap-driver-apks")
        val shell = byteArrayOf(1)
        val first = DriverApks.extract(stateDir, shell, byteArrayOf(2))
        val second = DriverApks.extract(stateDir, shell, byteArrayOf(3))

        assertNotEquals(first.driverTestApk.parent, second.driverTestApk.parent)
        assertContentEquals(byteArrayOf(2), Files.readAllBytes(first.driverTestApk))
        assertContentEquals(byteArrayOf(3), Files.readAllBytes(second.driverTestApk))
        assertEquals(BlobFrames.sha256Hex(shell) to BlobFrames.sha256Hex(byteArrayOf(2)), first.sha256())
        assertEquals(true, first.driverApk.parent.name.startsWith("$DRIVER_APK_BUILD_ID-"))
    }

    @Test
    fun `the bundled digests come from the daemon's bytes, not from the files on disk`() {
        val stateDir = Files.createTempDirectory("tap-driver-apks")
        val apks = DriverApks.extract(stateDir, byteArrayOf(1), byteArrayOf(2))
        Files.write(apks.driverTestApk, byteArrayOf(9))

        assertEquals(BlobFrames.sha256Hex(byteArrayOf(2)), apks.sha256().second)
        // A second start finds the overwritten file and restores it; no temporary files remain.
        DriverApks.extract(stateDir, byteArrayOf(1), byteArrayOf(2))
        assertContentEquals(byteArrayOf(2), Files.readAllBytes(apks.driverTestApk))
        assertEquals(setOf("driver.apk", "driver-test.apk"), apks.driverApk.parent.listDirectoryEntries().map { it.name }.toSet())
    }

    @Test
    fun `an override is hashed from disk at each call`() {
        val dir = Files.createTempDirectory("tap-driver-override")
        val apk = Files.write(dir.resolve("driver.apk"), byteArrayOf(1))
        val testApk = Files.write(dir.resolve("driver-test.apk"), byteArrayOf(2))
        val apks = DriverApks.override(apk, testApk)
        Files.write(testApk, byteArrayOf(3))

        assertEquals(BlobFrames.sha256Hex(byteArrayOf(3)), apks.sha256().second)
    }
}
