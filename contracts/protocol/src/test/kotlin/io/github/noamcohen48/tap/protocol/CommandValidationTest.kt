package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.AllOf
import io.github.noamcohen48.tap.api.v1.AnyOf
import io.github.noamcohen48.tap.api.v1.AwaitToast
import io.github.noamcohen48.tap.api.v1.ChoosePermission
import io.github.noamcohen48.tap.api.v1.Command
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.DisplayRotation
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.First
import io.github.noamcohen48.tap.api.v1.Flag
import io.github.noamcohen48.tap.api.v1.Match
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.Node
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.OpenSystemPanel
import io.github.noamcohen48.tap.api.v1.Orientation
import io.github.noamcohen48.tap.api.v1.PermissionChoice
import io.github.noamcohen48.tap.api.v1.Pinch
import io.github.noamcohen48.tap.api.v1.PinchDirection
import io.github.noamcohen48.tap.api.v1.Related
import io.github.noamcohen48.tap.api.v1.Relation
import io.github.noamcohen48.tap.api.v1.Selector
import io.github.noamcohen48.tap.api.v1.SetDisplayRotation
import io.github.noamcohen48.tap.api.v1.SetOrientation
import io.github.noamcohen48.tap.api.v1.StabilitySignal
import io.github.noamcohen48.tap.api.v1.Swipe
import io.github.noamcohen48.tap.api.v1.SystemPanel
import io.github.noamcohen48.tap.api.v1.TextProperty
import io.github.noamcohen48.tap.api.v1.StandardAction
import io.github.noamcohen48.tap.api.v1.PerformAccessibilityAction
import io.github.noamcohen48.tap.api.v1.LocationAccuracy
import io.github.noamcohen48.tap.wire.v1.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandValidationTest {
    private val button = Selectors.text("OK")
    private val list = Selectors.androidResource(AUT, "list")

    // ---- commands --------------------------------------------------------------------------------

    @Test
    fun acceptsEveryRepresentativeCommand() {
        OperationsTest.sampleCommands.values.forEach { CommandValidation.validate(it) }
        OperationsTest.sampleRequests.values.forEach { CommandValidation.validate(it) }
    }

    @Test
    fun rejectsAnUnsetOperationAsUnsupported() {
        assertInvalid(ErrorCode.ERR_UNSUPPORTED) { CommandValidation.validate(Command.getDefaultInstance()) }
        assertInvalid(ErrorCode.ERR_UNSUPPORTED) { CommandValidation.validate(Request.getDefaultInstance()) }
        // A newer host's operation arrives as an unknown field: the op stays unset.
        val unknownOp = Command.parseFrom(byteArrayOf(0x9A.toByte(), 0x06, 0x00)) // field 99, empty message
        assertInvalid(ErrorCode.ERR_UNSUPPORTED) { CommandValidation.validate(unknownOp) }
    }

    @Test
    fun rejectsOutOfRangeArguments() {
        val invalid =
            mapOf(
                "timeout" to Commands.exists(button).toBuilder().setTimeoutMs(MAX_REQUEST_TIMEOUT_MS + 1).build(),
                "negative timeout" to Commands.exists(button).toBuilder().setTimeoutMs(-1).build(),
                "key code" to Commands.pressKey(-1),
                "app package" to Commands.waitAppVisible(" "),
                "stable package" to Commands.waitScreenStable(""),
                "stable zero" to Commands.waitScreenStable(AUT, stableForMs = 0),
                "stable too long" to Commands.waitScreenStable(AUT, stableForMs = MAX_STABLE_FOR_MS + 1),
                "set_text length" to Commands.setText(button, "x".repeat(MAX_TEXT_INPUT_CHARS + 1)),
                "type_text length" to Commands.typeText("x".repeat(MAX_TEXT_INPUT_CHARS + 1)),
                "swipe direction" to Commands.swipe(button, Direction.DIR_UNSPECIFIED),
                "swipe percent" to Commands.swipe(button, Direction.DIR_UP, distancePercent = 0),
                "scroll direction" to Commands.scroll(list, Direction.DIR_UNSPECIFIED),
                "scroll percent" to Commands.scroll(list, Direction.DIR_DOWN, distancePercent = 101),
                "at index" to Commands.tap(button.pickAt(-1)),
                "system panel" to Commands.openSystemPanel(SystemPanel.SYSTEM_PANEL_UNSPECIFIED),
                "unknown system panel" to
                    Command.newBuilder().setOpenSystemPanel(OpenSystemPanel.newBuilder().setPanelValue(99)).build(),
                "orientation" to Commands.setOrientation(Orientation.ORIENTATION_UNSPECIFIED),
                "unknown orientation" to
                    Command.newBuilder().setSetOrientation(SetOrientation.newBuilder().setOrientationValue(99)).build(),
                "display rotation" to Commands.setDisplayRotation(DisplayRotation.DISPLAY_ROTATION_UNSPECIFIED),
                "unknown display rotation" to
                    Command.newBuilder().setSetDisplayRotation(SetDisplayRotation.newBuilder().setRotationValue(99)).build(),
                "pinch direction" to Commands.pinch(button, PinchDirection.PINCH_UNSPECIFIED),
                "unknown pinch direction" to
                    Command.newBuilder().setPinch(Pinch.newBuilder().setSelector(button).setDirectionValue(99)).build(),
                "pinch percent" to Commands.pinch(button, PinchDirection.PINCH_OPEN, percent = 0),
                "pinch percent high" to Commands.pinch(button, PinchDirection.PINCH_CLOSE, percent = 101),
                "fling direction" to Commands.fling(list, Direction.DIR_UNSPECIFIED),
                "permission choice" to Commands.choosePermission(PermissionChoice.PERMISSION_CHOICE_UNSPECIFIED),
                "unknown permission choice" to
                    Command.newBuilder().setChoosePermission(ChoosePermission.newBuilder().setChoiceValue(99)).build(),
                "clipboard too long" to Commands.setClipboard("x".repeat(MAX_CLIPBOARD_CHARS + 1)),
                "toast mode without text" to Commands.awaitToast(mode = MatchMode.MATCH_CONTAINS),
                "toast text too long" to Commands.awaitToast("x".repeat(MAX_SELECTOR_STRING_CHARS + 1)),
                "toast regex" to Commands.awaitToast("(", MatchMode.MATCH_REGEX),
                "toast unknown mode" to
                    Command.newBuilder().setAwaitToast(AwaitToast.newBuilder().setText("x").setModeValue(99)).build(),
                "toast blank package" to Commands.awaitToast(packageName = " "),
                "unknown location accuracy" to
                    Command
                        .newBuilder()
                        .setChoosePermission(ChoosePermission.newBuilder().setChoice(PermissionChoice.PERMISSION_DENY).setAccuracyValue(99))
                        .build(),
                "no accessibility action" to
                    Command.newBuilder().setPerformAccessibilityAction(PerformAccessibilityAction.newBuilder().setSelector(button)).build(),
                "standard action" to Commands.performAccessibilityAction(button, StandardAction.STANDARD_ACTION_UNSPECIFIED),
                "unknown standard action" to
                    Command
                        .newBuilder()
                        .setPerformAccessibilityAction(PerformAccessibilityAction.newBuilder().setSelector(button).setStandardValue(99))
                        .build(),
                "empty custom action" to Commands.performCustomAction(button, ""),
                "custom action too long" to Commands.performCustomAction(button, "x".repeat(MAX_SELECTOR_STRING_CHARS + 1)),
                "progress NaN" to Commands.setProgress(button, Float.NaN),
                "progress infinite" to Commands.setProgress(button, Float.NEGATIVE_INFINITY),
                "latitude above 90" to Commands.setLocation(90.01, 0.0),
                "latitude NaN" to Commands.setLocation(Double.NaN, 0.0),
                "longitude below -180" to Commands.setLocation(0.0, -180.01),
                "zero accuracy" to Commands.setLocation(0.0, 0.0, accuracyM = 0f),
                "infinite accuracy" to Commands.setLocation(0.0, 0.0, accuracyM = Float.POSITIVE_INFINITY),
                "NaN altitude" to Commands.setLocation(0.0, 0.0, altitudeM = Double.NaN),
            )
        invalid.forEach { (name, command) ->
            assertInvalid(ErrorCode.ERR_INVALID_REQUEST, message = name) { CommandValidation.validate(command) }
        }
    }

    @Test
    fun acceptsBoundaryArguments() {
        listOf(
            Commands.exists(button).toBuilder().setTimeoutMs(0).build(),
            Commands.exists(button).toBuilder().setTimeoutMs(MAX_REQUEST_TIMEOUT_MS).build(),
            Commands.pressKey(0),
            Commands.waitScreenStable(AUT, stableForMs = 1, signal = StabilitySignal.STABILITY_UNSPECIFIED),
            Commands.waitScreenStable(AUT, stableForMs = MAX_STABLE_FOR_MS),
            Commands.setText(button, "x".repeat(MAX_TEXT_INPUT_CHARS)),
            Commands.setText(button, ""),
            Commands.swipe(button, Direction.DIR_LEFT, distancePercent = 1),
            Commands.scroll(list, Direction.DIR_RIGHT, distancePercent = 100),
            Commands.tap(button.pickAt(0)),
            Commands.openSystemPanel(SystemPanel.SYSTEM_PANEL_NOTIFICATIONS),
            Commands.openSystemPanel(SystemPanel.SYSTEM_PANEL_QUICK_SETTINGS),
            Commands.setOrientation(Orientation.ORIENTATION_PORTRAIT),
            Commands.setOrientation(Orientation.ORIENTATION_LANDSCAPE),
            Commands.setDisplayRotation(DisplayRotation.DISPLAY_ROTATION_NATURAL),
            Commands.setDisplayRotation(DisplayRotation.DISPLAY_ROTATION_LEFT),
            Commands.setDisplayRotation(DisplayRotation.DISPLAY_ROTATION_UPSIDE_DOWN),
            Commands.setDisplayRotation(DisplayRotation.DISPLAY_ROTATION_RIGHT),
            Commands.unfreezeRotation(),
            Commands.dismissKeyguard(),
            Commands.waitPermissionPrompt(),
            Commands.choosePermission(PermissionChoice.PERMISSION_KEEP_ONE_TIME),
            Commands.doubleTap(button),
            Commands.drag(button, list),
            Commands.pinch(button, PinchDirection.PINCH_OPEN),
            Commands.pinch(button, PinchDirection.PINCH_CLOSE, percent = 1),
            Commands.pinch(button, PinchDirection.PINCH_OPEN, percent = 100),
            Commands.fling(list, Direction.DIR_LEFT),
            Commands.hideKeyboard(),
            Commands.performImeAction(button),
            Commands.choosePermission(PermissionChoice.PERMISSION_ALLOW_FOREGROUND_ONLY, LocationAccuracy.LOCATION_APPROXIMATE),
            Commands.performAccessibilityAction(button, StandardAction.A11Y_PASTE),
            Commands.performCustomAction(button, "x".repeat(MAX_SELECTOR_STRING_CHARS)),
            Commands.setProgress(button, -1.5f),
            Commands.setLocation(-90.0, 180.0, accuracyM = 0.1f, altitudeM = -400.0),
            Commands.setLocation(90.0, -180.0),
            Commands.setClipboard(""),
            Commands.setClipboard("x".repeat(MAX_CLIPBOARD_CHARS)),
            Commands.getClipboard(),
            Commands.awaitToast(),
            Commands.awaitToast("Saved"),
            Commands.awaitToast("Sav.*", MatchMode.MATCH_REGEX, packageName = "com.android.systemui"),
            Commands.awaitToast("x".repeat(MAX_SELECTOR_STRING_CHARS)),
        ).forEach { CommandValidation.validate(it) }
    }

    @Test
    fun rejectsEnumValuesThisBuildDoesNotKnow() {
        val swipe = Commands.swipe(button, Direction.DIR_UP).toBuilder()
        swipe.setSwipe(Swipe.newBuilder(swipe.swipe).setDirectionValue(99))
        assertInvalid(ErrorCode.ERR_INVALID_REQUEST) { CommandValidation.validate(swipe.build()) }


        val stable = Commands.waitScreenStable(AUT).toBuilder()
        stable.setWaitScreenStable(stable.waitScreenStable.toBuilder().setSignalValue(7))
        assertInvalid(ErrorCode.ERR_INVALID_REQUEST) { CommandValidation.validate(stable.build()) }

        val property = Node.newBuilder().setMatch(Match.newBuilder().setPropertyValue(99).setValue("x")).build()
        assertInvalid(ErrorCode.ERR_INVALID_SELECTOR, ErrorDetail.UNSPECIFIED_VALUE) { validate(property) }
        val mode = Node.newBuilder().setMatch(Match.newBuilder().setProperty(TextProperty.PROPERTY_TEXT).setModeValue(99).setValue("x")).build()
        assertInvalid(ErrorCode.ERR_INVALID_SELECTOR, ErrorDetail.UNSPECIFIED_VALUE) { validate(mode) }
        val flag = Node.newBuilder().setFlag(Flag.newBuilder().setPropertyValue(99)).build()
        assertInvalid(ErrorCode.ERR_INVALID_SELECTOR, ErrorDetail.UNSPECIFIED_VALUE) { validate(flag) }
        val relation = Node.newBuilder().setRelated(Related.newBuilder().setRelationValue(99).setNode(Nodes.text("x"))).build()
        assertInvalid(ErrorCode.ERR_INVALID_SELECTOR, ErrorDetail.UNSPECIFIED_VALUE) { validate(relation) }
    }

    @Test
    fun everyTargetedCommandNeedsASelector() {
        val missing = Selector.getDefaultInstance()
        listOf(
            Commands.exists(missing),
            Commands.count(missing),
            Commands.snapshot(missing),
            Commands.waitVisible(missing),
            Commands.waitGone(missing),
            Commands.tap(missing),
            Commands.longTap(missing),
            Commands.setText(missing, "x"),
            Commands.clearText(missing),
            Commands.swipe(missing, Direction.DIR_UP),
            Commands.scroll(missing, Direction.DIR_UP),
            Commands.doubleTap(missing),
            Commands.drag(missing, list),
            Commands.drag(button, missing),
            Commands.pinch(missing, PinchDirection.PINCH_OPEN),
            Commands.fling(missing, Direction.DIR_UP),
        ).forEach { command ->
            assertInvalid(ErrorCode.ERR_INVALID_SELECTOR, ErrorDetail.EMPTY_NODE, command.op) { CommandValidation.validate(command) }
        }
    }


    @Test
    fun validatesSyncArguments() {
        CommandValidation.validate(Requests.syncBootstrap(1, "t", AUT, AUTHORITY))
        CommandValidation.validate(Requests.syncPoll(1, "t", "uuid", "id", AUT, AUTHORITY))
        listOf(
            Requests.syncBootstrap(1, " ", AUT, AUTHORITY),
            Requests.syncBootstrap(1, "t", "", AUTHORITY),
            Requests.syncBootstrap(1, "t", AUT, " "),
            Requests.syncPoll(1, "", "uuid", "id", AUT, AUTHORITY),
            Requests.syncPoll(1, "t", "", "id", AUT, AUTHORITY),
            Requests.syncPoll(1, "t", "uuid", " ", AUT, AUTHORITY),
            Requests.syncPoll(1, "t", "uuid", "id", " ", AUTHORITY),
            Requests.syncPoll(1, "t", "uuid", "id", AUT, ""),
        ).forEach { request ->
            assertInvalid(ErrorCode.ERR_INVALID_REQUEST) { CommandValidation.validate(request) }
        }
    }

    // ---- selectors -------------------------------------------------------------------------------

    @Test
    fun rejectsStructurallyInvalidSelectors() {
        val longString = "x".repeat(MAX_SELECTOR_STRING_CHARS + 1)
        val cases: List<Triple<String, String, Selector>> =
            listOf(
                Triple("no node", ErrorDetail.EMPTY_NODE, Selector.newBuilder().setFirst(First.getDefaultInstance()).build()),
                Triple("empty kind", ErrorDetail.EMPTY_NODE, Selectors.of(Node.getDefaultInstance())),
                Triple("long package", ErrorDetail.STRING_TOO_LONG, Selectors.of(Nodes.packageName(longString))),
                Triple("long value", ErrorDetail.STRING_TOO_LONG, Selectors.text(longString)),
                Triple("long resource", ErrorDetail.STRING_TOO_LONG, Selectors.resource(longString)),
                Triple("long resource package", ErrorDetail.STRING_TOO_LONG, Selectors.androidResource(longString, "id")),
                Triple("empty contains", ErrorDetail.EMPTY_VALUE, Selectors.text("", MatchMode.MATCH_CONTAINS)),
                Triple("empty regex", ErrorDetail.EMPTY_VALUE, Selectors.text("", MatchMode.MATCH_REGEX)),
                Triple("bad regex", ErrorDetail.INVALID_REGEX, Selectors.text("(", MatchMode.MATCH_REGEX)),
                Triple("backreference", ErrorDetail.INVALID_REGEX, Selectors.text("(a)\\1", MatchMode.MATCH_REGEX)),
                Triple(
                    "unspecified property",
                    ErrorDetail.UNSPECIFIED_VALUE,
                    Selectors.of(Node.newBuilder().setMatch(Match.newBuilder().setValue("x")).build()),
                ),
                Triple("unspecified flag", ErrorDetail.UNSPECIFIED_VALUE, Selectors.of(Node.newBuilder().setFlag(Flag.getDefaultInstance()).build())),
                Triple("empty resource", ErrorDetail.EMPTY_VALUE, Selectors.resource("")),
                Triple("empty resource package", ErrorDetail.EMPTY_VALUE, Selectors.androidResource("", "id")),
                Triple("qualified resource name", ErrorDetail.QUALIFIED_RESOURCE_NAME, Selectors.resource("$AUT:id/login")),
                Triple(
                    "unspecified relation",
                    ErrorDetail.UNSPECIFIED_VALUE,
                    Selectors.of(Node.newBuilder().setRelated(Related.newBuilder().setNode(Nodes.text("x"))).build()),
                ),
                Triple(
                    "relation without node",
                    ErrorDetail.EMPTY_NODE,
                    Selectors.of(Node.newBuilder().setRelated(Related.newBuilder().setRelation(Relation.RELATION_PARENT)).build()),
                ),
                Triple(
                    "single all_of",
                    ErrorDetail.EMPTY_NODE,
                    Selectors.of(Node.newBuilder().setAllOf(AllOf.newBuilder().addNodes(Nodes.text("x"))).build()),
                ),
                Triple(
                    "single any_of",
                    ErrorDetail.EMPTY_NODE,
                    Selectors.of(Node.newBuilder().setAnyOf(AnyOf.newBuilder().addNodes(Nodes.text("x"))).build()),
                ),
                Triple("empty nested operand", ErrorDetail.EMPTY_NODE, Selectors.of(Nodes.allOf(Nodes.text("x"), Node.getDefaultInstance()))),
                Triple("too deep", ErrorDetail.SELECTOR_TOO_DEEP, Selectors.of(nested(MAX_SELECTOR_DEPTH + 1))),
                Triple(
                    "too many nodes",
                    ErrorDetail.SELECTOR_TOO_LARGE,
                    Selectors.of(Nodes.anyOf((1..MAX_SELECTOR_NODES).map { Nodes.text("$it") })),
                ),
            )
        cases.forEach { (name, detail, selector) ->
            assertInvalid(ErrorCode.ERR_INVALID_SELECTOR, detail, name) { CommandValidation.validateSelector(selector) }
        }
    }

    @Test
    fun acceptsSelectorsAtTheLimits() {
        CommandValidation.validateSelector(Selectors.of(nested(MAX_SELECTOR_DEPTH)))
        CommandValidation.validateSelector(Selectors.of(Nodes.anyOf((1 until MAX_SELECTOR_NODES).map { Nodes.text("$it") })))
        CommandValidation.validateSelector(Selectors.text("x".repeat(MAX_SELECTOR_STRING_CHARS)))
        // EXACT "" is meaningful (an empty value); so is the unspecified mode, which means EXACT.
        CommandValidation.validateSelector(Selectors.text(""))
        CommandValidation.validateSelector(Selectors.of(Nodes.match(TextProperty.PROPERTY_HINT, "", MatchMode.MATCH_UNSPECIFIED)))
        CommandValidation.validateSelector(Selectors.of(Nodes.resource("login")))
    }

    @Test
    fun choosesTheNativePlanWhenBySelectorCanHoldEveryPredicate() {
        val native =
            listOf(
                Selectors.text("OK"),
                Selectors.text("OK", MatchMode.MATCH_ENDS_WITH),
                Selectors.androidResource(AUT, "login"),
                Selectors.of(Nodes.resource("login")),
                Selectors.of(Nodes.text("OK") and Nodes.flag(NodeFlag.FLAG_ENABLED) and Nodes.parent(Nodes.className("List"))),
                Selectors.of(Nodes.text("OK") and Nodes.contentDescription("OK") and Nodes.hint("OK")),
                // BySelector holds any number of child/descendant constraints.
                Selectors.of(Nodes.child(Nodes.text("a")) and Nodes.child(Nodes.text("b")) and Nodes.descendant(Nodes.text("c"))),
                Selectors.of(Nodes.ancestor(Nodes.text("a")) and Nodes.parent(Nodes.text("b"))),
            )
        native.forEach { assertEquals(SelectorPlanKind.NATIVE, CommandValidation.validateSelector(it), it.render()) }
    }

    @Test
    fun choosesTraversalForRegexAnyOfAndRepeatedSingleValuedPredicates() {
        val traversal =
            listOf(
                Selectors.text("Row \\d+", MatchMode.MATCH_REGEX),
                Selectors.of(Nodes.text("Allow") or Nodes.contentDescription("Allow")),
                Selectors.of(Nodes.text("a", MatchMode.MATCH_CONTAINS) and Nodes.text("b", MatchMode.MATCH_CONTAINS)),
                Selectors.of(Nodes.flag(NodeFlag.FLAG_ENABLED) and Nodes.flag(NodeFlag.FLAG_ENABLED, false)),
                Selectors.of(Nodes.resource("a") and Nodes.androidResource(AUT, "b")),
                Selectors.of(Nodes.parent(Nodes.text("a")) and Nodes.parent(Nodes.text("b"))),
                Selectors.of(Nodes.ancestor(Nodes.text("a")) and Nodes.ancestor(Nodes.text("b"))),
                // A traversal-only predicate anywhere in the tree makes the whole selector traversal.
                Selectors.of(Nodes.text("OK") and Nodes.descendant(Nodes.text("x.*", MatchMode.MATCH_REGEX))),
                Selectors.of(Nodes.child(Nodes.text("a") or Nodes.text("b"))),
            )
        traversal.forEach { assertEquals(SelectorPlanKind.TRAVERSAL, CommandValidation.validateSelector(it), it.render()) }
    }

    @Test
    fun invalidCommandExceptionConvertsToAFailure() {
        val failure =
            assertFailsWith<InvalidCommandException> { CommandValidation.validateSelector(Selectors.text("(", MatchMode.MATCH_REGEX)) }
                .toFailure()
        assertEquals(ErrorCode.ERR_INVALID_SELECTOR, failure.code)
        assertEquals(ErrorDetail.INVALID_REGEX, failure.detail)
    }

    private fun validate(node: Node) = CommandValidation.validateSelector(Selectors.of(node))

    /** A chain of [depth] nodes: `parent(parent(…text…))`. */
    private fun nested(depth: Int): Node = (1 until depth).fold(Nodes.text("leaf")) { node, _ -> Nodes.parent(node) }

    private fun assertInvalid(
        code: ErrorCode,
        detail: String? = null,
        message: String? = null,
        block: () -> Unit,
    ) {
        val error = assertFailsWith<InvalidCommandException>(message) { block() }
        assertEquals(code, error.code, message)
        assertEquals(detail, error.detail, message)
    }
}

internal const val AUT = "io.github.noamcohen48.tap.fixture"
internal const val AUTHORITY = "$AUT.tap-sync"
