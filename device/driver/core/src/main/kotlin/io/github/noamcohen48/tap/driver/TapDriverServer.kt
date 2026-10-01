package io.github.noamcohen48.tap.driver

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.DRIVER_APK_BUILD_ID
import io.github.noamcohen48.tap.protocol.DRIVER_TEST_APK_BUILD_ID
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameCodec
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.Operations
import io.github.noamcohen48.tap.protocol.ProtocolAuthentication
import io.github.noamcohen48.tap.protocol.ProtocolNegotiation
import io.github.noamcohen48.tap.protocol.SUPPORTED_CAPABILITIES
import io.github.noamcohen48.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import io.github.noamcohen48.tap.protocol.UIAUTOMATOR_BUILD_ID
import io.github.noamcohen48.tap.protocol.label
import io.github.noamcohen48.tap.protocol.parsePayload
import io.github.noamcohen48.tap.wire.v1.Authentication
import io.github.noamcohen48.tap.wire.v1.AuthenticationResult
import io.github.noamcohen48.tap.wire.v1.Challenge
import io.github.noamcohen48.tap.wire.v1.Hello
import io.github.noamcohen48.tap.wire.v1.Negotiation
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/**
 * The driver's TAP1 server: listens on loopback, authenticates one host connection and serves
 * it until the transport ends. Runs inside the instrumentation (`TapDriverServerTest`), which
 * supplies the arguments and, in the `validation` flavor only, a real [faults] implementation.
 */
