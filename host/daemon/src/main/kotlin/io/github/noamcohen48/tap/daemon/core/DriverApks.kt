package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.DRIVER_APK_BUILD_ID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The driver APK pair attach installs: either the APKs compiled into the daemon (resources under
 * `io/github/noamcohen48/tap/daemon/driver`, extracted at daemon start into the state directory so
 * `adb install` can read them) or an override given to `tap serve --driver-apk/--driver-test-apk`.
 *
 * [sha256] identifies the exact build: attach compares it with the APKs installed on the device,
 * so a different build of the same version (another daemon, an emulator snapshot restored with
 * an older driver) is reinstalled rather than trusted.
 */
class DriverApks private constructor(
    val driverApk: Path,
    val driverTestApk: Path,
    /** The bundled pair's digests, taken from the daemon's own resources; null for an override. */
    private val bundledSha256: Pair<String, String>?,
) {
    /**
     * The SHA-256 of [driverApk] and [driverTestApk]. The bundled pair's never changes; an
     * override's is read from disk at each call, because it may be rebuilt while the daemon runs.
     */
    fun sha256(): Pair<String, String> =
        bundledSha256 ?: (BlobFrames.sha256Hex(Files.readAllBytes(driverApk)) to BlobFrames.sha256Hex(Files.readAllBytes(driverTestApk)))

    companion object {
        private const val RESOURCE_DIR = "/io/github/noamcohen48/tap/daemon/driver"

        /**
         * The bundled pair, or null when this build carries none. The directory is named by the
         * version and a digest of both APKs (the driver code lives in the test APK), so daemons of
         * different builds sharing a state directory never write to each other's files. A file
         * already there with the right digest is left alone.
         */
        fun extractBundled(stateDir: Path): DriverApks? {
            val apk = resource("driver.apk") ?: return null
            val testApk = resource("driver-test.apk") ?: return null
            return extract(stateDir, apk, testApk)
        }

        internal fun extract(
            stateDir: Path,
            apk: ByteArray,
            testApk: ByteArray,
        ): DriverApks {
            val digests = BlobFrames.sha256Hex(apk) to BlobFrames.sha256Hex(testApk)
            val pair = BlobFrames.sha256Hex((digests.first + digests.second).toByteArray())
            val target = stateDir.resolve("driver").resolve("$DRIVER_APK_BUILD_ID-${pair.take(12)}")
            Files.createDirectories(target)
            val apkPath = target.resolve("driver.apk")
            val testApkPath = target.resolve("driver-test.apk")
            write(apk, digests.first, apkPath)
            write(testApk, digests.second, testApkPath)
            return DriverApks(apkPath, testApkPath, digests)
        }

        /** An explicit pair; both files must exist. */
        fun override(
            driverApk: Path,
            driverTestApk: Path,
        ): DriverApks {
            for (path in listOf(driverApk, driverTestApk)) require(Files.isRegularFile(path)) { "driver APK $path does not exist" }
            return DriverApks(driverApk.toAbsolutePath(), driverTestApk.toAbsolutePath(), null)
        }

        private fun write(
            bytes: ByteArray,
            sha256: String,
            path: Path,
        ) {
            if (Files.isRegularFile(path) && BlobFrames.sha256Hex(Files.readAllBytes(path)) == sha256) return
            val partial = Files.createTempFile(path.parent, path.fileName.toString(), ".partial")
            try {
                Files.write(partial, bytes)
                Files.move(partial, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(partial)
            }
        }

        private fun resource(name: String): ByteArray? = DriverApks::class.java.getResourceAsStream("$RESOURCE_DIR/$name")?.use { it.readBytes() }
    }
}
