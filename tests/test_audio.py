"""audio.* against synthetic WAVs and a fake PipeWire (no real subprocess, no Bitwig)."""

import asyncio
import json
import math
import re
import struct
import wave
from pathlib import Path

import pytest

from bitwig_agent import audio, client_side
from bitwig_agent.catalog import CLIENT_SIDE, TOOLS, validate_call
from bitwig_agent.cli import execute, parse_arguments
from bitwig_agent.client import BridgeError

RATE = 48000


def write_wav(path, samples, rate=RATE, header_zero=False):
    frames = b"".join(struct.pack("<hh", int(s * 32767), int(s * 32767)) for s in samples)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(frames)
    if header_zero:  # streamed header as parec leaves it
        raw = bytearray(path.read_bytes())
        raw[40:44] = b"\0\0\0\0"
        path.write_bytes(bytes(raw))


def notes(midis, step=0.125, length=0.1, total=None):
    """Sine notes with fast attack/decay at each step."""
    n = int((total or step * len(midis) + 0.3) * RATE)
    out = [0.0] * n
    for i, m in enumerate(midis):
        hz = 440 * 2 ** ((m - 69) / 12)
        start = int((0.1 + i * step) * RATE)
        for k in range(int(length * RATE)):
            env = min(1, k / 48) * (1 - k / (length * RATE))
            if start + k < n:
                out[start + k] = 0.8 * env * math.sin(2 * math.pi * hz * k / RATE)
    return out


def overlapping(midis, step=0.1, decay=0.26, total=None):
    """Plucks whose exponential tail (about 260 ms) runs under the next several notes, summed."""
    n = int((total or 0.1 + step * len(midis) + 0.6) * RATE)
    out = [0.0] * n
    for i, m in enumerate(midis):
        hz = 440 * 2 ** ((m - 69) / 12)
        start = int((0.1 + i * step) * RATE)
        for k in range(min(int(1.0 * RATE), n - start)):
            t = k / RATE
            out[start + k] += (
                0.25
                * min(1, k / 48)
                * math.exp(-t / (decay / 4))
                * (math.sin(2 * math.pi * hz * t) + 0.4 * math.sin(4 * math.pi * hz * t))
            )
    return out


def run_analyze(path, **args):
    params, _ = validate_call("audio.analyze", {"path": str(path), **args})
    return asyncio.run(audio.analyze(params))


def test_level_reports_peak_rms_and_silence(tmp_path):
    p = tmp_path / "a.wav"
    write_wav(p, [0.5 * math.sin(2 * math.pi * 440 * i / RATE) if i < RATE else 0.0 for i in range(2 * RATE)])
    r = run_analyze(p, mode="level")
    assert r["peak"] == pytest.approx(0.5, abs=0.01)
    assert r["rms"] == pytest.approx(0.5 / 2, abs=0.01)  # sine rms over half the file
    assert 0.4 < r["silenceRatio"] < 0.6
    assert r["clipping"] is False
    assert r["windows"][0]["t"] == 0
    assert r["windows"][-1]["rms"] == 0


def test_level_flags_clipping_and_tolerates_streamed_header(tmp_path):
    p = tmp_path / "c.wav"
    write_wav(p, [1.0, -1.0] * 4800, header_zero=True)
    r = run_analyze(p, mode="level")
    assert r["clipping"] is True
    assert r["durationSeconds"] == pytest.approx(0.2, abs=0.001)


def test_onsets_and_skip(tmp_path):
    p = tmp_path / "o.wav"
    write_wav(p, notes([60] * 4))
    r = run_analyze(p, mode="onsets")
    assert r["count"] == 4
    assert all(abs(t - (0.1 + 0.125 * i)) < 0.012 for i, t in enumerate(r["onsets"]))
    skipped = run_analyze(p, mode="onsets", skipSeconds=0.3)
    assert skipped["count"] == 2
    assert skipped["onsets"][0] > 0.3


def test_silent_file_reports_silent(tmp_path):
    p = tmp_path / "s.wav"
    write_wav(p, [0.0] * RATE)
    assert run_analyze(p, mode="onsets")["silent"] is True


