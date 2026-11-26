"""The agent Skill may only teach commands the CLI has: every `bitwig-agent ...` it shows must resolve."""

from __future__ import annotations

import re
import shlex
from pathlib import Path

import pytest

from bitwig_agent.core import views
from bitwig_agent.protocol.registry import TOOLS

SKILL = Path(__file__).resolve().parent.parent / "skills" / "bitwig-agent"
FILES = sorted(SKILL.rglob("*.md"))
COMMAND = re.compile(r"(?<![\w/.-])bitwig-agent(?=\s+\S)")
CODE = re.compile(r"```[^\n]*\n(.*?)```|`([^`\n]+)`", re.DOTALL)
SEPARATORS = re.compile(r"\s*(?:\|\||&&|[|;)]|\$\()\s*")
WORD = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*")
DOTTED = re.compile(r"[a-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)+")
CLI_FLAGS = {"help", "version", "format", "fields", "timeoutMs"}
LINK = re.compile(r"\]\((?!https?://|#|mailto:)([^)#\s]+)")
EXPOSED = {name: spec for name, spec in TOOLS.items() if spec.expose}
RESOURCES = {name.split(".")[0] for name in EXPOSED}
STALE = re.compile(r"bitwig-agent-rpc|bitwig-midi|stale_context|stale_target|\bexpected[A-Z]\w*|\btrackId\b")


def code_spans(text: str) -> list[str]:
    return [block or span for block, span in CODE.findall(text)]


def commands(text: str) -> list[str]:
    """Each `bitwig-agent ...` invocation in code, cut at pipes, `;`, `&&` and `)`."""
    found = []
    for code in code_spans(text):
        for line in code.splitlines():
            for part in SEPARATORS.split(line):
                match = COMMAND.search(part)
                if match:
                    found.append(part[match.start() :].strip())
    return found


def tokens(command: str) -> list[str]:
    try:
        return shlex.split(command)[1:]
    except ValueError:
        return command.split()[1:]


def is_resource(path: str) -> bool:
    return any(name.startswith(path + ".") for name in EXPOSED)


def resolve(words: list[str]) -> tuple[str, int]:
    """The longest leading run of words that names a method or resource, joined with dots."""
    path, count = "", 0
    for index, word in enumerate(words):
        if not WORD.fullmatch(word):
            break
        candidate = ".".join(words[: index + 1])
        if candidate not in EXPOSED and not is_resource(candidate):
            break
        path, count = candidate, index + 1
    return path, count


def help_problems(words: list[str]) -> list[str]:
    topics = views.help()["topics"]
    if not words or words[0] in topics or resolve(words)[0]:
        return []
    return [f"help {words[0]}: not a topic or method (topics: {', '.join(topics)})"]


def method_problems(path: str, flags: list[str]) -> list[str]:
    if path not in EXPOSED:
        return [] if set(flags) <= {"--help"} else [f"{path} is a resource: add a verb"]
    allowed = set(EXPOSED[path].model.model_fields) | CLI_FLAGS
    return [f"{path}: no field {flag}" for flag in flags if flag[2:] not in allowed]


def problems(command: str) -> list[str]:
    words = tokens(command)
    flags = [word.partition("=")[0] for word in words if word.startswith("--")]
    leading = [word for word in words if not word.startswith("-") and "<" not in word]
    if not leading:
        return [f"unknown option {flag}" for flag in flags if flag not in {"--help", "--version"}]
    if leading[0] == "batch":
        return []
    if leading[0] == "help":
        return help_problems(leading[1:])
    path, _ = resolve(leading)
    return method_problems(path, flags) if path else [f"{' '.join(leading[:3])}: not a method or resource"]


@pytest.mark.parametrize("path", FILES, ids=lambda path: str(path.relative_to(SKILL)))
def test_every_command_in_the_skill_resolves(path: Path):
    failures = [f"{command}  ->  {problem}" for command in commands(path.read_text()) for problem in problems(command)]
    assert not failures, "\n".join(failures)


@pytest.mark.parametrize("path", FILES, ids=lambda path: str(path.relative_to(SKILL)))
def test_method_names_in_prose_are_exposed_methods(path: Path):
    named = {span.split()[0] for span in code_spans(path.read_text()) if span.strip()}
    stale = sorted(
        name
        for name in named
        if DOTTED.fullmatch(name)
        and name.split(".")[0] in RESOURCES
        and name not in EXPOSED
        and name.split(".")[0] not in EXPOSED
    )
    assert not stale, f"not exposed methods: {stale}"


def test_the_skill_names_no_removed_entry_point_or_field():
    text = "\n".join(path.read_text() for path in FILES)
    assert sorted(set(STALE.findall(text))) == []
    assert not re.search(r'version:\s*"0\.4', text)


def test_every_reference_is_reachable_from_skill_md_and_every_link_resolves():
    entry = SKILL / "SKILL.md"
    linked = {(entry.parent / target).resolve() for target in LINK.findall(entry.read_text())}
    references = {path.resolve() for path in (SKILL / "references").glob("*.md")}
    assert references <= linked, sorted(path.name for path in references - linked)
    broken = [
        f"{path.name}: {target}"
        for path in FILES
        for target in LINK.findall(path.read_text())
        if not (path.parent / target).exists()
    ]
    assert not broken


def test_long_references_open_with_a_contents_section():
    long = [path for path in (SKILL / "references").glob("*.md") if len(path.read_text().splitlines()) > 100]
    missing = [path.name for path in long if "## Contents" not in "\n".join(path.read_text().splitlines()[:12])]
    assert not missing


def test_the_checker_rejects_what_the_cli_does_not_have():
    bad = [
        "bitwig-agent notes apply --target=x",
        "bitwig-agent tracks set --volumeDb=1",
        "bitwig-agent help nothing",
        "bitwig-agent tracks --target=x",
        "bitwig-agent grid modules insert --target=x",
    ]
    good = [
        "bitwig-agent --help",
        "bitwig-agent tracks set --target=x --volume=0.5",
        "bitwig-agent help values",
        "bitwig-agent help grid.apply",
        "bitwig-agent tracks --help",
        "bitwig-agent status",
    ]
    assert all(problems(command) for command in bad)
    assert not any(problems(command) for command in good)
