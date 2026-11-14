package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.CapabilityId
import io.github.noamcohen48.tap.api.v1.GetDeviceCapabilitiesRequest
import io.github.noamcohen48.tap.host.Adb
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceCapabilitiesServiceTest {
    @Test fun `every device and app RPC is classified by the host catalog`() {
        assertEquals(
            io.github.noamcohen48.tap.api.v1.DeviceServiceGrpc.getServiceDescriptor().methods.map { it.bareMethodName }.toSet(),
            io.github.noamcohen48.tap.host.DeviceCapabilities.deviceRpcs.keys,
        )
        assertEquals(
            io.github.noamcohen48.tap.api.v1.AppServiceGrpc.getServiceDescriptor().methods.map { it.bareMethodName }.toSet(),
            io.github.noamcohen48.tap.host.DeviceCapabilities.appRpcs.keys,
        )
    }

    @Test fun `discovery uses handshake facts without a driver command and checks ownership`() =
        runBlocking {
            AttachedHarness.with(Adb("must-not-be-invoked")) {
                val request =
                    GetDeviceCapabilitiesRequest
                        .newBuilder()
                        .setClientConnectionId(connectionId)
                        .setAttachedDeviceId(attached.id)
                        .build()
                // The fake driver answers no commands here. A query or mutation would time out.
                val first = devices.withDeadlineAfter(2, TimeUnit.SECONDS).getDeviceCapabilities(request)
                val second = devices.withDeadlineAfter(2, TimeUnit.SECONDS).getDeviceCapabilities(request)
                assertEquals(first, second)
                assertEquals(89, first.capabilitiesCount)
                assertTrue(first.capabilitiesList.any { it.id == CapabilityId.CAPABILITY_APP_SET_LOCALES })
                assertTrue(attached.events.after(0).first.isEmpty())
                val denied =
                    assertFailsWith<StatusException> {
                        devices.getDeviceCapabilities(request.toBuilder().setClientConnectionId("not-the-owner").build())
                    }
                assertEquals(Status.Code.PERMISSION_DENIED, denied.status.code)
                val missing =
                    assertFailsWith<StatusException> {
                        devices.getDeviceCapabilities(request.toBuilder().setAttachedDeviceId("missing").build())
                    }
                assertEquals(Status.Code.NOT_FOUND, missing.status.code)
            }
        }
}
