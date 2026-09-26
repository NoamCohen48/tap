package io.github.noamcohen48.tap.protocol

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.wire.v1.Challenge
import io.github.noamcohen48.tap.wire.v1.Hello
import io.github.noamcohen48.tap.wire.v1.Negotiation
import io.github.noamcohen48.tap.wire.v1.ProtocolVersion
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val NONCE_BYTES = 32

/**
 * The HMAC transcript: the exact HELLO, CHALLENGE and serialized-Negotiation payload bytes,
 * each length-prefixed. Both sides MAC what was on the wire, never a re-encoding.
 */
object ProtocolAuthentication {
    private val random = SecureRandom()

    fun nonce(): ByteString = ByteArray(NONCE_BYTES).also(random::nextBytes).let(ByteString::copyFrom)

    fun transcript(
        helloPayload: ByteArray,
        challengePayload: ByteArray,
        negotiationPayload: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                listOf(helloPayload, challengePayload, negotiationPayload).forEach { payload ->
                    output.writeInt(payload.size)
                    output.write(payload)
                }
            }
            bytes.toByteArray()
        }

    fun hostMac(
        secret: ByteArray,
        transcript: ByteArray,
    ): ByteString = mac(secret, "TAP1-HOST-AUTH", transcript)

    fun driverMac(
        secret: ByteArray,
        transcript: ByteArray,
    ): ByteString = mac(secret, "TAP1-DRIVER-AUTH", transcript)

    fun constantTimeEquals(
        expected: ByteString,
        actual: ByteString,
    ): Boolean = MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())

    private fun mac(
        secret: ByteArray,
        domain: String,
        transcript: ByteArray,
    ): ByteString {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(secret, "HmacSHA256"))
        hmac.update(domain.encodeToByteArray())
        hmac.update(0)
        return ByteString.copyFrom(hmac.doFinal(transcript))
    }
}

object ProtocolNegotiation {
    fun isValidHello(hello: Hello): Boolean =
        hello.hostBuildId.isNotBlank() &&
            isValidNonce(hello.hostNonce) &&
            hello.sessionGeneration >= 0 &&
            hello.sessionId.isNotBlank() &&
            isAscendingDistinct(hello.supportedVersionsList)

    fun isValidChallenge(challenge: Challenge): Boolean =
        challenge.androidApiLevel > 0 &&
            isSortedDistinct(challenge.capabilitiesList) &&
            challenge.driverApkBuildId.isNotBlank() &&
            challenge.driverInstanceId.isNotBlank() &&
            isValidNonce(challenge.driverNonce) &&
            challenge.driverTestApkBuildId.isNotBlank() &&
            isValidNonce(challenge.hostNonce) &&
            challenge.sessionGeneration >= 0 &&
            challenge.sessionId.isNotBlank() &&
            challenge.supportedOperationsList.isNotEmpty() &&
            isSortedDistinct(challenge.supportedOperationsList) &&
            challenge.supportedOperationsList.all(String::isNotBlank) &&
            isAscendingDistinct(challenge.supportedVersionsList) &&
            challenge.uiAutomatorBuildId.isNotBlank()

    fun selectVersion(
        hostVersions: List<ProtocolVersion>,
        driverVersions: List<ProtocolVersion>,
    ): ProtocolVersion? = hostVersions.filter { it in driverVersions }.maxWithOrNull(PROTOCOL_VERSION_ORDER)

    fun negotiate(
        hello: Hello,
        challenge: Challenge,
    ): Negotiation? {
        val selected = selectVersion(hello.supportedVersionsList, challenge.supportedVersionsList) ?: return null
        return Negotiation.newBuilder()
            .addAllEnabledCapabilities(challenge.capabilitiesList.intersect(SUPPORTED_CAPABILITIES.toSet()).sorted())
            .setSelectedVersion(selected)
            .build()
    }

    fun isValid(
        hello: Hello,
        challenge: Challenge,
        negotiation: Negotiation,
    ): Boolean {
        val expectedVersion = selectVersion(hello.supportedVersionsList, challenge.supportedVersionsList)
        val enabled = negotiation.enabledCapabilitiesList
        return isValidHello(hello) &&
            isValidChallenge(challenge) &&
            expectedVersion != null &&
            negotiation.hasSelectedVersion() &&
            negotiation.selectedVersion == expectedVersion &&
            isSortedDistinct(enabled) &&
            challenge.capabilitiesList.containsAll(enabled) &&
            SUPPORTED_CAPABILITIES.containsAll(enabled)
    }

    fun isValidNonce(value: ByteString): Boolean = value.size() == NONCE_BYTES

    private fun isSortedDistinct(values: List<String>): Boolean = values.zipWithNext().all { (a, b) -> a < b }

    private fun isAscendingDistinct(versions: List<ProtocolVersion>): Boolean =
        versions.isNotEmpty() && versions.zipWithNext().all { (a, b) -> a < b }
}
