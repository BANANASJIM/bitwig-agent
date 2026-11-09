import pytest
from pydantic import ValidationError
from bitwig_agent.catalog import validate_call

TARGET = {"trackId": "11111111-1111-1111-1111-111111111111", "slotIndex": 0, "expectedClipName": "Verse"}


def test_next_clip_after_one_loop():
    args = {**TARGET, "nextAction": "play_next", "nextActionEnabled": True,
            "nextActionLinkToLoop": True, "nextActionLoopCount": 1, "nextActionDelayBeats": 0}
    params, _ = validate_call("launcher.set", args)
    assert params == args
    assert validate_call("launcher.get", TARGET)[0] == TARGET


@pytest.mark.parametrize("changes", [{}, {"nextAction": "random"}, {"nextActionEnabled": 1},
    {"nextActionLoopCount": 0}, {"nextActionLoopCount": 65}, {"nextActionLoopCount": 1.0},
    {"loopLengthBeats": 16}, {"loopEnabled": True}, {"playStartBeats": 0}, {"playStopBeats": 16}, {"playStartBeats": 4, "playStopBeats": 4},
    {"loopStartBeats": 4194304, "loopLengthBeats": 1}, {"nextActionDelayBeats": None},
    {"expectedClipName": ""}, {"slotIndex": 16}, {"extra": 1}])
def test_invalid_settings_rejected(changes):
    with pytest.raises(ValidationError):
        validate_call("launcher.set", {**TARGET, **changes})
