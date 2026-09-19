package com.company.tap.host

import com.company.tap.protocol.ArtifactInfo
import com.company.tap.protocol.Authentication
import com.company.tap.protocol.BlobEnd
import com.company.tap.protocol.BlobFrames
import com.company.tap.protocol.BlobStart
import com.company.tap.protocol.MAX_BLOB_CHUNK_BYTES
import com.company.tap.protocol.AuthenticationResult
import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.Challenge
import com.company.tap.protocol.DRIVER_APK_BUILD_ID
import com.company.tap.protocol.DRIVER_TEST_APK_BUILD_ID
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Hello
import com.company.tap.protocol.OPERATION_VERSION
import com.company.tap.protocol.Operation
import com.company.tap.protocol.OperationSupport
import com.company.tap.protocol.ProtocolAuthentication
import com.company.tap.protocol.ProtocolNegotiation
import com.company.tap.protocol.Response
import com.company.tap.protocol.SUPPORTED_CAPABILITIES
import com.company.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import com.company.tap.protocol.UIAUTOMATOR_BUILD_ID
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Loopback stand-in for the device driver: performs the real protocol 1.0 handshake, then hands
 * every authenticated frame to the test, which replies explicitly. Nothing is executed, so tests
 * control response ordering, cancellation, heartbeats, and transport loss precisely.
 */
class FakeDriverServer(
    private val sessionId: String,
    private val generation: Long,
    private val secret: ByteArray,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val received = LinkedBlockingQueue<Frame>()
    @Volatile private var client: Socket? = null
    private val acceptor = Thread(::serve, "fake-driver").apply { isDaemon = true; start() }

    val port: Int get() = server.localPort

    fun nextFrame(timeoutMs: Long = 2_000): Frame =
        received.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("Driver received no frame within $timeoutMs ms")

    fun respond(requestId: Long, response: Response) {
        write(Frame(FrameType.RESPONSE, requestId, json.encodeToString(response).encodeToByteArray()))
    }

    fun pong() = write(Frame(FrameType.PONG, 0, byteArrayOf()))

    /**
     * Streams [bytes] as a blob followed by a successful artifact response. [corrupt] lets a
     * test damage one aspect of the transfer after the frames were built.
     */
    fun sendArtifact(requestId: Long, bytes: ByteArray, corrupt: Corruption = Corruption.NONE): ArtifactInfo {
        val blobId = UUID.randomUUID()
        val sha256 = BlobFrames.sha256Hex(bytes)
        val start = BlobStart(blobId.toString(), "image/png", bytes.size.toLong(), sha256)
        write(Frame(FrameType.BLOB_START, requestId, json.encodeToString(start).encodeToByteArray()))
        val chunks = bytes.toList().chunked(MAX_BLOB_CHUNK_BYTES).map { it.toByteArray() }
        chunks.forEachIndexed { index, chunk ->
            if (corrupt == Corruption.DROP_CHUNK && index == 0) return@forEachIndexed
            val data = if (corrupt == Corruption.FLIP_BYTE && index == 0) chunk.copyOf().also { it[0] = (it[0] + 1).toByte() } else chunk
            val wireIndex = if (corrupt == Corruption.REORDER && chunks.size > 1) chunks.size - 1 - index else index
            write(Frame(FrameType.BLOB_CHUNK, requestId, BlobFrames.encodeChunk(blobId, wireIndex, data, 0, data.size)))
        }
        if (corrupt != Corruption.NO_END) {
            write(Frame(FrameType.BLOB_END, requestId, json.encodeToString(BlobEnd(blobId.toString(), bytes.size.toLong(), sha256)).encodeToByteArray()))
        }
        val info = ArtifactInfo(blobId.toString(), "image/png", bytes.size.toLong(), sha256, 4, 4)
        respond(requestId, Response(true, durationMs = 5, artifact = info))
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
        val hello = CanonicalJson.decodeCanonical<Hello>(helloFrame.payload)
        check(hello.sessionId == sessionId && hello.sessionGeneration == generation)
        val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
        val challenge = Challenge(
            androidApiLevel = 34,
            capabilities = SUPPORTED_CAPABILITIES,
            driverApkBuildId = DRIVER_APK_BUILD_ID,
            driverInstanceId = "fake-driver",
            driverNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
            driverTestApkBuildId = DRIVER_TEST_APK_BUILD_ID,
            hostNonce = hello.hostNonce,
            sessionGeneration = generation,
            sessionId = sessionId,
            supportedOperations = Operation.entries
                .map { OperationSupport(it.name, OPERATION_VERSION) }
                .sortedBy(OperationSupport::name),
            supportedVersions = SUPPORTED_PROTOCOL_VERSIONS,
            uiAutomatorBuildId = UIAUTOMATOR_BUILD_ID,
        )
        val challengePayload = CanonicalJson.encode(challenge)
        FrameCodec.write(output, Frame(FrameType.CHALLENGE, 0, challengePayload))

        val authFrame = FrameCodec.read(input)
        check(authFrame.type == FrameType.AUTH)
        val authentication = CanonicalJson.decodeCanonical<Authentication>(authFrame.payload)
        check(ProtocolNegotiation.isValid(hello, challenge, authentication.negotiation))
        val transcript = ProtocolAuthentication.transcript(
            helloFrame.payload,
            challengePayload,
            CanonicalJson.encode(authentication.negotiation),
        )
        check(
            ProtocolAuthentication.constantTimeEquals(
                ProtocolAuthentication.hostMac(secret, transcript),
                authentication.transcriptHmac,
            )
        )
        val result = AuthenticationResult(
            ok = true,
            enabledCapabilities = authentication.negotiation.enabledCapabilities,
            selectedVersion = authentication.negotiation.selectedVersion,
            transcriptHmac = ProtocolAuthentication.driverMac(secret, transcript),
        )
        FrameCodec.write(output, Frame(FrameType.AUTH_RESULT, 0, CanonicalJson.encode(result)))
    }
}
