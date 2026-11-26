"""Argument contracts of the marker and time-signature tools (host-free)."""

import re

import pytest

from bitwig_agent.protocol import TOOLS, validate_call


def ok(method, **args):
    return validate_call(method, args)[0]


def bad(method, **args):
    with pytest.raises(ValueError, match=re.escape("1 validation error for ")):
        validate_call(method, args)


def test_marker_tools_are_registered_with_examples():
    for name in ("markers.list", "markers.insert", "markers.set", "markers.delete"):
        assert name in TOOLS
        assert TOOLS[name].examples
    assert TOOLS["markers.list"].read_only
    assert not TOOLS["markers.insert"].read_only


def marker(index=3, name="Verse", beat=8):
    return {"kind": "marker", "index": index, "name": name, "beat": beat}


def test_marker_arguments():
    ok("markers.insert", beat=0)
    ok("markers.insert", beat=16.5, name="Chorus")
    bad("markers.insert", beat=-0.1)
    bad("markers.insert", beat=4, name="")
    ok("markers.set", target=marker(), name="Verse 2")
    bad("markers.set", target=marker())
    bad("markers.set", target=marker(), name="")
    bad("markers.set", target={"kind": "marker", "index": 3, "name": "Verse"}, name="X")
    bad("markers.set", target={**marker(), "kind": "track"}, name="X")
    ok("markers.delete", target=marker(0, "Intro", 0))
    bad("markers.delete")
    for old in (
        ("markers.set", {"markerIndex": 3, "expectedName": "Verse", "name": "V"}),
        ("markers.delete", {"markerIndex": 0, "expectedName": "Intro"}),
    ):
        with pytest.raises(ValueError, match="Extra inputs are not permitted"):
            validate_call(*old)
    ok("markers.list")
    bad("markers.list", beat=1)


def test_a_marker_ref_survives_the_round_trip_of_its_read():
    row = marker(2, "Drop", 32.5)
    assert ok("markers.delete", target=row) == {"target": row}
    assert ok("markers.set", target=row, name="Drop 2")["target"] == row


def test_time_signature_rules():
    for good in ("4/4", "7/8", "3/4", "12/8", "32/32", "1/1"):
        assert ok("transport.set", timeSignature=good) == {"timeSignature": good}
    for wrong in ("4", "0/4", "33/4", "4/3", "4/64", "a/b", " 4/4", "04/4", "4/4/4", "4 / 4", ""):
        bad("transport.set", timeSignature=wrong)
    bad("transport.set", timeSignature=4)
    ok("transport.set", timeSignature="6/8", tempoBpm=100)
