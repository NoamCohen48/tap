package io.github.noamcohen48.tap.host.validation

/** `SCREENSHOT` streams a verified PNG blob whose metadata matches the received bytes. */
@DeviceTest
class ScreenshotTest {
    @OnEachDevice
    fun `screenshot is a PNG matching its artifact metadata`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                device.openFixtureMain(session.client)
                val screenshot = session.client.screenshot()
                val pngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
                check(screenshot.png.copyOf(4).contentEquals(pngSignature)) { "Screenshot is not a PNG" }
                check(screenshot.info.byteCount == screenshot.png.size.toLong() && screenshot.info.width > 0) {
                    "Screenshot metadata does not match its bytes: ${screenshot.info}"
                }
                report("screenshot", serial, "bytes" to screenshot.png.size, "size" to "${screenshot.info.width}x${screenshot.info.height}")
            }
        }
}
