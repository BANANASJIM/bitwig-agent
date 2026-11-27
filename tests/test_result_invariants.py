"""The verification rule of an applied result: the leaf rule and the roll-up of a composed result."""

import inspect
from collections.abc import Callable

import pytest
import test_audio_pipewire
import test_grid_apply
import test_grid_probe
import test_grid_render

from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.base import Verification, leaf_rule_holds, shared_verification

NATIVE = {"outcome": "applied", "verification": "native_readback"}
API = {"outcome": "applied", "verification": "api_readback"}


@pytest.mark.parametrize(
    "node",
    [
        NATIVE,
        {"outcome": "already_matched"},
        {"outcome": "unknown", "reason": "not_observed"},
        {"tracks": []},
        {"outcome": "applied", "id": "x", "released": "hold"},
        {"outcome": "applied", "fields": {"enabled": NATIVE}},
        {"outcome": "applied", "writes": [NATIVE, API]},
    ],
)
def test_the_leaf_rule_accepts(node):
    assert leaf_rule_holds(node)


@pytest.mark.parametrize(
    "node",
    [
        {"outcome": "applied"},
        {"outcome": "applied", "fields": {"enabled": {"outcome": "applied"}}},
        {"outcome": "applied", "verification": "api_readback", "entries": [NATIVE, {"outcome": "applied"}]},
    ],
)
def test_the_leaf_rule_rejects_an_applied_leaf_without_verification(node):
    assert not leaf_rule_holds(node)


def test_a_composed_result_repeats_the_verification_its_applied_entries_share():
    assert shared_verification([NATIVE, NATIVE, {"outcome": "already_matched"}]) is Verification.NATIVE_READBACK
    assert shared_verification([NATIVE, API]) is None
    assert shared_verification([{"outcome": "already_matched"}]) is None


# Client-side methods whose result carries an outcome -> the test that runs one on a fake host and checks the leaf rule.
# Host methods are checked on the Java fixture (ResultContractTest).
CHECKED: dict[str, Callable[..., object]] = {
    "grid.apply": test_grid_apply.test_fresh_build_then_idempotent_reapply_writes_nothing,
    "grid.probe": test_grid_probe.test_probe_reroutes_records_and_restores_exactly,
    "grid.render": test_grid_render.test_fixture_pages_completely_and_renders_wellformed_svg,
    "audio.capture": test_audio_pipewire.test_capture_from_start_orders_transport_and_reports_levels,
    "audio.route": test_audio_pipewire.test_route_outcomes,
}


def test_every_client_side_write_has_an_example_checked_for_the_leaf_rule():
    # grid.render is read-only toward Bitwig, but it writes its image file and reports that outcome
    writes = {name for name, spec in TOOLS.items() if not spec.host and not spec.read_only} | {"grid.render"}
    assert writes == CHECKED.keys()
    for method, test in CHECKED.items():
        assert "leaf_rule_holds(" in inspect.getsource(test), method
