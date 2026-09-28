package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.Done
import io.github.noamcohen48.tap.protocol.BlobFrames
import io.github.noamcohen48.tap.protocol.DRIVER_APK_BUILD_ID
import io.github.noamcohen48.tap.protocol.DRIVER_TEST_APK_BUILD_ID
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameCodec
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.MAX_BLOB_CHUNK_BYTES
import io.github.noamcohen48.tap.protocol.Operations
import io.github.noamcohen48.tap.protocol.ProtocolAuthentication
import io.github.noamcohen48.tap.protocol.ProtocolNegotiation
import io.github.noamcohen48.tap.protocol.SUPPORTED_CAPABILITIES
import io.github.noamcohen48.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import io.github.noamcohen48.tap.protocol.UIAUTOMATOR_BUILD_ID
import io.github.noamcohen48.tap.wire.v1.ArtifactInfo
import io.github.noamcohen48.tap.wire.v1.Authentication
import io.github.noamcohen48.tap.wire.v1.AuthenticationResult
import io.github.noamcohen48.tap.wire.v1.BlobEnd
import io.github.noamcohen48.tap.wire.v1.BlobStart
import io.github.noamcohen48.tap.wire.v1.Challenge
import io.github.noamcohen48.tap.wire.v1.Hello
import io.github.noamcohen48.tap.wire.v1.Negotiation
import io.github.noamcohen48.tap.wire.v1.ProtocolVersion
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Response
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Loopback stand-in for the device driver: performs the real protocol handshake, then hands
 * every authenticated frame to the test, which replies explicitly. Nothing is executed, so tests
 * control response ordering, cancellation, heartbeats, and transport loss precisely.
 */
