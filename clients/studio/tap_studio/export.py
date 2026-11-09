"""A recording as a pytest file on ``tap-e2e``'s public API, mirroring ``steps.run`` call for call.

``tests/test_export.py`` runs every kind of step both ways and compares the SDK calls, so the file
means what a replay in Studio means. Anything this module does not know how to write raises
``ExportError``: an export that quietly dropped a step or a budget would pass while testing less.
Nothing here retries, and the file never starts a Tap server.
"""

from __future__ import annotations

import keyword
import re

from google.protobuf import text_format
from tap_e2e import (
    CONTAINS,
    ENDS_WITH,
    EXACT,
    REGEX,
    STARTS_WITH,
    Direction,
    DisplayRotation,
    LocationAccuracy,
    MatchMode,
    Orientation,
    PermissionChoice,
    Selector,
    StabilitySignal,
    StandardAction,
    class_name,
    desc,
    hint,
    res,
    res_id,
    text,
)
from tap_e2e import proto as tap

from ._gen import studio_pb2 as studio
from .recording import RecordingError, parameter_name, picks, secret_name, validate
from .steps import DEFAULT_GESTURE_PERCENT, DEFAULT_MAX_SCROLLS, DEFAULT_STABLE_FOR_MS
from .timing import seconds_from_ms


class ExportError(ValueError):
    """The recording cannot be written without changing what it does."""


_MODES = {EXACT: None, CONTAINS: "CONTAINS", STARTS_WITH: "STARTS_WITH", ENDS_WITH: "ENDS_WITH", REGEX: "REGEX"}
_MATCHERS = {tap.PROPERTY_TEXT: ("text", text), tap.PROPERTY_CONTENT_DESCRIPTION: ("desc", desc), tap.PROPERTY_CLASS_NAME: ("class_name", class_name), tap.PROPERTY_HINT: ("hint", hint)}
_MODE_OF = {tap.MATCH_EXACT: EXACT, tap.MATCH_CONTAINS: CONTAINS, tap.MATCH_STARTS_WITH: STARTS_WITH, tap.MATCH_ENDS_WITH: ENDS_WITH, tap.MATCH_REGEX: REGEX, tap.MATCH_UNSPECIFIED: EXACT}


def env_name(secret: str) -> str:
    """The environment variable that gives an exported test a secret's value."""
    return "TAP_SECRET_" + re.sub(r"[^A-Za-z0-9]", "_", secret).upper()


def test_name(name: str) -> str:
    cleaned = re.sub(r"\W", "_", name, flags=re.ASCII).strip("_").lower() or "recording"
    return f"test_{cleaned}" if not keyword.iskeyword(f"test_{cleaned}") else f"test_{cleaned}_"


class _Writer:
    def __init__(self) -> None:
        self.tap: set[str] = set()
        self.needs_replace = False
        self.needs_selector_text = False

    def name(self, symbol: str) -> str:
        self.tap.add(symbol)
        return symbol

    def selector(self, message: tap.Selector) -> str:
        """Readable SDK builders when they give exactly this selector; its text form otherwise."""
        simple = self._builder(message)
        if simple is not None:
            return simple
        self.needs_selector_text = True
        return f"_selector({text_format.MessageToString(message, as_one_line=True)!r})"

    def _builder(self, message: tap.Selector) -> str | None:
        node = message.node
        which = node.WhichOneof("kind")
        built: Selector | None = None
        code = ""
        symbol = ""
        if which == "resource":
            if node.resource.HasField("package_name"):
                symbol, code = "res_id", f"res_id({node.resource.package_name!r}, {node.resource.name!r})"
                built = res_id(node.resource.package_name, node.resource.name)
            else:
                symbol, code = "res", f"res({node.resource.name!r})"
                built = res(node.resource.name)
        elif which == "match" and node.match.property in _MATCHERS and node.match.mode in _MODE_OF:
            symbol, builder = _MATCHERS[node.match.property]
            mode = _MODE_OF[node.match.mode]
            constant = _MODES[mode]
            code = f"{symbol}({node.match.value!r}" + (f", {constant})" if constant else ")")
            built = builder(node.match.value, mode)
            if constant:
                self.tap.add(constant)
        if built is None:
            return None
        pick = message.WhichOneof("pick")
        if pick == "first":
            code, built = f"{code}.first()", built.first()
        elif pick == "at":
            code, built = f"{code}.at({message.at.index})", built.at(message.at.index)
        if built.to_proto() != message:
            return None
        self.tap.add(symbol)
        return code

    def enum(self, cls: type, member: str) -> str:
        self.tap.add(cls.__name__)
        return f"{cls.__name__}.{member}"


