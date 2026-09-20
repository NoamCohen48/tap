package com.company.tap.host

import com.company.tap.protocol.Authentication
import com.company.tap.protocol.ArtifactInfo
import com.company.tap.protocol.AuthenticationResult
import com.company.tap.protocol.BlobEnd
import com.company.tap.protocol.BlobStart
import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.Challenge
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Hello
import com.company.tap.protocol.HOST_BUILD_ID
import com.company.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import com.company.tap.protocol.Command
import com.company.tap.protocol.CommandResult
import com.company.tap.protocol.Health
import com.company.tap.protocol.Mutation
import com.company.tap.protocol.ProtocolAuthentication
import com.company.tap.protocol.ProtocolNegotiation
import com.company.tap.protocol.ProtocolVersion
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Returning
import com.company.tap.protocol.Screenshot as ScreenshotCommand
import com.company.tap.protocol.Targeted
import com.company.tap.protocol.ArtifactResult
import com.company.tap.protocol.SelectorValidation
import com.company.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import com.company.tap.protocol.result
import com.company.tap.protocol.selectors
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DriverClient(
    hostPort: Int,
    private val sessionId: String,
    private val generation: Long,
    private val secret: ByteArray,
    private val overallDeadlineNanos: Long? = null,
    private val serial: String? = null,
    /** Idle `PING` cadence that keeps the driver's heartbeat window open; 0 disables (tests only). */
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = Socket()
    private val transportLock = Any()
    private val pending = ConcurrentHashMap<Long, PendingCommand>()
    private val pongs = LinkedBlockingQueue<Long>()
    private var nextRequestId = 1L
    @Volatile private var poisoned = false
    @Volatile private var closed = false
    @Volatile private var lastWriteNanos = System.nanoTime()
    private val pingLock = Any()
    private lateinit var reader: Thread
    private var heartbeat: Thread? = null
    lateinit var driverInstanceId: String
        private set
    lateinit var negotiatedVersion: ProtocolVersion
        private set
    lateinit var enabledCapabilities: Set<String>
        private set
    lateinit var driverContract: Challenge
        private set

    init {
        try {
            socket.connect(InetSocketAddress("127.0.0.1", hostPort), remainingTimeoutMs(10_000))
            socket.soTimeout = remainingTimeoutMs(10_000)
            authenticate()
            socket.soTimeout = 0
            reader = Thread(::readFrames, "tap-driver-client-reader").apply {
                isDaemon = true
                start()
            }
            if (heartbeatIntervalMs > 0) {
                heartbeat = Thread(::runHeartbeat, "tap-driver-client-heartbeat").apply {
                    isDaemon = true
                    start()
                }
            }
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    /**
     * A request the driver has been asked to run. [await] returns its single terminal response;
     * [cancel] asks the driver to stop it cooperatively. The driver ignores a cancel once the
     * command has mutated, so the awaited response is always the definitive outcome.
     */
    inner class PendingCommand internal constructor(
        val requestId: Long,
        val command: Command,
        private val timeoutMs: Long,
    ) {
        private val selector: String? get() = (command as? Targeted)?.selector?.render()

        private val result = CompletableFuture<Response>()
        @Volatile var transmissionState: TransmissionState = TransmissionState.NOT_WRITTEN
            internal set
        internal var blob: BlobReceiver? = null
        @Volatile private var artifactBytes: ByteArray? = null

        /**
         * Applies the blob verdict: a successful artifact response whose blob did not arrive
         * intact becomes `ARTIFACT_TRANSFER_FAILED`; a driver failure is never replaced.
         */
        internal fun complete(response: Response) {
            transmissionState = TransmissionState.TERMINAL_RESPONSE
            val receiver = blob
            val artifact = (response.result as? ArtifactResult)?.artifact
            val terminal = when {
                response !is Response.Ok -> response
                artifact == null -> if (receiver == null) response else artifactFailure(response, ErrorDetail.BLOB_UNEXPECTED)
                receiver == null -> artifactFailure(response, ErrorDetail.BLOB_INCOMPLETE)
                receiver.failureDetail != null -> artifactFailure(response, requireNotNull(receiver.failureDetail))
                !receiver.complete -> artifactFailure(response, ErrorDetail.BLOB_INCOMPLETE)
                receiver.bytes?.size?.toLong() != artifact.byteCount ->
                    artifactFailure(response, ErrorDetail.BLOB_LENGTH_MISMATCH)
                else -> response.also { artifactBytes = receiver.bytes }
            }
            result.complete(terminal)
        }

        private fun artifactFailure(response: Response, detail: String): Response = Response.failure(
            ErrorCode.ARTIFACT_TRANSFER_FAILED,
            detail = detail,
            message = "Artifact ${(response.result as? ArtifactResult)?.artifact?.blobId} was not received intact",
            durationMs = response.durationMs,
        )

        /** Verified artifact bytes of a successful artifact response; null otherwise. */
        fun artifact(): ByteArray? = artifactBytes?.copyOf()

        internal fun fail(cause: Throwable) {
            result.completeExceptionally(cause)
        }

        val isDone: Boolean get() = result.isDone

        /** Diagnostic view of an already-terminal response; null while in flight or failed. */
        val responseOrNull: Response? get() = if (result.isDone && !result.isCompletedExceptionally) result.get() else null

        /** Sends `CANCEL` if the request is in flight. Returns false when there was nothing to cancel. */
        fun cancel(): Boolean {
            if (result.isDone || transmissionState != TransmissionState.WRITTEN) return false
            synchronized(transportLock) {
                if (poisoned || closed || result.isDone) return false
                try {
                    writeFrame(Frame(FrameType.CANCEL, requestId, byteArrayOf()), remainingTimeoutMs(5_000))
                } catch (error: Throwable) {
                    poison(error)
                    return false
                }
            }
            return true
        }

        fun await(): Response {
            val budget = remainingTimeoutMs((timeoutMs + 5_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            return try {
                result.get(budget.toLong(), TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                poison(SocketTimeoutException("No terminal response within $budget ms").apply { initCause(error) })
                throw transportFailure(error)
            } catch (error: ExecutionException) {
                throw transportFailure(error.cause ?: error)
            }
        }

        /** Like [await] but converts a driver error response into [RemoteCommandException]. */
        fun awaitOrThrow(): Response.Ok = when (val response = await()) {
            is Response.Ok -> response
            is Response.Error ->
                throw RemoteCommandException.from(response, command.op, requestId, generation, serial, selector, timeoutMs)
        }

        internal fun transportFailure(cause: Throwable): CommandTransportException {
            val code = if (command is Mutation && transmissionState != TransmissionState.NOT_WRITTEN) {
                ErrorCode.INDETERMINATE
            } else {
                ErrorCode.TRANSPORT_LOST
            }
            return CommandTransportException(
                code, command.op, requestId, generation, transmissionState, cause,
                serial, selector, timeoutMs,
            )
        }
    }

    /**
     * Runs [command] and returns its typed result; a driver error response becomes
     * [RemoteCommandException] and a lost response [CommandTransportException]. The cast is
     * safe by construction: the driver's `CommandHandler` returns the type [Returning] names.
     */
    @Suppress("UNCHECKED_CAST")
    fun <R : CommandResult, C> execute(command: C, timeoutMs: Long = 5_000): R where C : Command, C : Returning<R> =
        submit(command, timeoutMs).awaitOrThrow().result as R

    /** Runs [command] and returns the raw [Response], error or not. */
    fun send(command: Command, timeoutMs: Long = 5_000): Response = submit(command, timeoutMs).await()

    /**
     * Allocates the next request ID and writes the complete frame under the transport lock, so
     * concurrent callers can never put a lower ID on the socket after a higher one.
     */
    fun submit(command: Command, timeoutMs: Long = 5_000): PendingCommand {
        require(timeoutMs in 0..MAX_REQUEST_TIMEOUT_MS) {
            "timeoutMs must be between 0 and $MAX_REQUEST_TIMEOUT_MS"
        }
        // Structural selector problems fail here, before a request ID is consumed.
        command.selectors.forEach(SelectorValidation::validate)
        val request = Request(sessionId = sessionId, generation = generation, timeoutMs = timeoutMs, command = command)
        return synchronized(transportLock) {
            if (poisoned || closed) {
                throw CommandTransportException(
                    ErrorCode.TRANSPORT_LOST,
                    command.op,
                    -1,
                    generation,
                    TransmissionState.NOT_WRITTEN,
                    IllegalStateException("Driver connection is closed or poisoned"),
                    serial,
                    (command as? Targeted)?.selector?.render(),
                    timeoutMs,
                )
            }
            transmit(nextRequestId++, request)
        }
    }

    /** PNG screenshot: the verified bytes plus the driver's artifact metadata. */
    fun screenshot(timeoutMs: Long = 30_000): Screenshot {
        val command = submit(ScreenshotCommand, timeoutMs = timeoutMs)
        val response = command.awaitOrThrow()
        return Screenshot(requireNotNull(command.artifact()), (response.result as ArtifactResult).artifact)
    }

    /** Round-trips a connection-level `PING` on the writer/reader lanes. Returns the latency in ms. */
    fun ping(timeoutMs: Long = 5_000): Long = synchronized(pingLock) {
        val started = System.nanoTime()
        pongs.clear()
        synchronized(transportLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
            try {
                writeFrame(Frame(FrameType.PING, 0, byteArrayOf()), remainingTimeoutMs(timeoutMs.toInt()))
            } catch (error: Throwable) {
                poison(error)
                throw error
            }
        }
        val pong = pongs.poll(remainingTimeoutMs(timeoutMs.toInt()).toLong(), TimeUnit.MILLISECONDS)
        if (pong == null) {
            val timeout = SocketTimeoutException("No PONG within $timeoutMs ms")
            poison(timeout)
            throw timeout
        }
        (System.nanoTime() - started) / 1_000_000L
    }

    /**
     * Keeps the driver's heartbeat window open while the caller is idle. Any frame counts as
     * host activity on the driver, so a `PING` is only sent after [heartbeatIntervalMs] without
     * a write. A missed `PONG` poisons the client like any other transport failure.
     */
    private fun runHeartbeat() {
        try {
            while (!poisoned && !closed) {
                val idleMs = (System.nanoTime() - lastWriteNanos) / 1_000_000L
                val waitMs = heartbeatIntervalMs - idleMs
                if (waitMs > 0) {
                    Thread.sleep(waitMs)
                    continue
                }
                ping(heartbeatIntervalMs)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // ping() already poisoned the client; nothing else to do on this thread.
        }
    }

    /** Validation flow only: sends `health` with an explicit request ID / identity to probe fencing. */
    fun executeValidationRequest(
        requestId: Long,
        requestSessionId: String = sessionId,
        requestGeneration: Long = generation,
    ): Response {
        val request = Request(sessionId = requestSessionId, generation = requestGeneration, timeoutMs = 5_000, command = Health)
        return awaitValidation(synchronized(transportLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
            transmit(requestId, request)
        })
    }

    /**
     * Validation flow only: sends an arbitrary JSON payload as a `REQUEST` frame, for probing how
     * the driver answers what this build cannot express (an unknown `op`, a malformed command).
     */
    fun executeRawValidationRequest(requestId: Long, payload: String): Response =
        awaitValidation(synchronized(transportLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
            transmit(requestId, Health, 5_000, payload.encodeToByteArray())
        })

    private fun awaitValidation(command: PendingCommand): Response = try {
        command.await()
    } catch (error: CommandTransportException) {
        throw error.cause ?: error
    }

    /** Validation flow only: poisons this client as if the transport had failed. */
    fun disconnectForValidation() {
        synchronized(transportLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
        }
        poison(IllegalStateException("Disconnected for validation"))
    }

    override fun close() {
        synchronized(transportLock) {
            if (closed) return
            closed = true
            if (!poisoned) runCatching {
                writeFrame(Frame(FrameType.CLOSE, 0, byteArrayOf()), remainingTimeoutMs(5_000))
            }
            socket.close()
        }
        failPending(IllegalStateException("Driver connection closed"))
        heartbeat?.interrupt()
        if (::reader.isInitialized && Thread.currentThread() !== reader) reader.join(2_000)
    }

    // Caller holds transportLock.
    private fun transmit(requestId: Long, request: Request): PendingCommand =
        transmit(requestId, request.command, request.timeoutMs, json.encodeToString(request).encodeToByteArray())

    // Caller holds transportLock.
    private fun transmit(requestId: Long, command: Command, timeoutMs: Long, payload: ByteArray): PendingCommand {
        val command = PendingCommand(requestId, command, timeoutMs)
        check(pending.putIfAbsent(requestId, command) == null) { "Request $requestId is already pending" }
        // Explicit validation IDs consume the driver watermark too; never allocate below them.
        nextRequestId = maxOf(nextRequestId, Math.addExact(requestId, 1L))
        try {
            command.transmissionState = TransmissionState.WRITING
            writeFrame(Frame(FrameType.REQUEST, requestId, payload), remainingTimeoutMs(10_000))
            command.transmissionState = TransmissionState.WRITTEN
        } catch (error: Throwable) {
            poison(error)
            throw command.transportFailure(error)
        }
        return command
    }

    private fun readFrames() {
        try {
            while (!poisoned && !closed) {
                val frame = FrameCodec.read(socket.getInputStream())
                when (frame.type) {
                    FrameType.RESPONSE -> {
                        val command = pending.remove(frame.requestId)
                            ?: throw IllegalStateException("Response for unknown request ${frame.requestId}")
                        command.complete(json.decodeFromString<Response>(frame.payload.decodeToString()))
                    }
                    FrameType.PONG -> {
                        check(frame.requestId == 0L) { "PONG must use request ID 0" }
                        pongs.put(System.nanoTime())
                    }
                    FrameType.BLOB_START -> {
                        val command = pendingFor(frame)
                        val start = json.decodeFromString<BlobStart>(frame.payload.decodeToString())
                        check(command.blob == null) { "Second BLOB_START for request ${frame.requestId}" }
                        command.blob = BlobReceiver(start)
                    }
                    FrameType.BLOB_CHUNK -> pendingFor(frame).blob?.chunk(frame.payload)
                        ?: throw IllegalStateException("BLOB_CHUNK before BLOB_START for request ${frame.requestId}")
                    FrameType.BLOB_END -> pendingFor(frame).blob?.end(json.decodeFromString<BlobEnd>(frame.payload.decodeToString()))
                        ?: throw IllegalStateException("BLOB_END before BLOB_START for request ${frame.requestId}")
                    else -> throw IllegalStateException("Unexpected ${frame.type} frame from driver")
                }
            }
        } catch (error: Throwable) {
            if (!closed) poison(error)
        }
    }

    private fun pendingFor(frame: Frame): PendingCommand =
        pending[frame.requestId] ?: throw IllegalStateException("${frame.type} for unknown request ${frame.requestId}")

    /** Transport can no longer be trusted: close it and fail every in-flight command. */
    private fun poison(cause: Throwable) {
        synchronized(transportLock) {
            if (poisoned) return
            poisoned = true
            runCatching { socket.close() }
        }
        failPending(cause)
    }

    private fun failPending(cause: Throwable) {
        val commands = pending.values.toList()
        pending.clear()
        commands.forEach { it.fail(cause) }
    }

    private fun authenticate() {
        val hostNonce = ByteArray(32).also(SecureRandom()::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        val hello = Hello(
            hostBuildId = HOST_BUILD_ID,
            hostNonce = hostNonce,
            sessionGeneration = generation,
            sessionId = sessionId,
            supportedVersions = SUPPORTED_PROTOCOL_VERSIONS,
        )
        val helloPayload = CanonicalJson.encode(hello)
        writeFrame(
            Frame(FrameType.HELLO, 0, helloPayload),
            remainingTimeoutMs(10_000),
        )

        socket.soTimeout = remainingTimeoutMs(10_000)
        val challengeFrame = FrameCodec.read(socket.getInputStream())
        check(challengeFrame.type == FrameType.CHALLENGE)
        val challenge = CanonicalJson.decodeCanonical<Challenge>(challengeFrame.payload)
        check(ProtocolNegotiation.isValidChallenge(challenge)) { "Driver contract is invalid" }
        driverInstanceId = challenge.driverInstanceId
        check(challenge.sessionId == sessionId && challenge.sessionGeneration == generation)
        check(challenge.hostNonce == hostNonce)
        check(ProtocolNegotiation.isValidNonce(challenge.driverNonce))
        val negotiation = requireNotNull(ProtocolNegotiation.negotiate(hello, challenge)) {
            "Driver does not support a compatible application protocol version"
        }
        val transcript = ProtocolAuthentication.transcript(
            helloPayload,
            challengeFrame.payload,
            CanonicalJson.encode(negotiation),
        )

        val authentication = Authentication(
            negotiation = negotiation,
            transcriptHmac = ProtocolAuthentication.hostMac(secret, transcript),
        )
        writeFrame(
            Frame(FrameType.AUTH, 0, CanonicalJson.encode(authentication)),
            remainingTimeoutMs(10_000),
        )

        socket.soTimeout = remainingTimeoutMs(10_000)
        val resultFrame = FrameCodec.read(socket.getInputStream())
        check(resultFrame.type == FrameType.AUTH_RESULT)
        val result = CanonicalJson.decodeCanonical<AuthenticationResult>(resultFrame.payload)
        check(result.ok) { result.error ?: "Authentication failed" }
        check(result.selectedVersion == negotiation.selectedVersion)
        check(result.enabledCapabilities == negotiation.enabledCapabilities)
        check(
            ProtocolAuthentication.constantTimeEquals(
                ProtocolAuthentication.driverMac(secret, transcript),
                requireNotNull(result.transcriptHmac),
            )
        ) { "Driver authentication failed" }
        negotiatedVersion = negotiation.selectedVersion
        enabledCapabilities = negotiation.enabledCapabilities.toSet()
        driverContract = challenge
    }

    private fun remainingTimeoutMs(maximumMs: Int): Int {
        val deadline = overallDeadlineNanos ?: return maximumMs
        val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
        check(remainingMs > 0) { "Driver operation exceeded its containing deadline" }
        return minOf(maximumMs.toLong(), remainingMs).coerceAtLeast(1L).toInt()
    }

    private fun writeFrame(frame: Frame, timeoutMs: Int) {
        lastWriteNanos = System.nanoTime()
        val write = FutureTask {
            FrameCodec.write(socket.getOutputStream(), frame)
        }
        val thread = Thread(write, "tap-socket-writer").apply {
            isDaemon = true
            start()
        }
        try {
            write.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            runCatching { socket.close() }
            write.cancel(true)
            throw SocketTimeoutException("Socket write exceeded $timeoutMs ms").apply {
                initCause(error)
            }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            thread.join(1_000)
            check(!thread.isAlive) { "Socket writer survived socket close" }
        }
    }
}

class Screenshot(val png: ByteArray, val info: ArtifactInfo)

/** Well under the driver's default 30 s heartbeat timeout. */
const val DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L
