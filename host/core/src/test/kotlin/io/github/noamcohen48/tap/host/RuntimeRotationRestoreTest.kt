package io.github.noamcohen48.tap.host

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeRotationRestoreTest {
    private fun baseline(mode: String?) =
        StateKey.ROTATION.map { key ->
            SavedState(key, if (key == StateKey.ACCELEROMETER_ROTATION) mode else "0")
        }

    @Test
    fun `an unchanged unfrozen baseline may use readback alone`() {
        assertTrue(canUseReadOnlyRotationRestore(baseline("1"), false))
        assertTrue(canUseReadOnlyRotationRestore(baseline("1").reversed(), false))
    }

    @Test
    fun `a frozen snapshot may differ from user rotation and always needs full restore`() {
        assertFalse(canUseReadOnlyRotationRestore(baseline("0"), false))
    }

    @Test
    fun `touched unknown and incomplete baselines always need full restore`() {
        assertFalse(canUseReadOnlyRotationRestore(baseline("1"), true))
        assertFalse(canUseReadOnlyRotationRestore(baseline(null), false))
        assertFalse(canUseReadOnlyRotationRestore(baseline("unexpected"), false))
        assertFalse(canUseReadOnlyRotationRestore(emptyList(), false))
        assertFalse(canUseReadOnlyRotationRestore(baseline("1").take(2), false))
        assertFalse(canUseReadOnlyRotationRestore(List(3) { SavedState(StateKey.ACCELEROMETER_ROTATION, "1") }, false))
    }
}