class FakeDriverServer(
    private val sessionId: String,
    private val generation: Long,
    secret: ByteArray,
    /** When true, the handshake accepts any session id/generation (for session-open tests where
     * the id is generated inside the code under test). */
    private val acceptAnySession: Boolean = false,
    /** Build ids the fake driver reports in its challenge; tests change them to prove the
     * host rejects a driver of another build. */
    private val driverApkBuildId: String = DRIVER_APK_BUILD_ID,
    private val driverTestApkBuildId: String = DRIVER_TEST_APK_BUILD_ID,
    /** Protocol versions the fake driver speaks; with none in common it refuses the HELLO. */
    private val supportedVersions: List<ProtocolVersion> = SUPPORTED_PROTOCOL_VERSIONS,
) : AutoCloseable {
    /** The session secret the handshake HMACs with; tests update it when the code under test
     * generates the secret itself (it travels in the instrumentation command). */
    @Volatile var secret: ByteArray = secret
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val received = LinkedBlockingQueue<Frame>()

    @Volatile private var client: Socket? = null
    private val acceptor =
        Thread(::serve, "fake-driver").apply {
            isDaemon = true
            start()
        }

    val port: Int get() = server.localPort

    fun nextFrame(timeoutMs: Long = 2_000): Frame =
        received.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("Driver received no frame within $timeoutMs ms")

    /** The next frame, which must be a `REQUEST`, decoded. */
    fun nextRequest(timeoutMs: Long = 2_000): Pair<Frame, Request> {
        val frame = nextFrame(timeoutMs)
        check(frame.type == FrameType.REQUEST) { "Expected REQUEST, got ${frame.type}" }
        return frame to Request.parseFrom(frame.payload)
    }

    /** Sends [response] as is; tests stamp identity themselves when they assert on it. */
    fun respond(
        requestId: Long,
        response: Response,
    ) {
        write(Frame(FrameType.RESPONSE, requestId, response.toByteArray()))
    }

    fun pong() = write(Frame(FrameType.PONG, 0, byteArrayOf()))

    /**
     * Streams [bytes] as a blob followed by a successful artifact response. [corrupt] lets a
     * test damage one aspect of the transfer after the frames were built.
     */
    fun sendArtifact(
        requestId: Long,
        bytes: ByteArray,
        corrupt: Corruption = Corruption.NONE,
    ): ArtifactInfo {
        val blobId = UUID.randomUUID()
        val sha256 = BlobFrames.sha256Hex(bytes)
        val start =
            BlobStart
                .newBuilder()
                .setBlobId(blobId.toString())
                .setMediaType("image/png")
                .setTotalLength(bytes.size.toLong())
                .setSha256(sha256)
                .build()
        write(Frame(FrameType.BLOB_START, requestId, start.toByteArray()))
        val chunks = bytes.toList().chunked(MAX_BLOB_CHUNK_BYTES).map { it.toByteArray() }
        chunks.forEachIndexed { index, chunk ->
            if (corrupt == Corruption.DROP_CHUNK && index == 0) return@forEachIndexed
            val data = if (corrupt == Corruption.FLIP_BYTE && index == 0) chunk.copyOf().also { it[0] = (it[0] + 1).toByte() } else chunk
            val wireIndex = if (corrupt == Corruption.REORDER && chunks.size > 1) chunks.size - 1 - index else index
            write(Frame(FrameType.BLOB_CHUNK, requestId, BlobFrames.encodeChunk(blobId, wireIndex, data, 0, data.size)))
        }
        if (corrupt != Corruption.NO_END) {
            write(
                Frame(
                    FrameType.BLOB_END,
                    requestId,
                    BlobEnd
                        .newBuilder()
                        .setBlobId(blobId.toString())
                        .setByteCount(bytes.size.toLong())
                        .setSha256(sha256)
                        .build()
                        .toByteArray(),
                ),
            )
        }
        val info =
            ArtifactInfo
                .newBuilder()
                .setBlobId(blobId.toString())
                .setMediaType("image/png")
                .setByteCount(bytes.size.toLong())
                .setSha256(sha256)
                .setWidth(4)
                .setHeight(4)
                .build()
        val result = CommandResult.newBuilder().setDurationMs(5).setDone(Done.getDefaultInstance())
        respond(requestId, Response.newBuilder().setResult(result).setArtifact(info).build())
        return info
    }

    enum class Corruption { NONE, FLIP_BYTE, DROP_CHUNK, REORDER, NO_END }

    fun write(frame: Frame) {
        val socket = requireNotNull(client) { "No authenticated client" }
        synchronized(socket) { FrameCodec.write(socket.getOutputStream(), frame) }
    }

    /** Abruptly closes the authenticated connection, as a dying driver would. */
    fun dropConnection() {
        client?.let { socket ->
            runCatching { socket.setSoLinger(true, 0) }
            runCatching { socket.close() }
        }
    }

    override fun close() {
        dropConnection()
        runCatching { server.close() }
        acceptor.join(2_000)
    }

    private fun serve() {
        try {
            val socket = server.accept()
            socket.tcpNoDelay = true
            client = socket
            authenticate(socket)
            while (true) {
                received.put(FrameCodec.read(socket.getInputStream()))
            }
        } catch (_: EOFException) {
        } catch (_: Throwable) {
        }
    }

    private fun authenticate(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val helloFrame = FrameCodec.read(input)
        check(helloFrame.type == FrameType.HELLO)
        val hello = Hello.parseFrom(helloFrame.payload)
        if (!acceptAnySession) {
            check(hello.sessionId == sessionId && hello.sessionGeneration == generation)
        }
        // Session-open tests generate the id inside the code under test; echo it back so the
        // client's challenge check (sessionId/sessionGeneration equality) can succeed. Fixed-id
        // tests keep the constructor values, preserving the fencing check they exercise.
        if (ProtocolNegotiation.selectVersion(hello.supportedVersionsList, supportedVersions) == null) {
            val unsupported = AuthenticationResult.newBuilder().setOk(false).setError("UNSUPPORTED").build()
            FrameCodec.write(output, Frame(FrameType.AUTH_RESULT, 0, unsupported.toByteArray()))
            socket.close()
            return
        }
        val challengeSessionId = if (acceptAnySession) hello.sessionId else sessionId
        val challengeGeneration = if (acceptAnySession) hello.sessionGeneration else generation
        val challenge =
            Challenge
                .newBuilder()
                .setAndroidApiLevel(34)
                .addAllCapabilities(SUPPORTED_CAPABILITIES)
                .setDriverApkBuildId(driverApkBuildId)
                .setDriverInstanceId("fake-driver")
                .setDriverNonce(ProtocolAuthentication.nonce())
                .setDriverTestApkBuildId(driverTestApkBuildId)
                .setHostNonce(hello.hostNonce)
                .setSessionGeneration(challengeGeneration)
                .setSessionId(challengeSessionId)
                .addAllSupportedOperations(Operations.ALL)
                .addAllSupportedVersions(supportedVersions)
                .setUiAutomatorBuildId(UIAUTOMATOR_BUILD_ID)
                .build()
        val challengePayload = challenge.toByteArray()
        FrameCodec.write(output, Frame(FrameType.CHALLENGE, 0, challengePayload))

        val authFrame = FrameCodec.read(input)
        check(authFrame.type == FrameType.AUTH)
        val authentication = Authentication.parseFrom(authFrame.payload)
        val negotiationPayload = authentication.negotiation.toByteArray()
        val negotiation = Negotiation.parseFrom(negotiationPayload)
        check(ProtocolNegotiation.isValid(hello, challenge, negotiation))
        val transcript = ProtocolAuthentication.transcript(helloFrame.payload, challengePayload, negotiationPayload)
        check(
            ProtocolAuthentication.constantTimeEquals(
                ProtocolAuthentication.hostMac(secret, transcript),
                authentication.transcriptHmac,
            ),
        )
        val result =
            AuthenticationResult
                .newBuilder()
                .setOk(true)
                .addAllEnabledCapabilities(negotiation.enabledCapabilitiesList)
                .setSelectedVersion(negotiation.selectedVersion)
                .setTranscriptHmac(ProtocolAuthentication.driverMac(secret, transcript))
                .build()
        FrameCodec.write(output, Frame(FrameType.AUTH_RESULT, 0, result.toByteArray()))
    }
}
