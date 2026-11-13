package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.CapabilityId
import io.github.noamcohen48.tap.api.v1.GetDeviceCapabilitiesResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilitiesTest {
    @Test fun `SDK enum covers every contract operation`() {
        assertEquals(
            CapabilityId.entries
                .filter {
                    it != CapabilityId.UNRECOGNIZED && it != CapabilityId.CAPABILITY_UNSPECIFIED
                }.map { it.name.removePrefix("CAPABILITY_") }
                .toSet(),
            Capability.entries
                .filter { it != Capability.UNKNOWN }
                .map { it.name }
                .toSet(),
        )
    }

    @Test fun `mapping preserves conditional unsupported and future values without collapsing unknown IDs`() {
        val response =
            GetDeviceCapabilitiesResponse
                .newBuilder()
                .addCapabilities(
                    io.github.noamcohen48.tap.api.v1.DeviceCapability
                        .newBuilder()
                        .setId(CapabilityId.CAPABILITY_APP_SET_LOCALES)
                        .setStatus(io.github.noamcohen48.tap.api.v1.CapabilityStatus.CAPABILITY_STATUS_UNSUPPORTED)
                        .addReasons(io.github.noamcohen48.tap.api.v1.CapabilityReason.CAPABILITY_REASON_REQUIRES_API)
                        .setRequiredApi(33)
                        .setDetail("Needs API 33"),
                ).addCapabilities(
                    io.github.noamcohen48.tap.api.v1.DeviceCapability
                        .newBuilder()
                        .setId(CapabilityId.CAPABILITY_ACCESSIBILITY_PAGE_DOWN)
                        .setStatus(io.github.noamcohen48.tap.api.v1.CapabilityStatus.CAPABILITY_STATUS_CONDITIONAL)
                        .addRequirements(
                            io.github.noamcohen48.tap.api.v1.CapabilityRequirement.CAPABILITY_REQUIREMENT_TARGET_OFFERS_ACTION,
                        ),
                ).addCapabilities(
                    io.github.noamcohen48.tap.api.v1.DeviceCapability
                        .newBuilder()
                        .setIdValue(9001)
                        .setStatusValue(9002)
                        .addReasonsValue(9003)
                        .addRequirementsValue(9004),
                ).addCapabilities(
                    io.github.noamcohen48.tap.api.v1.DeviceCapability
                        .newBuilder()
                        .setIdValue(9005),
                ).build()
                .toModel()
        assertEquals(CapabilityStatus.UNSUPPORTED, response[Capability.APP_SET_LOCALES].status)
        assertEquals(33, response[Capability.APP_SET_LOCALES].requiredApi)
        assertEquals(listOf(CapabilityReason.REQUIRES_API), response[Capability.APP_SET_LOCALES].reasons)
        assertEquals(CapabilityStatus.CONDITIONAL, response[Capability.ACCESSIBILITY_PAGE_DOWN].status)
        assertEquals(listOf(CapabilityRequirement.TARGET_OFFERS_ACTION), response[Capability.ACCESSIBILITY_PAGE_DOWN].requirements)
        assertEquals(CapabilityStatus.UNKNOWN, response[Capability.CLIPBOARD_READ].status)
        val future = response.entries.takeLast(2)
        assertEquals(listOf(9001, 9005), future.map { it.rawId })
        assertTrue(future.all { it.id == Capability.UNKNOWN && it.status == CapabilityStatus.UNKNOWN })
        assertEquals(listOf(CapabilityReason.UNKNOWN), future.first().reasons)
        assertEquals(listOf(CapabilityRequirement.UNKNOWN), future.first().requirements)
    }
}
