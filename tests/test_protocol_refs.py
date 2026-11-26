import typing
from typing import Any

import pytest
from pydantic import TypeAdapter, ValidationError

from bitwig_agent.core.invoke import invalid_params
from bitwig_agent.protocol import TOOLS, export_bundle, invalid_params_reason
from bitwig_agent.protocol.positions import DeviceDestination, DevicePosition, TrackDestination, TrackPosition
from bitwig_agent.protocol.refs import (
    PARAMETER_VARIANTS,
    READS,
    ROLES,
    AnyRef,
    AutomationParameterRef,
    MappingParameterRef,
    RemoteSlotParameterRef,
    SetParameterRef,
)

TRACK = {"kind": "track", "id": "8f2c0000-0000-4000-8000-000000000001", "name": "Lead"}
DEVICE = {"kind": "device", "track": TRACK, "index": 1, "name": "EQ+"}
UUID = "c41d0000-0000-4000-8000-000000000002"
CHAIN = {"kind": "chain", "device": DEVICE, "id": UUID, "name": "Pad"}
SCENE = {"kind": "scene", "index": 33, "name": "Drop"}
PAGE = {"kind": "remotePage", "device": DEVICE, "index": 0, "name": "Main"}
MODULE = {"kind": "gridModule", "device": DEVICE, "id": UUID, "name": "F: lp"}
MODULATOR = {"kind": "modulator", "device": DEVICE, "id": UUID, "name": "LFO"}
OWNERS = {"device": DEVICE, "remotePage": PAGE, "modulator": MODULATOR, "gridModule": MODULE, "track": TRACK}
EXAMPLES = [
    TRACK,
    SCENE,
    {"kind": "scene", "index": 0, "name": None},
    {"kind": "slot", "track": TRACK, "scene": SCENE},
    {"kind": "clip", "track": TRACK, "slot": {"scene": SCENE}, "name": "Hook"},
    {"kind": "clip", "track": TRACK, "lane": "main", "startBeats": 64, "name": "Hook"},
    DEVICE,
    CHAIN,
    {"kind": "device", "chain": CHAIN, "index": 0, "name": "Polysynth"},
    PAGE,
    {"kind": "parameter", "owner": DEVICE, "id": "CONTENTS/GAIN", "name": "Gain"},
    {"kind": "parameter", "owner": PAGE, "id": "2", "name": "Gain 1"},
    {"kind": "parameter", "owner": MODULATOR, "id": "RATE"},
    {"kind": "parameter", "owner": MODULE, "id": "CUTOFF"},
    {"kind": "parameter", "owner": TRACK, "id": "volume"},
    {"kind": "send", "track": TRACK, "index": 0, "name": "Reverb"},
    {"kind": "sidechain", "device": DEVICE, "index": 0, "id": "component", "modulatorId": UUID},
    MODULATOR,
    MODULE,
    {"kind": "marker", "index": 2, "name": "Verse", "beat": 16},
]


def members(alias):
    """The models of a tagged-union alias."""
    inner = typing.get_args(alias)[0]
    if isinstance(inner, type):
        return [inner]
    return [typing.get_args(option)[0] for option in typing.get_args(inner)]


VARIANTS = members(AnyRef)
# Section 1.3 of the P2 method table: accepted owners, and the method a refused owner is sent to.
OWNER_MATRIX: dict[Any, tuple[set[str], dict[str, str]]] = {
    SetParameterRef: ({"device", "remotePage", "modulator"}, {"gridModule": "grid.apply", "track": "tracks.set"}),
    AutomationParameterRef: ({"track", "gridModule"}, {}),
    MappingParameterRef: ({"gridModule", "modulator"}, {}),
    RemoteSlotParameterRef: ({"gridModule"}, {}),
}


def test_every_kind_has_examples_that_validate_and_come_back_unchanged():
    adapter = TypeAdapter(AnyRef)
    tags = set()
    for example in EXAMPLES:
        ref = adapter.validate_python(example)
        tags.add(type(ref).TAG)
        assert adapter.dump_python(ref, exclude_unset=True) == example
    assert tags == {variant.TAG for variant in VARIANTS}


def test_the_roles_of_a_kind_are_exactly_the_fields_of_its_variants():
    fields: dict[str, set[str]] = {}
    for variant in VARIANTS:
        kind = typing.get_args(variant.model_fields["kind"].annotation)[0]
        fields.setdefault(kind, set()).update(variant.model_fields)
    assert {kind: set(roles.fields()) for kind, roles in ROLES.items()} == fields