def test_mono_pitch_summary_and_grid(tmp_path):
    seq = [57, 59, 60, 72, 71, 57, 57, 64]
    p = tmp_path / "m.wav"
    write_wav(p, notes(seq, step=0.125))
    r = run_analyze(p, mode="mono_pitch", bpm=120)
    assert [round(n["midi"]) for n in r["notes"]] == seq
    assert all(abs(n["midi"] - round(n["midi"])) < 0.1 for n in r["notes"])
    s = r["summary"]
    assert s["range"] == {"min": 57, "max": 72, "semitones": 15}
    assert s["intervals"] == [2, 1, 12, -1, -14, 0, 7]
    assert s["leaps"] == 3
    assert s["pitchClassNames"][0] == "C"
    assert s["stepwiseRatio"] == pytest.approx(4 / 7, abs=0.01)
    assert r["grid"]["positions16"] == [0, 1, 2, 3, 4, 5, 6, 7]  # 0.125 s = one 16th at 120 BPM


def test_probe_means(tmp_path):
    p = tmp_path / "p.wav"
    write_wav(p, [0.5] * (RATE // 5) + [-0.25] * (RATE // 5))
    r = run_analyze(p, mode="probe")
    assert r["count"] == 40
    assert r["means"][0] == 0.5
    assert r["means"][-1] == -0.25
    assert r["max"] == 0.5
    assert r["min"] == -0.25
    assert run_analyze(p, mode="probe", skipSeconds=0.2)["means"][0] == -0.25


def test_analyze_errors(tmp_path):
    bad = tmp_path / "x.wav"
    bad.write_bytes(b"nope")
    with pytest.raises(BridgeError) as e:
        run_analyze(bad, mode="level")
    assert e.value.error["code"] == "invalid_params"
    with pytest.raises(BridgeError):
        run_analyze(tmp_path / "missing.wav", mode="level")
    with pytest.raises(ValueError, match=re.escape("1 validation error for AudioAnalyzeArgs")):
        validate_call("audio.analyze", {"path": "relative.wav", "mode": "level"})
    with pytest.raises(ValueError, match=re.escape("1 validation error for AudioAnalyzeArgs")):
        validate_call("audio.analyze", {"path": "/tmp/a.mp3", "mode": "level"})


class FakePW:
    """Mimics pw-link/pactl output and state; records every call."""

    def __init__(self, links, sink=False):
        self.links = set(links)
        self.sink = sink
        self.calls = []

    def run(self, args, timeout=5.0):  # noqa: PLR0911
        self.calls.append(args)
        if args[:2] == ["pw-link", "-l"]:
            text = "alsa_output.pci:playback_FL\n  |<- Bitwig Studio:out1\nalsa_output.pci:playback_FR\n  |<- Bitwig Studio:out2\n"
            ports: dict[str, list[str]] = {}
            for a, b in sorted(self.links):
                ports.setdefault(a, []).append(b)
            for a, targets in ports.items():
                text += a + "\n" + "".join(f"  |-> {t}\n" for t in targets)
            return 0, text, ""
        if args[:2] == ["pw-link", "-o"]:
            return 0, "Bitwig Studio:out1\nBitwig Studio:out2\nFirefox:output_FL\nFirefox:output_FR\n", ""
        if args[:2] == ["pw-link", "-i"]:
            return (
                0,
                "".join(f"{n}:input_{c}\n" for n in NODES for c in ("FL", "FR")) + "alsa_output.pci:playback_FL\n",
                "",
            )
        if args[:2] == ["pw-link", "-d"]:
            self.links.discard((args[2], args[3]))
            return 0, "", ""
        if args[0] == "pw-link":
            if (args[1], args[2]) in self.links:
                return 1, "", "failed to link ports: File exists"
            self.links.add((args[1], args[2]))
            return 0, "", ""
        if args[:3] == ["pactl", "list", "short"]:
            return 0, ("7\tbwtest\tmodule\tfmt\tRUNNING\n" if self.sink else "1\tother\tm\tf\tIDLE\n"), ""
        if args[:2] == ["pactl", "load-module"]:
            self.sink = True
            return 0, "42\n", ""
        raise AssertionError(args)


NODES: list[str] = []  # capture nodes the fake pw-record has "created"
SINK_LINKS = {("Bitwig Studio:out1", "bwtest:playback_FL"), ("Bitwig Studio:out2", "bwtest:playback_FR")}


@pytest.fixture
def pw(tmp_path, monkeypatch):
    fake = FakePW(
        {("Bitwig Studio:out1", "alsa_output.pci:playback_FL"), ("Bitwig Studio:out2", "alsa_output.pci:playback_FR")}
    )
    monkeypatch.setattr(audio, "run", fake.run)
    monkeypatch.setattr(audio, "check_platform", lambda: None)
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))
    return fake