def _seconds(message, field: str = "timeout_ms") -> str:
    return repr(seconds_from_ms(getattr(message, field))) if message.HasField(field) else "None"


def _value(text_value: str | None, secret: str | None, parameter: str | None) -> str:
    if secret is not None:
        return f"secrets[{secret!r}]"
    if parameter is not None:
        return f"PARAMETERS[{parameter!r}]"
    return repr(text_value or "")


def _optional(message, field: str) -> str:
    return repr(getattr(message, field)) if message.HasField(field) else "None"


def _match(writer: _Writer, match: tap.NotificationMatch) -> str:
    mode = MatchMode.EXACT if match.mode == tap.MATCH_UNSPECIFIED else MatchMode[tap.MatchMode.Name(match.mode).removeprefix("MATCH_")]
    return f"title={_optional(match, 'title')}, text={_optional(match, 'text')}, mode={writer.enum(MatchMode, mode.name)}, package_name={_optional(match, 'package_name')}"


def _extra(extra: tap.IntentExtra, writer: _Writer) -> str:
    which = extra.WhichOneof("value")
    value = getattr(extra, which)
    if which == "long_value":
        return f"{writer.name('Long')}({value})"
    return repr(value)


def _app(writer: _Writer, call: tap.AppCall) -> list[str]:
    app = f"device.app({call.package_name!r})"
    timeout = _seconds(call)
    activity = _optional(call, "activity")
    extras = "{" + ", ".join(f"{extra.key!r}: {_extra(extra, writer)}" for extra in call.extras) + "}"
    operations = {
        "cold_launch": f"{app}.cold_launch({activity}, {timeout}, extras={extras})",
        "launch": f"{app}.launch({activity}, {timeout}, extras={extras})",
        "foreground": f"{app}.foreground({timeout})",
        "open_link": f"{app}.open_link({call.uri!r}, {call.any_app!r}, {timeout})",
        "revoke_permission": f"{app}.revoke_permission({call.permission!r})",
        "grant_permission": f"{app}.grant_permission({call.permission!r})",
        "set_locales": f"{app}.set_locales({list(call.locales)!r})",
        "force_stop": f"{app}.force_stop({timeout})",
        "clear_data": f"{app}.clear_data({timeout})",
    }
    if call.operation not in operations:
        raise ExportError(f"cannot export the app operation {call.operation!r}")
    return [operations[call.operation]]


def _direction(writer: _Writer, number: int) -> str:
    return writer.enum(Direction, tap.Direction.Name(number).removeprefix("DIR_"))


