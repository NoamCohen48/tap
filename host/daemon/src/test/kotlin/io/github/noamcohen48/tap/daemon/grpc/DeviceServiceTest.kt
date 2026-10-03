package io.github.noamcohen48.tap.daemon.grpc

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AddMediaHeader
import io.github.noamcohen48.tap.api.v1.AddMediaRequest
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.PullFileRequest
import io.github.noamcohen48.tap.api.v1.PushFileHeader
import io.github.noamcohen48.tap.api.v1.PushFileRequest
import io.github.noamcohen48.tap.api.v1.SetAnimationsRequest
import io.github.noamcohen48.tap.api.v1.SetDarkModeRequest
import io.github.noamcohen48.tap.api.v1.SetDensityRequest
import io.github.noamcohen48.tap.api.v1.SetFontScaleRequest
import io.github.noamcohen48.tap.api.v1.GetForegroundActivityRequest
import io.github.noamcohen48.tap.api.v1.SetAccessibilityDisplayRequest
import io.github.noamcohen48.tap.api.v1.SetNetworkRequest
import io.github.noamcohen48.tap.api.v1.SetStayAwakeRequest
import io.github.noamcohen48.tap.api.v1.SetLocationRequest
import io.github.noamcohen48.tap.api.v1.SetSystemLocalesRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.io.path.listDirectoryEntries

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

        fun display(build: SetAccessibilityDisplayRequest.Builder.() -> Unit) =
            code {
                stub.setAccessibilityDisplay(SetAccessibilityDisplayRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").apply(build).build())
            }
        assertEquals(Status.Code.INVALID_ARGUMENT, display {})
        assertEquals(Status.Code.NOT_FOUND, display { boldText = false })
        assertEquals(
            Status.Code.NOT_FOUND,
            code { stub.setStayAwake(SetStayAwakeRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setEnabled(true).build()) },
        )
        assertEquals(
            Status.Code.NOT_FOUND,
            code { stub.getForegroundActivity(GetForegroundActivityRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").build()) },
        )

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

    private fun pushHeader(
        path: String,
        size: Long,
    ) = PushFileRequest
        .newBuilder()
        .setHeader(PushFileHeader.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setDevicePath(path).setSizeBytes(size))
        .build()

    private fun pushChunk(bytes: Int) = PushFileRequest.newBuilder().setChunk(ByteString.copyFrom(ByteArray(bytes))).build()

    private fun mediaHeader(
        name: String,
        size: Long,
    ) = AddMediaRequest
        .newBuilder()
        .setHeader(AddMediaHeader.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setFileName(name).setSizeBytes(size))
        .build()

    private fun mediaChunk(bytes: Int) = AddMediaRequest.newBuilder().setChunk(ByteString.copyFrom(ByteArray(bytes))).build()

    @Test
    fun `malformed file uploads and paths are INVALID_ARGUMENT and leave no spool file`() {
        fun push(vararg parts: PushFileRequest) = code { stub.pushFile(flowOf(*parts)) }
        assertEquals(Status.Code.INVALID_ARGUMENT, push(pushChunk(2)))
        assertEquals(Status.Code.INVALID_ARGUMENT, push(pushHeader("/data/local/tmp/a", 2), pushChunk(1)))
        assertEquals(Status.Code.INVALID_ARGUMENT, push(pushHeader("/data/local/tmp/a", 2), pushChunk(3)))
        assertEquals(Status.Code.INVALID_ARGUMENT, push(pushHeader("/data/local/tmp/a", 1), pushHeader("/data/local/tmp/a", 1)))
        assertEquals(Status.Code.INVALID_ARGUMENT, push(pushHeader("/data/local/tmp/a", -1)))
        for (path in listOf("", "a.txt", "/data/local/tmp/", "/data//a", "/data/./a", "/data/../a", "/data/a\nb")) {
            assertEquals(Status.Code.INVALID_ARGUMENT, push(pushHeader(path, 1), pushChunk(1)), path)
        }
        // An empty file is a file: the header alone is a complete upload.
        assertEquals(Status.Code.NOT_FOUND, push(pushHeader("/data/local/tmp/empty", 0)))
        assertEquals(Status.Code.NOT_FOUND, push(pushHeader("/data/local/tmp/a", 2), pushChunk(2)))

        fun media(vararg parts: AddMediaRequest) = code { stub.addMedia(flowOf(*parts)) }
        for (name in listOf("notes.txt", "cat", "../cat.jpg", "a/cat.jpg", ".jpg", "cat'.jpg")) {
            assertEquals(Status.Code.INVALID_ARGUMENT, media(mediaHeader(name, 1), mediaChunk(1)), name)
        }
        assertEquals(Status.Code.NOT_FOUND, media(mediaHeader("Cat 1.JPG", 1), mediaChunk(1)))
        assertEquals(Status.Code.NOT_FOUND, media(mediaHeader("clip.mp4", 1), mediaChunk(1)))

        fun pull(path: String) =
            code { stub.pullFile(PullFileRequest.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setDevicePath(path).build()).toList() }
        assertEquals(Status.Code.INVALID_ARGUMENT, pull("relative"))
        assertEquals(Status.Code.NOT_FOUND, pull("/data/local/tmp/a"))

        assertTrue(stateDir.resolve("uploads").listDirectoryEntries().isEmpty())
    }
}
