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

/** Independent, bounded fan-out. No owner/session methods, journals or liveness refreshes. */
internal class SharedVideo(
    private val factory: (String) -> VideoSource,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val producers = HashMap<String, Producer>()
    private val gated = HashSet<String>()
    private var closed = false

    private class Reader {
        val channel = Channel<VideoResponse>(128)
        var bytes = 0L
    }

    private inner class Producer(
        val serial: String,
        val source: VideoSource,
    ) {
        val readers = HashSet<Reader>()
        val frames = ArrayDeque<VideoResponse>()
        var bytes = 0L
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
                if (existing != null && existing.readers.size >= 8) throw VideoException("Video reader limit reached")
                if (existing == null && producers.size >= 4) throw VideoException("Video producer limit reached")
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
                producer.frames.clear()
                producer.bytes = 0
                return@synchronized
            }

            is VideoPacket.Data -> {
                if (packet.configuration) {
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
                    if (producer.header == null || (producer.frames.isEmpty() && !packet.key)) return@synchronized
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
                    producer.frames.addLast(update)
                    producer.bytes += packet.bytes.size
                    // Discard whole GOPs; the retained window must always start at a key frame.
                    while (producer.frames.size > 1 && (
                            producer.bytes > 32L * 1024 * 1024 ||
                                sample.receivedMonotonicNs -
                                producer.frames
                                    .first()
                                    .frame.receivedMonotonicNs > 120_000_000_000L
                        )
                    ) {
                        producer.bytes -=
                            producer.frames
                                .removeFirst()
                                .frame.data
                                .size()
                        while (producer.frames.isNotEmpty() &&
                            !producer.frames
                                .first()
                                .frame.keyFrame
                        ) {
                            producer.bytes -=
                                producer.frames
                                    .removeFirst()
                                    .frame.data
                                    .size()
                        }
                    }
                }
            }
        }
        val size = update.frame.data.size()
        val slow = producer.readers.filter { reader ->
            if (reader.bytes + size > 4L * 1024 * 1024 || reader.channel.trySend(update).isFailure) true
            else { reader.bytes += size; false }
        }
        slow.forEach {
            it.channel.close(EventReaderOverflowException())
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
