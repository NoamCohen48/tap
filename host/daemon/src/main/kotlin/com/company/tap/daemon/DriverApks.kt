package com.company.tap.daemon

import com.company.tap.protocol.DRIVER_APK_BUILD_ID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The driver APK pair attach installs: either the APKs compiled into the daemon (resources under
 * `com/company/tap/daemon/driver`, extracted once per daemon start into the state directory so
 * `adb install` can read them) or an override given to `tap serve --driver-apk/--driver-test-apk`.
 */
class DriverApks private constructor(
    val driverApk: Path,
    val driverTestApk: Path,
    /** True for the APKs built with this daemon; an override is trusted as given. */
    val bundled: Boolean,
) {
    companion object {
        private const val RESOURCE_DIR = "/com/company/tap/daemon/driver"

        /** The bundled pair, or null when this build carries none. */
        fun extractBundled(stateDir: Path): DriverApks? {
            val target = stateDir.resolve("driver").resolve(DRIVER_APK_BUILD_ID)
            val apk = target.resolve("driver.apk")
            val testApk = target.resolve("driver-test.apk")
            for ((name, path) in listOf("driver.apk" to apk, "driver-test.apk" to testApk)) {
                val stream = DriverApks::class.java.getResourceAsStream("$RESOURCE_DIR/$name") ?: return null
                Files.createDirectories(target)
                stream.use { Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING) }
            }
            return DriverApks(apk, testApk, bundled = true)
        }

        /** An explicit pair; both files must exist. */
        fun override(
            driverApk: Path,
            driverTestApk: Path,
        ): DriverApks {
            for (path in listOf(driverApk, driverTestApk)) require(Files.isRegularFile(path)) { "driver APK $path does not exist" }
            return DriverApks(driverApk.toAbsolutePath(), driverTestApk.toAbsolutePath(), bundled = false)
        }
    }
}
