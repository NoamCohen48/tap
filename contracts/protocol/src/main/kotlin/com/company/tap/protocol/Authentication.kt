package com.company.tap.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ProtocolAuthentication {
    fun transcript(
        helloPayload: ByteArray,
        challengePayload: ByteArray,
        negotiationPayload: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            listOf(helloPayload, challengePayload, negotiationPayload).forEach { payload ->
                output.writeInt(payload.size)
                output.write(payload)
            }
        }
        bytes.toByteArray()
    }

    fun hostMac(secret: ByteArray, transcript: ByteArray): String =
        mac(secret, "TAP1-HOST-AUTH", transcript)

    fun driverMac(secret: ByteArray, transcript: ByteArray): String =
        mac(secret, "TAP1-DRIVER-AUTH", transcript)

    fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())

    private fun mac(secret: ByteArray, domain: String, transcript: ByteArray): String {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(secret, "HmacSHA256"))
        hmac.update(domain.encodeToByteArray())
        hmac.update(0)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(transcript))
    }
}

object ProtocolNegotiation {
    fun isValidHello(hello: Hello): Boolean =
        hello.hostBuildId.isNotBlank() &&
            isValidNonce(hello.hostNonce) &&
            hello.sessionGeneration >= 0 &&
            hello.sessionId.isNotBlank() &&
            hello.supportedVersions.isNotEmpty() &&
            hello.supportedVersions == hello.supportedVersions.distinct().sorted()

    fun isValidChallenge(challenge: Challenge): Boolean =
        challenge.androidApiLevel > 0 &&
            challenge.capabilities == challenge.capabilities.distinct().sorted() &&
            challenge.driverApkBuildId.isNotBlank() &&
            challenge.driverInstanceId.isNotBlank() &&
            isValidNonce(challenge.driverNonce) &&
            challenge.driverTestApkBuildId.isNotBlank() &&
            isValidNonce(challenge.hostNonce) &&
            challenge.sessionGeneration >= 0 &&
            challenge.sessionId.isNotBlank() &&
            challenge.supportedOperations.isNotEmpty() &&
            challenge.supportedOperations == challenge.supportedOperations.distinct().sorted() &&
            challenge.supportedOperations.all(String::isNotBlank) &&
            challenge.supportedVersions.isNotEmpty() &&
            challenge.supportedVersions == challenge.supportedVersions.distinct().sorted() &&
            challenge.uiAutomatorBuildId.isNotBlank()

    fun selectVersion(
        hostVersions: List<ProtocolVersion>,
        driverVersions: List<ProtocolVersion>,
    ): ProtocolVersion? = hostVersions.toSet().intersect(driverVersions.toSet()).maxOrNull()

    fun negotiate(hello: Hello, challenge: Challenge): Negotiation? {
        val selected = selectVersion(hello.supportedVersions, challenge.supportedVersions) ?: return null
        return Negotiation(
            enabledCapabilities = challenge.capabilities
                .intersect(SUPPORTED_CAPABILITIES.toSet())
                .sorted(),
            selectedVersion = selected,
        )
    }

    fun isValid(hello: Hello, challenge: Challenge, negotiation: Negotiation): Boolean {
        val expectedVersion = selectVersion(hello.supportedVersions, challenge.supportedVersions)
        return isValidHello(hello) && isValidChallenge(challenge) &&
            negotiation.selectedVersion == expectedVersion &&
            negotiation.enabledCapabilities == negotiation.enabledCapabilities.distinct().sorted() &&
            challenge.capabilities.containsAll(negotiation.enabledCapabilities) &&
            SUPPORTED_CAPABILITIES.containsAll(negotiation.enabledCapabilities)
    }

    fun isValidNonce(value: String): Boolean = runCatching {
        '=' !in value && Base64.getUrlDecoder().decode(value).size == 32
    }.getOrDefault(false)
}
