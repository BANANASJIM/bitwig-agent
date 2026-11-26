"""Raw scales: every recorded scale fits its evidence and the bundled schema; display writes convert client-side."""

import math

import pytest
from fakes import DEVICE, FakeGrid, apply_grid, assert_display_rejected

from bitwig_agent.errors import BridgeError
from bitwig_agent.grid import bundled, raw_scales
from bitwig_agent.grid import docs as grid_docs

SCHEMA = bundled.bundled_schema()["modules"]


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
                assert shown, (type_name, pid)
                assert abs(shown[0]) > 0, (type_name, pid)


def test_unverified_and_recorded_scales_do_not_overlap_and_alternatives_do_not_fit():
    assert not set(raw_scales.UNVERIFIED) & set(raw_scales.RAW_SCALES)
    assert not fits(raw_scales.SCALES["linear_fraction"], 0.7937, "50 %")
    assert fits(raw_scales.SCALES["cbrt_gain"], 0.7937, "50 %")
    assert not fits(raw_scales.SCALES["cbrt_gain"], 0.5, "50.0 %")  # Chance PROBABILITY is linear
    assert not fits(raw_scales.SCALES["linear_seconds"], 0.033**3 * 0 + 0.5, "125 ms")  # Delay is cbrt, not linear


@pytest.mark.parametrize(
    ("module", "pid", "text", "raw"),
    [
        ("Transpose", "VALUE", "\u221212 st", -12.0),
        ("Sine", "PITCH", "7 st", 7.0),
        ("Sine", "PITCH_MOD", "120 st", 1.0),
        ("Sine", "PITCH_MOD", "15 st", 0.5),  # oscillator PITCH_MOD is cube-root
        ("Comb", "CUTOFF_MOD", "60 st", 0.5),  # filter CUTOFF_MOD is linear
        ("SVF", "CUTOFF_MOD", "70.18 st", 0.584833),
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
    ],
)
def test_display_writes_convert_to_raw_and_are_idempotent(module, pid, text, raw):
    host = FakeGrid()
    spec = {
        "target": DEVICE,
        "modules": [{"name": "X", "type": module, "x": 0, "y": 0, "params": {pid: {"display": text}}}],
    }
    result = apply_grid(host, spec)
    assert result["applied"]["paramsWritten"] == 1
    writes = [w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"]]
    assert all("display" not in w and "shown" not in w for w in writes)
    assert host.param("X", pid) == pytest.approx(raw)
    host.calls.clear()
    assert apply_grid(host, spec)["outcome"] == "already_matched"
    assert host.writes() == []


@pytest.mark.parametrize(
    ("module", "pid", "text"),
    [
        ("Transpose", "VALUE", "40 st"),  # raw outside -36..36
        ("Amplify", "VALUE", "900 %"),  # raw 2.08 > 2
        ("Clock", "RATE", "40 Hz"),
        ("Sine", "PITCH", "12 hz"),  # wrong unit
        ("Long Delay", "TIME", "10 s"),  # cbrt 2.15 > 2
        ("Distortion", "DRIVE", "50 dB"),
    ],
)
def test_unwritable_display_is_rejected(module, pid, text):
    assert_display_rejected(module, pid, text)


def test_lfo_rate_follows_timebase():
    def spec(timebase, text):
        params = {"RATE": {"display": text}}
        if timebase is not None:
            params["TIMEBASE"] = timebase
        return {"target": DEVICE, "modules": [{"name": "L", "type": "LFO", "x": 0, "y": 0, "params": params}]}

    host = FakeGrid()
    apply_grid(host, spec({"label": "Hertz"}, "2 Hz"))
    assert host.param("L", "RATE") == 2.0
    host = FakeGrid()
    apply_grid(host, spec(1, "1500 Hz"))
    assert host.param("L", "RATE") == pytest.approx(1.5)
    for timebase in (4, {"label": "Bar"}, None):  # None: the default timebase is quarter notes
        with pytest.raises(BridgeError):
            apply_grid(FakeGrid(), spec(timebase, "2 Hz"))


def test_midi_pitch_cutoff_display_is_converted_client_side():
    host = FakeGrid()
    apply_grid(
        host,
        {
            "target": DEVICE,
            "modules": [{"name": "F", "type": "SVF", "x": 0, "y": 0, "params": {"CUTOFF": {"display": "800 Hz"}}}],
        },
    )
    writes = [w for m, c in host.calls if m == "grid.parameters.set" for w in c["writes"]]
    assert writes[0]["value"] == pytest.approx(69 + 12 * math.log2(800 / 440))
    assert "display" not in writes[0]


def test_describe_exposes_schema_facts_scales_footprint_and_category():
    grid_docs._cache.clear()
    try:
        desc = grid_docs.describe({"type": "Amplify"})
    except grid_docs.DocsUnavailable:
        pytest.skip("Bitwig install not present")
    value = next(p for p in desc["parameters"] if p["id"] == "VALUE")
    assert desc["footprint"] == {"width": 1, "height": 1}
    assert desc["category"]
    assert value["rawScale"]["scale"] == "cbrt_gain"
    assert value["minimum"] == 0.0
    assert value["default"] == 1.0
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
    assert all("category" in m for m in matches)
    assert {"SVF", "Low-pass"} <= {m["name"] for m in matches}


def test_filter_cutoff_mod_is_linear_and_oscillator_pitch_mod_is_cube_root():
    cutoff = raw_scales.SCALES["linear_st120"]
    assert raw_scales.RAW_SCALES[("Comb", "CUTOFF_MOD")].scale == "linear_st120"
    assert raw_scales.RAW_SCALES[("Sine", "PITCH_MOD")].scale == "cbrt_st120"
    assert fits(cutoff, 0.5, "60.00 st")
    assert fits(cutoff, 0.584804, "70.18 st")
    assert not fits(raw_scales.SCALES["cbrt_st120"], 0.5, "60.00 st")
    assert fits(raw_scales.SCALES["cbrt_st120"], 0.5, "15.00 st")
    for t in ("SVF", "Low-pass LD", "Low-pass MG", "Sallen-Key", "XP", "Rasp", "Ripple", "Comb", "Fizz"):
        assert raw_scales.RAW_SCALES[(t, "CUTOFF_MOD")].scale == "linear_st120", t
    assert raw_scales.convert("Comb", "CUTOFF_MOD", "60 st", (-1.0, 1.0)) == 0.5
    assert raw_scales.effective(cutoff, 0.5) == "60 st"