class TapDriverServer(
    private val instrumentation: Instrumentation,
    arguments: Bundle,
    private val faults: FaultHooks = FaultHooks.NONE,
) {
    private val config = SessionConfig.from(arguments::getString)
    private val device =
        UiDevice.getInstance(instrumentation).also {
            // Every UiAutomator lookup first waits for the accessibility event stream to go quiet
            // (default: up to 10 s). On a screen that never settles — a ticker, an indeterminate
            // spinner — that turns each command into a 10 s stall and a watchdog poison. Bound it
            // (Maestro uses 0, Appium's default is 10 s); stability is an explicit wait in Tap.
            Configurator.getInstance().waitForIdleTimeout = UI_AUTOMATOR_IDLE_TIMEOUT_MS
        }
    private val driverInstanceId = UUID.randomUUID().toString()
    private val engine =
        DriverCommandEngine(
            instrumentation,
            device,
            faults,
            SyncProviderClient(instrumentation),
        )

    /** Blocks until the one authenticated connection ends (or the process is killed). */
    fun serve() {
        ServerSocket(config.port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            announceReady()
            while (true) {
                server.accept().use { socket ->
                    // Whole frames go out in one write; don't let Nagle hold them for an ACK.
                    socket.tcpNoDelay = true
                    socket.soTimeout = 10_000
                    val authenticated =
                        try {
                            authenticate(socket.getInputStream(), socket.getOutputStream())
                        } catch (_: Exception) {
                            // A failed or malformed handshake only ends this attempt; Errors propagate.
                            false
                        }
                    if (!authenticated) return@use

                    socket.soTimeout = 0
                    val connection =
                        DriverConnection(
                            socket,
                            engine,
                            faults,
                            config.sessionId,
                            config.generation,
                            config.uninterruptibleGraceMs,
                            config.heartbeatTimeoutMs,
                            onPoisoned = { reason -> onPoisoned(server, reason) },
                        )
                    connection.run()
                    if (connection.isPoisoned && faults.watchdogKillsProcess) {
                        // The executor never came back; nothing may run in this process again.
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    return
                }
            }
        }
    }

    /**
     * Watchdog policy: after the pipeline is poisoned the session cannot be trusted. Report,
     * stop listening, and kill the instrumentation process after a bounded grace so the
     * pending terminal response can still be flushed. [FaultHooks.watchdogKillsProcess] lets a
     * validation fault keep the hung driver alive for the host to terminate.
     */
    private fun onPoisoned(
        server: ServerSocket,
        reason: String,
    ) {
        val marker = "TAP_POISONED session=${config.sessionId} generation=${config.generation} reason=$reason"
        println(marker)
        instrumentation.sendStatus(2, Bundle().apply { putString("tapPoisoned", marker) })
        if (!faults.watchdogKillsProcess) return
        Thread({
            android.os.SystemClock.sleep(POISON_KILL_GRACE_MS)
            try {
                server.close()
            } catch (_: IOException) {
                // Closing is best effort; the kill below ends the process either way.
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }, "tap-driver-poison-kill").apply { isDaemon = true }.start()
    }

    private fun announceReady() {
        val ready = "TAP_READY session=${config.sessionId} generation=${config.generation} " +
            "port=${config.port} instance=$driverInstanceId"
        println(ready)
        instrumentation.sendStatus(2, Bundle().apply { putString("tapReady", ready) })
    }

    /**
     * HELLO → CHALLENGE → AUTH → AUTH_RESULT. The transcript MACs the exact HELLO and CHALLENGE
     * payloads and the serialized negotiation bytes the host sent, never a re-encoding.
     */
    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        val helloFrame = FrameCodec.read(input)
        require(helloFrame.type == FrameType.HELLO && helloFrame.requestId == 0L)
        val hello = parsePayload("HELLO", helloFrame.payload, Hello::parseFrom)
        require(hello.sessionId == config.sessionId && hello.sessionGeneration == config.generation)
        require(ProtocolNegotiation.isValidHello(hello))
        if (ProtocolNegotiation.selectVersion(hello.supportedVersionsList, SUPPORTED_PROTOCOL_VERSIONS) == null) {
            // Say why instead of a bare EOF: the host reports it as a version mismatch.
            val unsupported = AuthenticationResult.newBuilder()
                .setOk(false)
                .setError(ErrorCode.ERR_UNSUPPORTED.label)
                .build()
            FrameCodec.write(output, Frame(FrameType.AUTH_RESULT, 0, unsupported.toByteArray()))
            return false
        }

        val challenge = Challenge.newBuilder()
            .setAndroidApiLevel(android.os.Build.VERSION.SDK_INT)
            .addAllCapabilities(SUPPORTED_CAPABILITIES)
            .setDriverApkBuildId(DRIVER_APK_BUILD_ID)
            .setDriverInstanceId(driverInstanceId)
            .setDriverNonce(ProtocolAuthentication.nonce())
            .setDriverTestApkBuildId(DRIVER_TEST_APK_BUILD_ID)
            .setHostNonce(hello.hostNonce)
            .setSessionGeneration(config.generation)
            .setSessionId(config.sessionId)
            .addAllSupportedOperations(Operations.ALL)
            .addAllSupportedVersions(SUPPORTED_PROTOCOL_VERSIONS)
            .setUiAutomatorBuildId(UIAUTOMATOR_BUILD_ID)
            .build()
        val challengePayload = challenge.toByteArray()
        FrameCodec.write(output, Frame(FrameType.CHALLENGE, 0, challengePayload))

        val authFrame = FrameCodec.read(input)
        require(authFrame.type == FrameType.AUTH && authFrame.requestId == 0L)
        val authentication = parsePayload("AUTH", authFrame.payload, Authentication::parseFrom)
        val negotiationPayload = authentication.negotiation.toByteArray()
        val negotiation = parsePayload("AUTH negotiation", negotiationPayload, Negotiation::parseFrom)
        val validNegotiation = ProtocolNegotiation.isValid(hello, challenge, negotiation)
        val transcript = ProtocolAuthentication.transcript(helloFrame.payload, challengePayload, negotiationPayload)
        val expected = ProtocolAuthentication.hostMac(config.secret, transcript)
        if (
            !validNegotiation ||
            !ProtocolAuthentication.constantTimeEquals(expected, authentication.transcriptHmac)
        ) {
            val rejected = AuthenticationResult.newBuilder()
                .setOk(false)
                .setError(ErrorCode.ERR_UNAUTHENTICATED.label)
                .build()
            FrameCodec.write(output, Frame(FrameType.AUTH_RESULT, 0, rejected.toByteArray()))
            return false
        }

        val result = AuthenticationResult.newBuilder()
            .setOk(true)
            .addAllEnabledCapabilities(negotiation.enabledCapabilitiesList)
            .setSelectedVersion(negotiation.selectedVersion)
            .setTranscriptHmac(ProtocolAuthentication.driverMac(config.secret, transcript))
            .build()
        FrameCodec.write(output, Frame(FrameType.AUTH_RESULT, 0, result.toByteArray()))
        return true
    }
}

private const val POISON_KILL_GRACE_MS = 2_000L

/** Upper bound for UiAutomator's implicit wait-for-idle before each lookup or gesture. */
private const val UI_AUTOMATOR_IDLE_TIMEOUT_MS = 1_000L
