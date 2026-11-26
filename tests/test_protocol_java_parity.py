"""Temporary: the Java extension still owns its own method tables; they must equal the Python specs until it reads the bundle."""

import re
from pathlib import Path

from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.base import MAX_TIMEOUT_MS, TOPOLOGY_TIMEOUT_MS

JAVA = Path(__file__).resolve().parents[1] / "extension/src/main/java/dev/bitwigagent"
HOST_METHODS = {name: spec for name, spec in TOOLS.items() if spec.host}


def source(name: str) -> str:
    return (JAVA / f"{name}.java").read_text(encoding="utf-8")


def string_set(name: str, constant: str) -> set[str]:
    text = source(name)
    body = re.search(rf"\b{constant}\s*=\s*Set\.of\(([^)]*)\)", text)
    assert body, f"{name}.{constant} not found"
    constants = dict(re.findall(r'\b(\w+)\s*=\s*"([^"]*)"', text))
    return {
        item.strip('"') if item.startswith('"') else constants[item] for item in re.findall(r'"[^"]*"|\w+', body.group(1))
    }


def spec_methods(predicate) -> set[str]:
    return {name for name, spec in HOST_METHODS.items() if predicate(spec)}


def test_project_writes_match_java():
    assert spec_methods(lambda spec: spec.project_write) == string_set("HostBridge", "PROJECT_WRITES")


def test_engine_required_methods_match_java():
    java = set(re.findall(r'method\.equals\("([^"]+)"\)\)\s*services\.requireEngineActive', source("HostBridge")))
    assert spec_methods(lambda spec: "engine" in spec.requires) == java


def test_long_timeout_methods_match_java():
    save_as = re.search(r'\bSAVE_AS\s*=\s*"([^"]+)"', source("ProjectModule"))
    assert save_as
    java = string_set("GridModule", "LONG_TIMEOUT_METHODS") | {save_as.group(1)} | string_set("RenderModule", "LONG")
    assert spec_methods(lambda spec: spec.max_timeout_ms > MAX_TIMEOUT_MS) == java
    assert {spec.max_timeout_ms for name, spec in HOST_METHODS.items() if name in java} == {TOPOLOGY_TIMEOUT_MS}
    assert f"MAX_TOPOLOGY_TIMEOUT_MS = {TOPOLOGY_TIMEOUT_MS}" in source("GridModule")
    assert f"MAX_TIMEOUT_MS = {MAX_TIMEOUT_MS}" in source("RpcServer")


def test_module_write_sets_match_java():
    for module in ("ModulatorEditModule", "RoutingModule"):
        methods = string_set(module, "METHODS")
        assert {name for name in methods if not HOST_METHODS[name].read_only} == string_set(module, "WRITES")
