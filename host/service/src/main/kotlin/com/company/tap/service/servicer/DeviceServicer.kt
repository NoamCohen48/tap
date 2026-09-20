package com.company.tap.service.servicer

import com.company.tap.api.v1.DeviceServiceGrpcKt
import com.company.tap.api.v1.DeviceState
import com.company.tap.api.v1.ListDevicesRequest
import com.company.tap.api.v1.ListDevicesResponse
import com.company.tap.service.DeviceEntry
import com.company.tap.service.DeviceStatus
import com.company.tap.service.TapService

class DeviceServicer(
    private val service: TapService,
) : DeviceServiceGrpcKt.DeviceServiceCoroutineImplBase() {
    override suspend fun listDevices(request: ListDevicesRequest) =
        reply {
            ListDevicesResponse.newBuilder().addAllDevices(service.devices().map(::deviceEntry)).build()
        }

    private fun deviceEntry(entry: DeviceEntry): com.company.tap.api.v1.DeviceEntry =
        com.company.tap.api.v1.DeviceEntry
            .newBuilder()
            .apply {
                serial = entry.serial
                when (val status = entry.status) {
                    DeviceStatus.Free -> {
                        state = DeviceState.DEVICE_FREE
                    }

                    is DeviceStatus.Held -> {
                        state = DeviceState.DEVICE_LEASED
                        status.connectionId?.let { heldByConnection = it }
                    }

                    is DeviceStatus.Quarantined -> {
                        state = DeviceState.DEVICE_QUARANTINED
                        quarantineReason = status.reason
                    }
                }
            }.build()
}
