"""grid.probe against the fake Grid host and a fake PipeWire/pw-record (no Bitwig, no subprocess)."""

import asyncio
import math
import re
from typing import Any

import pytest
from test_audio import NODES, SINK_LINKS, FakeProc, FakePW
from test_grid_apply import TARGET, FakeGrid

from bitwig_agent import audio, grid_probe
from bitwig_agent.audio_analysis import FFT_SIZE, spectral_peaks
from bitwig_agent.catalog import CLIENT_SIDE, validate_call
from bitwig_agent.client import BridgeError

SAFE = {
    "playing": True,
    "arrangerRecordEnabled": False,
    "arrangerAutomationWriteEnabled": False,
    "clipLauncherAutomationWriteEnabled": False,
}


class ProbeGrid(FakeGrid):
    """Two Audio Outs (each fed), a probe-able oscillator stand-in, and a transport."""

    def __init__(self):
        super().__init__()
        self.transport = dict(SAFE)
        self.engine = True
        self.set_calls = 0
        self.fail_on_set = None  # nth connections.set raises
        self.wires_during_capture: dict[Any, Any] | None = None
        self.after_spawn: Any = None
        self.osc = self.add("Add", "P: osc", 0, 0)
        self.mix = self.add("Multiply", "P: mix", 0, 4)
        self.alt = self.add("Add", "P: alt", 0, 8)
        self.out1 = self.add("Audio Out", "P: out1", 6, 0)
        self.out2 = self.add("Audio Out", "P: out2", 6, 4)
        self.conns[(self.out1, "IN")] = (self.mix, "OUT")
        self.conns[(self.out2, "IN")] = (self.alt, "OUT")
        self.original = dict(self.conns)

    async def call(self, method, params, timeout_ms=5000):
        if method == "transport.get":
            self.calls.append((method, params))
            return {"status": "observed", **self.transport}
        if method == "status":  # engine pre-check
            self.calls.append((method, params))
            return {"status": "observed", "engineActive": self.engine}
        if method == "context.get":  # audio.capture's isolation pre-check
            self.calls.append((method, params))
            return {"tracks": []}
        if method == "transport.set":
            self.calls.append((method, params))
            self.transport.update(params if "playing" in params else {})
            return {"status": "observed"}
        if method == "grid.connections.set":
            self.set_calls += 1
            if self.fail_on_set == self.set_calls:
                self.calls.append((method, params))
                raise BridgeError({"code": "deadline_exceeded", "message": "slow", "outcome": "unknown"})
        return await super().call(method, params, timeout_ms)

    def sets(self):
        return [p for m, p in self.calls if m == "grid.connections.set"]


@pytest.fixture
def rig(tmp_path, monkeypatch):
    pw = FakePW(SINK_LINKS, sink=True)
    monkeypatch.setattr(audio, "run", pw.run)
    monkeypatch.setattr(audio, "check_platform", lambda: None)
    host = ProbeGrid()
    host.wires_during_capture = None
    host.after_spawn = None

    def spawn(args):
        host.wires_during_capture = dict(host.conns)
        NODES[:] = [args[2].split("node.name=")[1].split()[0]]
        if host.after_spawn:
            host.after_spawn()
        return FakeProc(args[-1])

    async def no_sleep(_):
        return None

    monkeypatch.setattr(audio, "spawn", spawn)
    monkeypatch.setattr(audio, "sleep", no_sleep)
    return host, pw


def probe(host, **extra):
    args, timeout = validate_call(
        "grid.probe", {**TARGET, "port": "P: osc.OUT", "seconds": 1, "mode": "onsets", **extra}
    )
    return asyncio.run(grid_probe.probe(host, args, timeout))


def test_registered_client_side_and_validated():
    assert "grid.probe" in CLIENT_SIDE
    for bad in ({"seconds": 0.1}, {"seconds": 21}, {"mode": "other"}, {"port": "x"}):
        with pytest.raises(ValueError, match=re.escape("1 validation error for GridProbeArgs")):
            validate_call("grid.probe", {**TARGET, "port": "P: osc.OUT", "seconds": 1, "mode": "onsets", **bad})
    assert (
        validate_call(
            "grid.probe", {**TARGET, "port": "P: osc.OUT", "seconds": 0.2, "mode": "level", "timeoutMs": 60000}
        )[1]
        == 60000
    )


