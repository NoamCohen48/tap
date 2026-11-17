"""Temporary settings are client-side composition, with no scope RPCs."""

from types import SimpleNamespace

import pytest  # type: ignore[import-not-found]

from tap_e2e import SettingsRestorationError, TapClient

from .conftest import TOKEN


@pytest.fixture
def device(fake):
    with TapClient.create(fake.address, TOKEN) as client:
        with client.connect("temporary-settings") as connection:
            yield connection.attach_device("emulator-5554")


@pytest.fixture
def settings(device, monkeypatch):
    state = {"dark_mode": False, "font_scale": 1.1, "animations_enabled": True}
    calls = []

    def info():
        calls.append(("info",))
        return SimpleNamespace(**state)

    def setter(name):
        def set_value(value):
            calls.append((name, value))
            state[name] = value
        return set_value

    monkeypatch.setattr(device, "info", info)
    monkeypatch.setattr(device, "set_dark_mode", setter("dark_mode"))
    monkeypatch.setattr(device, "set_font_scale", setter("font_scale"))
    monkeypatch.setattr(device, "set_animations", setter("animations_enabled"))
    return state, calls


def test_settings_restore_before_detach_and_device_is_still_usable(device, settings, monkeypatch):
    state, calls = settings
    monkeypatch.setattr(device, "detach", lambda: pytest.fail("must not detach"))
    with device.temporary_settings(dark_mode=True, font_scale=1.3, animations=False) as value:
        assert value is None
        assert state == {"dark_mode": True, "font_scale": 1.3, "animations_enabled": False}
    assert state == {"dark_mode": False, "font_scale": 1.1, "animations_enabled": True}
    assert calls == [
        ("info",), ("animations_enabled", False), ("dark_mode", True), ("font_scale", 1.3),
        ("font_scale", 1.1), ("dark_mode", False), ("animations_enabled", True),
    ]
    device.press_home()  # The original attachment still accepts commands.


def test_nested_scopes_capture_on_entry_and_restore_parent_values(device, settings):
    state, calls = settings
    scope = device.temporary_settings(font_scale=1.3)
    assert calls == []
    with scope:
        with device.temporary_settings(font_scale=1.6, dark_mode=True):
            assert state["font_scale"] == 1.6
        assert state["font_scale"] == 1.3
        assert state["dark_mode"] is False
    assert state["font_scale"] == 1.1


def test_only_requested_settings_are_restored(device, settings):
    state, calls = settings
    with device.temporary_settings(font_scale=1.3):
        device.set_dark_mode(True)
    assert state["dark_mode"] is True
    assert calls == [("info",), ("font_scale", 1.3), ("dark_mode", True), ("font_scale", 1.1)]


@pytest.mark.parametrize("failure", [AssertionError("test failed"), KeyboardInterrupt(), SystemExit(2)])
def test_block_failure_remains_primary_and_settings_are_restored(device, settings, failure):
    state, _ = settings
    with pytest.raises(type(failure)) as caught:
        with device.temporary_settings(dark_mode=True, font_scale=1.3):
            raise failure
    assert caught.value is failure
    assert state["dark_mode"] is False
    assert state["font_scale"] == 1.1


def test_partial_setup_failure_restores_even_the_failing_setter(device, settings, monkeypatch):
    state, _ = settings
    failure = RuntimeError("read-back failed")
    calls = []

    def set_font_scale(value):
        calls.append(value)
        state["font_scale"] = value
        if value == 1.3:
            raise failure

    monkeypatch.setattr(device, "set_font_scale", set_font_scale)
    with pytest.raises(RuntimeError) as caught:
        with device.temporary_settings(dark_mode=True, font_scale=1.3):
            pytest.fail("setup failed; body must not run")
    assert caught.value is failure
    assert calls == [1.3, 1.1]
    assert state["font_scale"] == 1.1
    assert state["dark_mode"] is False


