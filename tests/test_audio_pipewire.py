"""audio.* against synthetic WAVs and a fake PipeWire (no real subprocess, no Bitwig)."""

import asyncio
import math
import re
import struct
import subprocess
import wave

import pytest
from fakes import (
    NODES,
    RATE,
    SINK_LINKS,
    FakeBridge,
    FakeProc,
    FakePW,
    notes,
    overlapping,
    parse_arguments,
    spawn_dead,
    write_wav,
)
from fakes import (
    execute_cli as execute,
)

from bitwig_agent.audio import pipewire
from bitwig_agent.core import composed
from bitwig_agent.errors import BridgeError, HostChanged, bridge_error, unknown_result
from bitwig_agent.protocol import CLIENT_SIDE, TOOLS, validate_call

REAL_RUN = pipewire.run


def run_analyze(path, **args):
    params, _ = validate_call("audio.analyze", {"path": str(path), **args})
    return asyncio.run(pipewire.analyze(params))


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
    assert e.value.error["reason"] == "invalid_params"
    with pytest.raises(BridgeError):
        run_analyze(tmp_path / "missing.wav", mode="level")
    with pytest.raises(ValueError, match=re.escape("1 validation error for AudioAnalyzeArgs")):
        validate_call("audio.analyze", {"path": "relative.wav", "mode": "level"})
    with pytest.raises(ValueError, match=re.escape("1 validation error for AudioAnalyzeArgs")):
        validate_call("audio.analyze", {"path": "/tmp/a.mp3", "mode": "level"})


@pytest.fixture
def pw(tmp_path, monkeypatch):
    fake = FakePW(
        {("Bitwig Studio:out1", "alsa_output.pci:playback_FL"), ("Bitwig Studio:out2", "alsa_output.pci:playback_FR")}
    )
    monkeypatch.setattr(pipewire, "run", fake.run)
    monkeypatch.setattr(pipewire, "check_platform", lambda: None)
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))
    return fake


def route(mode):
    return pipewire.route(validate_call("audio.route", {"mode": mode})[0])


def test_silent_then_restore_relinks_exactly(pw):
    original = set(pw.links)
    r = route("silent")
    assert r["silent"]
    assert r["sinkCreated"]
    assert pw.links == SINK_LINKS
    assert pipewire.state_path().exists()
    again = route("silent")  # idempotent: no second sink, previous targets kept
    assert again["sinkCreated"] is False
    assert pw.links == SINK_LINKS
    assert route("status")["silent"] is True
    r = route("restore")
    assert pw.links == original
    assert r["silent"] is False
    assert not pipewire.state_path().exists()
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
    assert e.value.error["reason"] == "not_ready"


def test_unsupported_platform(monkeypatch):
    monkeypatch.setattr(pipewire.sys, "platform", "darwin")
    with pytest.raises(BridgeError) as e:
        route("status")
    assert e.value.error["reason"] == "unsupported_platform"


def capture(tmp_path, monkeypatch, playing=(), solo=None, **extra):
    spawned = []
    NODES.clear()

    def spawn(args):
        spawned.append(args)
        NODES.append(args[2].split("node.name=")[1].split()[0])
        return FakeProc(args[-1])

    async def no_sleep(_):
        return None

    monkeypatch.setattr(pipewire, "spawn", spawn)
    monkeypatch.setattr(pipewire, "sleep", no_sleep)
    params, timeout = validate_call("audio.capture", {"seconds": 1, "outPath": str(tmp_path / "out.wav"), **extra})
    return FakeBridge(playing, solo=solo), spawned, params, timeout


def test_capture_requires_silent_route(tmp_path, monkeypatch, pw):
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    with pytest.raises(BridgeError) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "not_isolated"
    assert not spawned
    assert not bridge.calls


def test_capture_from_start_orders_transport_and_reports_levels(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
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
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "target_exists"
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch, overwrite=True, fromStart=False)
    asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert [m for m, _ in bridge.calls] == ["status", "context.get"]


def test_capture_allow_audible_records_bitwig_ports_not_the_default_monitor(tmp_path, monkeypatch, pw):
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, allowAudible=True)
    before = set(pw.links)
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
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
            return unknown_result("deadline", "x")
        return await _call(method, params, timeout_ms)

    bridge.call = broken
    with pytest.raises(HostChanged) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.result["reason"] == "deadline"
    assert pw.links == before


