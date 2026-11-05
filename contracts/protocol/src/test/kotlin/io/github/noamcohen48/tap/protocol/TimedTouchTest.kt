package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Selector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TimedTouchTest {
    private val button = Selectors.text("Button")

    @Test
    fun countedTapsAndHoldsAreMutationsWithValidatedSelectors() {
        for (command in listOf(Commands.multiTap(button, 3), Commands.hold(button, 2_000))) {
            CommandValidation.validate(command)
            assertTrue(command.isMutation)
            assertEquals(button, command.targetSelector)
            assertEquals(listOf(button), command.selectors)
            assertEquals(
                command,
                io.github.noamcohen48.tap.api.v1.Command
                    .parseFrom(command.toByteArray()),
            )
        }
        for (command in listOf(Commands.multiTap(Selector.getDefaultInstance(), 3), Commands.hold(Selector.getDefaultInstance(), 2_000))) {
            assertThrows<InvalidCommandException> { CommandValidation.validate(command) }
        }
    }

    @Test
    fun rejectsUnboundedAndInvalidTimingBeforeInput() {
        val invalid =
            listOf(
                Commands.multiTap(button, 0),
                Commands.multiTap(button, -1),
                Commands.multiTap(button, 101),
                Commands.multiTap(button, 3, 39),
                Commands.multiTap(button, 3, 1_001),
                Commands.multiTap(button, 100, 1_000),
                Commands.multiTap(button, Int.MAX_VALUE, Long.MAX_VALUE),
                Commands.hold(button, 0),
                Commands.hold(button, -1),
                Commands.hold(button, 30_001),
                Commands.hold(button, Long.MAX_VALUE),
            )
        for (command in invalid) {
            val failure = assertThrows<InvalidCommandException> { CommandValidation.validate(command) }
            assertEquals(ErrorCode.ERR_INVALID_REQUEST, failure.code)
        }
    }

    @Test
    fun acceptsBoundaryValuesAndCountsPressTime() {
        for (command in listOf(
            Commands.multiTap(button, 1, 40),
            Commands.multiTap(button, 100, 100),
            Commands.hold(button, 1),
            Commands.hold(button, 30_000),
        )) {
            CommandValidation.validate(command)
        }
        assertEquals(350, multiTapDurationMs(3, 100))
        assertEquals(50, multiTapDurationMs(1, 100))
    }
}
