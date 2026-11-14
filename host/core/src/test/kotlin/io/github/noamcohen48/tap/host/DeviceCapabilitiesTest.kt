package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.CapabilityId
import io.github.noamcohen48.tap.api.v1.CapabilityId.*
import io.github.noamcohen48.tap.api.v1.CapabilityReason.*
import io.github.noamcohen48.tap.api.v1.CapabilityRequirement.*
import io.github.noamcohen48.tap.api.v1.CapabilityStatus.*
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.protocol.SUPPORTED_CAPABILITIES
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceCapabilitiesTest {
    @TempDir lateinit var directory: Path

    private fun facts(
        api: Int? = 34,
        scrcpy: Boolean? = true,
        serial: String = "emulator-5554",
    ) = CapabilityFacts(api, serial, SUPPORTED_CAPABILITIES.toSet(), scrcpy)

    private fun report(facts: CapabilityFacts = facts()) = DeviceCapabilities.discover(facts).associateBy { it.id }

    @Test fun `every public operation and enum ID is represented or explicitly excluded`() {
        val commands = Command.OpCase.entries.filter { it != Command.OpCase.OP_NOT_SET }.map { it.name.lowercase() }.toSet()
        assertEquals(commands, DeviceCapabilities.commands.keys)
        val entries = DeviceCapabilities.discover(facts())
        assertEquals(
            CapabilityId.entries
                .filter {
                    it != CAPABILITY_UNSPECIFIED && it != CapabilityId.UNRECOGNIZED
                }.toSet(),
            entries.map { it.id }.toSet(),
        )
        assertEquals(entries.map { it.idValue }.distinct().sorted(), entries.map { it.idValue })
        assertTrue(entries.all { it.detail.isNotBlank() })
        assertTrue(DeviceCapabilities.appRpcs.getValue("AwaitIdle").isEmpty())
    }

    @Test fun `API boundaries agree with execution requirements`() {
        val thresholds =
            mapOf(
                CAPABILITY_ACCESSIBILITY_PAGE_DOWN to 29,
                CAPABILITY_DEVICE_SET_DARK_MODE to 29,
                CAPABILITY_DEVICE_SET_NETWORK to 29,
                CAPABILITY_KEYBOARD_IME_ACTION to 30,
                CAPABILITY_ACCESSIBILITY_PRESS_AND_HOLD to 30,
                CAPABILITY_RECORD_AUDIO_OUTPUT to 30,
                CAPABILITY_RECORD_AUDIO_MIC to 30,
                CAPABILITY_DEVICE_SET_BOLD_TEXT to 31,
                CAPABILITY_APP_SET_LOCALES to 33,
                CAPABILITY_APP_GET_LOCALES to 33,
                CAPABILITY_RECORD_AUDIO_PLAYBACK to 33,
            )
        for ((id, api) in thresholds) {
            val below = report(facts(api - 1)).getValue(id)
            assertEquals(CAPABILITY_STATUS_UNSUPPORTED, below.status, "$id below API $api")
            assertEquals(api, below.requiredApi)
            assertTrue(CAPABILITY_REASON_REQUIRES_API in below.reasonsList)
            val at = report(facts(api)).getValue(id)
            assertTrue(at.status in setOf(CAPABILITY_STATUS_SUPPORTED, CAPABILITY_STATUS_CONDITIONAL), "$id at API $api")
            assertFalse(CAPABILITY_REASON_REQUIRES_API in at.reasonsList)
        }
        assertEquals(CAPABILITY_STATUS_SUPPORTED, report(facts(26)).getValue(CAPABILITY_DEVICE_SET_ACCESSIBILITY_DISPLAY).status)
    }

    @Test fun `unknown facts are not reported as unsupported and known blockers take priority`() {
        assertEquals(CAPABILITY_STATUS_UNKNOWN, report(facts(api = null)).getValue(CAPABILITY_APP_SET_LOCALES).status)
        assertEquals(CAPABILITY_STATUS_UNKNOWN, report(facts(api = 0)).getValue(CAPABILITY_KEYBOARD_IME_ACTION).status)
        assertEquals(CAPABILITY_STATUS_UNKNOWN, report(facts(scrcpy = null)).getValue(CAPABILITY_RECORD_VIDEO).status)
        assertEquals(CAPABILITY_STATUS_UNSUPPORTED, report(facts(29, scrcpy = null)).getValue(CAPABILITY_RECORD_AUDIO_MIC).status)
        val missing = report(facts(scrcpy = false)).getValue(CAPABILITY_RECORD_VIDEO)
        assertEquals(CAPABILITY_STATUS_UNSUPPORTED, missing.status)
        assertTrue(CAPABILITY_REASON_MISSING_HOST_TOOL in missing.reasonsList)
    }

    @Test fun `conditions do not promise element support capture access or a secure unlock`() {
        val entries = report()
        for (id in listOf(CAPABILITY_ACCESSIBILITY_PAGE_DOWN, CAPABILITY_KEYBOARD_IME_ACTION, CAPABILITY_SET_PROGRESS)) {
            assertEquals(CAPABILITY_STATUS_CONDITIONAL, entries.getValue(id).status)
            assertTrue(CAPABILITY_REQUIREMENT_TARGET_OFFERS_ACTION in entries.getValue(id).requirementsList)
        }
        assertTrue(CAPABILITY_REQUIREMENT_NONSECURE_KEYGUARD in entries.getValue(CAPABILITY_DISMISS_KEYGUARD).requirementsList)
        val playback = entries.getValue(CAPABILITY_RECORD_AUDIO_PLAYBACK)
        assertEquals(CAPABILITY_STATUS_CONDITIONAL, playback.status)
        assertTrue(CAPABILITY_REQUIREMENT_CAPTURE_NOT_EXCLUDED in playback.requirementsList)
        assertEquals(CAPABILITY_STATUS_SUPPORTED, entries.getValue(CAPABILITY_CLIPBOARD_READ).status)
        assertTrue(entries.getValue(CAPABILITY_CLIPBOARD_READ).detail.contains("no manual IME"))
    }

    @Test fun `network ADB restrictions match the command safety gate`() {
        for (serial in listOf("192.0.2.1:5555", "adb-phone._adb-tls-connect._tcp")) {
            val entries = report(facts(serial = serial))
            assertEquals(CAPABILITY_STATUS_CONDITIONAL, entries.getValue(CAPABILITY_DEVICE_SET_NETWORK).status)
            for (id in listOf(CAPABILITY_NETWORK_DISABLE_WIFI, CAPABILITY_NETWORK_ENABLE_AIRPLANE_MODE)) {
                assertEquals(CAPABILITY_STATUS_UNSUPPORTED, entries.getValue(id).status)
                assertTrue(CAPABILITY_REASON_NETWORK_ADB in entries.getValue(id).reasonsList)
            }
        }
        assertEquals(CAPABILITY_STATUS_SUPPORTED, report().getValue(CAPABILITY_NETWORK_DISABLE_WIFI).status)
    }

    @Test fun `unnegotiated screenshot and hierarchy are not advertised`() {
        val entries = report(facts().copy(negotiatedCapabilities = emptySet()))
        for (id in listOf(CAPABILITY_SCREENSHOT, CAPABILITY_SCREEN_SNAPSHOT, CAPABILITY_DUMP_HIERARCHY, CAPABILITY_PRESS_KEY)) {
            assertEquals(CAPABILITY_STATUS_UNSUPPORTED, entries.getValue(id).status)
            assertTrue(CAPABILITY_REASON_NOT_NEGOTIATED in entries.getValue(id).reasonsList)
        }
    }

    @Test fun `host inspection reads presence only never launches or creates recorder state`() =
        runBlocking {
            val executable = Files.writeString(directory.resolve("scrcpy"), "not an executable program\n")
            assertFalse(executableAvailable("scrcpy", directory.toString())!!)
            assertTrue(executable.toFile().setExecutable(true))
            assertTrue(executableAvailable("scrcpy", directory.toString())!!)
            assertFalse(executableAvailable("absent", directory.toString())!!)
            assertNull(executableAvailable("scrcpy", List(129) { "x" }.joinToString(":")))
            assertNull(executableAvailable("scrcpy", "\u0000"))
            val recorder =
                ScrcpyRecorder("serial", directory.resolve("must-not-exist"), executable.toString(), launch = {
                    _,
                    _,
                    ->
                    error("must not launch")
                })
            assertEquals(true, recorder.executableAvailable())
            assertFalse(Files.exists(directory.resolve("must-not-exist")))
            assertEquals(1L, Files.list(directory).use { it.count() })
        }
}