def test_capture_needs_bitwig_output_ports(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    monkeypatch.setattr(
        pipewire, "bitwig_outputs", lambda: (_ for _ in ()).throw(pipewire.bridge_error("not_ready", "x"))
    )
    with pytest.raises(BridgeError) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "not_ready"
    assert not spawned


def test_capture_reports_other_playing_tracks(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, _, params, timeout = capture(tmp_path, monkeypatch, playing=[("a", "Kick"), ("b", "Pad")])
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert result["otherTracksPlaying"] == ["Kick", "Pad"]
    assert "mix" in result["warnings"][0]
    bridge, _, params, timeout = capture(tmp_path, monkeypatch, playing=[("a", "Kick")], overwrite=True)
    assert asyncio.run(composed.run("audio.capture", bridge, params, timeout))["otherTracksPlaying"] == []


def capture_solo_with_pad(tmp_path, monkeypatch, solo, **extra):
    return capture(
        tmp_path,
        monkeypatch,
        playing=[(solo, "Kick"), ("b", "Pad")],
        solo={solo: False, "b": False},
        soloTrack=solo,
        **extra,
    )


def test_capture_solo_track_refuses_while_others_play(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, spawned, params, timeout = capture_solo_with_pad(tmp_path, monkeypatch, solo)
    with pytest.raises(BridgeError) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "not_isolated"
    assert not spawned
    assert e.value.error["details"]["otherTracksPlaying"] == ["Pad"]
    assert not [m for m, _ in bridge.calls if m == "tracks.set"]
    # the solo track needs no playing launcher clip (a Poly Grid sounds without one)
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, solo={solo: False}, soloTrack=solo)
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
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
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
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
            raise bridge_error("host_refused", "x")
        return await inner(method, p, t)

    bridge.call = broken
    with pytest.raises(HostChanged) as changed:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    result = changed.value.result
    assert (result["outcome"], result["reason"], result["transportStopped"]) == ("not_applied", "host_refused", True)
    assert bridge.solo == states
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch, solo=states, soloTrack=solo, overwrite=True)
    bridge.fail_set = solo  # restoring the target (solo off) fails
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert result["restoreFailed"][0]["trackId"] == solo
    assert (result["outcome"], result["reason"]) == ("unknown", "job_failed")


def test_capture_solo_refuses_unreadable_or_unknown_tracks(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, solo={"b": False}, soloTrack=solo)
    with pytest.raises(BridgeError) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "target_not_found"
    assert not spawned
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, solo={solo: None}, soloTrack=solo)
    with pytest.raises(BridgeError) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "not_ready"
    assert not spawned


