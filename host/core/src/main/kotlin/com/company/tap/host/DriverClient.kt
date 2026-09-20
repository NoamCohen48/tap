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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.company.tap.protocol.Screenshot as ScreenshotCommand

class DriverClient private constructor(
    private val hostPort: Int,
    private val sessionId: String,
    private val generation: Long,
    private val secret: ByteArray,
    private val overallDeadlineNanos: Long? = null,
    private val serial: String? = null,
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    /** Padding added to a command's own timeout to form its private response budget. Production
     * default; tests inject a small value so budget expiry is deterministic without long sleeps. */
    private val responseBudgetPaddingMs: Long = DEFAULT_RESPONSE_BUDGET_PADDING_MS,
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

    /** Test seam: invoked inside the writer task before the physical socket write, so a test can
     * park the writer deterministically. Null in production; never alters the socket path. */
    internal var beforePhysicalWrite: (suspend () -> Unit)? = null

    /** Test seam: invoked inside the writer task after the physical write attempt finishes
     * (success or failure), so a test can observe that the writer is gone. Null in production. */
    internal var afterPhysicalWrite: (() -> Unit)? = null

    /** Test seam: invoked after a request frame was written but before WRITING -> WRITTEN. */
    internal var beforeMarkWritten: (() -> Unit)? = null

    /** Test seam: invoked after the reader installs a terminal transmission state. */
    internal var afterTerminalResponse: (() -> Unit)? = null

    /** Test seam: invoked synchronously in [PendingCommand.await]'s cancellation path after the
     * cooperative CANCEL is queued and before the original [CancellationException] is rethrown,
     * so a test can install a terminal response at that exact race point. Null in production. */
    internal var afterAwaitCancel: (() -> Unit)? = null

    /** Test seam for the physical write itself; the default is the real socket write. */
    internal var frameSink: FrameSink = FrameSink { frame -> FrameCodec.write(socket.getOutputStream(), frame) }

    /** Whether the transport can no longer be trusted; test visibility without exposing the flag. */
    internal val isPoisoned: Boolean get() = poisoned
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
            responseBudgetPaddingMs: Long = DEFAULT_RESPONSE_BUDGET_PADDING_MS,
        ): DriverClient {
            val client =
                DriverClient(
                    hostPort,
                    sessionId,
                    generation,
                    secret,
                    overallDeadlineNanos,
                    serial,
                    heartbeatIntervalMs,
                    responseBudgetPaddingMs,
                )
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

        /** Terminal response once [complete] ran; null while in flight or failed. An explicit
         * value instead of reading the deferred, so product code needs no experimental API. */
        @Volatile private var terminal: Response? = null

        private val transmission = AtomicReference(TransmissionState.NOT_WRITTEN)
        val transmissionState: TransmissionState get() = transmission.get()
        internal var blob: BlobReceiver? = null

        @Volatile private var artifactBytes: ByteArray? = null

        /** Set by the writer task when the physical socket write begins; classifies a write that
         * is cancelled or fails mid-flight. */
        @Volatile internal var writeStarted = false

        /** Client-owned enforcement of this command's private response budget; cancelled when the
         * terminal response arrives. Survives caller cancellation so an abandoned entry cannot
         * leak forever. */
        @Volatile internal var deadlineWatcher: Job? = null

        /**
         * Applies the blob verdict: a successful artifact response whose blob did not arrive
         * intact becomes `ARTIFACT_TRANSFER_FAILED`; a driver failure is never replaced.
         */
        internal fun complete(response: Response) {
            val receiver = blob
            val artifact = (response.result as? ArtifactResult)?.artifact
            val terminalResponse =
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
            terminal = terminalResponse
            transmission.getAndSet(TransmissionState.TERMINAL_RESPONSE)
            afterTerminalResponse?.invoke()
            deadlineWatcher?.cancel()
            result.complete(terminalResponse)
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
            deadlineWatcher?.cancel()
            result.completeExceptionally(cause)
        }

        val isDone: Boolean get() = result.isCompleted

        /** Diagnostic view of an already-terminal response; null while in flight or failed. */
        val responseOrNull: Response? get() = terminal

        /** Records `WRITTEN` only from `WRITING`: a reader response that already set
         * `TERMINAL_RESPONSE` must never regress. */
        internal fun beginWriting() {
            check(transmission.compareAndSet(TransmissionState.NOT_WRITTEN, TransmissionState.WRITING))
        }

        internal fun resetNotWritten() {
            transmission.compareAndSet(TransmissionState.WRITING, TransmissionState.NOT_WRITTEN)
        }

        internal fun markWritten() {
            beforeMarkWritten?.invoke()
            transmission.compareAndSet(TransmissionState.WRITING, TransmissionState.WRITTEN)
        }

        private val cancelQueued = AtomicBoolean(false)

        /**
         * Queues a cooperative `CANCEL` in the client-owned scope if the request is in flight.
         * The caller never waits for the transport mutex: after acquiring it, the queued task
         * rechecks that the command and transport are still usable before writing.
         */
        suspend fun cancel(): Boolean {
            if (result.isCompleted || transmissionState != TransmissionState.WRITTEN) return false
            if (!cancelQueued.compareAndSet(false, true)) return false
            scope.launch {
                transportMutex.withLock {
                    if (
                        poisoned || closed || result.isCompleted ||
                        transmissionState != TransmissionState.WRITTEN
                    ) {
                        return@withLock
                    }
                    try {
                        writeFrame(Frame(FrameType.CANCEL, requestId, byteArrayOf()), remainingTimeoutMs(5_000))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        poison(error)
                    }
                }
            }
            return true
        }

        /**
         * Returns the single terminal response; transport loss throws [CommandTransportException].
         *
         * The command owns a private response budget (its timeout plus padding). Expiry of that
         * budget poisons the transport and maps mutations by [transmissionState], exactly like any
         * other transport loss. Cancellation imposed by the caller — plain cancel or an enclosing
         * `withTimeout`/gRPC/JUnit deadline — is *not* the command's outcome: the client forwards
         * a cooperative `CANCEL` best-effort, keeps the pending entry registered for the reader so
         * a later frame is never an "unknown request id" and the mutation gate's verdict is
         * still recorded, and always rethrows the original cancellation, even when a terminal
         * response is installed concurrently afterwards. A terminal result that already completed
         * the deferred is returned normally; the cancellation path never returns one. The socket
         * is never closed merely because the caller was cancelled; a client-owned watcher enforces
         * the private budget afterwards so the abandoned entry cannot leak.
         */
        suspend fun await(): Response {
            val budgetMs =
                remainingTimeoutMs((timeoutMs + responseBudgetPaddingMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .toLong()
            val deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L
            ensureDeadlineWatcher(deadlineNanos, budgetMs)
            try {
                val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                // The private budget's expiry is a null, never an exception: any
                // CancellationException escaping this block is the caller's, not the command's.
                val response = withTimeoutOrNull(remainingMs) { result.await() }
                if (response != null) return response
                throw poisonAsLoss(budgetMs)
            } catch (cancelled: CancellationException) {
                cancel()
                afterAwaitCancel?.invoke()
                throw cancelled
            } catch (failure: Throwable) {
                throw transportFailure(failure)
            }
        }

        /** Starts the client-owned budget watcher once; it belongs to the client's scope so it
         * outlives a cancelled caller. Already-terminal commands need no watcher. */
        private fun ensureDeadlineWatcher(
            deadlineNanos: Long,
            budgetMs: Long,
        ) {
            if (result.isCompleted) return
            synchronized(this) {
                if (result.isCompleted || deadlineWatcher != null) return
                deadlineWatcher =
                    scope.launch {
                        val delayMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
                        delay(delayMs)
                        if (!result.isCompleted && pending.containsKey(requestId)) {
                            poison(SocketTimeoutException("No terminal response within $budgetMs ms"))
                        }
                    }
            }
        }

        private fun poisonAsLoss(budgetMs: Long): CommandTransportException {
            val error = SocketTimeoutException("No terminal response within $budgetMs ms")
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

    /** Test seam for deterministic cancellation while another operation owns the transport. */
    internal suspend fun withTransportLock(block: suspend () -> Unit) = transportMutex.withLock { block() }

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
        command.beginWriting()
        try {
            writeFrame(Frame(FrameType.REQUEST, requestId, payload), remainingTimeoutMs(10_000)) {
                command.writeStarted = true
            }
            command.markWritten()
        } catch (cancelled: CancellationException) {
            if (!command.writeStarted) {
                // The writer never touched the socket: the transport is intact, so the command
                // leaves no trace and a mutation is still NOT_WRITTEN, never INDETERMINATE.
                pending.remove(requestId, command)
                command.resetNotWritten()
                throw cancelled
            }
            poison(cancelled)
            throw command.transportFailure(cancelled)
        } catch (error: Throwable) {
            if (!command.writeStarted) {
                pending.remove(requestId, command)
                command.resetNotWritten()
            }
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
     * the write runs as a client-scoped child (independent of caller cancellation) while the
     * waiter holds the deadline. The write's own deadline expiry is a [SocketTimeoutException],
     * never an exception the caller could confuse with its own cancellation; any
     * [CancellationException] escaping here is the caller's.
     *
     * On caller cancellation before the physical write began, the writer is stopped before it
     * can touch the socket and the transport survives. Once the physical write began,
     * cancellation closes the socket to unblock the writer, reaps it boundedly, and the caller
     * ([transmit]) classifies the command as transport loss.
     */
    private suspend fun writeFrame(
        frame: Frame,
        timeoutMs: Int,
        markStarted: () -> Unit = {},
    ) {
        lastWriteNanos = System.nanoTime()
        val started = AtomicBoolean(false)
        val writer =
            scope.async(Dispatchers.IO) {
                beforePhysicalWrite?.invoke()
                markStarted()
                started.set(true)
                try {
                    frameSink.write(frame)
                } finally {
                    afterPhysicalWrite?.invoke()
                }
            }
        try {
            val completed =
                withTimeoutOrNull(timeoutMs.toLong()) {
                    writer.await()
                    true
                }
            if (completed != true) {
                unblockAndReap(writer)
                throw SocketTimeoutException("Socket write exceeded $timeoutMs ms")
            }
            // A writer failure surfaces through await() above and reaches the caller unchanged.
        } catch (cancelled: CancellationException) {
            if (!started.get()) {
                writer.cancel()
                withContext(NonCancellable) {
                    withTimeoutOrNull(WRITE_REAP_TIMEOUT_MS) { writer.join() }
                }
                if (!started.get()) throw cancelled
                // Lost the race: the writer began after the check; treat as transport loss.
            }
            unblockAndReap(writer)
            throw cancelled
        }
    }

    /** Cancels the writer, closes the socket to unblock a writer stuck in blocking IO, then
     * reaps it with a bound; never waits forever inside NonCancellable cleanup. */
    private suspend fun unblockAndReap(writer: Deferred<Unit>) {
        writer.cancel()
        runCatching { socket.close() }
        withContext(NonCancellable) {
            withTimeoutOrNull(WRITE_REAP_TIMEOUT_MS) { writer.join() }
        }
        writer.cancel()
    }
}

/** A physical frame write; suspends so tests can gate it. The default is the real socket write. */
internal fun interface FrameSink {
    suspend fun write(frame: Frame)
}

class Screenshot(
    val png: ByteArray,
    val info: ArtifactInfo,
)

/** Well under the driver's default 30 s heartbeat timeout. */
const val DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L

/** Padding added to a command's own timeout to form its private response budget. */
const val DEFAULT_RESPONSE_BUDGET_PADDING_MS = 5_000L

/** Bound for reaping a writer child after its socket was closed; never unbounded. */
const val WRITE_REAP_TIMEOUT_MS = 2_000L
