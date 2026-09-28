package io.github.noamcohen48.tap.host.validation

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.host.DriverClient
import io.github.noamcohen48.tap.host.DriverHandshakeException
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameCodec
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.HOST_BUILD_ID
import io.github.noamcohen48.tap.protocol.ProtocolAuthentication
import io.github.noamcohen48.tap.protocol.Requests
import io.github.noamcohen48.tap.protocol.errorCode
import io.github.noamcohen48.tap.protocol.label
import io.github.noamcohen48.tap.protocol.ok
import io.github.noamcohen48.tap.protocol.protocolVersion
import io.github.noamcohen48.tap.wire.v1.AuthenticationResult
import io.github.noamcohen48.tap.wire.v1.Hello
import io.github.noamcohen48.tap.wire.v1.Request
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The driver's session fencing, probed with what a well-behaved host never sends: stale
 * generations, unknown operations, malformed payloads, reused request IDs, an incompatible
 * protocol version and a wrong session secret.
 */
@DeviceTest
class FencingTest {
    @OnEachDevice
    fun `stale generation, unknown operation and malformed payload are rejected`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val validation = session.client.validationTransport()
                // ID 1 was consumed by the startup health check.
                val oldGeneration = validation.executeHealth(requestId = 2, generation = session.generation - 1)
                check(!oldGeneration.ok && oldGeneration.errorCode == ErrorCode.ERR_SESSION_MISMATCH) {
                    "Old-generation request was not rejected: $oldGeneration"
                }
                // An operation this driver does not know (a newer host's body case, here field 99)
                // parses as a request with no body: UNSUPPORTED, after the session identity checks.
                val envelope =
                    Request
                        .newBuilder()
                        .setSessionId(session.sessionId)
                        .setGeneration(session.generation)
                        .setTimeoutMs(5_000)
                        .build()
                        .toByteArray()
                val unsupported = validation.executeRaw(requestId = 3, payload = envelope + byteArrayOf(0x9A.toByte(), 0x06, 0x00))
                check(!unsupported.ok && unsupported.errorCode == ErrorCode.ERR_UNSUPPORTED) {
                    "Unknown operation was not rejected: $unsupported"
                }
                // Bytes that are not a protobuf message at all (field 1 with the invalid wire type 7).
                val malformed = validation.executeRaw(requestId = 4, payload = byteArrayOf(0x0F))
                check(!malformed.ok && malformed.errorCode == ErrorCode.ERR_INVALID_REQUEST) {
                    "Malformed request payload was not rejected: $malformed"
                }
                // Rejections are answers, not transport failures: the session stays usable.
                check(session.client.send(Requests.health()).ok) { "Session unusable after fenced requests" }
            }
        }

    @OnEachDevice
    fun `a consumed request id closes the connection`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession { session ->
                val validation = session.client.validationTransport()
                val malformed = validation.executeRaw(requestId = 2, payload = byteArrayOf(0x0F))
                check(!malformed.ok && malformed.errorCode == ErrorCode.ERR_INVALID_REQUEST) {
                    "Malformed request payload was not rejected: $malformed"
                }
                // A reused ID (here one consumed by the rejected payload) is a protocol violation: the
                // driver sends CLOSE with a DUPLICATE_OR_STALE reason instead of answering on that ID.
                val duplicate =
                    try {
                        validation.executeHealth(requestId = 2)
                    } catch (closed: Exception) {
                        closed
                    }
                check(duplicate is Exception && ErrorCode.ERR_DUPLICATE_OR_STALE.label in duplicate.message.orEmpty()) {
                    "Consumed request ID did not close the connection: $duplicate"
                }
            }
        }

    @OnEachDevice
    fun `an unsupported protocol version and a wrong secret are refused before a session`(serial: String) =
        deviceTest(serial) { device ->
            device.withSession(connect = false) { session ->
                assertUnsupportedProtocolRejected(session.hostPort, session.sessionId, session.generation)
                val wrongSecret = session.secret.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
                assertInvalidAuthenticationWithRetry(session, wrongSecret)
                // The driver still accepts its real host afterwards.
                check(session.connect().send(Requests.health()).ok)
            }
        }

    private fun assertUnsupportedProtocolRejected(
        hostPort: Int,
        sessionId: String,
        generation: Long,
    ) {
        val hello =
            Hello
                .newBuilder()
                .setHostBuildId(HOST_BUILD_ID)
                .setHostNonce(ProtocolAuthentication.nonce())
                .setSessionGeneration(generation)
                .setSessionId(sessionId)
                .addSupportedVersions(protocolVersion(99, 0))
                .build()
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", hostPort), 10_000)
            socket.soTimeout = 10_000
            FrameCodec.write(socket.getOutputStream(), Frame(FrameType.HELLO, 0, hello.toByteArray()))
            // The driver says why before it closes: AUTH_RESULT{ok=false, UNSUPPORTED}, then EOF.
            val refusal = FrameCodec.read(socket.getInputStream())
            check(refusal.type == FrameType.AUTH_RESULT) { "Driver answered an incompatible protocol version with ${refusal.type}" }
            val result = AuthenticationResult.parseFrom(refusal.payload)
            check(!result.ok && result.error == ErrorCode.ERR_UNSUPPORTED.label) {
                "Driver accepted an incompatible application protocol version: $result"
            }
            check(runCatching { FrameCodec.read(socket.getInputStream()) }.isFailure) {
                "Driver kept the connection open after refusing the protocol version"
            }
        }
    }

    private suspend fun assertInvalidAuthenticationWithRetry(
        session: FaultSession,
        wrongSecret: ByteArray,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            check(session.running.process.isAlive) { "Instrumentation exited before readiness" }
            val failure =
                runCatching {
                    DriverClient.connect(session.hostPort, session.sessionId, session.generation, wrongSecret).close()
                }.exceptionOrNull()
            if (failure is DriverHandshakeException && failure.message.orEmpty().endsWith(": UNAUTHENTICATED")) return
            lastFailure = failure
            delay(25)
        }
        error("Invalid-authentication check timed out: ${lastFailure?.message}")
    }
}
