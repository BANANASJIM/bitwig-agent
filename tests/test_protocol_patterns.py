import re
from typing import Annotated, Any

import pytest
from pydantic import Field, TypeAdapter, ValidationError

from bitwig_agent.protocol.bundle import export_bundle


def patterns(node: Any) -> set[str]:
    found: set[str] = set()
    if isinstance(node, dict):
        if isinstance(node.get("pattern"), str):
            found.add(node["pattern"])
        for value in node.values():
            found |= patterns(value)
    elif isinstance(node, list):
        for value in node:
            found |= patterns(value)
    return found


PATTERNS = sorted(set().union(*(patterns(spec["inputSchema"]) for spec in export_bundle()["methods"].values())))


@pytest.mark.parametrize("pattern", PATTERNS)
def test_every_exported_pattern_compiles_in_both_python_engines(pattern):
    re.compile(pattern)
    TypeAdapter(Annotated[str, Field(pattern=pattern)])


@pytest.mark.parametrize("pattern", PATTERNS)
def test_dollar_means_end_of_text_for_pydantic(pattern):
    sample = next((s for s in SAMPLES if re.search(pattern, s)), None)
    assert sample is not None, "add a sample that matches the new pattern"
    adapter = TypeAdapter(Annotated[str, Field(pattern=pattern)])
    assert adapter.validate_python(sample) == sample
    with pytest.raises(ValidationError):
        adapter.validate_python(sample + "\n")


SAMPLES = (
    "12345678-1234-1234-1234-123456789abc",
    "#00ffAA",
    "0" * 64,
    "C:dorian",
    "${name}",
)
