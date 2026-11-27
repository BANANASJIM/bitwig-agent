import dataclasses
import re

import pytest

from bitwig_agent.protocol.capabilities import (
    CAPABILITIES,
    CHAPTERS,
    NO_CAPABILITY,
    PARTIAL,
    TAXONOMY,
    Partial,
    check_capabilities,
)
from bitwig_agent.protocol.registry import TOOLS

ID = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+){1,2}$")
HIGH = [capability for capability in TAXONOMY if capability.tier == "high"]


def test_capability_ids_are_unique_well_formed_and_in_guide_chapter_order():
    ids = [capability.id for capability in TAXONOMY]
    assert len(ids) == len(set(ids))
    assert all(ID.match(id_) for id_ in ids)
    chapters = [id_.split(".")[0] for id_ in ids]
    assert sorted(chapters, key=list(CHAPTERS).index) == chapters
    assert set(chapters) == CHAPTERS.keys()


def test_the_headline_counts_116_high_tier_capabilities_merging_145_audit_rows():
    assert len(HIGH) == 116
    assert sum(capability.weight for capability in HIGH) == 145


def test_every_exposed_method_is_tagged_and_only_infrastructure_does_no_producer_task():
    exposed = {name for name, spec in TOOLS.items() if spec.expose}
    assert CAPABILITIES.keys() == exposed
    none = sorted(name for name, ids in CAPABILITIES.items() if ids == NO_CAPABILITY)
    assert none == [
        "ack",
        "audio.analyze",
        "audio.capture",
        "audio.route",
        "batch",
        "diagnostics.get",
        "grid.probe",
        "jobs.get",
    ]


def test_an_untagged_exposed_method_fails_the_registry_with_the_line_to_add():
    specs = {**TOOLS, "tracks.fold": dataclasses.replace(TOOLS["tracks.select"])}
    with pytest.raises(ValueError, match="without a capability tag") as error:
        check_capabilities(specs, CAPABILITIES, PARTIAL)
    assert '"tracks.fold": ("<capability id>",),' in str(error.value)
    assert "src/bitwig_agent/protocol/capabilities.py" in str(error.value)


def test_tags_must_name_exposed_methods_and_known_capabilities():
    hidden = {**TOOLS, "tracks.select": dataclasses.replace(TOOLS["tracks.select"], expose=False)}
    with pytest.raises(ValueError, match="not exposed"):
        check_capabilities(hidden, CAPABILITIES, PARTIAL)
    with pytest.raises(ValueError, match="not in TAXONOMY"):
        check_capabilities(TOOLS, {**CAPABILITIES, "tracks.select": ("arrange.tracks.fold",)}, PARTIAL)
    with pytest.raises(ValueError, match="not in TAXONOMY"):
        check_capabilities(TOOLS, CAPABILITIES, {**PARTIAL, "arrange.tracks.fold": Partial("scope", "x")})
    with pytest.raises(ValueError, match="no method does"):
        check_capabilities(TOOLS, CAPABILITIES, {**PARTIAL, "audio.fades": Partial("fields", "x")})
