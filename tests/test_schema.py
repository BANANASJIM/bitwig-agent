import pytest
from pydantic import ValidationError

from bitwig_agent.catalog import NOTE_WINDOW, TARGET, TRACK, validate_call


def note(**changes):
    return {"op": "set", "beat": 0, "pitch": 60, "velocity": 90, "durationBeats": 1, **changes}


@pytest.mark.parametrize("edits", [
    [note(), note()],
    [note(), note(beat=0.5)],
    [note(beat=3.5)],
    [note(beat=0.1)],
    [note(durationBeats=0.3)],
    [note(pitch=True)],
    [note(velocity=0)],
    [note(velocity=128)],
    [note(extra=1)],
    [{"op": "clear", "beat": 0, "pitch": 60, "durationBeats": 1}],
    [{"op": "clear", "beat": 0, "pitch": 60, "velocity": None}],
])
def test_rejects_ambiguous_or_out_of_window_note_edits(edits):
    with pytest.raises(ValidationError):
        validate_call("notes.edit", {**NOTE_WINDOW, "edits": edits})


def test_decimal_grid_alignment_is_exact_not_float_rounding():
    args = {**NOTE_WINDOW, "stepSizeBeats": 0.1,
            "edits": [note(beat=0.3, durationBeats=0.2)]}
    params, _ = validate_call("notes.edit", args)
    assert params["edits"][0]["beat"] == 0.3
    with pytest.raises(ValidationError):
        validate_call("notes.edit", {**args, "edits": [note(beat=0.30000000000000004, durationBeats=0.2)]})


def test_chords_adjacent_notes_and_absolute_window_offsets_are_valid():
    edits = [note(beat=4), note(beat=4, pitch=64), note(beat=5)]
    params, _ = validate_call("notes.edit", {**NOTE_WINDOW, "startStep": 16, "edits": edits})
    assert params["edits"] == edits
    with pytest.raises(ValidationError):
        validate_call("notes.edit", {**NOTE_WINDOW, "startStep": 16, "edits": [note(beat=0)]})


@pytest.mark.parametrize("kind,fields", [
    ("file", {"path": "relative.bwpreset"}),
    ("file", {"path": "/tmp/../preset.bwpreset"}),
    ("file", {"path": "/tmp//preset.bwpreset"}),
    ("file", {"path": "//tmp/preset.bwpreset"}),
    ("file", {"path": "/tmp/preset\u0085.bwpreset"}),
    ("file", {"path": "/tmp/preset.bwpreset", "id": "unexpected"}),
    ("clap", {"id": "has space"}),
    ("clap", {"id": "org.valid", "path": "/tmp/preset"}),
    ("clap", {"id": None}),
    ("vst2", {"id": "2147483648"}),
    ("vst2", {"id": "0001"}),
    ("vst3", {"id": "not-32-hex"}),
    ("bitwig", {"id": "missing-hyphens"}),
])
def test_insertion_rejects_ambiguous_or_malformed_identifiers(kind, fields):
    with pytest.raises(ValidationError):
        validate_call("devices.insert", {"trackId": TRACK, "expectedDeviceCount": 0, "kind": kind, **fields})


def test_file_insertion_preserves_path_and_omits_id():
    args = {"trackId": TRACK, "expectedDeviceCount": 2, "kind": "file", "path": "/tmp/Poly Sketch.bwpreset"}
    params, _ = validate_call("devices.insert", args)
    assert params == args


@pytest.mark.parametrize("path", ["/absolute/path/audio.wav", "/tmp/Audio Sketch.bwclip", "/tmp/音楽.flac"])
@pytest.mark.parametrize("slot", [0, 15])
def test_launcher_import_preserves_local_path_without_filesystem_checks(path, slot):
    args = {"trackId": TRACK, "slotIndex": slot, "path": path}
    params, timeout = validate_call("launcher.import", args)
    assert params == args
    assert timeout == 5000


@pytest.mark.parametrize("path", [
    "", "audio.wav", "./audio.wav", "~/audio.wav", "file:///tmp/audio.wav",
    "https://example.com/audio.wav", "C:\\audio.wav", "//tmp/audio.wav",
    "/tmp/../audio.wav", "/tmp/./audio.wav", "/tmp//audio.wav", "/tmp/audio.wav/",
    "/tmp/audio\x00.wav", "/tmp/audio\n.wav", "/tmp/audio\x7f.wav", "/tmp/audio\u0085.wav",
    None, True, 123, [], {}, "/" + "a" * 4096, "/" + "𝄞" * 2048,
])
def test_file_import_and_device_insertion_share_path_syntax(path):
    for method, args in [
        ("launcher.import", {"trackId": TRACK, "slotIndex": 0}),
        ("devices.insert", {"trackId": TRACK, "expectedDeviceCount": 0, "kind": "file"}),
    ]:
        with pytest.raises(ValidationError):
            validate_call(method, {**args, "path": path})