def route(mode):
    return audio.route(validate_call("audio.route", {"mode": mode})[0])


def test_silent_then_restore_relinks_exactly(pw):
    original = set(pw.links)
    r = route("silent")
    assert r["silent"]
    assert r["sinkCreated"]
    assert pw.links == SINK_LINKS
    assert audio.state_path().exists()
    again = route("silent")  # idempotent: no second sink, previous targets kept
    assert again["sinkCreated"] is False
    assert pw.links == SINK_LINKS
    assert route("status")["silent"] is True
    r = route("restore")
    assert pw.links == original
    assert r["silent"] is False
    assert not audio.state_path().exists()
    assert route("restore")["restored"] == []


def test_other_applications_are_never_touched(pw):
    route("silent")
    route("restore")
    touched = [c for c in pw.calls if c[0] == "pw-link" and c[1] != "-l"]
    assert touched
    assert all(any(p.startswith("Bitwig Studio:") for p in c) for c in touched)


def test_silent_without_bitwig_links_fails(pw):
    pw.links.clear()
    with pytest.raises(BridgeError) as e:
        route("silent")
    assert e.value.error["code"] == "bitwig_not_found"


def test_unsupported_platform(monkeypatch):
    monkeypatch.setattr(audio.sys, "platform", "darwin")
    with pytest.raises(BridgeError) as e:
        route("status")
    assert e.value.error["code"] == "unsupported_platform"
    assert e.value.error["outcome"] == "not_dispatched"


class FakeProc:
    def __init__(self, path):
        self.path, self.returncode, self.stderr, self.signals = path, None, None, []

    def poll(self):
        return self.returncode

    def send_signal(self, sig):
        self.signals.append(sig)

        write_wav(Path(self.path), notes([60, 62], total=0.6))
        self.returncode = 0

    def wait(self, timeout=None):
        return self.returncode

    def kill(self):
        self.returncode = -9


class FakeBridge:
    def __init__(self, playing=(), engine=True, solo=None):
        self.calls = []
        self.playing = playing
        self.engine = engine
        self.solo = dict(solo or {})
        self.fail_set = None

    async def call(self, method, params, timeout_ms=5000):
        self.calls.append((method, params))
        if method == "status":
            return {"engineActive": self.engine}
        if method == "context.get":
            return {
                "tracks": [
                    {"trackId": tid, "name": name, "launcherSlots": [{"slotIndex": 0, "playing": True}]}
                    for tid, name in self.playing
                ]
                + [{"trackId": "idle", "name": "Idle", "launcherSlots": [{"slotIndex": 0, "playing": False}]}]
            }
        if method == "tracks.list":
            return {"tracks": [{"trackId": t, "index": i, "name": t} for i, t in enumerate(self.solo)]}
        if method == "tracks.get":
            return {"trackId": params["trackId"], "solo": self.solo[params["trackId"]]}
        if method == "tracks.set":
            if params["trackId"] == self.fail_set and params["solo"] is False:
                raise BridgeError({"code": "host_error", "message": "boom", "outcome": "unknown"})
            self.solo[params["trackId"]] = params["solo"]
            return {}
        return {}


def capture(tmp_path, monkeypatch, playing=(), solo=None, **extra):
    spawned = []
    NODES.clear()

    def spawn(args):
        spawned.append(args)
        NODES.append(args[2].split("node.name=")[1].split()[0])
        return FakeProc(args[-1])

    async def no_sleep(_):
        return None

    monkeypatch.setattr(audio, "spawn", spawn)
    monkeypatch.setattr(audio, "sleep", no_sleep)
    params, timeout = validate_call("audio.capture", {"seconds": 1, "outPath": str(tmp_path / "out.wav"), **extra})
    return FakeBridge(playing, solo=solo), spawned, params, timeout


def test_capture_requires_silent_route(tmp_path, monkeypatch, pw):
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "not_silent"
    assert not spawned
    assert not bridge.calls


