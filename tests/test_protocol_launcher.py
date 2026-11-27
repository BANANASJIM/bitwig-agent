import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, invalid_params_reason, validate_call
from bitwig_agent.protocol.annotations import resolve
from bitwig_agent.protocol.launcher import SCENE_REF, TRACK_REF
from bitwig_agent.protocol.limits import SCENE_WINDOW
from bitwig_agent.protocol.scenes import MAX_SCENES_PER_INSERT


def test_launcher_list_and_stop_take_a_track_ref():
    assert validate_call("launcher.list", {"target": TRACK_REF})[0] == {"target": TRACK_REF}
    assert validate_call("launcher.list", {"target": TRACK_REF, "sceneOffset": 32})[0]["sceneOffset"] == 32
    for method in ("launcher.stop", "launcher.returnToArrangement"):
        assert validate_call(method, {"target": TRACK_REF})[0] == {"target": TRACK_REF}
        assert validate_call(method, {"target": "all"})[0] == {"target": "all"}
        assert not TOOLS[method].read_only
        assert TOOLS[method].inspect == "launcher.list"
    assert TOOLS["launcher.list"].read_only
    assert resolve("launcher.returnToArrangement", TOOLS["launcher.returnToArrangement"].annotations) == resolve(
        "launcher.stop", TOOLS["launcher.stop"].annotations
    )


@pytest.mark.parametrize(
    ("method", "args"),
    [
        ("launcher.list", {"trackId": TRACK_REF["id"]}),
        ("launcher.list", {"target": TRACK_REF["id"]}),
        ("launcher.list", {"target": TRACK_REF, "sceneOffset": -1}),
        ("launcher.list", {"target": SCENE_REF}),
        ("launcher.stop", {"target": "All"}),
        ("launcher.returnToArrangement", {}),
        ("launcher.returnToArrangement", {"target": "master"}),
        ("launcher.stop", {"target": {**TRACK_REF, "extra": 1}}),
        ("launcher.stop", {"trackId": TRACK_REF["id"]}),
    ],
)
def test_launcher_rejects_ids_and_other_refs(method, args):
    with pytest.raises(ValidationError):
        validate_call(method, args)


def test_scene_tools_take_a_scene_ref_whose_name_may_be_null():
    unnamed = {"kind": "scene", "index": 3, "name": None}
    assert validate_call("scenes.list", {})[0] == {}
    assert validate_call("scenes.list", {"sceneOffset": 16})[0] == {"sceneOffset": 16}
    for method in ("scenes.launch", "scenes.duplicate", "scenes.delete"):
        assert validate_call(method, {"target": unnamed})[0] == {"target": unnamed}
        assert validate_call(method, {"target": SCENE_REF})[0] == {"target": SCENE_REF}
        assert not TOOLS[method].read_only
    assert validate_call("scenes.set", {"target": SCENE_REF, "name": "Verse"})[0]["name"] == "Verse"
    assert TOOLS["scenes.list"].read_only


@pytest.mark.parametrize(
    ("method", "bad"),
    [
        ("scenes.delete", {"target": {**SCENE_REF, "index": -1}}),
        ("scenes.delete", {"sceneIndex": 0, "expectedName": ""}),
        ("scenes.delete", {}),
        ("scenes.launch", {"target": {"kind": "scene", "index": 0}}),
        ("scenes.set", {"target": SCENE_REF, "name": ""}),
        ("scenes.set", {"target": SCENE_REF}),
        ("scenes.set", {"target": SCENE_REF, "name": "x" * 129}),
        ("scenes.duplicate", {"target": SCENE_REF, "extra": 1}),
        ("scenes.list", {"sceneIndex": 0}),
    ],
)
def test_scene_tools_reject_bad_requests(method, bad):
    with pytest.raises(ValidationError):
        validate_call(method, bad)


def test_scenes_beyond_the_window_are_addressable_and_insert_has_no_window_limit():
    far = {"kind": "scene", "index": SCENE_WINDOW * 40, "name": "Far"}
    assert validate_call("scenes.delete", {"target": far})[0] == {"target": far}
    assert validate_call("scenes.insert", {})[0] == {}
    assert validate_call("scenes.insert", {"count": 40})[0] == {"count": 40}
    assert validate_call("scenes.insert", {"count": MAX_SCENES_PER_INSERT})[0]["count"] == MAX_SCENES_PER_INSERT
    for count in (0, MAX_SCENES_PER_INSERT + 1, 1.5, None):
        with pytest.raises(ValidationError):
            validate_call("scenes.insert", {"count": count})


def test_a_scene_rename_has_no_protection_for_an_unnamed_scene_and_old_names_are_gone():
    for old in ("scenes.append", "scenes.rename", "launcher.get", "launcher.set", "launcher.create", "launcher.import"):
        assert old not in TOOLS
    with pytest.raises(ValidationError) as error:
        validate_call("scenes.delete", {"target": {"kind": "scene", "index": 0}})
    assert invalid_params_reason(error.value)[0] == "invalid_params"
