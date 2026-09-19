package com.company.tap.protocol

import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolContractTest {
    private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))

    @Test
    fun canonicalHelloIsStable() {
        val hello = hello(listOf(ProtocolVersion(1, 0)))

        assertEquals(
            "{\"hostBuildId\":\"host\",\"hostNonce\":\"$nonce\",\"sessionGeneration\":3," +
                "\"sessionId\":\"session\",\"supportedVersions\":[{\"major\":1,\"minor\":0}]}",
            CanonicalJson.encode(hello).decodeToString(),
        )
        assertEquals(hello, CanonicalJson.decodeCanonical(CanonicalJson.encode(hello)))
    }

    @Test
    fun rejectsNonCanonicalAndDuplicateHandshakeJson() {
        val canonical = CanonicalJson.encode(hello(SUPPORTED_PROTOCOL_VERSIONS)).decodeToString()

        assertFailsWith<SerializationException> {
            CanonicalJson.decodeCanonical<Hello>(" $canonical".encodeToByteArray())
        }
        assertFailsWith<SerializationException> {
            CanonicalJson.decodeCanonical<Hello>(
                canonical.replaceFirst("{", "{\"hostBuildId\":\"duplicate\",").encodeToByteArray()
            )
        }
    }

    @Test
    fun selectsHighestCommonVersionAndSortedCapabilities() {
        val hello = hello(listOf(ProtocolVersion(1, 0), ProtocolVersion(1, 2), ProtocolVersion(2, 0)))
        val challenge = challenge(
            versions = listOf(ProtocolVersion(1, 0), ProtocolVersion(1, 2)),
            capabilities = listOf("diagnostic.hierarchy.v1", "synchronization.v1"),
        )

        val negotiation = ProtocolNegotiation.negotiate(hello, challenge)

        assertEquals(ProtocolVersion(1, 2), negotiation?.selectedVersion)
        assertEquals(
            listOf("diagnostic.hierarchy.v1", "synchronization.v1"),
            negotiation?.enabledCapabilities,
        )
        assertTrue(ProtocolNegotiation.isValid(hello, challenge, requireNotNull(negotiation)))
    }

    @Test
    fun rejectsNoCommonVersionAndInvalidCapabilitySelection() {
        val hello = hello(listOf(ProtocolVersion(2, 0)))
        val challenge = challenge(SUPPORTED_PROTOCOL_VERSIONS, SUPPORTED_CAPABILITIES)

        assertNull(ProtocolNegotiation.negotiate(hello, challenge))
        assertFalse(
            ProtocolNegotiation.isValid(
                hello(SUPPORTED_PROTOCOL_VERSIONS),
                challenge,
                Negotiation(listOf("not-offered.v1"), ProtocolVersion(1, 0)),
            )
        )
    }

    @Test
    fun negotiationIsAuthenticatedByTranscript() {
        val helloPayload = CanonicalJson.encode(hello(SUPPORTED_PROTOCOL_VERSIONS))
        val challengePayload = CanonicalJson.encode(challenge(SUPPORTED_PROTOCOL_VERSIONS, SUPPORTED_CAPABILITIES))
        val first = ProtocolAuthentication.transcript(
            helloPayload,
            challengePayload,
            CanonicalJson.encode(Negotiation(emptyList(), ProtocolVersion(1, 0))),
        )
        val second = ProtocolAuthentication.transcript(
            helloPayload,
            challengePayload,
            CanonicalJson.encode(
                Negotiation(listOf("synchronization.v1"), ProtocolVersion(1, 0))
            ),
        )

        assertFalse(
            ProtocolAuthentication.constantTimeEquals(
                ProtocolAuthentication.hostMac(ByteArray(32), first),
                ProtocolAuthentication.hostMac(ByteArray(32), second),
            )
        )
    }

    private fun hello(versions: List<ProtocolVersion>) = Hello(
        hostBuildId = "host",
        hostNonce = nonce,
        sessionGeneration = 3,
        sessionId = "session",
        supportedVersions = versions,
    )

    private fun challenge(
        versions: List<ProtocolVersion>,
        capabilities: List<String>,
    ) = Challenge(
        androidApiLevel = 34,
        capabilities = capabilities,
        driverApkBuildId = "driver",
        driverInstanceId = "instance",
        driverNonce = nonce,
        driverTestApkBuildId = "driver-test",
        hostNonce = nonce,
        sessionGeneration = 3,
        sessionId = "session",
        supportedOperations = Operation.entries
            .map { OperationSupport(it.name, OPERATION_VERSION) }
            .sortedBy(OperationSupport::name),
        supportedVersions = versions,
        uiAutomatorBuildId = "uiautomator",
    )
}
