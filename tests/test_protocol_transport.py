import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call


def test_transport_rejects_finite_fields_whose_loop_end_overflows():
    with pytest.raises(ValidationError):
        validate_call("transport.set", {"loop": {"start": 1e308, "length": 1e308}})
    with pytest.raises(ValidationError):
        validate_call("transport.set", {"loop": {}})


def test_transport_guide_settings():
    args = {
        "metronomeVolume": 0.5,
        "preRoll": "one_bar",
        "punchInEnabled": True,
        "punchOutEnabled": False,
        "defaultLaunchQuantization": "1",
        "recordQuantizationGrid": "1/16",
    }
    assert validate_call("transport.set", args)[0] == args
    for bad in (
        {"metronomeVolume": 1.1},
        {"metronomeVolume": -0.1},
        {"metronomeVolume": {"label": "Loud"}},
        {"metronomeVolume": {"display": ""}},
        {"metronomeVolumeNormalized": 0.5},
        {"preRoll": ""},
        {"preRoll": 1},
        {"punchInEnabled": 1},
        {"punchOutEnabled": None},
        {"defaultLaunchQuantization": None},
        {"recordQuantizationGrid": ""},
    ):
        with pytest.raises(ValidationError):
            validate_call("transport.set", bad)


def test_a_metronome_volume_takes_its_value_or_its_display():
    assert validate_call("transport.set", {"metronomeVolume": {"display": "-6 dB"}})[0] == {
        "metronomeVolume": {"display": "-6 dB"}
    }


def test_the_recording_fields_are_transport_set_fields():
    args = {
        "arrangerRecordEnabled": False,
        "arrangerOverdubEnabled": True,
        "clipLauncherOverdubEnabled": False,
        "arrangerAutomationWriteEnabled": False,
        "automationWriteMode": "latch",
    }
    assert validate_call("transport.set", args)[0] == args
    for bad in ({"arrangerRecordEnabled": 1}, {"automationWriteMode": ""}, {"recording": True}):
        with pytest.raises(ValidationError):
            validate_call("transport.set", bad)


def test_no_example_starts_recording():
    for example in TOOLS["transport.set"].examples:
        assert not (example.get("arrangerRecordEnabled") and example.get("playing")), example
        assert example.get("arrangerRecordEnabled") is not True, example


def test_the_launcher_automation_write_switch_is_an_unknown_field():
    with pytest.raises(ValidationError) as refused:
        validate_call("transport.set", {"clipLauncherAutomationWriteEnabled": False})
    assert [error["type"] for error in refused.value.errors()] == ["extra_forbidden"]
