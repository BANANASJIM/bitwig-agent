import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.launcher import TRACK_REF

TRACK = "11111111-1111-1111-1111-111111111111"


def test_arranger_list_takes_an_optional_track_ref_and_the_old_arranger_methods_are_gone():
    assert validate_call("arranger.list", {"target": TRACK_REF})[0] == {"target": TRACK_REF}
    assert validate_call("arranger.list", {})[0] == {}
    assert TOOLS["arranger.list"].read_only
    for old in (
        "arranger.clips.list",
        "arranger.clips.create",
        "arranger.clips.copyFromLauncher",
        "arranger.clips.edit",
    ):
        assert old not in TOOLS
    for bad in ({"trackId": TRACK}, {"target": TRACK}, {"target": {**TRACK_REF, "extra": 1}}, {"target": None}):
        with pytest.raises(ValidationError):
            validate_call("arranger.list", bad)
