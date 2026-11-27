"""The verification rule of an applied result: the leaf rule and the roll-up of a composed result."""

import pytest

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