def _action(writer: _Writer, step: studio.Step) -> list[str]:
    action = step.action
    command = action.command
    op = command.WhichOneof("op")
    message = getattr(command, op) if op else None
    untargeted = _device_action(writer, command)
    if untargeted is not None:
        return [untargeted]
    if message is None:
        raise ExportError("an action without a command")
    wait = action.wait.wait_visible
    wait_timeout = _seconds(action.wait)
    arrive = "one" if wait.exactly_one else "visible"
    selected = f"device.screen.wait({writer.selector(wait.selector)}, {wait_timeout}).{arrive}()"
    timeout = _seconds(command)
    secret = action.secret if action.HasField("secret") else None
    parameter = action.parameter if action.HasField("parameter") else None
    calls: dict[str, str] = {}
    if op == "tap":
        calls[op] = f"tap({timeout})"
    elif op == "long_tap":
        calls[op] = f"long_tap({timeout})"
    elif op == "multi_tap":
        interval = message.interval_ms / 1000 if message.HasField("interval_ms") else 0.1
        calls[op] = f"tap({timeout}, count={message.count}, interval={interval!r})"
    elif op == "hold":
        calls[op] = f"long_tap({timeout}, duration={message.duration_ms / 1000!r})"
    elif op == "set_text":
        sensitive = ", sensitive=True" if secret is not None else ""
        calls[op] = f"set_text({_value(message.text, secret, parameter)}, {timeout}{sensitive})"
    elif op == "clear_text":
        calls[op] = f"clear_text({timeout})"
    elif op in ("scroll", "swipe"):
        distance = message.distance_percent if message.HasField("distance_percent") else DEFAULT_GESTURE_PERCENT
        calls[op] = f"{op}({_direction(writer, message.direction)}, {distance}, {timeout})"
    elif op == "double_tap":
        calls[op] = f"double_tap({timeout})"
    elif op == "fling":
        calls[op] = f"fling({_direction(writer, message.direction)}, {timeout})"
    elif op == "pinch":
        name = "pinch_open" if message.direction == tap.PINCH_OPEN else "pinch_close"
        percent = message.percent if message.HasField("percent") else DEFAULT_GESTURE_PERCENT
        calls[op] = f"{name}({percent}, {timeout})"
    elif op == "drag":
        calls[op] = f"drag_to({writer.selector(message.target)}, {timeout})"
    elif op == "perform_ime_action":
        calls[op] = f"ime_action({timeout})"
    elif op == "perform_accessibility_action":
        if message.WhichOneof("action") == "custom":
            calls[op] = f"perform_custom_action({message.custom!r}, {timeout})"
        else:
            standard = tap.StandardAction.Name(message.standard).removeprefix("A11Y_")
            calls[op] = f"perform_action({writer.enum(StandardAction, standard)}, {timeout})"
    elif op == "set_progress":
        calls[op] = f"set_progress({message.value!r}, {timeout})"
    else:
        raise ExportError(f"cannot export the action {op!r}")
    return [f"{selected}.{calls[op]}"]


def _device_action(writer: _Writer, command: tap.Command) -> str | None:
    op = command.WhichOneof("op")
    if op is None:
        return None
    message = getattr(command, op)
    if op == "press_key":
        return f"device.press_key({message.key_code})"
    if op == "open_system_panel":
        return "device.open_quick_settings()" if message.panel == tap.SYSTEM_PANEL_QUICK_SETTINGS else "device.open_notifications()"
    if op == "set_orientation":
        return f"device.set_orientation({writer.enum(Orientation, tap.Orientation.Name(message.orientation).removeprefix('ORIENTATION_'))})"
    if op == "set_display_rotation":
        return f"device.set_display_rotation({writer.enum(DisplayRotation, tap.DisplayRotation.Name(message.rotation).removeprefix('DISPLAY_ROTATION_'))})"
    if op == "unfreeze_rotation":
        return "device.unfreeze_rotation()"
    if op == "dismiss_keyguard":
        return "device.dismiss_keyguard()"
    if op == "hide_keyboard":
        return "device.hide_keyboard()"
    if op == "set_clipboard":
        return f"device.set_clipboard({message.text!r})"
    if op == "choose_permission":
        accuracy = "None"
        if message.accuracy != tap.LOCATION_ACCURACY_UNSPECIFIED:
            accuracy = writer.enum(LocationAccuracy, tap.LocationAccuracy.Name(message.accuracy).removeprefix("LOCATION_"))
        return f"device.choose_permission({writer.enum(PermissionChoice, tap.PermissionChoice.Name(message.choice).removeprefix('PERMISSION_'))}, {accuracy})"
    if op == "open_notification":
        action = _optional(message, "action")
        return f"device.open_notification({_match(writer, message.match)}, action={action})"
    if op == "dismiss_notification":
        return f"device.dismiss_notification({_match(writer, message.match)})"
    return None


