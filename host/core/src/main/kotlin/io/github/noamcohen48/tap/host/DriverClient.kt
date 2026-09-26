package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.ArtifactInfo
import io.github.noamcohen48.tap.protocol.ArtifactResult
import io.github.noamcohen48.tap.protocol.Authentication
import io.github.noamcohen48.tap.protocol.AuthenticationResult
import io.github.noamcohen48.tap.protocol.CanonicalJson
import io.github.noamcohen48.tap.protocol.Challenge
import io.github.noamcohen48.tap.protocol.Command
import io.github.noamcohen48.tap.protocol.CommandResult
import io.github.noamcohen48.tap.protocol.CommandValidation
import io.github.noamcohen48.tap.protocol.DRIVER_APK_BUILD_ID
import io.github.noamcohen48.tap.protocol.DRIVER_TEST_APK_BUILD_ID
import io.github.noamcohen48.tap.protocol.ErrorCode
import io.github.noamcohen48.tap.protocol.ErrorDetail
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameCodec
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.HOST_BUILD_ID
import io.github.noamcohen48.tap.protocol.HOST_RESPONSE_PADDING_MS
import io.github.noamcohen48.tap.protocol.Health
import io.github.noamcohen48.tap.protocol.Hello
import io.github.noamcohen48.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import io.github.noamcohen48.tap.protocol.Mutation
import io.github.noamcohen48.tap.protocol.ProtocolAuthentication
import io.github.noamcohen48.tap.protocol.ProtocolJson
import io.github.noamcohen48.tap.protocol.ProtocolNegotiation
import io.github.noamcohen48.tap.protocol.ProtocolVersion
import io.github.noamcohen48.tap.protocol.Request
import io.github.noamcohen48.tap.protocol.Response
import io.github.noamcohen48.tap.protocol.Returning
import io.github.noamcohen48.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import io.github.noamcohen48.tap.protocol.Targeted
import io.github.noamcohen48.tap.protocol.result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import io.github.noamcohen48.tap.protocol.Screenshot as ScreenshotCommand

