package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.SetAnimationsRequest
import io.github.noamcohen48.tap.api.v1.SetDarkModeRequest
import io.github.noamcohen48.tap.api.v1.SetDensityRequest
import io.github.noamcohen48.tap.api.v1.SetFontScaleRequest
import io.github.noamcohen48.tap.api.v1.SetNetworkRequest
import io.github.noamcohen48.tap.api.v1.SetLocationRequest
import io.github.noamcohen48.tap.api.v1.SetSystemLocalesRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Device-condition arguments are checked before the attached device is even looked up. */
class DeviceServiceTest {
    private val stateDir = Files.createTempDirectory("tap-device-service")
    private val daemon = TapDaemon(DaemonConfig(adb = object : Adb("fake-adb") {}, stateDir = stateDir, driver = null, log = {}))
    private val name = InProcessServerBuilder.generateName()
    private val server = InProcessServerBuilder.forName(name).addService(DeviceService(daemon)).build().start()
    private val channel = InProcessChannelBuilder.forName(name).build()
    private val stub = DeviceServiceGrpcKt.DeviceServiceCoroutineStub(channel)

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private fun code(call: suspend () -> Unit): Status.Code = runBlocking { assertFailsWith<StatusException> { call() }.status.code }

    @Test
    fun `out of range conditions are INVALID_ARGUMENT, valid ones reach the device lookup`() {
        fun fontScale(scale: Float) =
            code { stub.setFontScale(SetFontScaleRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setScale(scale).build()) }
        for (scale in listOf(0f, 0.49f, 2.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(Status.Code.INVALID_ARGUMENT, fontScale(scale), "$scale")
        }
        assertEquals(Status.Code.NOT_FOUND, fontScale(0.5f))
        assertEquals(Status.Code.NOT_FOUND, fontScale(2.0f))

        fun density(dpi: Int?) =
            code {
                stub.setDensity(
                    SetDensityRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").apply { dpi?.let(::setDpi) }.build(),
                )
            }
        for (dpi in listOf(0, 99, 1001, -320)) assertEquals(Status.Code.INVALID_ARGUMENT, density(dpi), "$dpi")
        assertEquals(Status.Code.NOT_FOUND, density(100))
        assertEquals(Status.Code.NOT_FOUND, density(null))

        fun network(build: SetNetworkRequest.Builder.() -> Unit) =
            code { stub.setNetwork(SetNetworkRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").apply(build).build()) }
        assertEquals(Status.Code.INVALID_ARGUMENT, network {})
        assertEquals(Status.Code.NOT_FOUND, network { wifi = false })

        fun systemLocales(vararg tags: String) =
            code {
                stub.setSystemLocales(
                    SetSystemLocalesRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").addAllLocales(tags.toList()).build(),
                )
            }
        assertEquals(Status.Code.INVALID_ARGUMENT, systemLocales())
        assertEquals(Status.Code.INVALID_ARGUMENT, systemLocales("not a tag"))
        assertEquals(Status.Code.INVALID_ARGUMENT, systemLocales("fr-FR", "fr-fr"))
        assertEquals(Status.Code.NOT_FOUND, systemLocales("fr-FR", "en"))

        fun location(build: SetLocationRequest.Builder.() -> Unit) =
            code { stub.setLocation(SetLocationRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").apply(build).build()) }
        assertEquals(Status.Code.INVALID_ARGUMENT, location { latitude = 91.0 })
        assertEquals(Status.Code.INVALID_ARGUMENT, location { longitude = Double.NaN })
        assertEquals(Status.Code.INVALID_ARGUMENT, location { accuracyM = 0f })
        assertEquals(Status.Code.INVALID_ARGUMENT, location { altitudeM = Double.POSITIVE_INFINITY })
        assertEquals(Status.Code.NOT_FOUND, location { latitude = -90.0; longitude = 180.0; accuracyM = 1f })

        assertEquals(
            Status.Code.NOT_FOUND,
            code { stub.setAnimations(SetAnimationsRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").build()) },
        )
        assertEquals(
            Status.Code.NOT_FOUND,
            code { stub.setDarkMode(SetDarkModeRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setEnabled(true).build()) },
        )
    }
}
