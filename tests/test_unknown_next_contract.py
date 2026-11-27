"""Every composed write: once a step is unknown it may have happened, so next never says to run it again.

next may name an unknown step (its step name or method) only where a negation governs that name ("never resend X",
"do not repeat X or Y") or as the subject of a status ("X is unknown", "Call 1 (X) stopped"). Any other mention, an
instruction in whatever words, is a violation. A method another open step still needs ("finish with parameters.set"
for a parameter not reached) is not the unknown step's own and stays allowed.
"""

import re

import pytest
import test_batch
import test_configured_insert
import test_file_import
from fakes import SPEC, FakeGrid, apply_grid

from bitwig_agent.core.composed import HANDLERS
from bitwig_agent.errors import unknown_result
from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.base import SUCCEEDED, Outcome
from bitwig_agent.protocol.devices import TRACK_REF
from bitwig_agent.protocol.modulators import DEVICE_REF as GRID_DEVICE
from bitwig_agent.protocol.modulators import GRID_PARAMETER_REF

UNKNOWN = unknown_result("not_observed", "No callback.")
DEVICE = {"to": TRACK_REF, "insert": test_configured_insert.FILTER, "params": {"Cutoff": 0.2}}
TWO_PARAMS = {**DEVICE, "params": {"Cutoff": 0.2, "Resonance": 1}}
MODULATOR = {
    "to": GRID_DEVICE,
    "typeName": "LFO",
    "params": {"Rate": 0},
    "map": [{"target": GRID_PARAMETER_REF, "amount": 0.3}],
}
PAD = {"to": test_configured_insert.DRUMS, "pad": 38, "insert": test_configured_insert.SNARE}
IMPORT = {"to": test_file_import.TO, "path": "/samples/beat.wav"}
STEP_METHOD = {
    "fill": "devices.insert",
    "chain": "chains.insert",
    "import": "clips.import",
    "copy": "clips.copy",
    "delete": "clips.delete",
}
EXEMPT = {
    "grid.probe": "an unknown result only for a restore whose leftover cables it reads back and lists",
    "audio.capture": "an unknown result only for a solo restore that failed, read back and listed to restore",
    "audio.route": "pw-link writes with no steps or next of their own",
}


def step_method(method, step):
    if step.startswith("params."):
        return "parameters.set"
    if step.startswith("map["):
        return "modulators.map"
    return method if step == "insert" else STEP_METHOD[step]


def unknown_names(method, args, result):
    """The names of the unknown steps of a result: step name, and method unless another open step still needs it."""
    if method == "batch":
        stop = next(i for i, e in enumerate(result["entries"]) if e.get("outcome") == Outcome.UNKNOWN)
        call = args["calls"][stop]
        inner = (
            unknown_names(call["method"], call["params"], result["entries"][stop])
            if "entries" in result["entries"][stop]
            else []
        )
        return [call["method"], "it", *inner]
    if "entries" not in result:
        return [method]
    open_steps = [e for e in result["entries"] if Outcome(e["outcome"]) not in SUCCEEDED]
    needed = {step_method(method, e["step"]) for e in open_steps if e["outcome"] != Outcome.UNKNOWN}
    names = []
    for entry in open_steps:
        if entry["outcome"] == Outcome.UNKNOWN:
            own = step_method(method, entry["step"])
            names += [entry["step"], *([own] if own not in needed else [])]
    return names


def mentions(name):
    return rf"(?<![\w.\[]){re.escape(name)}(?![\w\[])"


def resends(text, names):
    """The names of unknown steps next mentions other than negated or as a status subject."""
    found = []
    for name in names:
        allowed = [
            m.span()
            for pattern in (
                rf"\b(?:never|do not|don't)\s+[\w-]+(?:\s+(?:it|them))?\s+(?:\S+\s+or\s+)*{mentions(name)}",
                rf"{mentions(name)}\)?\s+(?:is unknown|stopped|step said|applied)",
            )
            for m in re.finditer(pattern, text, re.IGNORECASE)
        ]
        found += [
            name
            for hit in re.finditer(mentions(name), text, re.IGNORECASE)
            if not any(start <= hit.start() < end for start, end in allowed)
        ]
    return found


