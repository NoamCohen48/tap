package com.company.tap.driver

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.uiautomator.UiDevice
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
import com.company.tap.protocol.OPERATION_VERSION
import com.company.tap.protocol.Operation
import com.company.tap.protocol.OperationSupport
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
    private val device = UiDevice.getInstance(instrumentation)
    private val driverInstanceId = UUID.randomUUID().toString()
    private val faults = FaultController(instrumentation, config.faultPoint, config.syncAuthority)
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
                    ClientConnection(
                        socket,
                        engine,
                        faults,
                        config.sessionId,
                        config.generation,
                    ).run()
                    return
                }
            }
        }
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
            supportedOperations = Operation.entries
                .map { OperationSupport(it.name, OPERATION_VERSION) }
                .sortedBy(OperationSupport::name),
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

private data class SessionConfig(
    val sessionId: String,
    val generation: Long,
    val secret: ByteArray,
    val port: Int,
    val expectedAut: String,
    val syncAuthority: String,
    val allowedSystemPackages: Set<String>,
    val faultPoint: FaultPoint,
) {
    companion object {
        fun from(arguments: Bundle): SessionConfig {
            val sessionId = requireNotNull(arguments.getString("tapSession"))
            val generation = requireNotNull(arguments.getString("tapGeneration")).toLong()
            val secret = Base64.getUrlDecoder().decode(requireNotNull(arguments.getString("tapSecret")))
            val port = requireNotNull(arguments.getString("tapPort")).toInt()
            val expectedAut = requireNotNull(arguments.getString("tapAutPackage"))
            val syncAuthority = requireNotNull(arguments.getString("tapSyncAuthority"))
            val allowedSystemPackages = requireNotNull(arguments.getString("tapSystemPackages"))
                .split(',')
                .filter(String::isNotBlank)
                .toSet()
            require(allowedSystemPackages.isNotEmpty())
            val faultPoint = FaultPoint.valueOf(
                arguments.getString("tapFaultPoint") ?: FaultPoint.NONE.name
            )
            return SessionConfig(
                sessionId,
                generation,
                secret,
                port,
                expectedAut,
                syncAuthority,
                allowedSystemPackages,
                faultPoint,
            )
        }
    }
}
