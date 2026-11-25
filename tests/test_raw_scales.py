"""Raw scales: every recorded scale fits its evidence and the bundled schema; display writes convert client-side."""

import math

import pytest

from bitwig_agent import grid_apply, grid_docs, raw_scales
from bitwig_agent.client import BridgeError
from test_grid_apply import TARGET, FakeGrid, run

SCHEMA = grid_apply.bundled_schema()["modules"]


def param(type_name, pid):
    return next(p for p in SCHEMA[type_name]["parameters"] if p["parameterId"] == pid)


def fits(scale, raw, display):
    parsed = raw_scales.parse(scale, display)
    assert parsed is not None, display
    natural = scale.from_raw(raw)
    return parsed[1] - 1e-9 <= natural <= parsed[2] + 1e-9


def entry_scales(entry):
    names = [entry.scale] if isinstance(entry.scale, str) else list(entry.scale["options"].values())
    return [raw_scales.SCALES[n] for n in names]


def test_every_entry_exists_in_the_schema_and_fits_its_evidence():
    for (type_name, pid), entry in raw_scales.RAW_SCALES.items():
        p = param(type_name, pid)
        assert p["kind"] == "float", (type_name, pid)
        for scale in entry_scales(entry):
            for raw, display in entry.evidence:
                assert fits(scale, raw, display), (type_name, pid, raw, display)
        if isinstance(entry.scale, str):
            scale = raw_scales.SCALES[entry.scale]
            shown = raw_scales.parse(scale, p["defaultDisplay"])
            if shown:  # the schema default pair must agree; verified entries must be discriminating (non-zero)
                assert fits(scale, p["default"], p["defaultDisplay"]), (type_name, pid)
            if entry.status == "verified":
                assert shown and abs(shown[0]) > 0, (type_name, pid)


def test_unverified_and_recorded_scales_do_not_overlap_and_alternatives_do_not_fit():
    assert not set(raw_scales.UNVERIFIED) & set(raw_scales.RAW_SCALES)
    assert not fits(raw_scales.SCALES["linear_fraction"], 0.7937, "50 %")
    assert fits(raw_scales.SCALES["cbrt_gain"], 0.7937, "50 %")
    assert not fits(raw_scales.SCALES["cbrt_gain"], 0.5, "50.0 %")  # Chance PROBABILITY is linear
    assert not fits(raw_scales.SCALES["linear_seconds"], 0.033 ** 3 * 0 + 0.5, "125 ms")  # Delay is cbrt, not linear


