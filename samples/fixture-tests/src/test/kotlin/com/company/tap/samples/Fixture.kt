package com.company.tap.samples

import com.company.tap.sdk.App
import com.company.tap.sdk.Device
import com.company.tap.protocol.Selector
import com.company.tap.sdk.resId
import java.nio.file.Path

/** Fixture app facts shared by the samples; a product test suite would own an equivalent file. */
object Fixture {
    const val PACKAGE = "com.company.tap.fixture"
    val apk: Path = Path.of(System.getProperty("tap.fixtureApk"))

    fun id(name: String): Selector = resId(PACKAGE, name)

    /** Installs once per JVM per device, then cold-launches the given activity. */
    fun launch(device: Device, activity: String = ".MainActivity"): App {
        val app = device.app(PACKAGE)
        if (installed.add(device.serial) || !app.isInstalled()) app.install(apk)
        app.coldLaunch(activity)
        return app
    }

    private val installed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
}
