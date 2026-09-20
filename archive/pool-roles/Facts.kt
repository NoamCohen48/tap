// Archived from host/service/.../TapService.kt (commit 4213140): Facts + factsOf (getprop once per serial).

/** Static facts gathered once per serial, for clients choosing devices from the inventory. */
data class Facts(val serial: String, val apiLevel: Int, val manufacturer: String, val model: String, val emulator: Boolean)

    private fun factsOf(serial: String): Facts = facts.getOrPut(serial) {
        val adb = config.adb
        fun prop(name: String) = runCatching { adb.run(serial, "shell", "getprop", name).trim() }.getOrDefault("")
        Facts(
            serial = serial,
            apiLevel = prop("ro.build.version.sdk").toIntOrNull() ?: 0,
            manufacturer = prop("ro.product.manufacturer"),
            model = prop("ro.product.model"),
            emulator = serial.startsWith("emulator-") || prop("ro.kernel.qemu") == "1" || prop("ro.boot.qemu") == "1",
        )
    }
