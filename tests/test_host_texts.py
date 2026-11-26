"""Texts the host returns to an agent (errors, hold hints, `next`) name only methods the agent can call."""

import re
from pathlib import Path

from bitwig_agent.protocol import TOOLS

JAVA = Path(__file__).resolve().parents[1] / "extension/src/main/java"
GRID = Path(__file__).resolve().parents[1] / "src/bitwig_agent/grid"
EXPOSED = {name for name, spec in TOOLS.items() if spec.expose}
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
NAME = re.compile(r"\b[a-z][A-Za-z]*(?:\.[A-Za-z]+)+\b|\bcapabilities\b")
STALE = {"capabilities", "devices.remote.deletePage", "grid.capabilities", "grid.graph", "grid.parameters.get"}


def test_host_texts_name_no_method_the_agent_cannot_call():
    sources = [*JAVA.rglob("*.java"), *GRID.glob("*.py")]
    problems = [
        f"{path.name}:{number} names {name}"
        for path in sorted(sources)
        if path.name != "docs.py"
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1)
        for literal in LITERAL.findall(line)
        if literal not in TOOLS
        for name in NAME.findall(literal)
        if (name in TOOLS and name not in EXPOSED) or name in STALE
    ]
    assert not problems, "\n".join(problems)
