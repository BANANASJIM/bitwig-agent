import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import validate_call
from bitwig_agent.protocol.limits import SCENE_WINDOW

TARGET = {"trackId": "11111111-1111-1111-1111-111111111111", "slotIndex": 0, "expectedClipName": "Verse"}


def test_next_clip_after_one_loop():
    args = {
        **TARGET,
        "nextAction": "play_next",
        "nextActionEnabled": True,
        "nextActionLinkToLoop": True,
        "nextActionLoopCount": 1,
        "nextActionDelayBeats": 0,
    }
    params, _ = validate_call("launcher.set", args)
    assert params == args
    assert validate_call("launcher.get", TARGET)[0] == TARGET


@pytest.mark.parametrize(
    "changes",
    [
        {},
        {"nextAction": "random"},
        {"nextActionEnabled": 1},
        {"nextActionLoopCount": 0},
        {"nextActionLoopCount": 65},
        {"nextActionLoopCount": 1.0},
        {"loopLengthBeats": 16},
        {"loopEnabled": True},
        {"playStartBeats": 0},
        {"playStopBeats": 16},
        {"playStartBeats": 4, "playStopBeats": 4},
        {"loopStartBeats": 4194304, "loopLengthBeats": 1},
        {"nextActionDelayBeats": None},
        {"expectedClipName": ""},
        {"slotIndex": SCENE_WINDOW},
        {"extra": 1},
    ],
)
def test_invalid_settings_rejected(changes):
    with pytest.raises(ValidationError):
        validate_call("launcher.set", {**TARGET, **changes})


def test_launch_fields_are_their_own_path():
    args = {**TARGET, "launchQuantization": "1/4", "launchMode": "trigger", "useLoopStartAsQuantizationReference": True}
    assert validate_call("launcher.set", args)[0] == args
    assert validate_call("launcher.set", {**TARGET, "launchMode": "gate"})[0] == {**TARGET, "launchMode": "gate"}


@pytest.mark.parametrize(
    "changes",
    [
        {"launchQuantization": ""},
        {"launchQuantization": 4},
        {"launchMode": None},
        {"useLoopStartAsQuantizationReference": "yes"},
        {"launchMode": "gate", "nextAction": "stop"},
        {"launchQuantization": "1", "nextActionEnabled": True},
    ],
)
def test_invalid_or_mixed_launch_fields_rejected(changes):
    with pytest.raises(ValidationError):
        validate_call("launcher.set", {**TARGET, **changes})
