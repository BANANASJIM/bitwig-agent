"""Every composed method: once a step is unknown it may have happened, so next never says to run it again.

A step's method, step name or pronoun may appear in next only in a clause that negates resending it ("never resend",
"do not repeat"); the other steps' finishing calls are still named.
"""

import re

import pytest
import test_batch
import test_configured_insert
import test_file_import
from fakes import SPEC, FakeGrid, apply_grid

from bitwig_agent.errors import unknown_result
from bitwig_agent.protocol.devices import TRACK_REF
from bitwig_agent.protocol.modulators import DEVICE_REF as GRID_DEVICE
from bitwig_agent.protocol.modulators import GRID_PARAMETER_REF

UNKNOWN = unknown_result("not_observed", "No callback.")
RESEND = re.compile(r"finish with|repeat|resend|again|re-apply|retry|delete it with|copy it with", re.IGNORECASE)
NEGATED = re.compile(r"\b(never|do not|not)\b", re.IGNORECASE)
DEVICE = {"to": TRACK_REF, "insert": test_configured_insert.FILTER, "params": {"Cutoff": 0.2}}
MODULATOR = {
    "to": GRID_DEVICE,
    "typeName": "LFO",
    "params": {"Rate": 0},
    "map": [{"target": GRID_PARAMETER_REF, "amount": 0.3}],
}
PAD = {"to": test_configured_insert.DRUMS, "pad": 38, "insert": test_configured_insert.SNARE}
IMPORT = {"to": test_file_import.TO, "path": "/samples/beat.wav"}
BATCH = {"calls": [test_batch.WRITE] * 3}


def composed(method, args, host):
    return lambda: test_configured_insert.call(host, method, args)


def chain_host(**unknown):
    return test_configured_insert.chain_host(unknown.get("chains.insert"), unknown.get("devices.insert"))


def grid(method):
    host = FakeGrid()
    host.fail[method] = UNKNOWN
    return lambda: apply_grid(host, SPEC)


# (case, run, the names next must not tell to run again)
CASES = [
    (
        "devices.insert insert",
        composed("devices.insert", DEVICE, test_configured_insert.Host(**{"devices.insert": UNKNOWN})),
        ("devices.insert",),
    ),
    (
        "devices.insert params",
        composed("devices.insert", DEVICE, test_configured_insert.Host(**{"parameters.set": UNKNOWN})),
        ("parameters.set", "params.Cutoff"),
    ),
    (
        "modulators.insert insert",
        composed("modulators.insert", MODULATOR, test_configured_insert.Host(**{"modulators.insert": UNKNOWN})),
        ("modulators.insert",),
    ),
    (
        "modulators.insert params",
        composed("modulators.insert", MODULATOR, test_configured_insert.Host(**{"parameters.set": UNKNOWN})),
        ("parameters.set", "params.Rate"),
    ),
    (
        "modulators.insert map",
        composed("modulators.insert", MODULATOR, test_configured_insert.Host(**{"modulators.map": UNKNOWN})),
        ("modulators.map", "map[0]"),
    ),
    (
        "chains.insert chain",
        composed("chains.insert", PAD, chain_host(**{"chains.insert": UNKNOWN})),
        ("chains.insert",),
    ),
    (
        "chains.insert fill",
        composed("chains.insert", PAD, chain_host(**{"devices.insert": UNKNOWN})),
        ("devices.insert", "fill"),
    ),
    (
        "clips.import import",
        composed("clips.import", IMPORT, test_file_import.Host(**{"clips.import": UNKNOWN})),
        ("clips.import",),
    ),
    (
        "clips.import copy",
        composed("clips.import", IMPORT, test_file_import.Host(**{"clips.copy": UNKNOWN})),
        ("clips.copy",),
    ),
    (
        "clips.import delete",
        composed("clips.import", IMPORT, test_file_import.Host(**{"clips.delete": UNKNOWN})),
        ("clips.delete",),
    ),
    ("batch", composed("batch", BATCH, test_batch.Host(["unknown", "native", "native"])), ("transport.set", "it")),
    ("grid.apply insert", grid("grid.modules.insert"), ("grid.apply", "spec")),
    ("grid.apply params", grid("grid.parameters.set"), ("grid.apply", "spec")),
]


def resends(next_text, names):
    """The clauses of next that tell to run one of names again without negating it."""
    clauses = re.split(r"\.\s|;\s|\.$", next_text)
    return [
        clause
        for clause in clauses
        if RESEND.search(clause)
        and not NEGATED.search(clause)
        and any(re.search(rf"(?<![\w.]){re.escape(name)}(?![\w])", clause) for name in names)
    ]


@pytest.mark.parametrize(("case", "run", "names"), CASES, ids=[case for case, _, _ in CASES])
def test_an_unknown_step_is_never_named_to_run_again(case, run, names):
    result = run()
    assert result["outcome"] == "unknown", case
    assert resends(result["next"], names) == [], result["next"]


def test_the_check_catches_a_resend():
    assert resends("Not done: fill; finish with devices.insert on the new object.", ("devices.insert",))
    assert resends("Delete it with clips.delete.", ("clips.delete",))
    assert not resends("fill is unknown: inspect with devices.list; never resend it.", ("devices.insert", "fill", "it"))
