"""audio.* against synthetic WAVs and a fake PipeWire (no real subprocess, no Bitwig)."""

import asyncio
import json
import math
import struct
import wave

import pytest

from bitwig_agent import audio, client_side
from bitwig_agent.catalog import validate_call
from bitwig_agent.client import BridgeError

RATE = 48000


def write_wav(path, samples, rate=RATE, header_zero=False):
    frames = b"".join(struct.pack("<hh", int(s * 32767), int(s * 32767)) for s in samples)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2), w.setsampwidth(2), w.setframerate(rate)
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


def run_analyze(path, **args):
    params, _ = validate_call("audio.analyze", {"path": str(path), **args})
    return asyncio.run(audio.analyze(params))


def test_level_reports_peak_rms_and_silence(tmp_path):
    p = tmp_path / "a.wav"
    write_wav(p, [0.5 * math.sin(2 * math.pi * 440 * i / RATE) if i < RATE else 0.0 for i in range(2 * RATE)])
    r = run_analyze(p, mode="level")
    assert r["peak"] == pytest.approx(0.5, abs=0.01)
    assert r["rms"] == pytest.approx(0.5 / 2, abs=0.01)  # sine rms over half the file
    assert 0.4 < r["silenceRatio"] < 0.6 and r["clipping"] is False
    assert r["windows"][0]["t"] == 0 and r["windows"][-1]["rms"] == 0


def test_level_flags_clipping_and_tolerates_streamed_header(tmp_path):
    p = tmp_path / "c.wav"
    write_wav(p, [1.0, -1.0] * 4800, header_zero=True)
    r = run_analyze(p, mode="level")
    assert r["clipping"] is True and r["durationSeconds"] == pytest.approx(0.2, abs=0.001)


def test_onsets_and_skip(tmp_path):
    p = tmp_path / "o.wav"
    write_wav(p, notes([60] * 4))
    r = run_analyze(p, mode="onsets")
    assert r["count"] == 4
    assert all(abs(t - (0.1 + 0.125 * i)) < 0.012 for i, t in enumerate(r["onsets"]))
    skipped = run_analyze(p, mode="onsets", skipSeconds=0.3)
    assert skipped["count"] == 2 and skipped["onsets"][0] > 0.3


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
    assert s["leaps"] == 3 and s["pitchClassNames"][0] == "C"
    assert s["stepwiseRatio"] == pytest.approx(4 / 7, abs=0.01)
    assert r["grid"]["positions16"] == [0, 1, 2, 3, 4, 5, 6, 7]  # 0.125 s = one 16th at 120 BPM


def test_probe_means(tmp_path):
    p = tmp_path / "p.wav"
    write_wav(p, [0.5] * (RATE // 5) + [-0.25] * (RATE // 5))
    r = run_analyze(p, mode="probe")
    assert r["count"] == 40 and r["means"][0] == 0.5 and r["means"][-1] == -0.25
    assert r["max"] == 0.5 and r["min"] == -0.25
    assert run_analyze(p, mode="probe", skipSeconds=0.2)["means"][0] == -0.25


def test_analyze_errors(tmp_path):
    bad = tmp_path / "x.wav"
    bad.write_bytes(b"nope")
    with pytest.raises(BridgeError) as e:
        run_analyze(bad, mode="level")
    assert e.value.error["code"] == "invalid_params"
    with pytest.raises(BridgeError):
        run_analyze(tmp_path / "missing.wav", mode="level")
    with pytest.raises(ValueError):
        validate_call("audio.analyze", {"path": "relative.wav", "mode": "level"})
    with pytest.raises(ValueError):
        validate_call("audio.analyze", {"path": "/tmp/a.mp3", "mode": "level"})


class FakePW:
    """Mimics pw-link/pactl output and state; records every call."""

    def __init__(self, links, sink=False):
        self.links = set(links)
        self.sink = sink
        self.calls = []

    def run(self, args, timeout=5.0):
        self.calls.append(args)
        if args[:2] == ["pw-link", "-l"]:
            text = "alsa_output.pci:playback_FL\n  |<- Bitwig Studio:out1\nalsa_output.pci:playback_FR\n  |<- Bitwig Studio:out2\n"
            ports = {}
            for a, b in sorted(self.links):
                ports.setdefault(a, []).append(b)
            for a, targets in ports.items():
                text += a + "\n" + "".join(f"  |-> {t}\n" for t in targets)
            return 0, text, ""
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


SINK_LINKS = {("Bitwig Studio:out1", "bwtest:playback_FL"), ("Bitwig Studio:out2", "bwtest:playback_FR")}


@pytest.fixture
def pw(tmp_path, monkeypatch):
    fake = FakePW({("Bitwig Studio:out1", "alsa_output.pci:playback_FL"), ("Bitwig Studio:out2", "alsa_output.pci:playback_FR")})
    monkeypatch.setattr(audio, "run", fake.run)
    monkeypatch.setattr(audio, "check_platform", lambda: None)
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))
    return fake


def route(mode):
    return audio.route(validate_call("audio.route", {"mode": mode})[0])


