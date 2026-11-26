import ast
import sys
from pathlib import Path

import pytest

PROTOCOL = Path(__file__).resolve().parents[1] / "src" / "bitwig_agent" / "protocol"
OWN = "bitwig_agent.protocol"
ALLOWED_THIRD_PARTY = {"pydantic"}


def imported_modules(path: Path) -> set[str]:
    modules = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            modules |= {alias.name for alias in node.names}
        elif isinstance(node, ast.ImportFrom):
            modules.add(f"{OWN}.{node.module or ''}" if node.level else node.module or "")
    return modules


@pytest.mark.parametrize("path", sorted(PROTOCOL.glob("*.py")), ids=lambda p: p.name)
def test_protocol_imports_only_stdlib_pydantic_and_itself(path):
    for name in imported_modules(path):
        own = name == OWN or name.startswith(f"{OWN}.")
        top = name.split(".")[0]
        assert own or top in ALLOWED_THIRD_PARTY or top in sys.stdlib_module_names, f"{path.name} imports {name}"


FRONTENDS = PROTOCOL.parent / "frontends"


@pytest.mark.parametrize("path", sorted(FRONTENDS.glob("[!_]*.py")), ids=lambda p: p.name)
def test_frontends_import_only_core_among_internal_modules(path):
    for name in imported_modules(path):
        if name.split(".")[0] == "bitwig_agent":
            assert name == "bitwig_agent.core" or name.startswith("bitwig_agent.core."), f"{path.name} imports {name}"
