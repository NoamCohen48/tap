package com.company.tap.host

import com.company.tap.protocol.Authentication
import com.company.tap.protocol.AuthenticationResult
import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.Challenge
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
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
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = Socket()
    private var nextRequestId = 1L
    private var poisoned = false
    private var closed = false
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
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    @Synchronized
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
    ): Response {
        require(timeoutMs in 0..MAX_REQUEST_TIMEOUT_MS) {
            "timeoutMs must be between 0 and $MAX_REQUEST_TIMEOUT_MS"
        }
        if (poisoned || closed) {
            throw CommandTransportException(
                CommandErrorCode.TRANSPORT_LOST,
                operation,
                -1,
                generation,
                TransmissionState.NOT_WRITTEN,
                IllegalStateException("Driver connection is closed or poisoned"),
            )
        }
        socket.soTimeout = remainingTimeoutMs((timeoutMs + 5_000).toInt())
        val requestId = nextRequestId++
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
        val payload = json.encodeToString(request).encodeToByteArray()
        var state = TransmissionState.NOT_WRITTEN
        try {
            state = TransmissionState.WRITING
            writeFrame(Frame(FrameType.REQUEST, requestId, payload), remainingTimeoutMs(10_000))
            state = TransmissionState.WRITTEN
            val response = FrameCodec.read(socket.getInputStream())
            check(response.type == FrameType.RESPONSE && response.requestId == requestId)
            val decoded = json.decodeFromString<Response>(response.payload.decodeToString())
            state = TransmissionState.TERMINAL_RESPONSE
            return decoded
        } catch (error: Throwable) {
            poisoned = true
            runCatching { socket.close() }
            val code = if (operation.isMutating() && state != TransmissionState.NOT_WRITTEN) {
                CommandErrorCode.INDETERMINATE
            } else {
                CommandErrorCode.TRANSPORT_LOST
            }
            throw CommandTransportException(code, operation, requestId, generation, state, error)
        }
    }

    @Synchronized
    internal fun executeValidationRequest(
        requestId: Long,
        requestSessionId: String = sessionId,
        requestGeneration: Long = generation,
        operationVersion: Int = OPERATION_VERSION,
    ): Response {
        check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
        val request = Request(
            sessionId = requestSessionId,
            sessionGeneration = requestGeneration,
            operation = Operation.HEALTH,
            operationVersion = operationVersion,
            timeoutMs = 5_000,
        )
        return try {
            socket.soTimeout = remainingTimeoutMs(10_000)
            writeFrame(
                Frame(FrameType.REQUEST, requestId, json.encodeToString(request).encodeToByteArray()),
                remainingTimeoutMs(10_000),
            )
            val response = FrameCodec.read(socket.getInputStream())
            check(response.type == FrameType.RESPONSE && response.requestId == requestId)
            json.decodeFromString<Response>(response.payload.decodeToString())
        } catch (error: Throwable) {
            poisoned = true
            runCatching { socket.close() }
            throw error
        }
    }

    @Synchronized
    internal fun disconnectForValidation() {
        check(!poisoned && !closed) { "Driver connection is closed or poisoned" }
        poisoned = true
        socket.close()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (!poisoned) runCatching {
            writeFrame(Frame(FrameType.CLOSE, 0, byteArrayOf()), remainingTimeoutMs(5_000))
        }
        socket.close()
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

enum class CommandErrorCode {
    TRANSPORT_LOST,
    INDETERMINATE,
}

enum class TransmissionState {
    NOT_WRITTEN,
    WRITING,
    WRITTEN,
    TERMINAL_RESPONSE,
}

class CommandTransportException(
    val code: CommandErrorCode,
    val operation: Operation,
    val requestId: Long,
    val sessionGeneration: Long,
    val transmissionState: TransmissionState,
    cause: Throwable,
) : RuntimeException(
    "$code during $operation request $requestId in generation $sessionGeneration ($transmissionState)",
    cause,
)