def composed(method, args, host):
    """A case: the method, its args and a run on a fresh fake host (host is a factory: a run consumes its answers)."""
    return method, args, lambda: test_configured_insert.call(host(), method, args)


def inserts(method, args, **answers):
    return composed(method, args, lambda: test_configured_insert.Host(**answers))


def pad(**unknown):
    return composed(
        "chains.insert",
        PAD,
        lambda: test_configured_insert.chain_host(unknown.get("chains.insert"), unknown.get("devices.insert")),
    )


def imports(**answers):
    return composed("clips.import", IMPORT, lambda: test_file_import.Host(**answers))


def grid(method):
    host = FakeGrid()
    host.fail[method] = UNKNOWN
    return "grid.apply", SPEC, lambda: apply_grid(host, SPEC)


BATCH_OF_INSERT = {"calls": [{"method": "devices.insert", "params": DEVICE}, test_batch.WRITE]}
CASES = {
    "devices.insert insert": inserts("devices.insert", DEVICE, **{"devices.insert": UNKNOWN}),
    "devices.insert params": inserts("devices.insert", DEVICE, **{"parameters.set": UNKNOWN}),
    "devices.insert unknown param then a param not run": inserts(
        "devices.insert", TWO_PARAMS, **{"parameters.set": UNKNOWN}
    ),
    "modulators.insert insert": inserts("modulators.insert", MODULATOR, **{"modulators.insert": UNKNOWN}),
    "modulators.insert params": inserts("modulators.insert", MODULATOR, **{"parameters.set": UNKNOWN}),
    "modulators.insert map": inserts("modulators.insert", MODULATOR, **{"modulators.map": UNKNOWN}),
    "chains.insert chain": pad(**{"chains.insert": UNKNOWN}),
    "chains.insert fill": pad(**{"devices.insert": UNKNOWN}),
    "clips.import import": imports(**{"clips.import": UNKNOWN}),
    "clips.import copy": imports(**{"clips.copy": UNKNOWN}),
    "clips.import delete": imports(**{"clips.delete": UNKNOWN}),
    "batch": composed(
        "batch", {"calls": [test_batch.WRITE] * 3}, lambda: test_batch.Host(["unknown", "native", "native"])
    ),
    "batch stopped by a composed call": composed(
        "batch", BATCH_OF_INSERT, lambda: test_configured_insert.Host(**{"parameters.set": UNKNOWN})
    ),
    "grid.apply insert": grid("grid.modules.insert"),
    "grid.apply params": grid("grid.parameters.set"),
}


def test_every_composed_write_has_a_case_or_a_reason():
    writes = {name for name in HANDLERS if not TOOLS[name].read_only}
    assert writes == {method for method, _, _ in CASES.values()} | EXEMPT.keys()


@pytest.mark.parametrize("case", CASES)
def test_an_unknown_step_is_never_named_to_run_again(case):
    method, args, run = CASES[case]
    result = run()
    assert result["outcome"] == "unknown", case
    names = unknown_names(method, args, result)
    assert names, case
    assert resends(result["next"], names) == [], result["next"]


def test_a_not_run_step_of_the_unknown_steps_method_is_still_finished():
    method, args, run = CASES["devices.insert unknown param then a param not run"]
    result = run()
    assert unknown_names(method, args, result) == ["params.Cutoff"]
    assert "finish with parameters.set" in result["next"]


@pytest.mark.parametrize(
    "text",
    [
        "Re-run X.",
        "Rerun X.",
        "Re-send X.",
        "Then call X.",
        "Send X with the same params.",
        "Run X once more.",
        "Redo X.",
        "Reissue X.",
        "Finish the job with X.",
        "Complete it with X.",
        "Use X to place it.",
        "If it is not on the arranger, resend X.",
        "never ack blindly, then resend X.",
        "The file is in the clip, not on the arranger: copy it with X.",
    ],
)
def test_the_check_catches_a_resend_in_any_words(text):
    assert resends(text, ["X"]) == ["X"]


@pytest.mark.parametrize(
    "text",
    ["never resend X.", "do not repeat X.", "Never resend clips.copy or X.", "X is unknown: inspect it first."],
)
def test_the_check_allows_a_negated_or_status_mention(text):
    assert resends(text, ["X"]) == []
