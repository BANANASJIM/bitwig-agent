"""The agent Skill may only teach what the CLI has: every command, method, field, reason and link must resolve."""

from __future__ import annotations

import asyncio
import re
import shlex
from pathlib import Path
from typing import Any

import pytest

from bitwig_agent.frontends import cli
from bitwig_agent.protocol.base import REASONS, ErrorCode
from bitwig_agent.protocol.registry import TOOLS

ROOT = Path(__file__).resolve().parent.parent
SKILL = ROOT / "skills" / "bitwig-agent"
FILES = sorted(SKILL.rglob("*.md"))
LINKED = [*FILES, ROOT / "USAGE.md"]
COMMAND = re.compile(r"(?<![\w/.-])bitwig-agent(?=\s+\S)")
CODE = re.compile(r"```[^\n]*\n(.*?)```|`([^`\n]+)`", re.DOTALL)
SEPARATORS = re.compile(r"\s*(?:\|\||&&|[|;)]|\$\()\s*")
WORD = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*")
DOTTED = re.compile(r"[a-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)+")
SNAKE = re.compile(r"[a-z]+(?:_[a-z]+)+")
CAMEL = re.compile(r"[a-z]+(?:[A-Z][a-z0-9]*)+")
LINK = re.compile(r"\]\((?!https?://|mailto:)([^)#\s]*)(?:#([^)\s]+))?\)")
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*$", re.MULTILINE)
CLI_FLAGS = {"help", "version", "format", "fields", "timeoutMs"}
EXPOSED = {name: spec for name, spec in TOOLS.items() if spec.expose}
RESOURCES = {name.split(".")[0] for name in EXPOSED}
STALE = re.compile(r"bitwig-agent-rpc|bitwig-midi|stale_context|stale_target|\bexpected[A-Z]\w*|\btrackId\b")
OUTCOMES = {"already_matched", "not_applied"}
# Names the Skill uses that no method takes: result fields and a diagnostics event.
RESULT_NAMES = {
    "projectModified",
    "cursorClip",
    "selectedTrack",
    "diagnosticsMarker",
    "grid_rejected",
    "rawScale",
    "wouldFail",
    "parentPid",
    "waylandDisplay",
    "isError",
}


def schema_names() -> tuple[set[str], set[str]]:
    """Every property name and every enum or const string in the exposed methods' schemas."""
    properties: set[str] = set()
    values: set[str] = set()

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            properties.update(node.get("properties", {}))
            values.update(item for item in node.get("enum", []) if isinstance(item, str))
            if isinstance(node.get("const"), str):
                values.add(node["const"])
            for child in node.values():
                walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    for spec in EXPOSED.values():
        walk(spec.model.model_json_schema())
    return properties, values


FIELDS, ENUM_VALUES = schema_names()
KNOWN_SNAKE = set(REASONS) | {code.value for code in ErrorCode} | OUTCOMES | ENUM_VALUES | RESULT_NAMES


@pytest.fixture(autouse=True)
def offline(monkeypatch):
    monkeypatch.setenv("BITWIG_AGENT_PORT", "1")


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
        candidate = ".".join(words[: index + 1])
        if not WORD.fullmatch(word) or (candidate not in EXPOSED and not is_resource(candidate)):
            break
        path, count = candidate, index + 1
    return path, count


def run_cli(argv: list[str]) -> str:
    """The error a real offline `bitwig-agent` run gives for argv, or an empty string when it exits 0."""
    out = asyncio.run(cli.execute(argv))
    return "" if out.code == 0 else (out.stderr or out.stdout).strip()[:160]


def field_problems(path: str, flags: list[str]) -> list[str]:
    if path not in EXPOSED:
        return [] if "--help" in flags else [f"{path} is a resource: add a verb or --help"]
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
        return [error] if (error := run_cli(leading)) else []
    path, count = resolve(leading)
    stray = [word for word in leading[count:] if WORD.fullmatch(word)]
    if not path or stray:
        return [f"{' '.join(leading[: count + 1])}: not a method or resource"]
    failure = run_cli([*leading[:count], "--help"])
    return [failure] if failure else field_problems(path, flags)


def prose_method_problems(span: str) -> list[str]:
    """A span written as space-form method (`clips copy`, `notes read`) must name an exposed method."""
    words = span.split()
    if len(words) < 2 or words[0] not in RESOURCES:
        return []
    path, _ = resolve(words)
    if path not in EXPOSED:
        return [f"`{span}`: not an exposed method"]
    return field_problems(path, [word.partition("=")[0] for word in words if word.startswith("--")])


def single_names(text: str) -> set[str]:
    return {span for span in code_spans(text) if re.fullmatch(r"[\w]+", span)}


