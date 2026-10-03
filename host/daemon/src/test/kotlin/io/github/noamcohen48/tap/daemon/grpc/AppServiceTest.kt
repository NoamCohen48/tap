package io.github.noamcohen48.tap.daemon.grpc

import com.google.protobuf.ByteString
import io.github.noamcohen48.tap.api.v1.AppServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.AppTarget
import io.github.noamcohen48.tap.api.v1.ClearDataRequest
import io.github.noamcohen48.tap.api.v1.ColdLaunchRequest
import io.github.noamcohen48.tap.api.v1.ForceStopRequest
import io.github.noamcohen48.tap.api.v1.InstallHeader
import io.github.noamcohen48.tap.api.v1.InstallRequest
import io.github.noamcohen48.tap.api.v1.IntentExtra
import io.github.noamcohen48.tap.api.v1.LaunchRequest
import io.github.noamcohen48.tap.api.v1.OpenLinkRequest
import io.github.noamcohen48.tap.api.v1.IsPermissionGrantedRequest
import io.github.noamcohen48.tap.api.v1.RevokePermissionRequest
import io.github.noamcohen48.tap.api.v1.SetLocalesRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.host.DRIVER_PACKAGE
import io.github.noamcohen48.tap.host.DRIVER_TEST_PACKAGE
import io.github.noamcohen48.tap.host.MAX_APP_LOCALES
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Arguments are validated before anything reaches ADB: the streaming Install upload (which never
 * leaks its spool file), OpenLink's URI, launch extras, and Tap's own driver packages as a target.
 */
class AppServiceTest {
    private val stateDir = Files.createTempDirectory("tap-app-service")
    private val daemon = TapDaemon(DaemonConfig(adb = object : Adb("fake-adb") {}, stateDir = stateDir, driver = null, log = {}))
    private val name = InProcessServerBuilder.generateName()
    private val server = InProcessServerBuilder.forName(name).addService(AppService(daemon)).build().start()
    private val channel = InProcessChannelBuilder.forName(name).build()
    private val stub = AppServiceGrpcKt.AppServiceCoroutineStub(channel)

    @AfterTest
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private val target = AppTarget.newBuilder().setClientConnectionId("c").setAttachedDeviceId("d").setPackageName("com.test")

    private fun header(size: Long) =
        InstallRequest
            .newBuilder()
            .setHeader(InstallHeader.newBuilder().setApp(target).setSizeBytes(size))
            .build()

    private fun chunk(bytes: Int) = InstallRequest.newBuilder().setChunk(ByteString.copyFrom(ByteArray(bytes))).build()

    private fun rejected(vararg parts: InstallRequest): Status.Code =
        runBlocking { assertFailsWith<StatusException> { stub.install(flowOf(*parts)) }.status.code }