class DriverClient private constructor(
    private val hostPort: Int,
    private val sessionId: String,
    private val generation: Long,
    private val secret: ByteArray,
    handshakeDeadlineNanos: Long? = null,
    private val serial: String? = null,
    private val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    /** Padding added to a command's own timeout to form its private response budget. Production
     * default; tests inject a small value so budget expiry is deterministic without long sleeps. */
    private val responseBudgetPaddingMs: Long = DEFAULT_RESPONSE_BUDGET_PADDING_MS,
) {
    private val json = ProtocolJson.codec
    private val socket = Socket()

    /** Bounds connect + handshake only; cleared once authenticated so no later frame write,
     * ping or response budget inherits the caller's connect deadline. */
    @Volatile private var handshakeDeadlineNanos: Long? = handshakeDeadlineNanos

    /** Session-owned usability gate, bound once by [DeviceSession]. Null keeps this client usable
     * standalone (tests, validation probes): the default is a no-op admission. Once bound, every
     * command admission consults the same sticky session state, so previously captured references
     * cannot bypass a later poison. Internal bind-once state, never a public mutable hook. */
    private var sessionGate: (() -> Unit)? = null

    /** Binds the owning session's usability check exactly once; later binds fail. */
    internal fun bindSessionGate(gate: () -> Unit) {
        check(sessionGate == null) { "DriverClient session gate is already bound" }
        sessionGate = gate
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport =
        DriverTransport(socket, scope, json, generation, serial, ::remainingTimeoutMs) { requestId, command, timeoutMs ->
            PendingCommand(requestId, command, timeoutMs)
        }

    internal var beforePhysicalWrite: (suspend () -> Unit)?
        get() = transport.beforePhysicalWrite
        set(value) {
            transport.beforePhysicalWrite = value
        }
    internal var afterPhysicalWrite: (() -> Unit)?
        get() = transport.afterPhysicalWrite
        set(value) {
            transport.afterPhysicalWrite = value
        }
    internal var beforeMarkWritten: (() -> Unit)?
        get() = transport.beforeMarkWritten
        set(value) {
            transport.beforeMarkWritten = value
        }
    internal var afterTerminalResponse: (() -> Unit)?
        get() = transport.afterTerminalResponse
        set(value) {
            transport.afterTerminalResponse = value
        }
    internal var afterAwaitCancel: (() -> Unit)?
        get() = transport.afterAwaitCancel
        set(value) {
            transport.afterAwaitCancel = value
        }
    internal var frameSink: FrameSink
        get() = transport.frameSink
        set(value) {
            transport.frameSink = value
        }

    /** Whether the transport can no longer be trusted; test visibility without exposing the flag. */
    internal val isPoisoned: Boolean get() = transport.isPoisoned

    /** The first failure that poisoned the transport; null while healthy. */
    internal val poisonCause: Throwable? get() = transport.poisonCause
    lateinit var driverInstanceId: String
        private set
    lateinit var negotiatedVersion: ProtocolVersion
        private set
    lateinit var enabledCapabilities: Set<String>
        private set
    lateinit var driverContract: Challenge
        private set

    companion object {
        /**
         * Connects to the forwarded driver port and authenticates. [overallDeadlineNanos] bounds
         * the TCP connect and the handshake only; the returned client's commands, heartbeat and
         * cancels are bounded by their own timeouts. A failure before the driver's `CHALLENGE`
         * arrives is an [java.io.IOException] (the driver may simply not be listening yet); any
         * failure after it is a [DriverHandshakeException] and never worth retrying.
         */
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
                    // Whole frames go out in one write; don't let Nagle hold them for an ACK.
                    client.socket.tcpNoDelay = true
                    client.socket.connect(InetSocketAddress("127.0.0.1", hostPort), client.remainingTimeoutMs(10_000))
                    client.socket.soTimeout = client.remainingTimeoutMs(10_000)
                    client.authenticate()
                    client.socket.soTimeout = 0
                }
                client.handshakeDeadlineNanos = null
                client.scope.launch { client.transport.readFrames() }
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

        /**
         * Claims the sole write attempt before the writer coroutine starts. Later transitions use
         * compare-and-set so a fast terminal response can never regress to `WRITTEN`.
         */
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
            transport.queueCancel(this)
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
                        if (!result.isCompleted && transport.isPending(this@PendingCommand)) {
                            transport.poison(SocketTimeoutException("No terminal response within $budgetMs ms"))
                        }
                    }
            }
        }

        private fun poisonAsLoss(budgetMs: Long): CommandTransportException {
            val error = SocketTimeoutException("No terminal response within $budgetMs ms")
            transport.poison(error)
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

    /** Allocates and transmits one request under [DriverTransport]'s ordered admission lock. */
    suspend fun submit(
        command: Command,
        timeoutMs: Long = 5_000,
    ): PendingCommand {
        require(timeoutMs in 0..MAX_REQUEST_TIMEOUT_MS) {
            "timeoutMs must be between 0 and $MAX_REQUEST_TIMEOUT_MS"
        }
        CommandValidation.validate(command)
        val request = Request(sessionId = sessionId, generation = generation, timeoutMs = timeoutMs, command = command)
        return transport.submit(request) { sessionGate?.invoke() }
    }

    /** Test seam for deterministic cancellation while another operation owns the transport. */
    internal suspend fun withTransportLock(block: suspend () -> Unit) = transport.withLock(block)

    /** Test visibility: the next request ID that [submit] would allocate. */
    internal suspend fun nextRequestIdForTest(): Long = transport.nextRequestIdForTest()

    /** PNG screenshot: the verified bytes plus the driver's artifact metadata. */
    suspend fun screenshot(timeoutMs: Long = 30_000): Screenshot {
        val command = submit(ScreenshotCommand, timeoutMs = timeoutMs)
        val response = command.awaitOrThrow()
        return Screenshot(requireNotNull(command.artifact()), (response.result as ArtifactResult).artifact)
    }

    /** Round-trips a connection-level `PING` on the writer/reader lanes. Returns the latency in ms. */
    suspend fun ping(timeoutMs: Long = 5_000): Long = transport.ping(timeoutMs) { sessionGate?.invoke() }

    /**
     * Keeps the driver's heartbeat window open while the caller is idle. Any frame counts as
     * host activity on the driver, so a `PING` is only sent after [heartbeatIntervalMs] without
     * a write. A missed `PONG` poisons the client like any other transport failure.
     */
    private suspend fun runHeartbeat() {
        try {
            while (currentCoroutineContext().isActive) {
                val idleMs = (System.nanoTime() - transport.lastWriteNanos) / 1_000_000L
                val waitMs = heartbeatIntervalMs - idleMs
                if (waitMs > 0) {
                    delay(waitMs)
                    continue
                }
                try {
                    transport.ping(heartbeatIntervalMs) {
                        try {
                            sessionGate?.invoke()
                        } catch (rejected: Exception) {
                            throw HeartbeatStopped(rejected)
                        }
                    }
                } catch (_: HeartbeatStopped) {
                    // The owning session is closing or quarantined; its own state decides the
                    // journal, so the heartbeat just stops without poisoning.
                    return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Whatever ended the heartbeat, the driver's heartbeat window is no longer being kept
            // open: poison so the client fails fast instead of dying silently (a closed client
            // stays closed, not poisoned).
            transport.poisonUnlessClosed(error)
        }
    }

    private class HeartbeatStopped(cause: Throwable) : RuntimeException(cause)

    /** Validation flow only: sends `health` with an explicit request ID / identity to probe fencing. */
    suspend fun executeValidationRequest(
        requestId: Long,
        requestSessionId: String = sessionId,
        requestGeneration: Long = generation,
    ): Response {
        val request = Request(sessionId = requestSessionId, generation = requestGeneration, timeoutMs = 5_000, command = Health)
        return awaitValidation(
            transport.submitValidation(requestId, request) { sessionGate?.invoke() },
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
            transport.submitRawValidation(requestId, Health, 5_000, payload.encodeToByteArray()) {
                sessionGate?.invoke()
            },
        )

    private suspend fun awaitValidation(command: PendingCommand): Response =
        try {
            command.await()
        } catch (error: CommandTransportException) {
            throw error.cause ?: error
        }

    /** Validation flow only: poisons this client as if the transport had failed. */
    fun disconnectForValidation() = transport.disconnectForValidation()

    suspend fun close() =
        withContext(NonCancellable) {
            transport.close()
            scope.cancel()
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
        transport.writeFrame(
            Frame(FrameType.HELLO, 0, helloPayload),
            remainingTimeoutMs(10_000),
        )

        socket.soTimeout = remainingTimeoutMs(10_000)
        val challengeFrame = withContext(Dispatchers.IO) { FrameCodec.read(socket.getInputStream()) }
        try {
            completeHandshake(hello, helloPayload, hostNonce, challengeFrame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (typed: DriverStartException) {
            throw typed
        } catch (error: Exception) {
            throw DriverHandshakeException("Driver handshake failed: ${error.message}", error)
        }
    }

    /** Everything after a frame answered `HELLO`: a failure here is a real rejection or a
     * mismatched driver, never "not listening yet". */
    private suspend fun completeHandshake(
        hello: Hello,
        helloPayload: ByteArray,
        hostNonce: String,
        challengeFrame: Frame,
    ) {
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
        transport.writeFrame(
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
        // Checked only once the transcript MAC proved the challenge came from our driver. The
        // driver APKs carry the engine version they were built with; a host talking to another
        // build's driver may disagree on commands or semantics the negotiation cannot see.
        if (challenge.driverApkBuildId != DRIVER_APK_BUILD_ID || challenge.driverTestApkBuildId != DRIVER_TEST_APK_BUILD_ID) {
            throw DriverBuildMismatchException(
                expected = DRIVER_APK_BUILD_ID,
                driverApkBuildId = challenge.driverApkBuildId,
                driverTestApkBuildId = challenge.driverTestApkBuildId,
                serial = serial,
            )
        }
        negotiatedVersion = negotiation.selectedVersion
        enabledCapabilities = negotiation.enabledCapabilities.toSet()
        driverContract = challenge
    }

    private fun remainingTimeoutMs(maximumMs: Int): Int {
        val deadline = handshakeDeadlineNanos ?: return maximumMs
        val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
        if (remainingMs <= 0) throw DriverStartException("Driver connect${serial?.let { " on $it" } ?: ""} exceeded its handshake deadline")
        return minOf(maximumMs.toLong(), remainingMs).coerceAtLeast(1L).toInt()
    }
}

/** The driver answered `HELLO` but the handshake then failed (bad contract, identity, version,
 * or authentication). Not transient: retrying reaches the same driver with the same answer. */
open class DriverHandshakeException(
    message: String,
    cause: Throwable? = null,
) : DriverStartException(message, cause)

/**
 * The authenticated driver was built from another engine version than this host
 * ([DRIVER_APK_BUILD_ID] / [DRIVER_TEST_APK_BUILD_ID]). Reinstall the driver APKs that ship with
 * this host; the session is not opened.
 */
class DriverBuildMismatchException(
    val expected: String,
    val driverApkBuildId: String,
    val driverTestApkBuildId: String,
    serial: String?,
) : DriverHandshakeException(
        "Driver build mismatch${serial?.let { " on $it" } ?: ""}: host expects $expected, device runs " +
            "driver $driverApkBuildId / driver test $driverTestApkBuildId; reinstall the bundled driver APKs",
    )

class Screenshot(
    val png: ByteArray,
    val info: ArtifactInfo,
)

/** Well under the driver's default 30 s heartbeat timeout. */
const val DEFAULT_HEARTBEAT_INTERVAL_MS = 5_000L

/**
 * Padding added to a command's own timeout to form its private response budget; derived from
 * the driver's watchdog grace so a late command's driver-side verdict still arrives in time.
 */
const val DEFAULT_RESPONSE_BUDGET_PADDING_MS = HOST_RESPONSE_PADDING_MS