def slug(heading: str) -> str:
    return re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")


def link_problems(path: Path) -> list[str]:
    found = []
    for target, anchor in LINK.findall(path.read_text()):
        file = (path.parent / target).resolve() if target else path
        if not file.exists():
            found.append(f"{path.name}: {target} is missing")
        elif anchor and file.suffix == ".md" and anchor not in {slug(h) for h in HEADING.findall(file.read_text())}:
            found.append(f"{path.name}: {target}#{anchor} has no such heading")
    return found


@pytest.mark.parametrize("path", FILES, ids=lambda path: str(path.relative_to(SKILL)))
def test_every_command_in_the_skill_resolves_and_runs_offline(path: Path):
    failures = [f"{command}  ->  {problem}" for command in commands(path.read_text()) for problem in problems(command)]
    assert not failures, "\n".join(failures)


@pytest.mark.parametrize("path", FILES, ids=lambda path: str(path.relative_to(SKILL)))
def test_methods_named_in_prose_are_exposed(path: Path):
    text = path.read_text()
    dotted = sorted(
        name
        for name in {span.split()[0] for span in code_spans(text) if span.strip()}
        if DOTTED.fullmatch(name) and name.split(".")[0] in RESOURCES - set(EXPOSED) and name not in EXPOSED
    )
    spaced = [p for span in code_spans(text) if "bitwig-agent" not in span for p in prose_method_problems(span.strip())]
    assert not dotted + spaced, dotted + spaced


@pytest.mark.parametrize("path", FILES, ids=lambda path: str(path.relative_to(SKILL)))
def test_reasons_fields_and_enum_values_in_prose_exist(path: Path):
    names = single_names(path.read_text())
    reasons = sorted(name for name in names if SNAKE.fullmatch(name) and name not in KNOWN_SNAKE)
    fields = sorted(name for name in names if CAMEL.fullmatch(name) and name not in FIELDS | RESULT_NAMES)
    assert not reasons + fields, f"unknown reasons or enum values {reasons}, unknown fields {fields}"


def test_the_skill_names_no_removed_entry_point_or_field():
    text = "\n".join(path.read_text() for path in FILES)
    assert sorted(set(STALE.findall(text))) == []
    assert not re.search(r'version:\s*"0\.4', text)


def test_every_reference_is_reachable_from_skill_md():
    entry = SKILL / "SKILL.md"
    linked = {(entry.parent / target).resolve() for target, _ in LINK.findall(entry.read_text())}
    references = {path.resolve() for path in (SKILL / "references").glob("*.md")}
    assert references <= linked, sorted(path.name for path in references - linked)


@pytest.mark.parametrize("path", LINKED, ids=lambda path: str(path.relative_to(ROOT)))
def test_local_links_and_anchors_resolve(path: Path):
    assert not link_problems(path)


def test_long_references_open_with_a_contents_section():
    long = [path for path in (SKILL / "references").glob("*.md") if len(path.read_text().splitlines()) > 100]
    missing = [path.name for path in long if "## Contents" not in "\n".join(path.read_text().splitlines()[:16])]
    assert not missing


def test_the_checker_rejects_what_the_cli_does_not_have():
    bad = [
        "bitwig-agent notes apply --target=x",
        "bitwig-agent notes apply --help",
        "bitwig-agent grid modules insert --help",
        "bitwig-agent context fetch",
        "bitwig-agent audio capture now",
        "bitwig-agent tracks set --volumeDb=1",
        "bitwig-agent help nothing",
        "bitwig-agent tracks --target=x",
    ]
    good = [
        "bitwig-agent --help",
        "bitwig-agent tracks set --target=x --volume=0.5",
        "bitwig-agent help values",
        "bitwig-agent help grid.apply",
        "bitwig-agent tracks --help",
        "bitwig-agent status",
        "bitwig-agent grid apply @patch.json --dryRun",
    ]
    assert all(problems(command) for command in bad)
    assert not [command for command in good if problems(command)]
    assert prose_method_problems("notes apply")
    assert prose_method_problems("clips copy --from=x --bogus=1")
    assert not prose_method_problems("clips copy")
    assert not prose_method_problems("tracks sends set")
    assert "no_such_reason" not in KNOWN_SNAKE
    assert "target_not_found" in KNOWN_SNAKE
    assert "volumeDb" not in FIELDS
    assert "volume" in FIELDS


def test_the_link_checker_rejects_a_missing_heading_and_file(tmp_path):
    page = tmp_path / "a.md"
    page.write_text("# Top\n\n[ok](#top) [bad](#nope) [gone](b.md)\n")
    assert link_problems(page) == ["a.md: #nope has no such heading", "a.md: b.md is missing"]