def test_silent_then_restore_relinks_exactly(pw):
    original = set(pw.links)
    r = route("silent")
    assert r["silent"] and r["sinkCreated"]
    assert pw.links == SINK_LINKS
    assert audio.state_path().exists()
    again = route("silent")  # idempotent: no second sink, previous targets kept
    assert again["sinkCreated"] is False and pw.links == SINK_LINKS
    assert route("status")["silent"] is True
    r = route("restore")
    assert pw.links == original and r["silent"] is False and not audio.state_path().exists()
    assert route("restore")["restored"] == []


def test_other_applications_are_never_touched(pw):
    route("silent")
    route("restore")
    touched = [c for c in pw.calls if c[0] == "pw-link" and c[1] != "-l"]
    assert touched and all(any(p.startswith("Bitwig Studio:") for p in c) for c in touched)


def test_silent_without_bitwig_links_fails(pw):
    pw.links.clear()
    with pytest.raises(BridgeError) as e:
        route("silent")
    assert e.value.error["code"] == "bitwig_not_found"


def test_unsupported_platform(monkeypatch):
    monkeypatch.setattr(audio.sys, "platform", "darwin")
    with pytest.raises(BridgeError) as e:
        route("status")
    assert e.value.error["code"] == "unsupported_platform" and e.value.error["outcome"] == "not_dispatched"


class FakeProc:
    def __init__(self, path):
        self.path, self.returncode, self.stderr, self.signals = path, None, None, []

    def poll(self):
        return self.returncode

    def send_signal(self, sig):
        self.signals.append(sig)
        from pathlib import Path
        write_wav(Path(self.path), notes([60, 62], total=0.6))
        self.returncode = 0

    def wait(self, timeout=None):
        return self.returncode

    def kill(self):
        self.returncode = -9


class FakeBridge:
    def __init__(self):
        self.calls = []

    async def call(self, method, params, timeout_ms=5000):
        self.calls.append((method, params))
        return {}


def capture(tmp_path, monkeypatch, **extra):
    spawned = []

    def spawn(args):
        spawned.append(args)
        return FakeProc(args[-1])

    async def no_sleep(_):
        return None

    monkeypatch.setattr(audio, "spawn", spawn)
    monkeypatch.setattr(audio, "sleep", no_sleep)
    params, timeout = validate_call("audio.capture", {"seconds": 1, "outPath": str(tmp_path / "out.wav"), **extra})
    return FakeBridge(), spawned, params, timeout


def test_capture_requires_silent_route(tmp_path, monkeypatch, pw):
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "not_silent" and not spawned and not bridge.calls


def test_capture_from_start_orders_transport_and_reports_levels(tmp_path, monkeypatch, pw):
    route("silent")
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    result = asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert spawned[0][:3] == ["parec", "-d", "bwtest.monitor"]
    assert bridge.calls == [("transport.set", {"playing": False}), ("transport.set", {"positionBeats": 0}),
                            ("transport.set", {"playing": True}), ("transport.set", {"playing": False})]
    assert result["peak"] > 0.5 and result["clipping"] is False and result["silent"] is False
    assert result["durationSeconds"] == pytest.approx(0.6, abs=0.01)


def test_capture_overwrite_rules_and_no_transport(tmp_path, monkeypatch, pw):
    route("silent")
    (tmp_path / "out.wav").write_bytes(b"x")
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch)
    with pytest.raises(BridgeError) as e:
        asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert e.value.error["code"] == "file_exists"
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, overwrite=True, fromStart=False)
    asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert bridge.calls == []


def test_capture_allow_audible_records_default_monitor(tmp_path, monkeypatch, pw):
    bridge, spawned, params, timeout = capture(tmp_path, monkeypatch, allowAudible=True)
    asyncio.run(client_side.run("audio.capture", bridge, params, timeout))
    assert "@DEFAULT_MONITOR@" in spawned[0]


def test_capture_validation():
    for bad in ({"seconds": 0.1, "outPath": "/tmp/a.wav"}, {"seconds": 200, "outPath": "/tmp/a.wav"},
                {"seconds": 1, "outPath": "a.wav"}, {"seconds": 1, "outPath": "/tmp/a.flac"}):
        with pytest.raises(ValueError):
            validate_call("audio.capture", bad)


def test_cli_and_catalog_expose_tools():
    from bitwig_agent.catalog import CLIENT_SIDE, TOOLS
    from bitwig_agent.cli import execute, parse_arguments
    assert {"audio.route", "audio.capture", "audio.analyze"} <= CLIENT_SIDE <= set(TOOLS)
    params, _ = parse_arguments("audio.capture", ["seconds=2", "outPath=/tmp/x.wav", "fromStart=false"], None, None)
    assert params == {"seconds": 2.0, "outPath": "/tmp/x.wav", "fromStart": False}
    payload, code = asyncio.run(execute(["audio.analyze", "path=/nonexistent/x.wav", "mode=level"]))
    assert code == 1 and payload["error"]["code"] == "invalid_params"
    payload, code = asyncio.run(execute(["describe", "audio.route"]))
    assert code == 0 and json.dumps(payload)