def test_capture_stop_others_stops_then_records(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, spawned, params, timeout = capture_solo_with_pad(tmp_path, monkeypatch, solo, stopOthers=True)
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert ("launcher.stop", {"trackId": "b"}) in bridge.calls
    assert (
        "launcher.stop",
        {"trackId": solo},
    ) not in bridge.calls
    assert result["stoppedTracks"] == ["Pad"]
    assert result["otherTracksPlaying"] == []
    assert spawned


WRITES = ("launcher.stop", "tracks.set", "transport.set")


def test_capture_resolves_the_solo_target_before_stopping_anything(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, spawned, params, timeout = capture(
        tmp_path,
        monkeypatch,
        playing=[("b", "Pad")],
        solo={"b": False},
        soloTrack="00000000-0000-0000-0000-00000000000f",
        stopOthers=True,
    )
    with pytest.raises(BridgeError) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "target_not_found"
    assert not [c for c in bridge.calls if c[0] in WRITES], "an error means no host write happened"
    assert not spawned


def test_a_refusal_after_stopping_tracks_is_a_result_naming_them(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, _spawned, params, timeout = capture_solo_with_pad(tmp_path, monkeypatch, solo, stopOthers=True)
    monkeypatch.setattr(pipewire, "spawn", spawn_dead)
    with pytest.raises(HostChanged) as changed:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    result = changed.value.result
    assert (result["outcome"], result["reason"]) == ("not_applied", "job_failed")
    assert result["stoppedTracks"] == ["Pad"]
    assert result["transportStopped"] is False


def test_a_refusal_after_a_failed_solo_restore_is_an_unknown_result(tmp_path, monkeypatch, pw):
    route("silent")
    solo = "00000000-0000-0000-0000-000000000001"
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch, solo={solo: False, "b": True}, soloTrack=solo)
    monkeypatch.setattr(pipewire, "spawn", spawn_dead)
    bridge.fail_set = solo  # putting the target's solo back fails
    with pytest.raises(HostChanged) as changed:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    result = changed.value.result
    assert (result["outcome"], result["reason"]) == ("unknown", "job_failed")
    assert result["restoreFailed"][0]["trackId"] == solo
    assert "tracks.set" in result["next"]


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
    params, _ = parse_arguments("audio.capture", ["--seconds=2", "--outPath=/tmp/x.wav", "--fromStart=false"])
    assert params == {"seconds": 2.0, "outPath": "/tmp/x.wav", "fromStart": False}
    payload, code = asyncio.run(execute(["audio.analyze", "--path=/nonexistent/x.wav", "--mode=level"]))
    assert code == 2
    assert payload["error"]["code"] == "invalid_params"
    payload, code = asyncio.run(execute(["help", "audio.route"]))
    assert code == 0
    assert payload["result"]["method"] == "audio.route"


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
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.error["reason"] == "engine_inactive"
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
    return asyncio.run(composed.audio_analyze(None, validate_call("audio.analyze", args)[0], 5000))


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


def test_route_outcomes(pw):
    assert "outcome" not in route("status")
    assert route("restore")["outcome"] == "already_matched"
    assert route("silent")["outcome"] == "applied"
    assert route("silent")["outcome"] == "already_matched"
    assert route("restore")["outcome"] == "applied"


def test_failed_or_timed_out_pw_link_is_unknown_not_an_error(pw, monkeypatch):
    real = pw.run

    def failing(args, timeout=5.0):
        return (1, "", "boom") if args[:2] == ["pw-link", "-d"] else real(args, timeout)

    monkeypatch.setattr(pipewire, "run", failing)
    with pytest.raises(HostChanged) as e:
        route("silent")
    assert (e.value.result["outcome"], e.value.result["reason"]) == ("unknown", "job_failed")
    assert "audio.route mode=status" in e.value.result["next"]

    def slow(args, timeout=5.0):
        raise bridge_error("deadline", "pw-link did not finish")

    monkeypatch.setattr(pipewire, "run", slow)
    with pytest.raises(HostChanged) as e:
        pipewire.link(["-d", "a", "b"], "disconnect")
    assert e.value.result["reason"] == "deadline"


def test_capture_stops_on_a_write_that_did_not_succeed(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch)
    inner = bridge.call

    async def refused(method, p, t=5000):
        if method == "transport.set" and p.get("playing") is True:
            return {"outcome": "not_applied", "reason": "unsafe_state"}
        return await inner(method, p, t)

    bridge.call = refused
    with pytest.raises(HostChanged) as e:
        asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert e.value.result["outcome"] == "not_applied"
    assert "transport.set ended" in e.value.result["message"]


def test_capture_reports_a_transport_that_could_not_be_stopped(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch)
    inner = bridge.call
    stops = {"n": 0}

    async def stuck(method, p, t=5000):
        if method == "transport.set" and p == {"playing": False}:
            stops["n"] += 1
            if stops["n"] == 2:  # the first is the pre-roll stop, the second the final one
                raise bridge_error("not_ready", "no host")
        return await inner(method, p, t)

    bridge.call = stuck
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert (result["outcome"], result["reason"]) == ("unknown", "not_ready")
    assert result["path"]


def test_capture_stop_that_did_not_succeed_is_reported_unknown(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, _spawned, params, timeout = capture(tmp_path, monkeypatch)
    inner = bridge.call
    stops = {"n": 0}

    async def refused(method, p, t=5000):
        if method == "transport.set" and p == {"playing": False}:
            stops["n"] += 1
            if stops["n"] == 2:
                return {"outcome": "unknown", "reason": "deadline", "message": "slow"}
        return await inner(method, p, t)

    bridge.call = refused
    result = asyncio.run(composed.run("audio.capture", bridge, params, timeout))
    assert (result["outcome"], result["reason"]) == ("unknown", "deadline")


def test_subprocess_failures_are_errors(monkeypatch):
    def timeout(*_a, **_k):
        raise subprocess.TimeoutExpired("pw-link", 5)

    def missing(*_a, **_k):
        raise FileNotFoundError(2, "No such file")

    monkeypatch.setattr(subprocess, "run", timeout)
    with pytest.raises(BridgeError) as e:
        REAL_RUN(["pw-link", "-l"])
    assert e.value.error["reason"] == "deadline"
    monkeypatch.setattr(subprocess, "run", missing)
    with pytest.raises(BridgeError) as e:
        REAL_RUN(["pw-link", "-l"])
    assert e.value.error["reason"] == "job_failed"


@pytest.mark.parametrize(
    ("failing", "call"),
    [
        (["pw-link", "-l"], pipewire.current_links),
        (["pactl", "load-module"], pipewire.ensure_sink),
        (["pw-link", "-o"], pipewire.bitwig_outputs),
    ],
)
def test_failed_tool_is_a_job_failure(pw, monkeypatch, failing, call):
    real = pw.run
    pw.sink = False

    def run(args, timeout=5.0):
        return (1, "", "boom") if args[: len(failing)] == failing else real(args, timeout)

    monkeypatch.setattr(pipewire, "run", run)
    with pytest.raises(BridgeError) as e:
        call()
    assert e.value.error["reason"] == "job_failed"


def test_capture_node_that_never_appears_is_a_job_failure(monkeypatch):
    async def no_sleep(_):
        return None

    monkeypatch.setattr(pipewire, "run", lambda args, timeout=5.0: (0, "", ""))
    monkeypatch.setattr(pipewire, "sleep", no_sleep)
    with pytest.raises(BridgeError) as e:
        asyncio.run(pipewire.wait_for_inputs("node"))
    assert e.value.error["reason"] == "job_failed"
