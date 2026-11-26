import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.launcher import TRACK_REF
from bitwig_agent.protocol.limits import TRACK_WINDOW

TRACK = "11111111-1111-1111-1111-111111111111"
OTHER = "22222222-2222-2222-2222-222222222222"


def test_group_keeps_explicit_track_order_and_optional_name():
    params, _ = validate_call("tracks.group", {"trackIds": [OTHER, TRACK], "name": "Drums"})
    assert params == {"trackIds": [OTHER, TRACK], "name": "Drums"}
    assert validate_call("tracks.group", {"trackIds": [TRACK]})[0] == {"trackIds": [TRACK]}


@pytest.mark.parametrize(
    "args",
    [
        {"trackIds": []},
        {"trackIds": [TRACK, TRACK]},
        {"trackIds": [TRACK, TRACK.upper()]},
        {"trackIds": ["not-a-uuid"]},
        {"trackIds": [f"00000000-0000-0000-0000-{i:012d}" for i in range(TRACK_WINDOW + 1)]},
        {"trackIds": [TRACK], "name": ""},
        {"trackIds": [TRACK], "position": 0},
        {"name": "Drums"},
    ],
)
def test_group_rejects_ambiguous_requests(args):
    with pytest.raises(ValidationError):
        validate_call("tracks.group", args)


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