def _type(writer: _Writer, step: studio.TypeStep) -> list[str]:
    which = step.WhichOneof("input")
    value = _value(step.text if which == "text" else None, step.secret if which == "secret" else None, step.parameter if which == "parameter" else None)
    arrive = "visible" if picks(step.selector) else "one"
    element = f"device.screen.wait({writer.selector(step.selector)}, {_seconds(step, 'precondition_timeout_ms')}).{arrive}()"
    call = f".type_text({value}, await_focus={not step.skip_focus_wait!r}, timeout={_seconds(step)}, sensitive={which == 'secret'!r})"
    if not step.HasField("focus_timeout_ms"):
        return [f"{element}{call}"]
    writer.needs_replace = True
    return [
        f"element = {element}",
        "original = device.timeouts",
        "try:",
        f"    device.timeouts = replace(original, wait={_seconds(step, 'focus_timeout_ms')})",
        f"    element{call}",
        "finally:",
        "    device.timeouts = original",
    ]


_WAITS = {
    studio.CONDITION_VISIBLE: "visible()",
    studio.CONDITION_ONE: "one()",
    studio.CONDITION_GONE: "gone()",
    studio.CONDITION_ENABLED: "enabled()",
    studio.CONDITION_DISABLED: "disabled()",
    studio.CONDITION_CHECKED: "checked()",
    studio.CONDITION_UNCHECKED: "unchecked()",
    studio.CONDITION_FOCUSED: "focused()",
}


def _expected(step: studio.WaitStep | studio.AssertionStep) -> str:
    which = step.WhichOneof("value")
    return _value(step.text if which == "text" else None, None, step.parameter if which == "parameter" else None)


def _wait(writer: _Writer, step: studio.WaitStep) -> list[str]:
    wait = f"device.screen.wait({writer.selector(step.selector)}, {_seconds(step)})"
    if step.condition in _WAITS:
        return [f"{wait}.{_WAITS[step.condition]}"]
    if step.condition == studio.CONDITION_TEXT_EQUALS:
        return [f"{wait}.text_equals({_expected(step)})"]
    if step.condition == studio.CONDITION_TEXT_CONTAINS:
        return [f"{wait}.text_contains({_expected(step)})"]
    if step.condition == studio.CONDITION_COUNT:
        return [f"{wait}.count({step.count})"]
    if step.condition == studio.CONDITION_UNTIL:
        return [f"{wait}.until({writer.selector(tap.Selector(node=step.until))})"]
    raise ExportError(f"cannot export the wait condition {step.condition}")


def _assertion(writer: _Writer, step: studio.AssertionStep) -> list[str]:
    element = f"device.screen.element({writer.selector(step.selector)})"
    timeout = _seconds(step)
    check = step.check
    if check == studio.CHECK_EXISTS:
        return [f"assert {element}.exists({timeout})"]
    if check == studio.CHECK_COUNT:
        return [f"assert {element}.count({timeout}) == {step.count}"]
    if check == studio.CHECK_TEXT_EQUALS:
        return [f"assert {element}.text({timeout}) == {_expected(step)}"]
    if check == studio.CHECK_TEXT_CONTAINS:
        return [f"assert {_expected(step)} in ({element}.text({timeout}) or \"\")"]
    if check == studio.CHECK_ENABLED:
        return [f"assert {element}.is_enabled({timeout})"]
    if check == studio.CHECK_DISABLED:
        return [f"assert not {element}.is_enabled({timeout})"]
    if check == studio.CHECK_CHECKED:
        return [f"assert {element}.is_checked({timeout})"]
    if check == studio.CHECK_UNCHECKED:
        return [f"assert not {element}.is_checked({timeout})"]
    if check == studio.CHECK_FOCUSED:
        return [f"assert {element}.snapshot({timeout}).focused"]
    raise ExportError(f"cannot export the check {check}")


def _scroll_until(writer: _Writer, step: studio.ScrollUntilStep) -> list[str]:
    arrive = "visible" if picks(step.container) else "one"
    container = f"device.screen.wait({writer.selector(step.container)}, {_seconds(step, 'precondition_timeout_ms')}).{arrive}()"
    scrolls = step.max_scrolls if step.HasField("max_scrolls") else DEFAULT_MAX_SCROLLS
    distance = step.distance_percent if step.HasField("distance_percent") else DEFAULT_GESTURE_PERCENT
    return [f"{container}.scroll_until({writer.selector(step.target)}, {_direction(writer, step.direction)}, {scrolls}, {distance}, timeout={_seconds(step)})"]


