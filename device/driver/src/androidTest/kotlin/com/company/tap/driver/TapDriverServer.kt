package com.company.tap.driver

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import com.company.tap.driver.engine.CommandPipeline
import com.company.tap.protocol.Authentication
import com.company.tap.protocol.AuthenticationResult
import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.Challenge
import com.company.tap.protocol.DRIVER_APK_BUILD_ID
import com.company.tap.protocol.DRIVER_TEST_APK_BUILD_ID
import com.company.tap.protocol.Frame
import com.company.tap.protocol.FrameCodec
import com.company.tap.protocol.FrameType
import com.company.tap.protocol.Hello
import com.company.tap.protocol.Command
import com.company.tap.protocol.ProtocolAuthentication
import com.company.tap.protocol.ProtocolNegotiation
import com.company.tap.protocol.SUPPORTED_CAPABILITIES
import com.company.tap.protocol.SUPPORTED_PROTOCOL_VERSIONS
import com.company.tap.protocol.UIAUTOMATOR_BUILD_ID
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

internal class TapDriverServer(
    private val instrumentation: Instrumentation,
    arguments: Bundle,
) {
    private val config = SessionConfig.from(arguments)
    private val device = UiDevice.getInstance(instrumentation).also {
        // Every UiAutomator lookup first waits for the accessibility event stream to go quiet
        // (default: up to 10 s). On a screen that never settles — a ticker, an indeterminate
        // spinner — that turns each command into a 10 s stall and a watchdog poison. Bound it
        // (Maestro uses 0, Appium's default is 10 s); stability is an explicit wait in Tap.
        Configurator.getInstance().waitForIdleTimeout = UI_AUTOMATOR_IDLE_TIMEOUT_MS
    }
    private val driverInstanceId = UUID.randomUUID().toString()
    private val faults = FaultController(instrumentation, config.faultPoint, config.faultAuthority)
    private val engine = DriverCommandEngine(
        instrumentation,
        device,
        config.expectedAut,
        config.allowedSystemPackages,
        faults,
        SyncProviderClient(instrumentation, config.expectedAut, config.syncAuthority),
    )

    fun serve() {
        ServerSocket(config.port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            announceReady()
            while (true) {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val authenticated = runCatching {
                        authenticate(socket.getInputStream(), socket.getOutputStream())
                    }.getOrDefault(false)
                    if (!authenticated) return@use

                    socket.soTimeout = 0
                    val connection = ClientConnection(
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
                    if (connection.isPoisoned && config.watchdogKillsProcess) {
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
     * pending terminal response can still be flushed. The late-work fault disables the kill
     * because that scenario deliberately proves host-side termination of a hung driver.
     */
    private fun onPoisoned(server: ServerSocket, reason: String) {
        val marker = "TAP_POISONED session=${config.sessionId} generation=${config.generation} reason=$reason"
        println(marker)
        instrumentation.sendStatus(2, Bundle().apply { putString("tapPoisoned", marker) })
        if (!config.watchdogKillsProcess) return
        Thread({
            android.os.SystemClock.sleep(POISON_KILL_GRACE_MS)
            runCatching { server.close() }
            android.os.Process.killProcess(android.os.Process.myPid())
        }, "tap-driver-poison-kill").apply { isDaemon = true }.start()
    }

    private fun announceReady() {
        val ready = "TAP_READY session=${config.sessionId} generation=${config.generation} " +
            "port=${config.port} instance=$driverInstanceId"
        println(ready)
        instrumentation.sendStatus(2, Bundle().apply { putString("tapReady", ready) })
    }

    private fun authenticate(input: InputStream, output: OutputStream): Boolean {
        val helloFrame = FrameCodec.read(input)
        require(helloFrame.type == FrameType.HELLO && helloFrame.requestId == 0L)
        val hello = CanonicalJson.decodeCanonical<Hello>(helloFrame.payload)
        require(hello.sessionId == config.sessionId && hello.sessionGeneration == config.generation)
        require(ProtocolNegotiation.isValidHello(hello))
        if (ProtocolNegotiation.selectVersion(hello.supportedVersions, SUPPORTED_PROTOCOL_VERSIONS) == null) {
            return false
        }

        val nonceBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val challenge = Challenge(
            androidApiLevel = android.os.Build.VERSION.SDK_INT,
            capabilities = SUPPORTED_CAPABILITIES,
            driverApkBuildId = DRIVER_APK_BUILD_ID,
            driverInstanceId = driverInstanceId,
            driverNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes),
            driverTestApkBuildId = DRIVER_TEST_APK_BUILD_ID,
            hostNonce = hello.hostNonce,
            sessionGeneration = config.generation,
            sessionId = config.sessionId,
            supportedOperations = Command.names,
            supportedVersions = SUPPORTED_PROTOCOL_VERSIONS,
            uiAutomatorBuildId = UIAUTOMATOR_BUILD_ID,
        )
        val challengePayload = CanonicalJson.encode(challenge)
        FrameCodec.write(output, Frame(FrameType.CHALLENGE, 0, challengePayload))

        val authFrame = FrameCodec.read(input)
        require(authFrame.type == FrameType.AUTH && authFrame.requestId == 0L)
        val authentication = CanonicalJson.decodeCanonical<Authentication>(authFrame.payload)
        val validNegotiation = ProtocolNegotiation.isValid(hello, challenge, authentication.negotiation)
        val transcript = ProtocolAuthentication.transcript(
            helloFrame.payload,
            challengePayload,
            CanonicalJson.encode(authentication.negotiation),
        )
        val expected = ProtocolAuthentication.hostMac(config.secret, transcript)
        if (
            !validNegotiation ||
            !ProtocolAuthentication.constantTimeEquals(expected, authentication.transcriptHmac)
        ) {
            FrameCodec.write(
                output,
                Frame(
                    FrameType.AUTH_RESULT,
                    0,
                    CanonicalJson.encode(AuthenticationResult(ok = false, error = "UNAUTHENTICATED")),
                ),
            )
            return false
        }

        val result = AuthenticationResult(
            ok = true,
            enabledCapabilities = authentication.negotiation.enabledCapabilities,
            selectedVersion = authentication.negotiation.selectedVersion,
            transcriptHmac = ProtocolAuthentication.driverMac(config.secret, transcript),
        )
        FrameCodec.write(output, Frame(FrameType.AUTH_RESULT, 0, CanonicalJson.encode(result)))
        return true
    }

}

private const val POISON_KILL_GRACE_MS = 2_000L

private data class SessionConfig(
    val sessionId: String,
    val generation: Long,
    val secret: ByteArray,
    val port: Int,
    val expectedAut: String,
    val syncAuthority: String,
    /** Fixture-only provider used by the late-mutation fault; unset for product AUTs. */
    val faultAuthority: String,
    val allowedSystemPackages: Set<String>,
    val faultPoint: FaultPoint,
    val uninterruptibleGraceMs: Long,
    val heartbeatTimeoutMs: Long,
) {
    /** The late-work fault must leave a hung driver for the host to terminate. */
    val watchdogKillsProcess: Boolean get() = faultPoint != FaultPoint.LATE_UNINTERRUPTIBLE

    companion object {
        fun from(arguments: Bundle): SessionConfig {
            val sessionId = requireNotNull(arguments.getString("tapSession"))
            val generation = requireNotNull(arguments.getString("tapGeneration")).toLong()
            val secret = Base64.getUrlDecoder().decode(requireNotNull(arguments.getString("tapSecret")))
            val port = requireNotNull(arguments.getString("tapPort")).toInt()
            val expectedAut = requireNotNull(arguments.getString("tapAutPackage"))
            val syncAuthority = requireNotNull(arguments.getString("tapSyncAuthority"))
            val faultAuthority = arguments.getString("tapFaultAuthority").orEmpty()
            val allowedSystemPackages = requireNotNull(arguments.getString("tapSystemPackages"))
                .split(',')
                .filter(String::isNotBlank)
                .toSet()
            require(allowedSystemPackages.isNotEmpty())
            val faultPoint = FaultPoint.valueOf(
                arguments.getString("tapFaultPoint") ?: FaultPoint.NONE.name
            )
            val uninterruptibleGraceMs = arguments.getString("tapUninterruptibleGraceMs")?.toLong()
                ?: CommandPipeline.DEFAULT_UNINTERRUPTIBLE_GRACE_MS
            require(uninterruptibleGraceMs >= 0)
            val heartbeatTimeoutMs = arguments.getString("tapHeartbeatTimeoutMs")?.toLong()
                ?: DEFAULT_HEARTBEAT_TIMEOUT_MS
            require(heartbeatTimeoutMs > 0) { "The heartbeat timeout is bounded; it cannot be disabled" }
            return SessionConfig(
                sessionId,
                generation,
                secret,
                port,
                expectedAut,
                syncAuthority,
                faultAuthority,
                allowedSystemPackages,
                faultPoint,
                uninterruptibleGraceMs,
                heartbeatTimeoutMs,
            )
        }
    }
}

/** A silent host for this long means it is gone; the driver poisons itself and exits. */
private const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 30_000L

/** Upper bound for UiAutomator's implicit wait-for-idle before each lookup or gesture. */
private const val UI_AUTOMATOR_IDLE_TIMEOUT_MS = 1_000L
