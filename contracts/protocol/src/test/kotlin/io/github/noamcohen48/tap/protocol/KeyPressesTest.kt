package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.PressKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KeyPressesTest {
    private fun key(build: PressKey.Builder.() -> Unit): PressKey = PressKey.newBuilder().setKeyCode(25).apply(build).build()

    private fun command(key: PressKey): Command = Command.newBuilder().setPressKey(key).build()

    @Test
    fun aPlainPressIsADownAndAnUpAtOnce() {
        assertEquals(listOf(KeyStep(0, true), KeyStep(0, false)), keyPressPlan(key {}, 500, 50))
    }

    @Test
    fun aLongPressHoldsForTheLongPressTimeoutAndMarksItsOneRepeat() {
        assertEquals(
            listOf(KeyStep(0, true), KeyStep(500, true, 1, longPress = true), KeyStep(500, false)),
            keyPressPlan(key { longPress = true }, 500, 50),
        )
    }

    @Test
    fun aHeldKeyRepeatsLikeAHeldKeyAfterTheLongPress() {
        val plan = keyPressPlan(key { durationMs = 620 }, 500, 50)
        assertEquals(
            listOf(
                KeyStep(0, true),
                KeyStep(500, true, 1, longPress = true),
                KeyStep(550, true, 2),
                KeyStep(600, true, 3),
                KeyStep(620, false),
            ),
            plan,
        )
        assertEquals(listOf(KeyStep(0, true), KeyStep(200, false)), keyPressPlan(key { durationMs = 200 }, 500, 50))
    }

    @Test
    fun countedPressesAreTheIntervalApartReleaseToPress() {
        assertEquals(
            listOf(KeyStep(0, true), KeyStep(0, false), KeyStep(300, true), KeyStep(300, false)),
            keyPressPlan(key { count = 2; intervalMs = 300 }, 500, 50),
        )
        val held = keyPressPlan(key { count = 2; durationMs = 100 }, 500, 50)
        assertEquals(listOf(0L, 100L, 200L, 300L), held.map { it.atMs })
        assertEquals(300L, keyPressesDurationMs(key { count = 2; durationMs = 100 }, 500))
        assertEquals(1_100L, keyPressesDurationMs(key { count = 2; longPress = true }, 500))
    }

    @Test
    fun validationRefusesConflictingAndUnboundedPresses() {
        CommandValidation.validate(command(key { count = 3; intervalMs = 40 }))
        CommandValidation.validate(command(key { durationMs = 30_000 }))
        CommandValidation.validate(command(key { longPress = true; count = 2 }))
        CommandValidation.validate(command(key { longPress = true; count = 18; intervalMs = 100 }))
        for (bad in listOf(
            key { longPress = false },
            // A long press counts at its worst case (1500 ms): 20 of them do not fit in 30 s.
            key { longPress = true; count = 20; intervalMs = 100 },
            key { durationMs = 0 },
            key { durationMs = 30_001 },
            key { count = 0 },
            key { count = 101 },
            key { intervalMs = 39 },
            key { intervalMs = 1_001 },
            key { count = 100; durationMs = 1_000 },
        )) {
            assertThrows<InvalidCommandException>(bad.toString()) { CommandValidation.validate(command(bad)) }
        }
    }
}
