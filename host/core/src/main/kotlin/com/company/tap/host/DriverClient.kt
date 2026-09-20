package com.company.tap.host

import com.company.tap.protocol.ArtifactInfo
import com.company.tap.protocol.ArtifactResult
import com.company.tap.protocol.Authentication
import com.company.tap.protocol.AuthenticationResult
import com.company.tap.protocol.BlobEnd
import com.company.tap.protocol.BlobStart
import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.Challenge
import com.company.tap.protocol.Command
import com.company.tap.protocol.CommandResult
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.ErrorDetail
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.HOST_BUILD_ID
import com.company.tap.protocol.Health
import com.company.tap.protocol.Hello
import com.company.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import com.company.tap.protocol.Mutation
import com.company.tap.protocol.ProtocolAuthentication
import com.company.tap.protocol.ProtocolNegotiation
import com.company.tap.protocol.ProtocolVersion
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Returning
import com.company.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import com.company.tap.protocol.SelectorValidation
import com.company.tap.protocol.Targeted
import com.company.tap.protocol.result
import com.company.tap.protocol.selectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import com.company.tap.protocol.Screenshot as ScreenshotCommand

class DriverClient private constructor(
    private val hostPort: Int,
    private val sessionId: String,
    private val generation: Long,
    private val secret: ByteArray,
    private val overallDeadlineNanos: Long? = null,
    private val serial: String? = null,
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = Socket()
    private val transportMutex = Mutex()
    private val pingMutex = Mutex()
    private val poisonLock = Any()
    private val pending = ConcurrentHashMap<Long, PendingCommand>()
    private val pongs = Channel<Long>(Channel.UNLIMITED)
    private var nextRequestId = 1L

    @Volatile private var poisoned = false

    @Volatile private var closed = false

    @Volatile private var lastWriteNanos = System.nanoTime()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var driverInstanceId: String
        private set
    lateinit var negotiatedVersion: ProtocolVersion
        private set
    lateinit var enabledCapabilities: Set<String>
        private set
    lateinit var driverContract: Challenge
        private set

    companion object {
        suspend fun connect(
            hostPort: Int,
            sessionId: String,
            generation: Long,
            secret: ByteArray,
            overallDeadlineNanos: Long? = null,
            serial: String? = null,
            heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
        ): DriverClient {
            val client = DriverClient(hostPort, sessionId, generation, secret, overallDeadlineNanos, serial, heartbeatIntervalMs)
            try {
                withContext(Dispatchers.IO) {
                    client.socket.connect(InetSocketAddress("127.0.0.1", hostPort), client.remainingTimeoutMs(10_000))
                    client.socket.soTimeout = client.remainingTimeoutMs(10_000)
                    client.authenticate()
                    client.socket.soTimeout = 0
                }
                client.scope.launch { client.readFrames() }
                if (heartbeatIntervalMs > 0) client.scope.launch { client.runHeartbeat() }
                return client
            } catch (error: Throwable) {
                runCatching { client.socket.close() }
                client.scope.cancel()
                throw error
            }
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

        private val result = CompletableDeferred<Response>()

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
            val terminal =
                when {
                    response !is Response.Ok -> {
                        response
                    }

                    artifact == null -> {
                        if (receiver == null) response else artifactFailure(response, ErrorDetail.BLOB_UNEXPECTED)
                    }

                    receiver == null -> {
                        artifactFailure(response, ErrorDetail.BLOB_INCOMPLETE)
                    }

                    receiver.failureDetail != null -> {
                        artifactFailure(response, requireNotNull(receiver.failureDetail))
                    }

                    !receiver.complete -> {
                        artifactFailure(response, ErrorDetail.BLOB_INCOMPLETE)
                    }

                    receiver.bytes?.size?.toLong() != artifact.byteCount -> {
                        artifactFailure(response, ErrorDetail.BLOB_LENGTH_MISMATCH)
                    }

                    else -> {
                        response.also { artifactBytes = receiver.bytes }
                    }
                }
            result.complete(terminal)
        }

        private fun artifactFailure(
            response: Response,
            detail: String,
        ): Response =
            Response.failure(
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

        val isDone: Boolean get() = result.isCompleted

        /** Diagnostic view of an already-terminal response; null while in flight or failed. */
        val responseOrNull: Response? get() =
            if (result.isCompleted) runCatching { result.getCompleted() }.getOrNull() else null

        /**
         * Sends `CANCEL` if the request is in flight. Returns false when there was nothing to
         * cancel. Runs non-cancellably: a cooperative cancel is a best-effort signal that must
         * still reach the driver when the caller itself is being cancelled.
         */
        suspend fun cancel(): Boolean =
            withContext(NonCancellable) {
                if (result.isCompleted || transmissionState != TransmissionState.WRITTEN) return@withContext false
                transportMutex.withLock {
                    if (poisoned || closed || result.isCompleted) return@withContext false
                    try {
                        writeFrame(Frame(FrameType.CANCEL, requestId, byteArrayOf()), remainingTimeoutMs(5_000))
                    } catch (error: Throwable) {
                        poison(error)
                        return@withContext false
                    }
                }
                return@withContext true
            }

        /**
         * Returns the single terminal response, mapping a driver error to [Response.Error] and
         * transport loss to [CommandTransportException] (never returned, always thrown).
         *
         * Cancelling the awaiting coroutine is not the command's outcome: the client forwards a
         * cooperative `CANCEL` and keeps the pending entry registered until the terminal frame
         * arrives, so a later frame is never an "unknown request id" and the mutation gate's
         * verdict is still recorded. The socket is never closed mid-command; only transport
         * failure poisons the client.
         */
        suspend fun await(): Response {
            val budgetMs = remainingTimeoutMs((timeoutMs + 5_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).toLong()
            val deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L
            try {
                return awaitTerminal(deadlineNanos)
            } catch (timeout: TimeoutCancellationException) {
                throw poisonAsLoss(timeout, budgetMs)
            } catch (cancelled: CancellationException) {
                runCatching { cancel() }
                return withContext(NonCancellable) {
                    try {
                        awaitTerminal(deadlineNanos)
                    } catch (timeout: TimeoutCancellationException) {
                        throw poisonAsLoss(timeout, budgetMs)
                    }
                }
            }
        }

        private suspend fun awaitTerminal(deadlineNanos: Long): Response {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
            return try {
                withTimeout(remainingMs) { result.await() }
            } catch (failure: Throwable) {
                if (failure is TimeoutCancellationException || failure is CancellationException) throw failure
                throw transportFailure(failure)
            }
        }

        private fun poisonAsLoss(
            timeout: TimeoutCancellationException,
            budgetMs: Long,
        ): CommandTransportException {
            val error = SocketTimeoutException("No terminal response within $budgetMs ms").apply { initCause(timeout) }
            poison(error)
            return transportFailure(error)
        }

        /** Like [await] but converts a driver error response into [RemoteCommandException]. */
        suspend fun awaitOrThrow(): Response.Ok =
            when (val response = await()) {
                is Response.Ok -> {
                    response
                }

                is Response.Error -> {
                    throw RemoteCommandException.from(response, command.op, requestId, generation, serial, selector, timeoutMs)
                }
            }

        internal fun transportFailure(cause: Throwable): CommandTransportException {
            val code =
                if (command is Mutation && transmissionState != TransmissionState.NOT_WRITTEN) {
                    ErrorCode.INDETERMINATE
                } else {
                    ErrorCode.TRANSPORT_LOST
                }
            return CommandTransportException(
                code,
                command.op,
                requestId,
                generation,
                transmissionState,
                cause,
                serial,
                selector,
                timeoutMs,
            )
        }
    }

    /**
     * Runs [command] and returns its typed result; a driver error response becomes
     * [RemoteCommandException] and a lost response [CommandTransportException]. The cast is
     * safe by construction: the driver's `CommandHandler` returns the type [Returning] names.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <R : CommandResult, C> execute(
        command: C,
        timeoutMs: Long = 5_000,
    ): R where C : Command, C : Returning<R> = submit(command, timeoutMs).awaitOrThrow().result as R

    /** Runs [command] and returns the raw [Response], error or not. */
    suspend fun send(
        command: Command,
        timeoutMs: Long = 5_000,
    ): Response = submit(command, timeoutMs).await()

    /**
     * Allocates the next request ID and writes the complete frame under the transport mutex, so
     * concurrent callers can never put a lower ID on the socket after a higher one.
     */
    suspend fun submit(
        command: Command,
        timeoutMs: Long = 5_000,
    ): PendingCommand {
        require(timeoutMs in 0..MAX_REQUEST_TIMEOUT_MS) {
            "timeoutMs must be between 0 and $MAX_REQUEST_TIMEOUT_MS"
        }
        // Structural selector problems fail here, before a request ID is consumed.
        command.selectors.forEach(SelectorValidation::validate)
        val request = Request(sessionId = sessionId, generation = generation, timeoutMs = timeoutMs, command = command)
        return transportMutex.withLock {
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
    suspend fun screenshot(timeoutMs: Long = 30_000): Screenshot {
        val command = submit(ScreenshotCommand, timeoutMs = timeoutMs)
        val response = command.awaitOrThrow()
        return Screenshot(requireNotNull(command.artifact()), (response.result as ArtifactResult).artifact)
    }

    /** Round-trips a connection-level `PING` on the writer/reader lanes. Returns the latency in ms. */
    suspend fun ping(timeoutMs: Long = 5_000): Long =
        pingMutex.withLock {
            val started = System.nanoTime()
            while (pongs.tryReceive().isSuccess) Unit
            transportMutex.withLock {
                check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
                try {
                    writeFrame(Frame(FrameType.PING, 0, byteArrayOf()), remainingTimeoutMs(timeoutMs.toInt()))
                } catch (error: Throwable) {
                    poison(error)
                    throw error
                }
            }
            val pong = withTimeoutOrNull(remainingTimeoutMs(timeoutMs.toInt()).toLong()) { pongs.receive() }
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
    private suspend fun runHeartbeat() {
        try {
            while (currentCoroutineContext().isActive) {
                val idleMs = (System.nanoTime() - lastWriteNanos) / 1_000_000L
                val waitMs = heartbeatIntervalMs - idleMs
                if (waitMs > 0) {
                    delay(waitMs)
                    continue
                }
                ping(heartbeatIntervalMs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // ping() already poisoned the client; nothing else to do on this coroutine.
        }
    }

    /** Validation flow only: sends `health` with an explicit request ID / identity to probe fencing. */
    suspend fun executeValidationRequest(
        requestId: Long,
        requestSessionId: String = sessionId,
        requestGeneration: Long = generation,
    ): Response {
        val request = Request(sessionId = requestSessionId, generation = requestGeneration, timeoutMs = 5_000, command = Health)
        return awaitValidation(
            transportMutex.withLock {
                check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
                transmit(requestId, request)
            },
        )
    }

    /**
     * Validation flow only: sends an arbitrary JSON payload as a `REQUEST` frame, for probing how
     * the driver answers what this build cannot express (an unknown `op`, a malformed command).
     */
    suspend fun executeRawValidationRequest(
        requestId: Long,
        payload: String,
    ): Response =
        awaitValidation(
            transportMutex.withLock {
                check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
                transmit(requestId, Health, 5_000, payload.encodeToByteArray())
            },
        )

    private suspend fun awaitValidation(command: PendingCommand): Response =
        try {
            command.await()
        } catch (error: CommandTransportException) {
            throw error.cause ?: error
        }

    /** Validation flow only: poisons this client as if the transport had failed. */
    fun disconnectForValidation() {
        synchronized(poisonLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
        }
        poison(IllegalStateException("Disconnected for validation"))
    }

    suspend fun close() {
        if (closed) return
        withContext(NonCancellable) {
            transportMutex.withLock {
                if (closed) return@withLock
                closed = true
                if (!poisoned) {
                    runCatching {
                        writeFrame(Frame(FrameType.CLOSE, 0, byteArrayOf()), remainingTimeoutMs(5_000))
                    }
                }
                runCatching { socket.close() }
            }
            scope.cancel()
            failPending(IllegalStateException("Driver connection closed"))
        }
    }

    // Caller holds transportMutex.
    private suspend fun transmit(
        requestId: Long,
        request: Request,
    ): PendingCommand = transmit(requestId, request.command, request.timeoutMs, json.encodeToString(request).encodeToByteArray())

    // Caller holds transportMutex.
    private suspend fun transmit(
        requestId: Long,
        command: Command,
        timeoutMs: Long,
        payload: ByteArray,
    ): PendingCommand {
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
                        val command =
                            pending.remove(frame.requestId)
                                ?: throw IllegalStateException("Response for unknown request ${frame.requestId}")
                        command.complete(json.decodeFromString<Response>(frame.payload.decodeToString()))
                    }

                    FrameType.PONG -> {
                        check(frame.requestId == 0L) { "PONG must use request ID 0" }
                        pongs.trySend(System.nanoTime())
                    }

                    FrameType.BLOB_START -> {
                        val command = pendingFor(frame)
                        val start = json.decodeFromString<BlobStart>(frame.payload.decodeToString())
                        check(command.blob == null) { "Second BLOB_START for request ${frame.requestId}" }
                        command.blob = BlobReceiver(start)
                    }

                    FrameType.BLOB_CHUNK -> {
                        pendingFor(frame).blob?.chunk(frame.payload)
                            ?: throw IllegalStateException("BLOB_CHUNK before BLOB_START for request ${frame.requestId}")
                    }

                    FrameType.BLOB_END -> {
                        pendingFor(frame).blob?.end(json.decodeFromString<BlobEnd>(frame.payload.decodeToString()))
                            ?: throw IllegalStateException("BLOB_END before BLOB_START for request ${frame.requestId}")
                    }

                    else -> {
                        throw IllegalStateException("Unexpected ${frame.type} frame from driver")
                    }
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
        synchronized(poisonLock) {
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

    private suspend fun authenticate() {
        val hostNonce =
            ByteArray(32).also(SecureRandom()::nextBytes).let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            }
        val hello =
            Hello(
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
        val challengeFrame = withContext(Dispatchers.IO) { FrameCodec.read(socket.getInputStream()) }
        check(challengeFrame.type == FrameType.CHALLENGE)
        val challenge = CanonicalJson.decodeCanonical<Challenge>(challengeFrame.payload)
        check(ProtocolNegotiation.isValidChallenge(challenge)) { "Driver contract is invalid" }
        driverInstanceId = challenge.driverInstanceId
        check(challenge.sessionId == sessionId && challenge.sessionGeneration == generation)
        check(challenge.hostNonce == hostNonce)
        check(ProtocolNegotiation.isValidNonce(challenge.driverNonce))
        val negotiation =
            requireNotNull(ProtocolNegotiation.negotiate(hello, challenge)) {
                "Driver does not support a compatible application protocol version"
            }
        val transcript =
            ProtocolAuthentication.transcript(
                helloPayload,
                challengeFrame.payload,
                CanonicalJson.encode(negotiation),
            )

        val authentication =
            Authentication(
                negotiation = negotiation,
                transcriptHmac = ProtocolAuthentication.hostMac(secret, transcript),
            )
        writeFrame(
            Frame(FrameType.AUTH, 0, CanonicalJson.encode(authentication)),
            remainingTimeoutMs(10_000),
        )

        socket.soTimeout = remainingTimeoutMs(10_000)
        val resultFrame = withContext(Dispatchers.IO) { FrameCodec.read(socket.getInputStream()) }
        check(resultFrame.type == FrameType.AUTH_RESULT)
        val result = CanonicalJson.decodeCanonical<AuthenticationResult>(resultFrame.payload)
        check(result.ok) { result.error ?: "Authentication failed" }
        check(result.selectedVersion == negotiation.selectedVersion)
        check(result.enabledCapabilities == negotiation.enabledCapabilities)
        check(
            ProtocolAuthentication.constantTimeEquals(
                ProtocolAuthentication.driverMac(secret, transcript),
                requireNotNull(result.transcriptHmac),
            ),
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

    /**
     * Writes one frame with an explicit deadline. A socket write has no timeout of its own, so
     * the write runs as a child while the waiter holds the deadline; on expiry the socket is
     * closed to unblock the write, exactly as the old per-frame writer thread did.
     */
    private suspend fun writeFrame(
        frame: Frame,
        timeoutMs: Int,
    ) {
        lastWriteNanos = System.nanoTime()
        withContext(Dispatchers.IO) {
            val task = launch { FrameCodec.write(socket.getOutputStream(), frame) }
            if (withTimeoutOrNull(timeoutMs.toLong()) { task.join() } == null) {
                runCatching { socket.close() }
                task.join()
                throw SocketTimeoutException("Socket write exceeded $timeoutMs ms")
            }
        }
    }
}

class Screenshot(
    val png: ByteArray,
    val info: ArtifactInfo,
)

/** Well under the driver's default 30 s heartbeat timeout. */
const val DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L
