package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.host.SavedState
import io.github.noamcohen48.tap.host.StateKey
import java.nio.file.Files

/**
 * The ADB file layer the server's push, pull and add-media use: a pushed file stats and pulls back
 * byte for byte, a photo in `Pictures/Tap` is indexed by the media scanner (`scan_file` on API
 * 29+, the scan broadcast before), and restoring the captured absence removes both and drops the
 * photo from the index.
 */
@DeviceTest
class DeviceFilesTest {
    @OnEachDevice
    fun `files push and pull back and media is indexed then both are removed`(serial: String) =
        deviceTest(serial) { device ->
            val adb = device.adb
            val stamp = System.nanoTime().toString(36)
            val pushed = "/data/local/tmp/tap-validation-$stamp.bin"
            val photoName = "tap-validation-$stamp.png"
            val photo = "/sdcard/Pictures/Tap/$photoName"
            val local = Files.createTempFile("tap-files", ".bin")
            val pulled = Files.createTempFile("tap-files", ".pulled")
            val saved = listOf(SavedState(StateKey.DeviceFile(pushed).id, null), SavedState(StateKey.DeviceFile(photo, media = true).id, null))
            try {
                val payload = ByteArray(3 shl 20) { (it % 251).toByte() }
                Files.write(local, payload)
                adb.push(serial, local, pushed)
                val info = adb.fileInfo(serial, pushed)
                check(info?.regular == true && info.sizeBytes == payload.size.toLong()) { "Pushed file reads back as $info" }
                adb.pull(serial, pushed, pulled)
                check(Files.readAllBytes(pulled).contentEquals(payload)) { "Pulled bytes differ" }

                Files.write(local, PNG)
                adb.makeDirectories(serial, "/sdcard/Pictures/Tap")
                adb.push(serial, local, photo)
                adb.scanMedia(serial, photo)
                check(adb.mediaIndexed(serial, "Pictures/Tap", photoName)) { "The media scanner did not index $photo" }
            } finally {
                adb.restoreState(serial, saved)
                Files.deleteIfExists(local)
                Files.deleteIfExists(pulled)
            }
            check(adb.fileInfo(serial, pushed) == null) { "$pushed is still on the device" }
            check(adb.fileInfo(serial, photo) == null) { "$photo is still on the device" }
            check(!adb.mediaIndexed(serial, "Pictures/Tap", photoName)) { "$photoName is still in the media index" }
            report("device_files", serial, "push_pull" to "ok", "media_indexed" to "ok", "removed" to "ok", "api" to device.apiLevel)
        }

    private companion object {
        /** A valid 1x1 PNG: the media scanner skips files it cannot decode. */
        val PNG: ByteArray =
            java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC")
    }
}