@pytest.mark.parametrize("module,pid,text,raw", [
    ("Transpose", "VALUE", "−12 st", -12.0),
    ("Sine", "PITCH", "7 st", 7.0),
    ("Sine", "PITCH_MOD", "120 st", 1.0),
    ("Amplify", "VALUE", "50 %", 0.5 ** (1 / 3)),
    ("Amplify", "VALUE", "-6 dB", (10 ** (-6 / 20)) ** (1 / 3)),
    ("Mixer", "LEVEL_2", "-10.3 dB", (10 ** (-10.3 / 20)) ** (1 / 3)),
    ("All-pass Delay", "TIME", "10 ms", 0.01),
    ("Clock", "RATE", "4 Hz", 4.0),
    ("Chance", "PROBABILITY", "25 %", 0.25),
    ("Mod Delay", "FEEDBACK", "30 %", 0.3),  # zero default: no live pair needed
    ("SVF", "RESONANCE", "80 %", 0.8),
    ("Distortion", "DRIVE", "+6 dB", 6.0),
    ("Heat", "DRIVE", "40 dB", 40.0),
    ("Attenuate", "VALUE", "25 %", 0.25),
    ("Value", "VALUE", "50 %", 0.5),
    ("Long Delay", "TIME", "125 ms", 0.5),
    ("Long Delay", "TIME", "8 s", 2.0),
    ("Comb", "DECAY", "1 ms", 0.1),
    ("Audio Out", "HOLD_TIME", "100 ms", 0.1),
    ("Gate Repeat", "RATE", "31.6 ms", math.log10(0.0316)),
    ("Gate Repeat", "RATE", "1 s", 0.0),
    ("Pitch Shift", "PITCH", "-7 st", -7.0),
])
def test_display_writes_convert_to_raw_and_are_idempotent(module, pid, text, raw):
    host = FakeGrid()
    spec = {"target": TARGET, "modules": [{"name": "X", "type": module, "x": 0, "y": 0, "params": {pid: {"display": text}}}]}
    result = run(host, spec)
    assert result["applied"]["paramsWritten"] == 1
    writes = [w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"]]
    assert all("display" not in w and "shown" not in w for w in writes)
    assert host.param("X", pid) == pytest.approx(raw)
    host.calls.clear()
    assert run(host, spec)["status"] == "unchanged" and host.writes() == []


@pytest.mark.parametrize("module,pid,text", [
    ("Transpose", "VALUE", "40 st"),       # raw outside -36..36
    ("Amplify", "VALUE", "900 %"),         # raw 2.08 > 2
    ("Clock", "RATE", "40 Hz"),
    ("Sine", "PITCH", "12 hz"),            # wrong unit
    ("Long Delay", "TIME", "10 s"),       # cbrt 2.15 > 2
    ("Distortion", "DRIVE", "50 dB"),
])
def test_unwritable_display_is_rejected(module, pid, text):
    host = FakeGrid()
    with pytest.raises(BridgeError) as err:
        run(host, {"target": TARGET, "modules": [{"name": "X", "type": module, "x": 0, "y": 0, "params": {pid: {"display": text}}}]})
    assert err.value.error["code"] == "invalid_spec" and host.writes() == []


def test_lfo_rate_follows_timebase():
    def spec(timebase, text):
        params = {"RATE": {"display": text}}
        if timebase is not None:
            params["TIMEBASE"] = timebase
        return {"target": TARGET, "modules": [{"name": "L", "type": "LFO", "x": 0, "y": 0, "params": params}]}
    host = FakeGrid()
    run(host, spec({"label": "Hertz"}, "2 Hz"))
    assert host.param("L", "RATE") == 2.0
    host = FakeGrid()
    run(host, spec(1, "1500 Hz"))
    assert host.param("L", "RATE") == pytest.approx(1.5)
    for timebase in (4, {"label": "Bar"}, None):  # None: the default timebase is quarter notes
        with pytest.raises(BridgeError):
            run(FakeGrid(), spec(timebase, "2 Hz"))


def test_midi_pitch_cutoff_keeps_the_host_display_path():
    host = FakeGrid()
    run(host, {"target": TARGET, "modules": [{"name": "F", "type": "SVF", "x": 0, "y": 0, "params": {"CUTOFF": {"display": "800 Hz"}}}]})
    writes = [w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"]]
    assert writes and writes[0].get("display") == "800 Hz"


def test_describe_exposes_schema_facts_scales_footprint_and_category():
    grid_docs._cache.clear()
    try:
        desc = grid_docs.describe({"type": "Amplify"})
    except grid_docs.DocsUnavailable:
        pytest.skip("Bitwig install not present")
    value = next(p for p in desc["parameters"] if p["id"] == "VALUE")
    assert desc["footprint"] == {"width": 1, "height": 1} and desc["category"]
    assert value["rawScale"]["scale"] == "cbrt_gain" and value["minimum"] == 0.0 and value["default"] == 1.0
    svf = grid_docs.describe({"type": "SVF"})
    assert next(p for p in svf["parameters"] if p["id"] == "TYPE")["options"][0] == {"value": 0, "label": "Low-pass"}
    lag = grid_docs.describe({"type": "Lag"})
    assert next(p for p in lag["parameters"] if p["id"] == "TIME")["rawScale"]["scale"] == "log10_seconds"
    attenuate = grid_docs.describe({"type": "Attenuate"})
    assert next(p for p in attenuate["parameters"] if p["id"] == "VALUE")["rawScale"]["scale"] == "linear_fraction"


def test_query_returns_category_and_finds_filters_by_category():
    try:
        matches = grid_docs.describe({"query": "filter", "limit": 10})["matches"]
    except grid_docs.DocsUnavailable:
        pytest.skip("Bitwig install not present")
    assert all("category" in m for m in matches) and {"SVF", "Low-pass"} <= {m["name"] for m in matches}
