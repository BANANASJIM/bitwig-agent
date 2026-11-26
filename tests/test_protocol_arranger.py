import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.launcher import TRACK_REF

TRACK = "11111111-1111-1111-1111-111111111111"


def test_arranger_list_takes_a_track_ref_and_the_old_arranger_methods_are_gone():
    assert validate_call("arranger.list", {"parent": TRACK_REF})[0] == {"parent": TRACK_REF}
    assert TOOLS["arranger.list"].read_only
    for old in (
        "arranger.clips.list",
        "arranger.clips.create",
        "arranger.clips.copyFromLauncher",
        "arranger.clips.edit",
    ):
        assert old not in TOOLS
    for bad in ({"trackId": TRACK}, {"parent": TRACK}, {"parent": {**TRACK_REF, "extra": 1}}, {}):
        with pytest.raises(ValidationError):
            validate_call("arranger.list", bad)
