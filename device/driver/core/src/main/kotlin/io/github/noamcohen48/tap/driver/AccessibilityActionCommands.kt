package io.github.noamcohen48.tap.driver

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.PerformAccessibilityAction
import io.github.noamcohen48.tap.api.v1.Range
import io.github.noamcohen48.tap.api.v1.RangeType
import io.github.noamcohen48.tap.api.v1.StandardAction
import io.github.noamcohen48.tap.driver.engine.CommandContext
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.ErrorDetail

/**
 * Accessibility actions on one node, as a screen reader performs them (`perform_accessibility_action`,
 * `set_progress`): no touch, so nothing is checked for occlusion. What the node offers is the
 * contract: an action it does not list is refused before input (`ACTION_NOT_OFFERED`), never
 * tried in the hope that the view handles it anyway, and Android's answer to an offered one is
 * reported as it is.
 */
internal class AccessibilityActionCommands(
    private val objects: UiObjectAccess,
) {
    fun perform(
        context: CommandContext,
        target: CompiledSelector,
        command: PerformAccessibilityAction,
    ) {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        try {
            val node = element.accessibilityNodeInfo
            val (id, name) =
                when (command.actionCase) {
                    PerformAccessibilityAction.ActionCase.STANDARD -> {
                        val action = standardAction(command.standard)
                        if (node.actionList.none { it.id == action.id }) throw notOffered("The node does not offer ${command.standard.label}")
                        action.id to command.standard.label
                    }

                    else -> {
                        val custom = node.actionList.filter { it.label?.toString() == command.custom && it.id !in STANDARD_IDS }
                        when (custom.size) {
                            0 -> throw notOffered("The node offers no custom action '${command.custom}'")
                            1 -> custom.single().id to "custom action '${command.custom}'"
                            else -> throw notOffered("The node offers ${custom.size} custom actions labelled '${command.custom}'")
                        }
                    }
                }
            context.markMutationStarted()
            if (!node.performAction(id)) throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "The node refused $name")
        } finally {
            element.recycle()
        }
    }

    /**
     * `ACTION_SET_PROGRESS` with [value] in the node's RangeInfo units. A value outside min..max
     * is refused before input rather than clamped by the view, so the test sees its own mistake.
     */
    fun setProgress(
        context: CommandContext,
        target: CompiledSelector,
        value: Float,
    ) {
        context.checkpoint()
        val element = objects.resolveTarget(target)
        try {
            val node = element.accessibilityNodeInfo
            val setProgress = AccessibilityAction.ACTION_SET_PROGRESS
            if (node.actionList.none { it.id == setProgress.id }) throw notOffered("The node does not offer ACTION_SET_PROGRESS")
            val range = node.rangeInfo ?: throw notOffered("The node has no range")
            if (value < range.min || value > range.max) {
                throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, ErrorDetail.OUT_OF_RANGE, "$value is outside the node's range ${range.min}..${range.max}")
            }
            context.markMutationStarted()
            val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
            if (!node.performAction(setProgress.id, arguments)) {
                throw CommandFailure(ErrorCode.ERR_ACTION_REJECTED, message = "The node refused ACTION_SET_PROGRESS")
            }
        } finally {
            element.recycle()
        }
    }

    private fun notOffered(message: String) = CommandFailure(ErrorCode.ERR_ACTION_REJECTED, ErrorDetail.ACTION_NOT_OFFERED, message)

    private fun standardAction(action: StandardAction): AccessibilityAction =
        STANDARD[action] ?: run {
            val api = MIN_API[action]
            if (api != null && Build.VERSION.SDK_INT < api) {
                val detail = if (api == 29) ErrorDetail.REQUIRES_API_29 else ErrorDetail.REQUIRES_API_30
                throw CommandFailure(ErrorCode.ERR_UNSUPPORTED, detail, "${action.label} needs API $api (this device is API ${Build.VERSION.SDK_INT})")
            }
            throw CommandFailure(ErrorCode.ERR_INVALID_REQUEST, message = "A known standard action is required")
        }

    companion object {
        /** The standard actions this device knows (newer ones are absent on older APIs). */
        private val STANDARD: Map<StandardAction, AccessibilityAction> =
            buildMap {
                put(StandardAction.A11Y_EXPAND, AccessibilityAction.ACTION_EXPAND)
                put(StandardAction.A11Y_COLLAPSE, AccessibilityAction.ACTION_COLLAPSE)
                put(StandardAction.A11Y_DISMISS, AccessibilityAction.ACTION_DISMISS)
                put(StandardAction.A11Y_SCROLL_FORWARD, AccessibilityAction.ACTION_SCROLL_FORWARD)
                put(StandardAction.A11Y_SCROLL_BACKWARD, AccessibilityAction.ACTION_SCROLL_BACKWARD)
                put(StandardAction.A11Y_SCROLL_UP, AccessibilityAction.ACTION_SCROLL_UP)
                put(StandardAction.A11Y_SCROLL_DOWN, AccessibilityAction.ACTION_SCROLL_DOWN)
                put(StandardAction.A11Y_SCROLL_LEFT, AccessibilityAction.ACTION_SCROLL_LEFT)
                put(StandardAction.A11Y_SCROLL_RIGHT, AccessibilityAction.ACTION_SCROLL_RIGHT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(StandardAction.A11Y_PAGE_UP, AccessibilityAction.ACTION_PAGE_UP)
                    put(StandardAction.A11Y_PAGE_DOWN, AccessibilityAction.ACTION_PAGE_DOWN)
                    put(StandardAction.A11Y_PAGE_LEFT, AccessibilityAction.ACTION_PAGE_LEFT)
                    put(StandardAction.A11Y_PAGE_RIGHT, AccessibilityAction.ACTION_PAGE_RIGHT)
                }
                put(StandardAction.A11Y_SHOW_ON_SCREEN, AccessibilityAction.ACTION_SHOW_ON_SCREEN)
                put(StandardAction.A11Y_CONTEXT_CLICK, AccessibilityAction.ACTION_CONTEXT_CLICK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) put(StandardAction.A11Y_PRESS_AND_HOLD, AccessibilityAction.ACTION_PRESS_AND_HOLD)
                put(StandardAction.A11Y_SELECT, AccessibilityAction.ACTION_SELECT)
                put(StandardAction.A11Y_CLEAR_SELECTION, AccessibilityAction.ACTION_CLEAR_SELECTION)
                put(StandardAction.A11Y_FOCUS, AccessibilityAction.ACTION_FOCUS)
                put(StandardAction.A11Y_CLEAR_FOCUS, AccessibilityAction.ACTION_CLEAR_FOCUS)
                put(StandardAction.A11Y_COPY, AccessibilityAction.ACTION_COPY)
                put(StandardAction.A11Y_CUT, AccessibilityAction.ACTION_CUT)
                put(StandardAction.A11Y_PASTE, AccessibilityAction.ACTION_PASTE)
            }

        private val MIN_API =
            mapOf(
                StandardAction.A11Y_PAGE_UP to 29,
                StandardAction.A11Y_PAGE_DOWN to 29,
                StandardAction.A11Y_PAGE_LEFT to 29,
                StandardAction.A11Y_PAGE_RIGHT to 29,
                StandardAction.A11Y_PRESS_AND_HOLD to 30,
            )

        private val BY_ID: Map<Int, StandardAction> = STANDARD.entries.associate { (name, action) -> action.id to name }

        /**
         * Every id Android defines for a standard action (`AccessibilityNodeInfo.ACTION_*` and
         * the `R.id.accessibilityAction*` ids): a label on one of those (TalkBack-style
         * relabelling) is not a custom action.
         */
        private val STANDARD_IDS: Set<Int> get() = BY_ID.keys + OTHER_STANDARD_IDS

        private val OTHER_STANDARD_IDS: Set<Int> =
            buildSet {
                add(AccessibilityNodeInfo.ACTION_CLICK)
                add(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                add(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                add(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                add(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
                add(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY)
                add(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
                add(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT)
                add(AccessibilityNodeInfo.ACTION_SET_SELECTION)
                add(AccessibilityNodeInfo.ACTION_SET_TEXT)
                add(AccessibilityAction.ACTION_SET_PROGRESS.id)
                add(AccessibilityAction.ACTION_SCROLL_TO_POSITION.id)
                add(AccessibilityAction.ACTION_MOVE_WINDOW.id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AccessibilityAction.ACTION_SHOW_TOOLTIP.id)
                    add(AccessibilityAction.ACTION_HIDE_TOOLTIP.id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(AccessibilityAction.ACTION_IME_ENTER.id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AccessibilityAction.ACTION_DRAG_START.id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AccessibilityAction.ACTION_DRAG_DROP.id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AccessibilityAction.ACTION_DRAG_CANCEL.id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AccessibilityAction.ACTION_SHOW_TEXT_SUGGESTIONS.id)
                if (Build.VERSION.SDK_INT >= 34) add(AccessibilityAction.ACTION_SCROLL_IN_DIRECTION.id)
            }

        /** The node's standard actions (in StandardAction order) and custom action labels. */
        fun offered(node: AccessibilityNodeInfo): Pair<List<StandardAction>, List<String>> {
            val actions = node.actionList
            val standard = actions.mapNotNull { BY_ID[it.id] }.distinct().sortedBy { it.number }
            val custom = actions.filter { it.id !in STANDARD_IDS }.mapNotNull { it.label?.toString() }
            return standard to custom
        }

        /** The node's RangeInfo, or null when it is not a range node. */
        fun range(node: AccessibilityNodeInfo): Range? {
            val info = node.rangeInfo ?: return null
            val type =
                when (info.type) {
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT -> RangeType.RANGE_INT
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT -> RangeType.RANGE_FLOAT
                    AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT -> RangeType.RANGE_PERCENT
                    else -> RangeType.RANGE_TYPE_UNSPECIFIED
                }
            return Range.newBuilder().setType(type).setMin(info.min).setMax(info.max).setCurrent(info.current).build()
        }

        private val StandardAction.label: String get() = "ACTION_" + name.removePrefix("A11Y_")
    }
}