def test_probe_reroutes_records_and_restores_exactly(rig):
    host, _ = rig
    out = probe(host)
    assert host.conns == host.original
    assert host.transport["playing"] is False
    assert host.wires_during_capture == {
        (host.out1, "IN"): (host.osc, "OUT")
    }  # probe on one Audio Out, the other silenced
    assert out["status"] == "probed"
    assert out["restored"] is True
    assert out["audioOut"] == "P: out1"
    assert out["cablesChanged"] == 2
    assert out["analysis"]["mode"] == "onsets"
    assert out["analysis"]["count"] == 2
    assert "path" not in out["analysis"]
    first, last = host.sets()[0], host.sets()[-1]
    assert len(host.sets()) == 2
    assert len(first["connections"]) == 2
    assert len(last["connections"]) == 2
    # restore is a compare-and-set against the probe wiring
    assert {
        c["targetModuleId"]: c["expectedSource"]["moduleId"] for c in last["connections"] if c["expectedSource"]
    } == {host.out1: host.osc}
    assert ("transport.set", {"playing": False}) in host.calls


def test_probe_uses_the_requested_analysis_mode_and_options(rig):
    host, _ = rig
    out = probe(host, mode="mono_pitch", bpm=120, method="envelope")
    assert out["analysis"]["mode"] == "mono_pitch"
    assert out["analysis"]["method"] == "envelope"
    assert "grid" in out["analysis"]


def test_unsafe_transport_is_refused_before_any_write(rig):
    host, _ = rig
    host.transport["arrangerRecordEnabled"] = True
    with pytest.raises(BridgeError) as e:
        probe(host)
    assert e.value.error["code"] == "unsafe_state"
    assert e.value.error["outcome"] == "not_dispatched"
    assert host.sets() == []
    assert host.transport["playing"] is True
    host.transport["arrangerRecordEnabled"] = None  # unobserved is not safe
    with pytest.raises(BridgeError):
        probe(host)
    assert host.sets() == []


def test_audible_output_is_refused_unless_allowed(rig):
    host, pw = rig
    pw.links = {
        ("Bitwig Studio:out1", "alsa_output.pci:playback_FL"),
        ("Bitwig Studio:out2", "alsa_output.pci:playback_FR"),
    }
    with pytest.raises(BridgeError) as e:
        probe(host)
    assert e.value.error["code"] == "not_silent"
    assert host.sets() == []
    assert host.calls == []
    assert probe(host, allowAudible=True)["capture"]["audible"] is True
    assert host.conns == host.original


@pytest.mark.parametrize(("port", "fragment"), [("P: nope.OUT", "No module named"), ("P: osc.NOPE", "no output port")])
def test_unknown_module_or_port_writes_nothing(rig, port, fragment):
    host, _ = rig
    with pytest.raises(BridgeError) as e:
        probe(host, port=port)
    assert e.value.error["code"] == "invalid_params"
    assert fragment in e.value.error["message"]
    assert host.sets() == []


def test_no_audio_out_is_refused(rig):
    host, _ = rig
    for iid in (host.out1, host.out2):
        host.h_modules_delete({"moduleId": iid})
    with pytest.raises(BridgeError) as e:
        probe(host)
    assert e.value.error["code"] == "invalid_params"
    assert "Audio Out" in e.value.error["message"]


def test_capture_failure_still_restores_and_reports_the_capture_error(rig, monkeypatch):
    host, _ = rig

    def spawn_dead(args):
        NODES.append(args[2].split("node.name=")[1].split()[0])
        return type("Dead", (FakeProc,), {"poll": lambda self: 1})(args[-1])

    monkeypatch.setattr(audio, "spawn", spawn_dead)
    with pytest.raises(BridgeError) as e:
        probe(host)
    assert e.value.error["code"] == "audio_tool_failed"
    assert host.conns == host.original


