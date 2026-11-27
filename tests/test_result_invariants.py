"""The verification rule of an applied result: the leaf rule and the roll-up of a composed result."""

import inspect
from collections.abc import Callable

import pytest
import test_audio_pipewire
import test_batch
import test_configured_insert
import test_file_import
import test_grid_apply
import test_grid_probe
import test_grid_render

from bitwig_agent.protocol import COMPOSED, TOOLS
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
    # an applied entry without its own verification (fields that disagree) agrees with nothing
    assert shared_verification([NATIVE, {"outcome": "applied", "fields": {"a": NATIVE, "b": API}}]) is None


# Client-composed methods whose result carries an outcome -> the test that runs one on a fake host and checks the leaf
# rule. Host methods are checked on the Java fixture (ResultContractTest); the composed insert-and-configure steps here.
CHECKED: dict[str, Callable[..., object]] = {
    "grid.apply": test_grid_apply.test_fresh_build_then_idempotent_reapply_writes_nothing,
    "grid.probe": test_grid_probe.test_probe_reroutes_records_and_restores_exactly,
    "grid.render": test_grid_render.test_fixture_pages_completely_and_renders_wellformed_svg,
    "audio.capture": test_audio_pipewire.test_capture_from_start_orders_transport_and_reports_levels,
    "audio.route": test_audio_pipewire.test_route_outcomes,
    "devices.insert": test_configured_insert.test_a_later_failure_keeps_the_ref_and_says_how_to_finish_without_reinserting,
    "modulators.insert": test_configured_insert.test_modulators_insert_sets_controls_then_maps_from_the_new_modulator,
    "chains.insert": test_configured_insert.test_chains_insert_fills_the_new_pad_chain_with_the_sample,
    "clips.import": test_file_import.test_an_arranger_import_goes_through_the_first_empty_slot_then_copies_and_deletes,
    "batch": test_batch.test_n_calls_give_n_entries_in_order_and_nothing_runs_after_the_stop,
}


def test_every_client_side_write_has_an_example_checked_for_the_leaf_rule():
    # grid.render is read-only toward Bitwig, but it writes its image file and reports that outcome
    writes = {name for name in COMPOSED if not TOOLS[name].read_only} | {"grid.render"}
    assert writes == CHECKED.keys()
    for method, test in CHECKED.items():
        assert "leaf_rule_holds(" in inspect.getsource(test), method