@pytest.mark.parametrize(
    "ref",
    [
        {"kind": "galaxy"},
        {"kind": "track", "id": "not-a-uuid", "name": "Lead"},
        {"kind": "device", "track": TRACK, "chain": CHAIN, "index": 0, "name": "Both"},
        {"kind": "clip", "track": TRACK, "slot": {"scene": SCENE}, "lane": "main", "name": "Both"},
        {"kind": "parameter", "owner": PAGE, "id": "8", "name": "Slot 8"},
        {"kind": "parameter", "owner": PAGE, "id": "2"},
        {"kind": "parameter", "owner": TRACK, "id": "mute"},
        {"kind": "scene", "index": 0},
    ],
)
def test_malformed_refs_are_rejected(ref):
    with pytest.raises(ValidationError):
        TypeAdapter(AnyRef).validate_python(ref)


def test_each_method_takes_only_its_owners_and_names_where_the_others_go():
    for alias, (accepted, next) in OWNER_MATRIX.items():
        assert {key for key, model in PARAMETER_VARIANTS.items() if model in members(alias)} == accepted
        adapter: TypeAdapter[Any] = TypeAdapter(alias)
        for owner in PARAMETER_VARIANTS:
            ref = {"kind": "parameter", "owner": OWNERS[owner], "id": "0", "name": "x"}
            if owner == "track":
                ref = {"kind": "parameter", "owner": TRACK, "id": "volume"}
            if owner in accepted:
                assert adapter.validate_python(ref).owner.kind == owner
                continue
            with pytest.raises(ValidationError) as rejected:
                adapter.validate_python(ref)
            assert invalid_params_reason(rejected.value) == ("unsupported_scope", next.get(owner))
    schema = TypeAdapter(RemoteSlotParameterRef).json_schema()
    assert [branch["$ref"] for branch in schema["oneOf"]] == ["#/$defs/GridParameterRef"]
    with pytest.raises(ValidationError) as malformed:
        TypeAdapter(SetParameterRef).validate_python({"kind": "parameter", "owner": DEVICE})
    assert invalid_params_reason(malformed.value) == ("invalid_params", None), "only an owner refusal is a scope error"
    nested = {"kind": "parameter", "owner": {**PAGE, "device": {"kind": "galaxy"}}, "id": "0", "name": "x"}
    with pytest.raises(ValidationError) as inside:
        TypeAdapter(SetParameterRef).validate_python(nested)
    assert invalid_params_reason(inside.value) == ("invalid_params", None), "a malformed ref inside an accepted owner"


def test_an_owner_refusal_reaches_the_caller_as_unsupported_scope_with_next():
    with pytest.raises(ValidationError) as rejected:
        TypeAdapter(SetParameterRef).validate_python({"kind": "parameter", "owner": TRACK, "id": "pan"})
    error = invalid_params("parameters.set", rejected.value).error
    assert (error["code"], error["reason"], error["next"]) == ("invalid_params", "unsupported_scope", "tracks.set")
    assert invalid_params_reason(ValueError("plain")) == ("invalid_params", None)


def test_the_bundle_carries_every_kinds_roles_and_the_ref_schema():
    refs = export_bundle()["refs"]
    assert refs["roles"]["device"] == {"locate": ["track", "chain", "index"], "compared": ["name"], "targetOnly": []}
    assert set(refs["roles"]) == set(ROLES)
    assert "discriminator" not in str(refs["schema"]), "the Java schema validator has no discriminator keyword"


def test_every_variant_names_the_read_that_returns_it():
    pending = {"chains.list"}  # P2-D renames devices.layers.list
    assert set(READS) == {variant.TAG for variant in VARIANTS}
    assert set(READS.values()) - set(TOOLS) == pending
    assert export_bundle()["refs"]["parameterOwners"] == list(PARAMETER_VARIANTS)


def test_positions_are_an_edge_or_an_anchor_ref():
    position: TypeAdapter[Any] = TypeAdapter(DevicePosition)
    assert position.validate_python("start") == "start"
    assert position.validate_python({"after": DEVICE}).after.index == 1
    for wrong in ("middle", {"before": TRACK}, {"before": DEVICE, "after": DEVICE}, 2):
        with pytest.raises(ValidationError):
            position.validate_python(wrong)
    track_position: TypeAdapter[Any] = TypeAdapter(TrackPosition)
    assert track_position.validate_python({"before": TRACK}).before.id == TRACK["id"]
    moved = DeviceDestination.model_validate({"parent": CHAIN, "position": "end"})
    assert moved.parent.kind == "chain"
    assert "parent" not in DeviceDestination.model_validate({"position": "end"}).model_fields_set
    assert TrackDestination.model_validate({"position": {"after": TRACK}}).position


def test_ref_name_limit_counts_utf16_units():
    adapter = TypeAdapter(AnyRef)
    adapter.validate_python({**TRACK, "name": "\U0001f600" * 2048})
    with pytest.raises(ValidationError, match="UTF-16"):
        adapter.validate_python({**TRACK, "name": "\U0001f600" * 2049})
    adapter.validate_python({**SCENE, "name": "\U0001f600" * 2048})
    with pytest.raises(ValidationError, match="UTF-16"):
        adapter.validate_python({**SCENE, "name": "\U0001f600" * 2049})