    @Test
    fun `malformed uploads are INVALID_ARGUMENT and leave no spool file`() {
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(chunk(4)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(4), chunk(3)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(4), chunk(5)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(4), header(4)))
        assertEquals(Status.Code.INVALID_ARGUMENT, rejected(header(0)))
        assertTrue(stateDir.resolve("uploads").listDirectoryEntries().isEmpty())
    }

    @Test
    fun `a complete upload for a device the caller does not have is NOT_FOUND`() {
        assertEquals(Status.Code.NOT_FOUND, rejected(header(4), chunk(4)))
        assertTrue(stateDir.resolve("uploads").listDirectoryEntries().isEmpty())
    }

    @Test
    fun `openLink takes only an absolute URI without whitespace, before looking up the device`() {
        fun openLink(uri: String): Status.Code =
            runBlocking {
                assertFailsWith<StatusException> { stub.openLink(OpenLinkRequest.newBuilder().setApp(target).setUri(uri).build()) }.status.code
            }
        for (uri in listOf("", "orders/42", "/orders", "1app://x", "myapp://a b", "myapp://a\nb", "https://x/" + "a".repeat(MAX_URI_LENGTH))) {
            assertEquals(Status.Code.INVALID_ARGUMENT, openLink(uri), uri)
        }
        for (uri in listOf("myapp://orders/42", "https://example.com/a?b=1&c=%20", "tel:123", "x-app.v2+beta:path")) {
            assertEquals(Status.Code.NOT_FOUND, openLink(uri), uri)
        }
    }

    @Test
    fun `the driver packages are refused as a lifecycle target, before looking up the device`() {
        fun code(call: suspend () -> Unit): Status.Code = runBlocking { assertFailsWith<StatusException> { call() }.status.code }
        for (packageName in listOf(DRIVER_PACKAGE, DRIVER_TEST_PACKAGE)) {
            val app = target.clone().setPackageName(packageName)
            assertEquals(Status.Code.INVALID_ARGUMENT, code { stub.forceStop(ForceStopRequest.newBuilder().setApp(app).build()) }, packageName)
            assertEquals(Status.Code.INVALID_ARGUMENT, code { stub.clearData(ClearDataRequest.newBuilder().setApp(app).build()) }, packageName)
        }
        assertEquals(Status.Code.NOT_FOUND, code { stub.forceStop(ForceStopRequest.newBuilder().setApp(target).build()) })
    }

    @Test
    fun `launch extras and revokePermission are checked before looking up the device`() {
        fun code(call: suspend () -> Unit): Status.Code = runBlocking { assertFailsWith<StatusException> { call() }.status.code }
        fun extra(key: String) = IntentExtra.newBuilder().setKey(key)
        fun launch(vararg extras: IntentExtra.Builder): Status.Code =
            code { stub.launch(LaunchRequest.newBuilder().setApp(target).addAllExtras(extras.map { it.build() }).build()) }
        fun coldLaunch(vararg extras: IntentExtra.Builder): Status.Code =
            code { stub.coldLaunch(ColdLaunchRequest.newBuilder().setApp(target).addAllExtras(extras.map { it.build() }).build()) }
        val invalid =
            listOf(
                arrayOf(extra("")),
                arrayOf(extra("no value")),
                arrayOf(extra("a b").setBoolValue(true)),
                arrayOf(extra("a\nb").setBoolValue(true)),
                arrayOf(extra("k".repeat(MAX_EXTRA_KEY_LENGTH + 1)).setBoolValue(true)),
                arrayOf(extra("dup").setIntValue(1), extra("dup").setLongValue(2)),
                arrayOf(extra("long").setStringValue("x".repeat(MAX_EXTRA_STRING_LENGTH + 1))),
                arrayOf(extra("nul").setStringValue("a\u0000b")),
                arrayOf(extra("nan").setFloatValue(Float.NaN)),
                arrayOf(extra("inf").setFloatValue(Float.POSITIVE_INFINITY)),
                Array(MAX_INTENT_EXTRAS + 1) { extra("k$it").setBoolValue(true) },
            )
        for (extras in invalid) {
            assertEquals(Status.Code.INVALID_ARGUMENT, launch(*extras), extras.joinToString { it.key })
            assertEquals(Status.Code.INVALID_ARGUMENT, coldLaunch(*extras), extras.joinToString { it.key })
        }
        val valid =
            arrayOf(
                extra("query").setStringValue("it's a 'test'; id"),
                extra("empty").setStringValue(""),
                extra("flag").setBoolValue(false),
                extra("count").setIntValue(Int.MIN_VALUE),
                extra("id").setLongValue(Long.MAX_VALUE),
                extra("ratio").setFloatValue(-0.5f),
                extra("com.example.EXTRA_KEY").setStringValue("x".repeat(MAX_EXTRA_STRING_LENGTH)),
            )
        assertEquals(Status.Code.NOT_FOUND, launch(*valid))
        assertEquals(Status.Code.NOT_FOUND, launch(*Array(MAX_INTENT_EXTRAS) { extra("k$it").setBoolValue(true) }))
        assertEquals(Status.Code.NOT_FOUND, coldLaunch(*valid))

        fun revoke(app: AppTarget.Builder, permission: String): Status.Code =
            code { stub.revokePermission(RevokePermissionRequest.newBuilder().setApp(app).setPermission(permission).build()) }
        assertEquals(Status.Code.INVALID_ARGUMENT, revoke(target, " "))
        assertEquals(Status.Code.INVALID_ARGUMENT, revoke(target.clone().setPackageName(DRIVER_PACKAGE), "android.permission.CAMERA"))
        assertEquals(Status.Code.NOT_FOUND, revoke(target, "android.permission.CAMERA"))

        fun granted(permission: String): Status.Code =
            code { stub.isPermissionGranted(IsPermissionGrantedRequest.newBuilder().setApp(target).setPermission(permission).build()) }
        assertEquals(Status.Code.INVALID_ARGUMENT, granted(""))
        assertEquals(Status.Code.NOT_FOUND, granted("android.permission.CAMERA"))

        fun locales(vararg tags: String): Status.Code =
            code { stub.setLocales(SetLocalesRequest.newBuilder().setApp(target).addAllLocales(tags.toList()).build()) }
        assertEquals(Status.Code.INVALID_ARGUMENT, locales("not a tag"))
        assertEquals(Status.Code.INVALID_ARGUMENT, locales("fr-FR", "fr-fr"))
        assertEquals(Status.Code.INVALID_ARGUMENT, locales(""))
        assertEquals(Status.Code.INVALID_ARGUMENT, locales(*Array(MAX_APP_LOCALES + 1) { "en-${'A' + it / 26}${'A' + it % 26}" }))
        assertEquals(Status.Code.NOT_FOUND, locales())
        assertEquals(Status.Code.NOT_FOUND, locales("fr-FR", "en"))
    }
}