def test_capture_from_start_orders_transport_and_reports_levels(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert spawned[0][0] == "pw-record"
    assert "node.autoconnect=false" in spawned[0][2]
    assert "node.name=bwagent-capture-" in spawned[0][2]
    assert "bwtest.monitor" not in spawned[0]
    assert "@DEFAULT_MONITOR@" not in spawned[0]
    assert result["source"]["ports"] == ["Bitwig Studio:out1", "Bitwig Studio:out2"]
    assert bridge.calls == [
        ("status", {}),
        ("context.get", {}),
        ("transport.set", {"playing": False}),
        ("transport.set", {"positionBeats": 0}),
        ("transport.set", {"playing": True}),
        ("transport.set", {"playing": False}),
    ]
    assert result["peak"] > 0.5
    assert result["clipping"] is False
    assert result["silent"] is False
    assert result["durationSeconds"] == pytest.approx(0.6, abs=0.01)


def test_capture_overwrite_rules_and_no_transport(tmp_path, monkeypatch, pw):
    route("silent")
    (tmp_path / "out.wav").write_bytes(b"x")
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch)
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "file_exists"
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch, overwrite=True, fromStart=False)
    asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert [m for m, _ in bridge.calls] == ["status", "context.get"]


def test_capture_allow_audible_records_bitwig_ports_not_the_default_monitor(tmp_path, monkeypatch, pw):
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, allowAudible=True)
    before = set(pw.links)
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert result["audible"] is True
    assert "@DEFAULT_MONITOR@" not in spawned[0]
    node = result["source"]["node"]
    assert ["pw-link", "Bitwig Studio:out1", f"{node}:input_FL"] in pw.calls
    assert ["pw-link", "Bitwig Studio:out2", f"{node}:input_FR"] in pw.calls
    assert pw.links == before  # exactly the links it created are gone, nothing else changed
    assert [c for c in pw.calls if c[:2] == ["pw-link", "-d"]] == [
        ["pw-link", "-d", "Bitwig Studio:out1", f"{node}:input_FL"],
        ["pw-link", "-d", "Bitwig Studio:out2", f"{node}:input_FR"],
    ]


def test_capture_unlinks_its_own_links_when_it_fails(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch)
    before = set(pw.links)

    async def broken(method, params, timeout_ms=5000, _call=bridge.call):
        if method == "transport.set" and params.get("positionBeats") == 0:
            raise BridgeError({"code": "host_error", "message": "x", "outcome": "unknown"})
        return await _call(method, params, timeout_ms)

    bridge.call = broken
    with pytest.raises(BridgeError):
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert pw.links == before


def test_capture_needs_bitwig_output_ports(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    monkeypatch.setattr(audio, "bitwig_outputs", lambda: (_ for _ in ()).throw(audio.fail("bitwig_not_found", "x")))
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "bitwig_not_found"
    assert not spawned


def test_capture_reports_other_playing_tracks(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, _, params, timeout = capture(tmp_path, monkeypatch, playing=[("a", "Kick"), ("b", "Pad")])
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert result["otherTracksPlaying"] == ["Kick", "Pad"]
    assert "mix" in result["warnings"][0]
    bridge, _, params, timeout = capture(tmp_path, monkeypatch, playing=[("a", "Kick")], overwrite=True)
    assert asyncio.run(client_side.run("audio.capture", bridge, params, timeout))["otherTracksPlaying"] == []


def test_capture_solo_track_refuses_while_others_play(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, spawned, params, timeout = capture(
        tmp_path, monkeypatch, playing=[(solo, "Kick"), ("b", "Pad")], solo={solo: False, "b": False}, soloTrack=solo
    )
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "not_isolated"
    assert e.value.error["outcome"] == "not_applied"
    assert not spawned
    assert e.value.error["details"]["otherTracksPlaying"] == ["Pad"]
    assert not [m for m, _ in bridge.calls if m == "tracks.set"]
    # the solo track needs no playing launcher clip (a Poly Grid sounds without one)
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, solo={solo: False}, soloTrack=solo)
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert result["otherTracksPlaying"] == []
    assert "warnings" not in result
    assert spawned


def test_capture_solo_really_solos_and_restores(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    states = {solo: False, "b": True, "c": False}
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch, solo=states, soloTrack=solo)
    seen = []
    inner = bridge.call

    async def spy(method, p, t=5000):
        out = await inner(method, p, t)
        if method == "transport.set" and p.get("playing") is True:
            seen.append(dict(bridge.solo))  # state while recording
        return out

    bridge.call = spy
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert seen == [{solo: True, "b": False, "c": False}]
    assert bridge.solo == states  # restored
    assert result["soloed"] == solo
    assert result["previousSolo"] == ["b"]
    assert result["unsoloed"] == ["b"]
    assert result["restored"] == [{"trackId": "b", "solo": True}, {"trackId": solo, "solo": False}]
    assert "restoreFailed" not in result


def test_capture_solo_restores_on_failure_and_reports_restore_errors(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    states = {solo: False, "b": True}
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch, solo=states, soloTrack=solo)
    inner = bridge.call

    async def broken(method, p, t=5000):
        if method == "transport.set" and p.get("positionBeats") == 0:
            raise BridgeError({"code": "host_error", "message": "x", "outcome": "unknown"})
        return await inner(method, p, t)

    bridge.call = broken
    with pytest.raises(BridgeError):
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert bridge.solo == states
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch, solo=states, soloTrack=solo, overwrite=True)
    bridge.fail_set = solo  # restoring the target (solo off) fails
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert result["restoreFailed"][0]["trackId"] == solo


def test_capture_solo_refuses_unreadable_or_unknown_tracks(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, solo={"b": False}, soloTrack=solo)
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "unknown_track"
    assert not spawned
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, solo={solo: None}, soloTrack=solo)
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "solo_unreadable"
    assert not spawned


