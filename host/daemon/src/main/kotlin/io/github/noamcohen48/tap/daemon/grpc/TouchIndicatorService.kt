package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.GetTouchIndicatorsResponse
import io.github.noamcohen48.tap.api.v1.SetTouchIndicatorsResponse
import io.github.noamcohen48.tap.api.v1.GetTouchIndicatorsRequest
import io.github.noamcohen48.tap.api.v1.SetTouchIndicatorsRequest
import io.github.noamcohen48.tap.api.v1.TouchIndicatorServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.TouchIndicators
import io.github.noamcohen48.tap.daemon.core.DeviceStatus
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.TouchIndicatorState

/** Explicit device-wide diagnostics; never attaches to or renews a test's connection. */
class TouchIndicatorService(
    private val daemon: TapDaemon,
) : TouchIndicatorServiceGrpcKt.TouchIndicatorServiceCoroutineImplBase() {
    override suspend fun getTouchIndicators(request: GetTouchIndicatorsRequest): GetTouchIndicatorsResponse =
        reply {
            online(request.serial)
            GetTouchIndicatorsResponse.newBuilder()
                .setIndicators(daemon.config.adb.touchIndicators(request.serial).toProto()).build()
        }

    override suspend fun setTouchIndicators(request: SetTouchIndicatorsRequest): SetTouchIndicatorsResponse =
        reply {
            argument(request.hasShowTaps() || request.hasPointerLocation()) { "set at least one touch indicator" }
            online(request.serial)
            val indicators = daemon.config.adb.setTouchIndicators(
                request.serial,
                request.showTaps.takeIf { request.hasShowTaps() },
                request.pointerLocation.takeIf { request.hasPointerLocation() },
            ).toProto()
            SetTouchIndicatorsResponse.newBuilder().setIndicators(indicators).build()
        }

    private suspend fun online(serial: String) {
        argument(serial.isNotBlank()) { "serial is required" }
        val device = daemon.devices().firstOrNull { it.serial == serial }
        argument(device != null) { "$serial is not in the device inventory" }
        argument(device!!.status !is DeviceStatus.Unavailable) { "$serial is not online" }
    }

    private fun TouchIndicatorState.toProto(): TouchIndicators =
        TouchIndicators
            .newBuilder()
            .setShowTaps(showTaps)
            .setPointerLocation(pointerLocation)
            .build()
}