def _app_wait(writer: _Writer, step: studio.AppWaitStep) -> list[str]:
    command = step.command
    timeout = _seconds(command)
    if command.WhichOneof("op") == "wait_app_visible":
        return [f"device.app({command.wait_app_visible.package_name!r}).await_visible({timeout})"]
    wait = command.wait_screen_stable
    signal = tap.StabilitySignal.Name(wait.signal or tap.STABILITY_ALL).removeprefix("STABILITY_")
    quiet = wait.stable_for_ms if wait.HasField("stable_for_ms") else DEFAULT_STABLE_FOR_MS
    return [f"device.app({wait.package_name!r}).await_screen_stable({seconds_from_ms(quiet)!r}, {timeout}, {writer.enum(StabilitySignal, signal)})"]


def _device_wait(writer: _Writer, step: studio.DeviceWaitStep) -> list[str]:
    command = step.command
    timeout = _seconds(command)
    op = command.WhichOneof("op")
    if op == "await_toast":
        toast = command.await_toast
        mode = MatchMode.EXACT if toast.mode == tap.MATCH_UNSPECIFIED else MatchMode[tap.MatchMode.Name(toast.mode).removeprefix("MATCH_")]
        return [f"device.await_toast({_optional(toast, 'text')}, {writer.enum(MatchMode, mode.name)}, package_name={_optional(toast, 'package_name')}, timeout={timeout})"]
    if op == "await_notification":
        return [f"device.await_notification({_match(writer, command.await_notification.match)}, timeout={timeout})"]
    if op == "wait_permission_prompt":
        return [f"device.await_permission_prompt({timeout})"]
    raise ExportError(f"cannot export the device wait {op!r}")


def _device(writer: _Writer, call: tap.DeviceCall) -> list[str]:
    op = call.operation
    if op in ("set_animations", "set_dark_mode", "set_stay_awake"):
        return [f"device.{op}({call.enabled!r})"]
    if op == "set_font_scale":
        return [f"device.set_font_scale({call.font_scale!r})"]
    if op == "set_density":
        return [f"device.set_density({_optional(call, 'density_dpi')})"]
    if op == "set_network":
        return [f"device.set_network(airplane_mode={_optional(call, 'airplane_mode')}, wifi={_optional(call, 'wifi')}, mobile_data={_optional(call, 'mobile_data')})"]
    if op == "set_system_locales":
        return [f"device.set_system_locales({list(call.locales)!r})"]
    if op == "set_location":
        return [f"device.set_location({call.latitude!r}, {call.longitude!r}, accuracy_m={_optional(call, 'accuracy_m')}, altitude_m={_optional(call, 'altitude_m')})"]
    if op == "set_accessibility_display":
        return [f"device.set_accessibility_display(high_contrast_text={_optional(call, 'high_contrast_text')}, color_inversion={_optional(call, 'color_inversion')}, bold_text={_optional(call, 'bold_text')})"]
    raise ExportError(f"cannot export the device condition {op!r}")


def _device_assertion(step: studio.DeviceAssertionStep) -> list[str]:
    check = step.check
    if check == studio.DEVICE_CHECK_FOREGROUND_ACTIVITY:
        return ["top = device.foreground_activity()", f'assert (f"{{top.package_name}}/{{top.class_name}}" if top is not None else None) == {step.text!r}']
    if check == studio.DEVICE_CHECK_KEYBOARD_SHOWN:
        return ["assert device.keyboard_shown()"]
    if check == studio.DEVICE_CHECK_KEYBOARD_HIDDEN:
        return ["assert not device.keyboard_shown()"]
    if check == studio.DEVICE_CHECK_CLIPBOARD_EQUALS:
        return [f"assert device.clipboard() == {step.text!r}"]
    raise ExportError(f"cannot export the device check {check}")


