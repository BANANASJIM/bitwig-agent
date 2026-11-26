import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import validate_call


def test_transport_rejects_finite_fields_whose_loop_end_overflows():
    with pytest.raises(ValidationError):
        validate_call("transport.set", {"loopStartBeats": 1e308, "loopDurationBeats": 1e308})


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
