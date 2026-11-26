import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import validate_call
from bitwig_agent.protocol.base import TRACK


@pytest.mark.parametrize(
    ("method", "args", "field"),
    [
        ("tracks.set", {"trackId": TRACK, "mute": False}, "name"),
        ("transport.set", {"playing": False}, "loopDurationBeats"),
    ],
)
def test_absent_changes_not_serialized_and_explicit_null_rejected(method, args, field):
    params, _ = validate_call(method, args)
    assert params == args
    with pytest.raises(ValidationError):
        validate_call(method, {**args, field: None})


def test_track_color_activated_monitor_and_crossfade():
    args = {"trackId": TRACK, "color": "#ff8800", "activated": False, "monitorMode": "ON", "crossFadeMode": "AB"}
    assert validate_call("tracks.set", args)[0] == args
    for bad in (
        {"color": "ff8800"},
        {"color": "#ff88"},
        {"color": "#gg0000"},
        {"activated": "no"},
        {"monitorMode": ""},
        {"crossFadeMode": 1},
    ):
        with pytest.raises(ValidationError):
            validate_call("tracks.set", {"trackId": TRACK, **bad})