def test_restore_attempts_every_setting_and_raises_structured_error(device, settings, monkeypatch):
    state, calls = settings
    font_error = RuntimeError("font restore failed")
    theme_error = RuntimeError("theme restore failed")

    def font(value):
        if value == 1.1:
            raise font_error
        state["font_scale"] = value

    def theme(value):
        if value is False:
            raise theme_error
        state["dark_mode"] = value

    monkeypatch.setattr(device, "set_font_scale", font)
    monkeypatch.setattr(device, "set_dark_mode", theme)
    with pytest.raises(SettingsRestorationError) as caught:
        with device.temporary_settings(animations=False, dark_mode=True, font_scale=1.3):
            pass
    assert caught.value.failures == (("font_scale", font_error), ("dark_mode", theme_error))
    assert caught.value.__cause__ is font_error
    assert state["animations_enabled"] is True
    assert calls[-1] == ("animations_enabled", True)


@pytest.mark.parametrize("fail_during_setup", [False, True])
def test_original_failure_keeps_restoration_details(device, settings, monkeypatch, fail_during_setup):
    failure = AssertionError("original assertion")
    restore_error = RuntimeError("cannot restore")

    def font(value):
        if value == 1.1:
            raise restore_error
        if fail_during_setup:
            raise failure

    monkeypatch.setattr(device, "set_font_scale", font)
    with pytest.raises(AssertionError) as caught:
        with device.temporary_settings(font_scale=1.3):
            raise failure
    assert caught.value is failure
    assert failure.settings_restoration_error.failures == (("font_scale", restore_error),)
    if hasattr(failure, "__notes__"):
        assert "cannot restore" in failure.__notes__[0]


def test_nested_restore_failures_are_not_overwritten(device, settings, monkeypatch):
    failure = AssertionError("original assertion")
    restore_error = RuntimeError("cannot restore")

    with pytest.raises(AssertionError) as caught:
        with device.temporary_settings(font_scale=1.3):
            with device.temporary_settings(font_scale=1.6):
                def fail(value):
                    raise restore_error
                monkeypatch.setattr(device, "set_font_scale", fail)
                raise failure
    assert caught.value is failure
    assert failure.settings_restoration_error.failures == (
        ("font_scale", restore_error), ("font_scale", restore_error),
    )


def test_python310_fallback_logs_without_replacing_failure(device, settings, monkeypatch, caplog):
    class LegacyFailure(Exception):
        def __getattribute__(self, name):
            if name == "add_note":
                raise AttributeError(name)
            return super().__getattribute__(name)

    failure = LegacyFailure("original failure")

    def font(value):
        if value == 1.1:
            raise RuntimeError("cannot restore")

    monkeypatch.setattr(device, "set_font_scale", font)
    with pytest.raises(LegacyFailure) as caught:
        with device.temporary_settings(font_scale=1.3):
            raise failure
    assert caught.value is failure
    assert "cannot restore" in caplog.text
    assert failure.settings_restoration_error.failures[0][0] == "font_scale"


@pytest.mark.parametrize("kwargs, error", [
    ({}, ValueError),
    ({"dark_mode": "dark"}, TypeError),
    ({"animations": 1}, TypeError),
    ({"font_scale": 0.4}, ValueError),
    ({"font_scale": 2.1}, ValueError),
    ({"font_scale": float("nan")}, ValueError),
])
def test_invalid_settings_fail_before_any_device_access(device, settings, kwargs, error):
    _, calls = settings
    with pytest.raises(error):
        with device.temporary_settings(**kwargs):
            pytest.fail("invalid arguments")
    assert calls == []


def test_snapshot_failure_does_not_attempt_mutations(device, settings, monkeypatch):
    _, calls = settings

    def info():
        raise RuntimeError("device unavailable")

    monkeypatch.setattr(device, "info", info)
    with pytest.raises(RuntimeError, match="device unavailable"):
        with device.temporary_settings(font_scale=1.3):
            pytest.fail("no snapshot")
    assert calls == []
