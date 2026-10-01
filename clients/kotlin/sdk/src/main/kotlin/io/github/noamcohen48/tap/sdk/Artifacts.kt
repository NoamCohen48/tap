package io.github.noamcohen48.tap.sdk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Static facts about the device plus the package owning the focused window ([Device.info]).
 * Serialized as JSON.
 */
data class DeviceInfo(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    val product: String,
    val displayWidth: Int,
    val displayHeight: Int,
    /** `Surface.ROTATION_*`: 0, 1, 2 or 3 quarter turns. */
    val displayRotation: Int,
    /** The package owning the focused window, when there is one. */
    val currentPackage: String?,
) : Artifact {
    override val bytes: ByteArray
        get() =
            buildJsonObject {
                put("apiLevel", apiLevel)
                put("manufacturer", manufacturer)
                put("model", model)
                put("product", product)
                put("displayWidth", displayWidth)
                put("displayHeight", displayHeight)
                put("displayRotation", displayRotation)
                put("currentPackage", currentPackage)
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
