package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Selector

/**
 * How an action target is picked from the matches of a selector, in traversal order. Exactly
 * one (the default when no pick is set) looks for a second match so it can report `AMBIGUOUS`;
 * `first`/`at` stop after the match they want.
 */
internal class SelectorPick private constructor(
    val exactlyOne: Boolean,
    /** How many matches the search needs before it can stop. */
    val wanted: Int,
    private val index: Int,
) {
    /** The outcome of picking from [matchCount] matches (at most [wanted]). */
    sealed interface Outcome {
        data class Chosen(val index: Int) : Outcome

        data class Failed(val code: ErrorCode) : Outcome
    }

    fun choose(matchCount: Int): Outcome =
        when {
            exactlyOne && matchCount > 1 -> Outcome.Failed(ErrorCode.ERR_AMBIGUOUS)
            exactlyOne && matchCount == 1 -> Outcome.Chosen(0)
            !exactlyOne && index < matchCount -> Outcome.Chosen(index)
            else -> Outcome.Failed(ErrorCode.ERR_NOT_FOUND)
        }

    companion object {
        fun of(selector: Selector): SelectorPick =
            when (selector.pickCase) {
                Selector.PickCase.FIRST -> SelectorPick(exactlyOne = false, wanted = 1, index = 0)
                Selector.PickCase.AT -> SelectorPick(exactlyOne = false, wanted = selector.at.index + 1, index = selector.at.index)
                Selector.PickCase.EXACTLY_ONE, Selector.PickCase.PICK_NOT_SET, null ->
                    SelectorPick(exactlyOne = true, wanted = 2, index = 0)
            }
    }
}