def test_capture_stop_others_stops_then_records(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, spawned, params, timeout = capture(
        tmp_path,
        monkeypatch,
        playing=[(solo, "Kick"), ("b", "Pad")],
        solo={solo: False, "b": False},
        soloTrack=solo,
        stopOthers=True,
    )
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert ("launcher.stop", {"trackId": "b"}) in bridge.calls
    assert (
        "launcher.stop",
        {"trackId": solo},
    ) not in bridge.calls
    assert result["stoppedTracks"] == ["Pad"]
    assert result["otherTracksPlaying"] == []
    assert spawned


def test_capture_validation():
    for bad in (
        {"seconds": 0.1, "outPath": "/tmp/a.wav"},
        {"seconds": 200, "outPath": "/tmp/a.wav"},
        {"seconds": 1, "outPath": "a.wav"},
        {"seconds": 1, "outPath": "/tmp/a.flac"},
    ):
        with pytest.raises(ValueError, match=re.escape("1 validation error for AudioCaptureArgs")):
            validate_call("audio.capture", bad)


def test_cli_and_catalog_expose_tools():

    assert {"audio.route", "audio.capture", "audio.analyze"} <= CLIENT_SIDE <= set(TOOLS)
    params, _ = parse_arguments("audio.capture", ["seconds=2", "outPath=/tmp/x.wav", "fromStart=false"], None, None)
    assert params == {"seconds": 2.0, "outPath": "/tmp/x.wav", "fromStart": False}
    payload, code = asyncio.run(execute(["audio.analyze", "path=/nonexistent/x.wav", "mode=level"]))
    assert code == 1
    assert payload["error"]["code"] == "invalid_params"
    payload, code = asyncio.run(execute(["describe", "audio.route"]))
    assert code == 0
    assert json.dumps(payload)


def test_flux_counts_overlapping_long_decay_notes(tmp_path):
    seq = [60, 64, 67, 72, 60, 67, 64, 72, 60, 64, 67, 72, 71, 67, 64, 60]
    p = tmp_path / "ov.wav"
    write_wav(p, overlapping(seq))
    r = run_analyze(p, mode="onsets")
    assert r["method"] == "flux"
    assert r["count"] == len(seq)
    assert all(abs(t - (0.1 + 0.1 * i)) < 0.03 for i, t in enumerate(r["onsets"]))
    old = run_analyze(p, mode="onsets", method="envelope")
    assert old["method"] == "envelope"
    assert old["count"] < len(seq)


def test_mono_pitch_default_follows_flux_on_overlaps(tmp_path):
    seq = [60, 64, 67, 72, 60, 67, 64, 72]
    p = tmp_path / "ovp.wav"
    write_wav(p, overlapping(seq))
    r = run_analyze(p, mode="mono_pitch")
    assert [round(n["midi"]) for n in r["notes"]] == seq


def test_capture_refuses_an_inactive_engine_before_touching_anything(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    bridge.engine = False
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "engine_inactive"
    assert e.value.error["outcome"] == "not_applied"
    assert "project.activateEngine" in e.value.error["message"]
    assert not spawned
    assert bridge.calls == [("status", {})]


def write_stereo(path, left, right, rate=RATE):
    frames = b"".join(struct.pack("<hh", int(a * 32767), int(b * 32767)) for a, b in zip(left, right, strict=False))
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(frames)


def tone(hz, seconds, amp=0.5, rate=RATE):
    return [amp * math.sin(2 * math.pi * hz * i / rate) for i in range(int(seconds * rate))]


def run_bands(args):
    return asyncio.run(client_side.audio_analyze(None, validate_call("audio.analyze", args)[0], 5000))


def test_bands_places_energy_and_reports_stereo_correlation(tmp_path):
    low = tone(100, 2.5)
    wav = tmp_path / "bands.wav"
    write_stereo(wav, low, low)
    out = run_bands({"path": str(wav), "mode": "bands", "windowSeconds": 1})
    assert out["stereoCorrelation"] == 1.0
    assert len(out["windows"]) == 3
    row = out["windows"][0]
    assert (row["bandsDb"]["low"] > -0.5 and row["bandsDb"]["air"] is None) or row["bandsDb"]["air"] < -40
    assert 90 < row["centroidHz"] < 120
    assert row["t"] == 0.0
    assert 0.3 < row["rms"] < 0.4
    write_stereo(wav, low, [-v for v in low])
    assert run_bands({"path": str(wav), "mode": "bands", "windowSeconds": 1})["stereoCorrelation"] == -1.0
    write_stereo(wav, tone(8800, 1.5), tone(8800, 1.5))
    assert run_bands({"path": str(wav), "mode": "bands"})["windows"][0]["bandsDb"]["air"] > -0.5


def test_bands_chroma_resolves_a_low_register_chord(tmp_path):
    rate, seconds = 48000, 4
    freqs = [(hz * h, 1 / h) for hz in (73.416, 87.307, 110.0) for h in (1, 2, 3, 4)]  # D2 F2 A2 with harmonics
    mix = [sum(a * math.sin(2 * math.pi * f * i / rate) for f, a in freqs) / 6 for i in range(rate * seconds)]
    wav = tmp_path / "chord.wav"
    write_stereo(wav, mix, mix, rate=rate)
    chroma = run_bands({"path": str(wav), "mode": "bands", "key": "D:aeolian", "windowSeconds": 4})["chroma"]
    assert {p["name"] for p in chroma["topPitchClasses"]} == {"D", "F", "A"}
    assert chroma["inKeyShare"] > 0.95


def test_bands_key_chroma_and_spectrogram(tmp_path):
    wav, png = tmp_path / "key.wav", tmp_path / "spec.png"
    a3 = tone(220, 1.5)
    write_stereo(wav, a3, a3)
    out = run_bands({"path": str(wav), "mode": "bands", "key": "A:aeolian", "spectrogramPath": str(png)})
    assert out["chroma"]["inKeyShare"] == 1.0
    assert out["chroma"]["topPitchClasses"][0]["name"] == "A"
    assert out["windows"][0]["chroma"]["inKeyShare"] == 1.0
    assert run_bands({"path": str(wav), "mode": "bands", "key": "C#:ionian"})["chroma"]["inKeyShare"] == 0.0
    data = png.read_bytes()
    width, height = struct.unpack(">II", data[16:24])
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    assert (width, height) == (out["spectrogram"]["width"], 256)
    assert data.endswith(b"IEND\xaeB`\x82")
    with pytest.raises(ValueError, match=re.escape("1 validation error for AudioAnalyzeArgs")):
        validate_call("audio.analyze", {"path": str(wav), "mode": "level", "key": "A:aeolian"})
    with pytest.raises(ValueError, match=re.escape("1 validation error for AudioAnalyzeArgs")):
        validate_call("audio.analyze", {"path": str(wav), "mode": "bands", "key": "H:aeolian"})