def _step(writer: _Writer, step: studio.Step) -> list[str]:
    kind = step.WhichOneof("kind")
    if kind == "app":
        return _app(writer, step.app)
    if kind == "action":
        return _action(writer, step)
    if kind == "type":
        return _type(writer, step.type)
    if kind == "wait":
        return _wait(writer, step.wait)
    if kind == "assertion":
        return _assertion(writer, step.assertion)
    if kind == "scroll_until":
        return _scroll_until(writer, step.scroll_until)
    if kind == "app_wait":
        return _app_wait(writer, step.app_wait)
    if kind == "device_wait":
        return _device_wait(writer, step.device_wait)
    if kind == "device":
        return _device(writer, step.device)
    if kind == "device_assertion":
        return _device_assertion(step.device_assertion)
    raise ExportError(f"cannot export a step of kind {kind!r}")


def _comment(text_value: str) -> str:
    return " ".join(text_value.split())


def to_pytest(recording: studio.Recording, name: str = "recording") -> str:
    """The recording as a pytest file. Raises ``ExportError`` for one it cannot write faithfully."""
    try:
        validate(recording)
    except RecordingError as error:
        raise ExportError("; ".join(error.problems)) from None
    if not recording.steps:
        raise ExportError("nothing to export: the recording has no steps")
    writer = _Writer()
    body: list[str] = []
    for step in recording.steps:
        body.append(f"# {_comment(step.id)}" + (f": {_comment(step.note)}" if step.HasField("note") else ""))
        body.extend(_step(writer, step))
    secrets = list(recording.secrets)
    parameters = [p for p in recording.parameters if any(parameter_name(s) == p.name for s in recording.steps)]
    used_secrets = {secret_name(s) for s in recording.steps} - {None}
    if set(secrets) != used_secrets:
        raise ExportError("the recording's secrets do not match its steps")
    variables: dict[str, str] = {}
    for secret in secrets:
        other = variables.setdefault(env_name(secret), secret)
        if other != secret:
            raise ExportError(f"the secrets {other!r} and {secret!r} would both be read from {env_name(secret)}: rename one")

    head = ['"""Exported from a Tap Studio recording by ' + recording.recorder.replace('"', "'") + ".", ""]
    head += ["Needs a running Tap server (`tap start`) and tap-e2e's pytest plugin. It does not start the"]
    head += ["server, and it retries nothing: a step that fails ends the test."]
    head += ["", "When it fails, the plugin saves a screenshot, hierarchy and logs under tap-artifacts/ (Studio"]
    head += ["captures only when asked). They show what is on the screen; TAP_CAPTURE=off turns this off."]
    if secrets:
        head += ["", "Secrets come from the environment: " + ", ".join(env_name(name) for name in secrets) + "."]
    head += ['"""', "", "from __future__ import annotations", ""]
    if secrets:
        head.append("import os")
    if writer.needs_replace:
        head.append("from dataclasses import replace")
    if writer.needs_selector_text:
        head.append("from google.protobuf import text_format")
        writer.tap.add("Selector")
    if secrets or writer.needs_replace or writer.needs_selector_text:
        head.append("")
    head.append("from tap_e2e import (" + ", ".join(sorted(writer.tap)) + ")" if writer.tap else "")
    if writer.needs_selector_text:
        head.append("from tap_e2e import proto as tap")
    head.append("")
    if parameters:
        head.append("PARAMETERS = {")
        head += [f"    {p.name!r}: {p.value!r}," + (f"  # {_comment(p.description)}" if p.HasField("description") else "") for p in parameters]
        head += ["}", ""]
    if writer.needs_selector_text:
        head += ["", "def _selector(source: str) -> Selector:", "    return Selector.from_proto(text_format.Parse(source, tap.Selector()))", ""]
    head += ["", f"def {test_name(name)}(tap_device):", "    device = tap_device"]
    if secrets:
        # Every value is read first, so a missing one fails before any input.
        head.append("    secrets = {" + ", ".join(f"{n!r}: os.environ[{env_name(n)!r}]" for n in secrets) + "}")
    lines = head + [f"    {line}" for line in body]
    return "\n".join(lines) + "\n"
