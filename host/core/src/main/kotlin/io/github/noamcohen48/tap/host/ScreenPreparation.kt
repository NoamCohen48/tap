package io.github.noamcohen48.tap.host

/**
 * Conservative reading of PowerManager's dump, including newer per-display-group entries.
 * Every reported group must be awake and no reported wakefulness transition may be in progress.
 * Missing, unfamiliar or contradictory fields mean "send the wake key", not "assume awake".
 */
internal fun powerStateDefinitelyAwake(output: String): Boolean {
    // dumpsys may exit successfully with partial service output followed by a timeout marker.
    if (output.contains("DUMP TIMEOUT", ignoreCase = true)) return false
    val states =
        output
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("mWakefulness=") }
            .toList()
    val transitions =
        output
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("mWakefulnessChanging=") }
            .toList()
    return states.isNotEmpty() && states.all { it == "mWakefulness=Awake" } &&
        transitions.isNotEmpty() && transitions.all { it == "mWakefulnessChanging=false" }
}