@pytest.mark.parametrize("override", [
    {"trackId": ""}, {"trackId": None}, {"trackId": 1}, {"trackId": True},
    {"trackId": "Audio 2"},
    {"slotIndex": -1}, {"slotIndex": 16}, {"slotIndex": 0.0},
    {"slotIndex": "0"}, {"slotIndex": True}, {"slotIndex": None},
    {"overwrite": False}, {"unknown": 1}, {"timeoutMs": 99},
])
def test_launcher_import_rejects_invalid_targets_and_extra_fields(override):
    with pytest.raises(ValidationError):
        validate_call("launcher.import", {"trackId": TRACK, "slotIndex": 0,
                                          "path": "/absolute/path/audio.wav", **override})


@pytest.mark.parametrize("field", ["trackId", "slotIndex", "path"])
def test_launcher_import_requires_explicit_target_and_path(field):
    args = {"trackId": TRACK, "slotIndex": 0, "path": "/absolute/path/audio.wav"}
    del args[field]
    with pytest.raises(ValidationError):
        validate_call("launcher.import", args)


@pytest.mark.parametrize("method,args,field", [
    ("tracks.set", {"trackId": TRACK, "mute": False}, "name"),
    ("transport.set", {"playing": False}, "loopDurationBeats"),
])
def test_absent_changes_not_serialized_and_explicit_null_rejected(method, args, field):
    params, _ = validate_call(method, args)
    assert params == args
    with pytest.raises(ValidationError):
        validate_call(method, {**args, field: None})


def test_unicode_length_matches_java_utf16_bounds():
    args = {"trackId": TRACK, "slotIndex": 0, "lengthBeats": 4, "name": "𝄞" * 64}
    params, _ = validate_call("launcher.create", args)
    assert params["name"] == args["name"]
    with pytest.raises(ValidationError):
        validate_call("launcher.create", {**args, "name": "𝄞" * 65})


def test_remote_control_target_and_identity_fields_are_required():
    args = {**TARGET, "contextToken": "observed-page-token", "expectedPageName": "Main",
            "parameterIndex": 7, "expectedParameterName": "Macro", "normalizedValue": 0.5}
    params, _ = validate_call("devices.remote.set", args)
    assert params == args
    for field in args:
        with pytest.raises(ValidationError):
            validate_call("devices.remote.set", {key: value for key, value in args.items() if key != field})


def test_transport_rejects_finite_fields_whose_loop_end_overflows():
    with pytest.raises(ValidationError):
        validate_call("transport.set", {"loopStartBeats": 1e308, "loopDurationBeats": 1e308})


@pytest.mark.parametrize("field,values", [
    ("query", [None, 1, True, [], "x" * 129, "𝄞" * 65]),
    ("offset", [-1, 100001, 0.0, "0", True, None]),
    ("limit", [0, 257, 1.0, "1", False, None]),
])
def test_grid_catalog_rejects_unbounded_or_coerced_queries(field, values):
    for value in values:
        with pytest.raises(ValidationError):
            validate_call("grid.catalog", {field: value})


@pytest.mark.parametrize("field,values", [
    ("moduleOffset", [-1, 100001, 0.0, "0", True, None]),
    ("moduleLimit", [0, 129, 1.0, "1", False, None]),
    ("includeParameters", [0, 1, "true", None, []]),
    ("trackId", ["selected", "", None, 1]),
    ("deviceIndex", [-1, 16, 0.0, "0", True, None]),
    ("expectedDeviceName", ["", None, 1, "x" * 4097]),
])
def test_grid_get_rejects_implicit_targets_and_invalid_windows(field, values):
    for value in values:
        with pytest.raises(ValidationError):
            validate_call("grid.get", {**TARGET, field: value})


@pytest.mark.parametrize("field", ["trackId", "deviceIndex", "expectedDeviceName"])
def test_grid_get_requires_every_explicit_device_identity_field(field):
    with pytest.raises(ValidationError):
        validate_call("grid.get", {key: value for key, value in TARGET.items() if key != field})


@pytest.mark.parametrize("method,args,extra", [
    ("grid.capabilities", {}, {"trackId": TRACK}),
    ("grid.capabilities", {}, {"force": True}),
    ("grid.catalog", {}, {"includeDevices": True}),
    ("grid.catalog", {}, {"moduleLimit": 64}),
    ("grid.get", TARGET, {"selected": True}),
    ("grid.get", TARGET, {"offset": 0}),
    ("grid.get", TARGET, {"revision": "invented"}),
])
def test_grid_reads_reject_extra_fields_and_wrong_method_options(method, args, extra):
    with pytest.raises(ValidationError):
        validate_call(method, {**args, **extra})


def test_grid_accepts_explicit_false_and_unicode_window_boundaries():
    for query in ("", "x" * 128, "𝄞" * 64):
        args = {"query": query, "offset": 100000, "limit": 256}
        assert validate_call("grid.catalog", args)[0] == args
    for offset, limit, device in ((0, 1, 0), (100000, 128, 15)):
        args = {**TARGET, "deviceIndex": device, "moduleOffset": offset,
                "moduleLimit": limit, "includeParameters": False, "timeoutMs": 10000}
        params, timeout = validate_call("grid.get", args)
        assert params == {key: value for key, value in args.items() if key != "timeoutMs"}
        assert timeout == 10000