def test_failed_restore_reports_exactly_what_is_left(rig):
    host, _ = rig
    host.fail_on_set = 2
    with pytest.raises(BridgeError) as e:
        probe(host)
    err = e.value.error
    assert err["code"] == "probe_restore_failed"
    assert err["outcome"] == "unknown"
    assert err["details"]["restoreError"]["code"] == "deadline_exceeded"
    assert err["details"]["left"] == "unknown: reading or writing the graph failed; inspect with grid.graph"
    assert {d["input"]: d["source"] for d in err["details"]["originally"]} == {
        "P: out1.IN": "P: mix.OUT",
        "P: out2.IN": "P: alt.OUT",
    }


def test_restore_leaves_foreign_edits_alone_and_names_them(rig):
    host, _ = rig

    def meanwhile():  # someone rewires out2 while recording
        host.conns[(host.out2, "IN")] = (host.mix, "OUT")

    host.after_spawn = meanwhile
    with pytest.raises(BridgeError) as e:
        probe(host)
    d = e.value.error["details"]
    assert e.value.error["code"] == "probe_restore_failed"
    assert d["changedMeanwhile"] == [{"input": "P: out2.IN", "source": "P: mix.OUT", "wanted": "P: alt.OUT"}]
    assert host.conns[(host.out1, "IN")] == (host.mix, "OUT")  # the cable we did own is back
    assert host.conns[(host.out2, "IN")] == (host.mix, "OUT")  # theirs untouched


def test_partial_rewire_is_restored_from_the_real_state(rig):
    host, _ = rig
    original_set = host.h_connections_set

    def half(p):  # applies the first entry, then reports partial
        original_set({"connections": p["connections"][:1]})
        return {"status": "observed", "batchStatus": "partial", "completion": "complete", "connections": []}

    host.h_connections_set = lambda p: half(p) if host.set_calls == 1 else original_set(p)
    with pytest.raises(BridgeError) as e:
        probe(host)
    assert e.value.error["code"] == "not_applied"
    assert host.conns == host.original


def test_inactive_engine_refuses_before_any_cable_is_touched(rig):
    host, _ = rig
    host.engine = False
    with pytest.raises(BridgeError) as e:
        probe(host)
    assert e.value.error["code"] == "engine_inactive"
    assert e.value.error["outcome"] == "not_applied"
    assert host.sets() == []
    assert host.conns == host.original


def test_probe_bands_mode_reports_balance_and_spectral_peaks(rig):
    host, _ = rig
    out = probe(host, mode="bands", windowSeconds=0.5)
    analysis = out["analysis"]
    assert analysis["mode"] == "bands"
    assert "bandsDb" in analysis["windows"][0]
    assert {"C4", "D4"} <= {p["note"] for p in analysis["peaks"]}
    assert "wavPath" not in out
    assert host.conns == host.original


def test_probe_keeps_the_wav_only_with_outpath(rig, tmp_path):
    host, _ = rig
    wav = tmp_path / "kept.wav"
    out = probe(host, outPath=str(wav))
    assert wav.is_file()
    assert out["wavPath"] == str(wav)
    with pytest.raises(BridgeError) as e:  # refused before any cable moves
        probe(host, outPath=str(wav))
    assert e.value.error["code"] == "file_exists"
    assert e.value.error["outcome"] == "not_dispatched"
    assert host.conns == host.original
    assert probe(host, outPath=str(wav), overwrite=True)["wavPath"] == str(wav)
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridProbeArgs")):
        validate_call("grid.probe", {**TARGET, "port": "P: osc.OUT", "seconds": 1, "mode": "level", "overwrite": True})
    with pytest.raises(ValueError, match=re.escape("1 validation error for GridProbeArgs")):
        validate_call("grid.probe", {**TARGET, "port": "P: osc.OUT", "seconds": 1, "mode": "level", "key": "C:ionian"})


def test_spectral_peaks_flag_low_notes_finer_than_one_bin_as_approximate():

    rate = 48000
    power = [0.0] * (FFT_SIZE // 2 + 1)
    for hz in (55.0, 880.0):
        k = round(hz * FFT_SIZE / rate)
        power[k - 1], power[k], power[k + 1] = 0.5, 1.0, 0.5
    peaks = {round(p["hz"]): p for p in spectral_peaks(power, rate)}
    low = min(peaks.values(), key=lambda p: p["hz"])
    high = max(peaks.values(), key=lambda p: p["hz"])
    assert low.get("approximate") is True
    assert "approximate" not in high
    assert not math.isnan(high["midi"])
