package io.github.noamcohen48.tap.sdk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * Something the device produced that a test may keep: a [Screenshot], a [Hierarchy], a
 * [DeviceInfo] or a [DriverLog]. Every artifact is plain data with its serialized [bytes] and
 * [mediaType], so it can go to a file ([save]), a report, an HTTP upload or an assertion without
 * touching the device again.
 */
interface Artifact {
    /** The serialized form: PNG, UTF-8 XML, UTF-8 JSON or UTF-8 text. */
    val bytes: ByteArray

    /** The IANA media type of [bytes], e.g. `image/png`. */
    val mediaType: String

    /** The usual file extension for [bytes], without the dot, e.g. `png`. */
    val extension: String

    /** Writes [bytes] to [path] (parent directories are created) and returns [path]. */
    fun save(path: Path): Path {
        path.toAbsolutePath().parent?.let(Files::createDirectories)
        return Files.write(path, bytes)
    }
}

/** A bounded device recording ([Device.stopRecording]): MP4, Matroska, or Opus. */
class Recording(override val bytes: ByteArray, override val extension: String) : Artifact {
    init { require(extension in setOf("mp4", "mkv", "opus")) { "Unknown recording format: $extension" } }
    override val mediaType: String get() = when (extension) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        else -> "audio/ogg"
    }

    override fun equals(other: Any?): Boolean = other is Recording && extension == other.extension && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * extension.hashCode() + bytes.contentHashCode()
    override fun toString(): String = "Recording($extension, ${bytes.size} bytes)"
}

/** The encoding of a [Screenshot]'s bytes. */
enum class ImageFormat(
    val mediaType: String,
    val extension: String,
) {
    PNG("image/png", "png"),
}

/**
 * A screen capture ([Device.screenshot]). [bytes] are the encoded image in [format]; [width] and
 * [height] are its size in pixels.
 */
class Screenshot(
    override val bytes: ByteArray,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
) : Artifact {
    override val mediaType: String get() = format.mediaType
    override val extension: String get() = format.extension

    override fun equals(other: Any?): Boolean =
        other is Screenshot && other.format == format && other.width == width && other.height == height && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "Screenshot($format ${width}x$height, ${bytes.size} bytes)"

    internal companion object {
        /** A PNG with its size read from the `IHDR` chunk (0 x 0 when the header is not a PNG's). */
        fun png(bytes: ByteArray): Screenshot {
            val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            val isPng = bytes.size >= 24 && bytes.copyOfRange(0, 4).contentEquals(signature)
            fun int(at: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (bytes[at + i].toInt() and 0xFF) }
            return Screenshot(bytes, ImageFormat.PNG, if (isPng) int(16) else 0, if (isPng) int(20) else 0)
        }
    }
}

/**
 * The accessibility hierarchy as XML ([Device.dumpHierarchy]). Diagnostic only: selectors never
 * use it, so keep it out of assertions.
 */
class Hierarchy(
    val xml: String,
) : Artifact {
    override val bytes: ByteArray get() = xml.encodeToByteArray()
    override val mediaType: String get() = "application/xml"
    override val extension: String get() = "xml"

    override fun equals(other: Any?): Boolean = other is Hierarchy && other.xml == xml

    override fun hashCode(): Int = xml.hashCode()

    override fun toString(): String = "Hierarchy(${xml.length} chars)"
}

/**
 * Facts about the device at the moment of [Device.info]: identity, display geometry and
 * rotation, screen, keyguard and keyboard state, the package owning the focused window and the
 * device conditions ([Device.setAnimations] and the rest). Serialized as JSON.
 */
data class DeviceInfo(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val product: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val displayRotation: DisplayRotation,
    /** The package owning the focused window, when there is one. */
    val currentPackage: String?,
    /** The screen is on (interactive). */
    val screenOn: Boolean,
    /** The keyguard (lock screen) is showing. */
    val keyguardLocked: Boolean,
    /** A PIN, pattern or password is set: Tap cannot dismiss this keyguard. */
    val keyguardSecure: Boolean,
    /** A soft keyboard (any input method's window) is on screen. */
    val keyboardShown: Boolean,
    /** Auto-rotate is on: the sensor turns the display (off while a rotation is frozen). */
    val autoRotate: Boolean,
    /** Window, transition or animator animations run (any of the three scales is not 0). */
    val animationsEnabled: Boolean,
    /** The UI is in night mode (dark theme). */
    val darkMode: Boolean,
    /** The font scale apps see (1.0 = the default size). */
    val fontScale: Float,
    /** The display density apps see, in dpi. */
    val densityDpi: Int,
    /** Airplane mode is on. */
    val airplaneMode: Boolean,
    /** Wi-Fi is switched on (also while airplane mode is on); says nothing about a connection. */
    val wifiEnabled: Boolean,
    /** Mobile data is switched on; false on a device without telephony. */
    val mobileDataEnabled: Boolean,
    /** The device's languages, BCP-47 tags in preference order ([Device.setSystemLocales]). */
    val systemLocales: List<String> = emptyList(),
    /** The screen stays on while plugged in ([Device.setStayAwake]). */
    val stayAwake: Boolean = false,
    /** High-contrast text is on; null when Android does not let Tap read it. */
    val highContrastText: Boolean? = null,
    /** Color inversion is on; null when Android does not let Tap read it. */
    val colorInversion: Boolean? = null,
    /** The system font is bold (API 31+; always false below). */
    val boldText: Boolean = false,
) : Artifact {
    /** [PORTRAIT][Orientation.PORTRAIT] when the display is at least as tall as it is wide. */
    val orientation: Orientation get() = if (displayHeight >= displayWidth) Orientation.PORTRAIT else Orientation.LANDSCAPE

    override val bytes: ByteArray
        get() =
            buildJsonObject {
                put("apiLevel", apiLevel)
                put("manufacturer", manufacturer)
                put("model", model)
                put("product", product)
                put("displayWidth", displayWidth)
                put("displayHeight", displayHeight)
                put("displayRotation", displayRotation.name)
                put("currentPackage", currentPackage)
                put("screenOn", screenOn)
                put("keyguardLocked", keyguardLocked)
                put("keyguardSecure", keyguardSecure)
                put("keyboardShown", keyboardShown)
                put("autoRotate", autoRotate)
                put("animationsEnabled", animationsEnabled)
                put("darkMode", darkMode)
                put("fontScale", fontScale)
                put("densityDpi", densityDpi)
                put("airplaneMode", airplaneMode)
                put("wifiEnabled", wifiEnabled)
                put("mobileDataEnabled", mobileDataEnabled)
                putJsonArray("systemLocales") { systemLocales.forEach(::add) }
                put("stayAwake", stayAwake)
                put("highContrastText", highContrastText)
                put("colorInversion", colorInversion)
                put("boldText", boldText)
            }.toString().encodeToByteArray()
    override val mediaType: String get() = "application/json"
    override val extension: String get() = "json"
}

/** The driver instrumentation's recent output ([Device.driverLog]), one entry per line. */
class DriverLog(
    val lines: List<String>,
) : Artifact {
    /** The lines joined with `\n`. */
    val text: String get() = lines.joinToString("\n")
    override val bytes: ByteArray get() = text.encodeToByteArray()
    override val mediaType: String get() = "text/plain"
    override val extension: String get() = "txt"

    override fun equals(other: Any?): Boolean = other is DriverLog && other.lines == lines

    override fun hashCode(): Int = lines.hashCode()

    override fun toString(): String = "DriverLog(${lines.size} lines)"
}
