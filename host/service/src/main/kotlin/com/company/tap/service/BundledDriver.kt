package com.company.tap.service

import com.company.tap.protocol.DRIVER_APK_BUILD_ID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The driver APKs compiled into the service (resources under `com/company/tap/service/driver`), extracted
 * once per service start into the state directory so `adb install` can read them.
 */
class BundledDriver private constructor(val driverApk: Path, val driverTestApk: Path) {
    companion object {
        private const val RESOURCE_DIR = "/com/company/tap/service/driver"

        fun extract(stateDir: Path): BundledDriver? {
            val target = stateDir.resolve("driver").resolve(DRIVER_APK_BUILD_ID)
            val apk = target.resolve("driver.apk")
            val testApk = target.resolve("driver-test.apk")
            for ((name, path) in listOf("driver.apk" to apk, "driver-test.apk" to testApk)) {
                val stream = BundledDriver::class.java.getResourceAsStream("$RESOURCE_DIR/$name") ?: return null
                Files.createDirectories(target)
                stream.use { Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING) }
            }
            return BundledDriver(apk, testApk)
        }
    }
}
