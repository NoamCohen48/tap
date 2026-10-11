package io.github.noamcohen48.tap.protocol

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.wire.v1.Challenge
import io.github.noamcohen48.tap.wire.v1.Hello
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticationTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val transcript = ProtocolAuthentication.transcript("hello".encodeToByteArray(), "challenge".encodeToByteArray(), "negotiation".encodeToByteArray())

    @Test
    fun hostAndDriverMacsAreDomainSeparated() {
        val hostMac = ProtocolAuthentication.hostMac(secret, transcript)
        val driverMac = ProtocolAuthentication.driverMac(secret, transcript)

        assertEquals(32, hostMac.size())
        assertNotEquals(hostMac, driverMac)
        assertEquals(hostMac, ProtocolAuthentication.hostMac(secret, transcript), "deterministic")
        assertTrue(ProtocolAuthentication.constantTimeEquals(hostMac, hostMac))
        assertFalse(ProtocolAuthentication.constantTimeEquals(hostMac, driverMac))
        assertFalse(ProtocolAuthentication.constantTimeEquals(hostMac, hostMac.substring(0, 31)))
    }

    @Test
    fun macsDependOnTheSecretAndEveryTranscriptByte() {
        val hostMac = ProtocolAuthentication.hostMac(secret, transcript)
        assertNotEquals(hostMac, ProtocolAuthentication.hostMac(secret.copyOf().also { it[0] = 99 }, transcript))
        assertNotEquals(hostMac, ProtocolAuthentication.hostMac(secret, transcript.copyOf().also { it[it.size - 1] = 0 }))
    }

    @Test
    fun transcriptLengthPrefixesEveryPayload() {
        val transcript = ProtocolAuthentication.transcript(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf())
        assertContentEquals(byteArrayOf(0, 0, 0, 2, 1, 2, 0, 0, 0, 1, 3, 0, 0, 0, 0), transcript)
        // Moving a byte across a payload boundary changes the transcript.
        assertFalse(transcript.contentEquals(ProtocolAuthentication.transcript(byteArrayOf(1), byteArrayOf(2, 3), byteArrayOf())))
    }

    @Test
    fun noncesAreRandomAndTheRightLength() {
        val first = ProtocolAuthentication.nonce()
        val second = ProtocolAuthentication.nonce()
        assertEquals(NONCE_BYTES, first.size())
        assertNotEquals(first, second)
        assertTrue(ProtocolNegotiation.isValidNonce(first))
        assertFalse(ProtocolNegotiation.isValidNonce(first.substring(1)))
        assertFalse(ProtocolNegotiation.isValidNonce(ByteString.EMPTY))
    }

    @Test
    fun negotiationSelectsTheHighestCommonVersionAndSharedCapabilities() {
        val hello = hello(versions = listOf(protocolVersion(2, 0), PROTOCOL_VERSION, protocolVersion(5, 5)))
        val challenge = challenge(capabilities = listOf("artifact.screenshot.v1", "future.capability.v9", "synchronization.v1"))

        val negotiation = requireNotNull(ProtocolNegotiation.negotiate(hello, challenge))
        assertEquals(PROTOCOL_VERSION, negotiation.selectedVersion)
        assertEquals(listOf("artifact.screenshot.v1", "synchronization.v1"), negotiation.enabledCapabilitiesList)
        assertTrue(ProtocolNegotiation.isValid(hello, challenge, negotiation))
    }

    @Test
    fun negotiationFailsWithoutACommonVersion() {
        assertNull(ProtocolNegotiation.negotiate(hello(versions = listOf(protocolVersion(2, 0))), challenge()))
        assertNull(ProtocolNegotiation.selectVersion(listOf(protocolVersion(9, 9)), SUPPORTED_PROTOCOL_VERSIONS))
    }

    @Test
    fun rejectsATamperedNegotiation() {
        val hello = hello()
        val challenge = challenge()
        val negotiation = requireNotNull(ProtocolNegotiation.negotiate(hello, challenge))

        val wrongVersion = negotiation.toBuilder().setSelectedVersion(protocolVersion(2, 0)).build()
        val noVersion = negotiation.toBuilder().clearSelectedVersion().build()
        val notOffered = negotiation.toBuilder().addEnabledCapabilities("zzz.not.offered").build()
        val unsorted = negotiation.toBuilder().clearEnabledCapabilities().addAllEnabledCapabilities(SUPPORTED_CAPABILITIES.reversed()).build()
        listOf(wrongVersion, noVersion, notOffered, unsorted).forEach {
            assertFalse(ProtocolNegotiation.isValid(hello, challenge, it), it.toString())
        }
    }

    @Test
    fun validatesHelloAndChallengeShape() {
        assertTrue(ProtocolNegotiation.isValidHello(hello()))
        assertFalse(ProtocolNegotiation.isValidHello(hello().toBuilder().setHostNonce(ByteString.copyFrom(ByteArray(16))).build()))
        assertFalse(ProtocolNegotiation.isValidHello(hello().toBuilder().setSessionId(" ").build()))
        assertFalse(ProtocolNegotiation.isValidHello(hello().toBuilder().setHostBuildId("").build()))
        assertFalse(ProtocolNegotiation.isValidHello(hello().toBuilder().setSessionGeneration(-1).build()))
        assertFalse(ProtocolNegotiation.isValidHello(hello(versions = emptyList())))
        assertFalse(ProtocolNegotiation.isValidHello(hello(versions = listOf(PROTOCOL_VERSION, protocolVersion(2, 0)))))
        assertFalse(ProtocolNegotiation.isValidHello(hello(versions = listOf(PROTOCOL_VERSION, PROTOCOL_VERSION))))

        assertTrue(ProtocolNegotiation.isValidChallenge(challenge()))
        assertFalse(ProtocolNegotiation.isValidChallenge(challenge(capabilities = listOf("b", "a"))))
        assertFalse(ProtocolNegotiation.isValidChallenge(challenge().toBuilder().clearSupportedOperations().build()))
        assertFalse(
            ProtocolNegotiation.isValidChallenge(
                challenge().toBuilder().clearSupportedOperations().addAllSupportedOperations(listOf("tap", "exists")).build(),
            ),
        )
        assertFalse(ProtocolNegotiation.isValidChallenge(challenge().toBuilder().setDriverNonce(ByteString.EMPTY).build()))
        assertFalse(ProtocolNegotiation.isValidChallenge(challenge().toBuilder().setAndroidApiLevel(0).build()))
        assertFalse(ProtocolNegotiation.isValidChallenge(challenge().toBuilder().setUiAutomatorBuildId("").build()))
    }

    @Test
    fun protocolVersionsOrderByMajorThenMinor() {
        assertEquals("5.1", PROTOCOL_VERSION.render())
        assertTrue(protocolVersion(2, 9) < protocolVersion(3, 0))
        assertTrue(protocolVersion(3, 1) > protocolVersion(3, 0))
        assertEquals(listOf(PROTOCOL_VERSION), SUPPORTED_PROTOCOL_VERSIONS)
    }

    private fun hello(versions: List<io.github.noamcohen48.tap.wire.v1.ProtocolVersion> = SUPPORTED_PROTOCOL_VERSIONS): Hello =
        Hello.newBuilder()
            .setHostBuildId(HOST_BUILD_ID)
            .setHostNonce(ProtocolAuthentication.nonce())
            .setSessionGeneration(1)
            .setSessionId("session")
            .addAllSupportedVersions(versions)
            .build()

    private fun challenge(capabilities: List<String> = SUPPORTED_CAPABILITIES): Challenge =
        Challenge.newBuilder()
            .setAndroidApiLevel(34)
            .addAllCapabilities(capabilities)
            .setDriverApkBuildId(DRIVER_APK_BUILD_ID)
            .setDriverInstanceId("instance")
            .setHostNonce(ProtocolAuthentication.nonce())
            .setDriverNonce(ProtocolAuthentication.nonce())
            .setDriverTestApkBuildId(DRIVER_TEST_APK_BUILD_ID)
            .setSessionGeneration(1)
            .setSessionId("session")
            .addAllSupportedOperations(Operations.ALL)
            .addAllSupportedVersions(SUPPORTED_PROTOCOL_VERSIONS)
            .setUiAutomatorBuildId(UIAUTOMATOR_BUILD_ID)
            .build()
}
