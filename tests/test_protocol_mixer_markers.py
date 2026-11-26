"""Argument contracts of the mixer-display, send, meter, marker and time-signature tools (host-free)."""

import re

import pytest

from bitwig_agent.protocol import TOOLS, validate_call
from bitwig_agent.protocol.limits import TRACK_WINDOW

TRACK = "12345678-1234-1234-1234-123456789abc"
OTHER = "12345678-1234-1234-1234-123456789abd"


def ok(method, **args):
    return validate_call(method, args)[0]


def bad(method, **args):
    with pytest.raises(ValueError, match=re.escape("1 validation error for ")):
        validate_call(method, args)


def test_new_tools_are_registered_with_examples():
    for name in (
        "tracks.sends.set",
        "tracks.meters",
        "markers.list",
        "markers.insert",
        "markers.set",
        "markers.delete",
    ):
        assert name in TOOLS
        assert TOOLS[name].examples
    assert TOOLS["tracks.meters"].read_only
    assert TOOLS["markers.list"].read_only
    assert not TOOLS["tracks.sends.set"].read_only
    assert not TOOLS["markers.insert"].read_only


def test_send_addressing_and_value_rules():
    assert ok("tracks.sends.set", trackId=TRACK, sendIndex=0, valueNormalized=0.3) == {
        "trackId": TRACK,
        "sendIndex": 0,
        "valueNormalized": 0.3,
    }
    ok("tracks.sends.set", trackId=TRACK, sendName="A-Reverb", display="-12 dB", enabled=True)
    ok("tracks.sends.set", trackId=TRACK, sendIndex=2, expectedSendName="Delay", enabled=False)
    bad("tracks.sends.set", trackId=TRACK, valueNormalized=0.3)  # no address
    bad("tracks.sends.set", trackId=TRACK, sendIndex=0, sendName="A", valueNormalized=0.3)  # two addresses
    bad("tracks.sends.set", trackId=TRACK, sendIndex=0)  # nothing to change
    bad("tracks.sends.set", trackId=TRACK, sendIndex=0, valueNormalized=0.3, display="-6 dB")  # two level forms
    bad("tracks.sends.set", trackId=TRACK, sendIndex=0, valueNormalized=1.1)
    bad("tracks.sends.set", trackId=TRACK, sendIndex=-1, valueNormalized=0.1)
    bad("tracks.sends.set", trackId=TRACK, sendIndex=0, display="")
    bad("tracks.sends.set", trackId=TRACK, sendIndex=0, valueNormalized=0.1, levelDb=-6)


def test_track_display_fields_are_alternatives():
    assert ok("tracks.set", trackId=TRACK, volumeDb=-6, panPercent=-25) == {
        "trackId": TRACK,
        "volumeDb": -6,
        "panPercent": -25,
    }
    ok("tracks.set", trackId=TRACK, volumeDisplay="-inf dB", panDisplay="Center", mute=True)
    bad("tracks.set", trackId=TRACK, volumeNormalized=0.5, volumeDb=-6)
    bad("tracks.set", trackId=TRACK, volumeDb=-6, volumeDisplay="-6 dB")
    bad("tracks.set", trackId=TRACK, panNormalized=0.5, panPercent=10)
    bad("tracks.set", trackId=TRACK, panPercent=101)
    bad("tracks.set", trackId=TRACK, volumeDb=25)
    ok("tracks.set", trackId=TRACK, volumeNormalized=0.5, panPercent=10)


def test_meters_arguments():
    ok("tracks.meters", trackIds=[TRACK, OTHER], windowMs=500)
    bad("tracks.meters", trackIds=[], windowMs=500)
    bad("tracks.meters", trackIds=[TRACK, TRACK.upper()], windowMs=500)
    bad("tracks.meters", trackIds=[TRACK], windowMs=49)
    bad("tracks.meters", trackIds=[TRACK], windowMs=5001)
    bad("tracks.meters", trackIds=[TRACK] * (TRACK_WINDOW + 1), windowMs=100)
    bad("tracks.meters", trackIds=["nope"], windowMs=100)


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
