package io.github.noamcohen48.tap.host

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** A refused capture or malformed external bitstream, not an AUT/driver failure. */
open class VideoException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Cleanup is unproven: the caller must gate new producers on this serial, not retry blindly. */
class VideoCleanupException(
    message: String,
    cause: Throwable,
) : VideoException(message, cause)

sealed interface VideoPacket {
    data class Session(
        val width: Int,
        val height: Int,
    ) : VideoPacket

    /** H.264 Annex B access unit (or SPS/PPS when [configuration]); bytes are immutable after receipt. */
    data class Data(
        val bytes: ByteArray,
        val ptsUs: Long,
        val key: Boolean,
        val configuration: Boolean,
    ) : VideoPacket
}

/**
 * Independently implemented reader for scrcpy 4.1's video-only socket, not copied code.
 * Protocol reference: Genymobile/scrcpy 2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0,
 * server/.../device/Streamer.java. Older releases have different flags and headers: reject them.
 * The caller consumes the forward handshake's dummy byte before constructing this reader.
 */
class ScrcpyVideoProtocol(
    input: InputStream,
) {
    private val input = DataInputStream(input)
    private var session = false

    init {
        if (this.input.readInt() != 0x68323634) throw VideoException("Capture did not negotiate H.264")
    }

    fun next(): VideoPacket {
        val flags = input.readInt()
        val middle = input.readInt()
        val size = input.readInt()
        if (flags < 0) {
            if ((flags and 0x7ffffffe) != 0 || middle !in 1..8192 || size !in 1..8192) {
                throw VideoException("Invalid video session header")
            }
            session = true
            return VideoPacket.Session(middle, size)
        }
        if (!session) throw VideoException("Video packet arrived before session metadata")
        if (size !in 1..MAX_PACKET_BYTES) throw VideoException("Invalid video packet size: $size")
        val configuration = flags and 0x40000000 != 0
        val key = flags and 0x20000000 != 0
        val ptsUs = ((flags.toLong() and 0x1fffffff) shl 32) or (middle.toLong() and 0xffffffffL)
        if (configuration && (key || ptsUs != 0L)) throw VideoException("Invalid codec configuration flags")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return VideoPacket.Data(bytes, ptsUs, key, configuration)
    }

    companion object {
        const val MAX_PACKET_BYTES = 1024 * 1024
    }
}

/** Fixed, passive server invocation. Keeping its argv below 256 bytes avoids a tested Samsung native crash. */
/** The scrcpy server version [ScrcpyVideoProtocol] speaks; the server refuses any other client version. */
const val SCRCPY_SERVER_VERSION = "4.1"

internal fun videoServerArguments(scid: String): List<String> {
    require(scid.matches(Regex("[0-7][0-9a-f]{7}")))
    return listOf(
        "app_process",
        "/",
        "--nice-name=tapv-$scid",
        "com.genymobile.scrcpy.Server",
        SCRCPY_SERVER_VERSION,
        "scid=$scid",
        "tunnel_forward=true",
        "audio=false",
        "control=false",
        "power_on=false",
        "cleanup=false",
        "max_size=1024",
        "max_fps=15",
        "video_bit_rate=2000000",
        "video_codec_options=i-frame-interval=1",
    ).also { require(it.joinToString(" ").toByteArray(Charsets.UTF_8).size < 256) }
}

internal fun videoServerPath(scid: String): String {
    videoServerArguments(scid) // validates the namespace before it enters an Android shell
    return "/data/local/tmp/tap-video-$scid.jar"
}
