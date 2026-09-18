package com.company.tap.host

import com.company.tap.protocol.Authentication
import com.company.tap.protocol.AuthenticationResult
import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.Challenge
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Hello
import com.company.tap.protocol.HOST_BUILD_ID
import com.company.tap.protocol.MAX_REQUEST_TIMEOUT_MS
import com.company.tap.protocol.Operation
import com.company.tap.protocol.OPERATION_VERSION
import com.company.tap.protocol.ProtocolAuthentication
import com.company.tap.protocol.ProtocolNegotiation
import com.company.tap.protocol.ProtocolVersion
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import com.company.tap.protocol.Selector
import com.company.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
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
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = Socket()
    private val transportLock = Any()
    private val pending = ConcurrentHashMap<Long, PendingCommand>()
    private val pongs = LinkedBlockingQueue<Long>()
    private var nextRequestId = 1L
    @Volatile private var poisoned = false
    @Volatile private var closed = false
    private lateinit var reader: Thread
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
        val operation: Operation,
        private val timeoutMs: Long,
        private val selector: Selector? = null,
    ) {
        private val result = CompletableFuture<Response>()
        @Volatile var transmissionState: TransmissionState = TransmissionState.NOT_WRITTEN
            internal set

        internal fun complete(response: Response) {
            transmissionState = TransmissionState.TERMINAL_RESPONSE
            result.complete(response)
        }

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
        fun awaitOrThrow(): Response {
            val response = await()
            if (response.ok) return response
            throw RemoteCommandException.from(response, operation, requestId, generation, serial, selector, timeoutMs)
        }

        internal fun transportFailure(cause: Throwable): CommandTransportException {
            val code = if (operation.isMutating() && transmissionState != TransmissionState.NOT_WRITTEN) {
                ErrorCode.INDETERMINATE
            } else {
                ErrorCode.TRANSPORT_LOST
            }
            return CommandTransportException(
                code, operation, requestId, generation, transmissionState, cause,
                serial, selector?.render(), timeoutMs,
            )
        }
    }

    fun execute(
        operation: Operation,
        selector: Selector? = null,
        timeoutMs: Long = 5_000,
        containerSelector: Selector? = null,
        inputText: String? = null,
        maxScrolls: Int = 20,
        observedPid: Int? = null,
        observedStartToken: String? = null,
        expectedProcessStartUuid: String? = null,
        expectedSessionIdentity: String? = null,
    ): Response = submit(
        operation,
        selector,
        timeoutMs,
        containerSelector,
        inputText,
        maxScrolls,
        observedPid,
        observedStartToken,
        expectedProcessStartUuid,
        expectedSessionIdentity,
    ).await()

    /** [execute] that throws [RemoteCommandException] instead of returning an error response. */
    fun executeOrThrow(
        operation: Operation,
        selector: Selector? = null,
        timeoutMs: Long = 5_000,
        containerSelector: Selector? = null,
        inputText: String? = null,
        maxScrolls: Int = 20,
    ): Response = submit(
        operation,
        selector,
        timeoutMs,
        containerSelector,
        inputText,
        maxScrolls,
    ).awaitOrThrow()

    /**
     * Allocates the next request ID and writes the complete frame under the transport lock, so
     * concurrent callers can never put a lower ID on the socket after a higher one.
     */
    fun submit(
        operation: Operation,
        selector: Selector? = null,
        timeoutMs: Long = 5_000,
        containerSelector: Selector? = null,
        inputText: String? = null,
        maxScrolls: Int = 20,
        observedPid: Int? = null,
        observedStartToken: String? = null,
        expectedProcessStartUuid: String? = null,
        expectedSessionIdentity: String? = null,
    ): PendingCommand {
        require(timeoutMs in 0..MAX_REQUEST_TIMEOUT_MS) {
            "timeoutMs must be between 0 and $MAX_REQUEST_TIMEOUT_MS"
        }
        val request = Request(
            sessionId = sessionId,
            sessionGeneration = generation,
            operation = operation,
            timeoutMs = timeoutMs,
            selector = selector,
            containerSelector = containerSelector,
            inputText = inputText,
            maxScrolls = maxScrolls,
            observedPid = observedPid,
            observedStartToken = observedStartToken,
            expectedProcessStartUuid = expectedProcessStartUuid,
            expectedSessionIdentity = expectedSessionIdentity,
        )
        return synchronized(transportLock) {
            if (poisoned || closed) {
                throw CommandTransportException(
                    ErrorCode.TRANSPORT_LOST,
                    operation,
                    -1,
                    generation,
                    TransmissionState.NOT_WRITTEN,
                    IllegalStateException("Driver connection is closed or poisoned"),
                    serial,
                    selector?.render(),
                    timeoutMs,
                )
            }
            transmit(nextRequestId++, request)
        }
    }

    /** Round-trips a connection-level `PING` on the writer/reader lanes. Returns the latency in ms. */
    fun ping(timeoutMs: Long = 5_000): Long {
        val started = System.nanoTime()
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
        return (System.nanoTime() - started) / 1_000_000L
    }

    internal fun executeValidationRequest(
        requestId: Long,
        requestSessionId: String = sessionId,
        requestGeneration: Long = generation,
        operationVersion: Int = OPERATION_VERSION,
    ): Response {
        val request = Request(
            sessionId = requestSessionId,
            sessionGeneration = requestGeneration,
            operation = Operation.HEALTH,
            operationVersion = operationVersion,
            timeoutMs = 5_000,
        )
        val command = synchronized(transportLock) {
            check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
            transmit(requestId, request)
        }
        return try {
            command.await()
        } catch (error: CommandTransportException) {
            throw error.cause ?: error
        }
    }

    internal fun disconnectForValidation() {
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
        if (::reader.isInitialized && Thread.currentThread() !== reader) reader.join(2_000)
    }

    // Caller holds transportLock.
    private fun transmit(requestId: Long, request: Request): PendingCommand {
        val command = PendingCommand(requestId, request.operation, request.timeoutMs, request.selector)
        check(pending.putIfAbsent(requestId, command) == null) { "Request $requestId is already pending" }
        // Explicit validation IDs consume the driver watermark too; never allocate below them.
        nextRequestId = maxOf(nextRequestId, Math.addExact(requestId, 1L))
        val payload = json.encodeToString(request).encodeToByteArray()
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
                    else -> throw IllegalStateException("Unexpected ${frame.type} frame from driver")
                }
            }
        } catch (error: Throwable) {
            if (!closed) poison(error)
        }
    }

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

    private fun Operation.isMutating(): Boolean = when (this) {
        Operation.TAP, Operation.SET_TEXT, Operation.TYPE_TEXT, Operation.SCROLL_UNTIL -> true
        else -> false
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
