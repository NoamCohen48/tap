package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.DeviceState as DeviceStateProto
import io.github.noamcohen48.tap.api.v1.Direction as DirectionProto
import io.github.noamcohen48.tap.api.v1.ErrorCode as ErrorCodeProto
import io.github.noamcohen48.tap.api.v1.FailureReason as FailureReasonProto
import io.github.noamcohen48.tap.api.v1.MatchMode as MatchModeProto
import io.github.noamcohen48.tap.api.v1.StabilitySignal as StabilitySignalProto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Every schema value has an SDK counterpart, so a proto addition without one fails here. */
class ModelsTest {
    private fun <E : Enum<E>> known(values: Array<E>): List<E> = values.filter { it.name != "UNRECOGNIZED" && !it.name.endsWith("UNSPECIFIED") }

    @Test
    fun `every proto enum value maps to its own SDK constant`() {
        known(ErrorCodeProto.values()).forEach { assertEquals(it.name.removePrefix("ERR_"), it.toModel().name) }
        known(FailureReasonProto.values()).forEach {
            assertNotEquals(FailureReason.UNSPECIFIED, it.toModel(), it.name)
            assertEquals(it.name.removePrefix("FAILURE_REASON_"), it.toModel().name)
        }
        known(DeviceStateProto.values()).forEach { assertEquals(it.name.removePrefix("DEVICE_"), it.toModel().name) }
        assertEquals(ErrorCode.UNKNOWN, ErrorCodeProto.ERR_UNSPECIFIED.toModel())
        assertEquals(DeviceState.UNKNOWN, DeviceStateProto.DEVICE_STATE_UNSPECIFIED.toModel())
    }

    @Test
    fun `every SDK argument enum maps to a proto value`() {
        assertEquals(known(MatchModeProto.values()), MatchMode.entries.map { it.toProto() })
        assertEquals(known(DirectionProto.values()), Direction.entries.map { it.toProto() })
        assertEquals(known(StabilitySignalProto.values()), StabilitySignal.entries.map { it.toProto() })
    }

    @Test
    fun `a screenshot reads its size from the PNG header`() {
        val header = ByteArray(24)
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(header)
        byteArrayOf(0, 0, 4, 56, 0, 0, 9, 96).copyInto(header, 16)
        val shot = Screenshot.png(header)
        assertEquals(1080 to 2400, shot.width to shot.height)
        assertEquals("image/png", shot.mediaType)
        assertEquals(0 to 0, Screenshot.png(byteArrayOf(1, 2, 3)).let { it.width to it.height })
    }

    @Test
    fun `artifacts save their bytes`(
        @TempDir dir: Path,
    ) {
        val info = DeviceInfo(34, "Google", "Pixel", "sdk", 1080, 2400, DisplayRotation.NATURAL, null, true, false, false, true, true, false, true, 1.3f, 420, false, true, true)
        val json = Json.parseToJsonElement(Files.readString(info.save(dir.resolve("a/info.json")))).jsonObject
        assertEquals("34", json.getValue("apiLevel").jsonPrimitive.content)
        assertEquals("NATURAL", json.getValue("displayRotation").jsonPrimitive.content)
        assertEquals("true", json.getValue("screenOn").jsonPrimitive.content)
        assertEquals("true", json.getValue("keyboardShown").jsonPrimitive.content)
        assertEquals("true", json.getValue("autoRotate").jsonPrimitive.content)
        assertEquals("false", json.getValue("animationsEnabled").jsonPrimitive.content)
        assertEquals("true", json.getValue("darkMode").jsonPrimitive.content)
        assertEquals("1.3", json.getValue("fontScale").jsonPrimitive.content)
        assertEquals("420", json.getValue("densityDpi").jsonPrimitive.content)
        assertEquals(Orientation.PORTRAIT, info.orientation)
        assertEquals(Orientation.LANDSCAPE, info.copy(displayWidth = 2400, displayHeight = 1080).orientation)
        assertEquals("null", json.getValue("currentPackage").toString())
        assertEquals("<a/>", Files.readString(Hierarchy("<a/>").save(dir.resolve("h.xml"))))
        assertEquals("one\ntwo", Files.readString(DriverLog(listOf("one", "two")).save(dir.resolve("d.txt"))))
    }
}
