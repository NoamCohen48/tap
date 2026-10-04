package io.github.noamcohen48.tap.daemon.core

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.VideoFrame
import io.github.noamcohen48.tap.api.v1.VideoHeader
import io.github.noamcohen48.tap.host.VideoCleanupException
import io.github.noamcohen48.tap.host.VideoException
import io.github.noamcohen48.tap.host.VideoPacket
import io.github.noamcohen48.tap.host.VideoSample
import io.github.noamcohen48.tap.host.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import io.github.noamcohen48.tap.api.v1.WatchVideoResponse as VideoResponse

/** A video reader fell [VIDEO_READER_UPDATES] updates or [VIDEO_READER_BYTES] behind and was dropped. */
class VideoReaderOverflowException : RuntimeException("video reader fell behind; reconnect to resume from the latest key frame")

/** Producers at once: each is a scrcpy server encoding on its device. */
const val VIDEO_MAX_PRODUCERS = 4

/** Readers sharing one producer. */
const val VIDEO_MAX_READERS = 8

/** Queued updates per reader before it is dropped as too slow. */
const val VIDEO_READER_UPDATES = 128

/** Queued frame bytes per reader before it is dropped as too slow. */
const val VIDEO_READER_BYTES = 4L * 1024 * 1024

/**
 * The largest current GOP a producer keeps for readers that join mid-stream. A longer one is
 * dropped: a reader joining then waits for the next key frame.
 */
const val VIDEO_JOIN_BYTES = 16L * 1024 * 1024

/**
 * Independent, bounded fan-out. No owner/session methods, journals or liveness refreshes.
 * Keeps no history: only the header and the current GOP (frames since the latest key frame),
 * so a reader that joins can decode at once. Looking back is the reader's business.
 */
internal class SharedVideo(
    private val factory: (String) -> VideoSource,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val producers = HashMap<String, Producer>()
    private val gated = HashSet<String>()
    private var closed = false

    private class Reader {
        val channel = Channel<VideoResponse>(VIDEO_READER_UPDATES)
        var bytes = 0L
    }

    private inner class Producer(
        val serial: String,
        val source: VideoSource,
    ) {
        val readers = HashSet<Reader>()
        // The current GOP: empty, or a key frame and the frames after it.
        val frames = ArrayDeque<VideoResponse>()
        var bytes = 0L

        // A key frame has been sent since the header; frames before one are not decodable.
        var started = false
        var width = 0
        var height = 0
        var streamId = UUID.randomUUID().toString()
        var header: VideoResponse? = null
        var seq = 0L
        var stopping = false
        lateinit var job: Job
    }

    fun watch(serial: String): Flow<VideoResponse> =
        flow {
            val reader = Reader()
            val producer: Producer
            val backlog: List<VideoResponse>
            synchronized(lock) {
                if (closed) throw VideoException("Video service is closing")
                if (serial in gated) throw VideoException("Video cleanup unproven on $serial; reconcile before restarting")
                val existing = producers[serial]
                if (existing?.stopping == true) throw VideoException("Video producer is stopping; retry shortly")
                if (existing != null && existing.readers.size >= VIDEO_MAX_READERS) throw VideoException("Video reader limit reached")
                if (existing == null && producers.size >= VIDEO_MAX_PRODUCERS) throw VideoException("Video producer limit reached")
                producer = existing ?: Producer(serial, factory(serial)).also { created ->
                    producers[serial] = created
                    created.job =
                        scope.launch {
                            try {
                                created.source.run { publish(created, it) }
                            } catch (error: Throwable) {
                                synchronized(lock) {
                                    if (error is VideoCleanupException) gated.add(serial)
                                    created.readers.forEach { it.channel.close(error) }
                                }
                            } finally {
                                synchronized(lock) {
                                    created.readers.forEach { it.channel.close() }
                                    if (producers[serial] === created) producers.remove(serial)
                                }
                            }
                        }
                }
                backlog = listOfNotNull(producer.header) + producer.frames.toList()
                producer.readers.add(reader)
            }
            try {
                backlog.forEach { emit(it) }
                for (update in reader.channel) {
                    synchronized(lock) { reader.bytes -= update.frame.data.size() }
                    emit(update)
                }
            } finally {
                synchronized(lock) {
                    producer.readers.remove(reader)
                    reader.channel.cancel()
                    if (producer.readers.isEmpty()) {
                        producer.stopping = true
                        producer.source.interrupt()
                        producer.job.cancel()
                    }
                }
            }
        }

    private fun publish(
        producer: Producer,
        sample: VideoSample,
    ) = synchronized(lock) {
        if (producer.stopping) return@synchronized
        val update: VideoResponse
        when (val packet = sample.packet) {
            is VideoPacket.Session -> {
                producer.width = packet.width
                producer.height = packet.height
                producer.streamId = UUID.randomUUID().toString()
                producer.header = null
                producer.started = false
                producer.frames.clear()
                producer.bytes = 0
                return@synchronized
            }

            is VideoPacket.Data -> {
                if (packet.configuration) {
                    producer.started = false
                    producer.frames.clear()
                    producer.bytes = 0
                    update =
                        VideoResponse
                            .newBuilder()
                            .setHeader(
                                VideoHeader
                                    .newBuilder()
                                    .setStreamId(producer.streamId)
                                    .setClockId(sample.clockId)
                                    .setWidth(producer.width)
                                    .setHeight(producer.height)
                                    .setConfiguration(ByteString.copyFrom(packet.bytes)),
                            ).build()
                    producer.header = update
                } else {
                    if (producer.header == null || !(producer.started || packet.key)) return@synchronized
                    producer.started = true
                    update =
                        VideoResponse
                            .newBuilder()
                            .setFrame(
                                VideoFrame
                                    .newBuilder()
                                    .setSeq(++producer.seq)
                                    .setPtsUs(packet.ptsUs)
                                    .setKeyFrame(packet.key)
                                    .setData(ByteString.copyFrom(packet.bytes))
                                    .setReceivedMonotonicNs(sample.receivedMonotonicNs)
                                    .setReceivedEpochMs(sample.receivedEpochMs),
                            ).build()
                    // A key frame starts a new GOP; the kept frames always start at a key frame.
                    if (packet.key) {
                        producer.frames.clear()
                        producer.bytes = 0
                    }
                    if (packet.key || producer.frames.isNotEmpty()) {
                        producer.frames.addLast(update)
                        producer.bytes += packet.bytes.size
                        if (producer.bytes > VIDEO_JOIN_BYTES) {
                            producer.frames.clear()
                            producer.bytes = 0
                        }
                    }
                }
            }
        }
        val size = update.frame.data.size()
        val slow =
            producer.readers.filter { reader ->
                if (reader.bytes + size > VIDEO_READER_BYTES || reader.channel.trySend(update).isFailure) {
                    true
                } else {
                    reader.bytes += size
                    false
                }
            }
        slow.forEach {
            it.channel.close(VideoReaderOverflowException())
            producer.readers.remove(it)
        }
        if (producer.readers.isEmpty()) {
            producer.stopping = true
            producer.source.interrupt()
            producer.job.cancel()
        }
    }

    fun stop() =
        synchronized(lock) {
            closed = true
            producers.values.forEach {
                it.stopping = true
                it.source.interrupt()
                it.job.cancel()
            }
        }

    suspend fun awaitStop(timeoutMs: Long) {
        val jobs = synchronized(lock) { producers.values.map { it.job } }
        if (timeoutMs > 0) withTimeoutOrNull(timeoutMs) { jobs.joinAll() }
        scope.cancel()
    }
}
