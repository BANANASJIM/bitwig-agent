import pytest
from pydantic import ValidationError

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.base import TRACK


def test_context_get_takes_no_parameters():
    params, _ = validate_call("context.get", {})
    assert params == {}
    assert TOOLS["context.get"].read_only
    with pytest.raises(ValidationError):
        validate_call("context.get", {"trackId": TRACK})


def test_project_save_is_a_write_tool_without_arguments():
    assert TOOLS["project.save"].read_only is False
    assert validate_call("project.save", {})[0] == {}
    with pytest.raises(ValidationError):
        validate_call("project.save", {"force": True})
    assert "projectModified" in TOOLS["extension.restart"].description


def test_project_save_as_takes_a_final_bwproject_path_and_explicit_flags():

    path = "/home/me/Bitwig Studio/Projects/Song/Song.bwproject"
    assert TOOLS["project.saveAs"].read_only is False
    assert validate_call("project.saveAs", {"path": path}) == ({"path": path}, 30000)
    assert validate_call("project.saveAs", {"path": path, "overwrite": True, "allowAnyPath": False})[0] == {
        "path": path,
        "overwrite": True,
        "allowAnyPath": False,
    }
    assert TOOLS["project.saveAs"].max_timeout_ms == 60000
    for bad in (
        {},
        {"path": "Song/Song.bwproject"},
        {"path": "~/Song/Song.bwproject"},
        {"path": "/p/Song/Song.txt"},
        {"path": "/p/../Song/Song.bwproject"},
        {"path": "/Song.bwproject"},
        {"path": "/p/Song/ Song.bwproject"},
        {"path": "/p/Song/.bwproject"},
        {"path": "/p/t/Template.bwproject"},
        {"path": "/p/auto-backups/S/S.bwproject"},
        {"path": path, "overwrite": "yes"},
        {"path": path, "overwrite": None},
        {"path": path, "force": True},
    ):
        with pytest.raises(ValidationError):
            validate_call("project.saveAs", bad)


def test_activate_engine_is_a_write_tool_without_arguments():
    assert TOOLS["project.activateEngine"].read_only is False
    assert validate_call("project.activateEngine", {})[0] == {}
    with pytest.raises(ValidationError):
        validate_call("project.activateEngine", {"force": True})
