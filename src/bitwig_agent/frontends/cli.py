"""Unix CLI over the core: `bitwig-agent <resource> <verb> --field=value`.

stdout carries data only and stderr one error object; the exit code says what to do next. Help and
discovery are offline; only a method call contacts the host.
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import difflib
import itertools
import os
import re
import shlex
import sys
import textwrap
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from bitwig_agent.core import batch as core_batch
from bitwig_agent.core import shaping, text_views, views
from bitwig_agent.core.invoke import (
    Envelope,
    Error,
    check,
    decode,
    invalid_params,
    invoke,
    stopped,
    unknown_method,
)
from bitwig_agent.core.serialize import dumps
from bitwig_agent.core.shaping import Shape

SENTENCE_END = re.compile(r"(?<!e\.g)(?<!i\.e)\.\s")
WORD = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*")
OWN_FLAGS = {"format": "string", "fields": "string"}
START_FLAGS = ("-h", "--help", "--version")
EXIT_INTERRUPTED = 130
EXIT_PIPE_CLOSED = 141
USAGE_CODES = frozenset({"invalid_params", "unknown_method"})
EXIT_OK, EXIT_ERROR, EXIT_USAGE, EXIT_UNKNOWN = 0, 1, 2, 3
SUMMARY_WIDTH = 90
BATCH_USAGE = "batch [-|@file]"
BATCH_HELP = "\n".join(
    [
        "bitwig-agent batch [-|@file]",
        "",
        "Run JSON Lines from stdin (or @file), one write per line, in order (the core batch method; MCP has it as",
        "the batch tool with calls=[{method, params}]):",
        '  {"method":"tracks.set","params":{"target":{"kind":"track","id":"...","name":"..."},"mute":true}}',
        "params is optional; blank lines are skipped. Every line is validated before the first call is sent;",
        "reads, ack and batch are refused (call them on their own). stdout gets one result line per line: it",
        "continues past applied|already_matched|sent and stops at the first error (stderr, prefixed with the",
        "line number) or other outcome (that result is printed), then prints",
        '{"outcome":"not_applied","reason":"not_reached"} for each later line and exits with that call\'s code.',
        "Nothing is retried or run in parallel. A later line cannot use a ref an earlier one returns: send it",
        "in a second batch.",
        "Example:",
        "  jq -c '.tracks[] | {method:\"tracks.set\",params:{target:.ref,mute:true}}' t.json | bitwig-agent batch",
    ]
)
EXIT_TABLE = (
    "0  success: a read, or outcome applied | already_matched | sent",
    "1  error other than invalid_params/unknown_method, or outcome not_applied (nothing changed, or not every entry)",
    "2  usage: invalid_params or unknown_method; fix the command",
    "3  outcome unknown: stop, inspect the real state, then ack; never resend",
    "130  interrupted (Ctrl-C): if a write was in flight its state is unknown; inspect before retrying",
    "141  stdout closed during batch: no further call was sent; the last result was not delivered",
)


@dataclass(frozen=True)
class Output:
    stdout: str = ""
    stderr: str = ""
    code: int = EXIT_OK


@dataclass
class Parsed:
    arguments: dict[str, Any] = field(default_factory=dict)
    view: Shape = field(default_factory=Shape)
    seen: set[str] = field(default_factory=set)


class Inputs:
    """JSON values from the command line, a file or stdin; stdin is read at most once."""

    def __init__(self, stdin: Callable[[], str]) -> None:
        self.stdin = stdin
        self.used = False

    def text(self, token: str) -> str:
        if token == "-":
            if self.used:
                raise ValueError("stdin can be read once: give - for a single value")
            self.used = True
            return self.stdin()
        if token.startswith("@"):
            try:
                with open(token[1:], encoding="utf-8") as handle:
                    return handle.read()
            except OSError as exc:
                raise ValueError(f"Cannot read {token[1:]}: {exc.strerror}") from exc
        return token

    def json(self, token: str, label: str) -> Any:
        text = self.text(token)
        try:
            if not text.strip():
                raise ValueError("empty input")
            return decode(text)
        except ValueError as exc:
            raise ValueError(f"{label}: not strict JSON ({exc})") from exc


def text_line(value: Any, pretty: bool = False) -> str:
    return dumps(value, pretty) + "\n"


def exit_code(envelope: Envelope) -> int:
    if isinstance(envelope, Error):
        return EXIT_USAGE if envelope.error["code"] in USAGE_CODES else EXIT_ERROR
    if not envelope.failed:
        return EXIT_OK
    return EXIT_UNKNOWN if envelope.result["outcome"] == "unknown" else EXIT_ERROR


def failure(envelope: Error) -> Output:
    return Output(stderr=text_line(envelope.error), code=exit_code(envelope))


def text_out(text: str) -> Output:
    return Output(stdout=text + "\n")


def summary(text: str) -> str:
    sentence = SENTENCE_END.split(text.strip().split("\n", 1)[0], maxsplit=1)[0].rstrip(".")
    return sentence if len(sentence) <= SUMMARY_WIDTH else sentence[: SUMMARY_WIDTH - 3].rstrip() + "..."


def command_line(method: str) -> str:
    return "bitwig-agent " + method.replace(".", " ")


def render_flag(key: str, value: Any, types: dict[str, Any]) -> str:
    if value is True and types.get(key) == "boolean":
        return f"--{key}"
    if isinstance(value, str) and types.get(key) == "string":
        return f"--{key}={shlex.quote(value)}"
    return f"--{key}={shlex.quote(dumps(value))}"


def example_command(method: str, arguments: dict[str, Any]) -> str:
    types = views.field_types(method)
    return " ".join([command_line(method), *(render_flag(k, v, types) for k, v in arguments.items())])


def resources() -> dict[str, int]:
    counts: dict[str, int] = {}
    for name in views.names():
        head = name.split(".", 1)[0]
        counts[head] = counts.get(head, 0) + 1
    return counts


def method_index() -> list[str]:
    """Every exposed method under its resource: the command words and the required fields, from the registry."""
    lines: list[str] = []
    for resource, count in resources().items():
        lines.append(f"  {resource} ({count} {'method' if count == 1 else 'methods'})")
        lines += [
            f"    {BATCH_USAGE if name == 'batch' else views.usage(name)}"
            for name in views.names()
            if name.split(".", 1)[0] == resource
        ]
    return lines


def overview() -> str:
    return "\n".join(
        [
            f"bitwig-agent {views.version()}: control Bitwig Studio from a shell",
            "",
            "Usage:",
            "  bitwig-agent <resource> <verb> [--field=value]... [--] [ARGS]   (tracks.set is the same as tracks set)",
            "  bitwig-agent <resource> --help | <resource> <verb> --help | help <resource> <verb>",
            f"  bitwig-agent help <topic>   topics: {', '.join(views.help()['topics'])}",
            "",
            "Loop:",
            *textwrap.wrap(views.help("loop")["text"], 100, initial_indent="  ", subsequent_indent="  "),
            "",
            "Methods (required fields; <verb> --help lists the rest):",
            *method_index(),
            "",
            "Input: --field=value; a string field is taken literally, any other field as JSON (a field that may",
            "  also be a string takes a value that is not JSON literally: --position=start).",
            "  A non-string value may be @file or - (stdin). ARGS is one JSON object: inline, @file or -.",
            "  Field names are the protocol's (--dryRun, --timeoutMs). Flags and ARGS merge; a repeat is an error.",
            "  A ref field also takes --<kind>=REF (--track, --clip, --device ...) when REF's own kind matches.",
            "Output: stdout is the result as JSON (indented on a terminal, one line in a pipe).",
            '  stderr is only an error: {"code","reason","message","next"?}. No colour, no progress.',
            "  Reads that return a record list: --format=jsonl|csv|tsv [--fields=a,b.c] prints only the records,",
            "  one per line (scope, total, window are not output; the default JSON keeps them).",
            "  notes get --format=grid and arranger list --format=timeline print a read-only text view of the",
            "  same result (never an input: write with the JSON records).",
            '  A partial list adds one {"warning"} line to stderr and still exits 0.',
            'Batch: runs JSON Lines of writes {"method":"a.b","params":{}} in order and stops at the first error',
            "  or outcome not in applied|already_matched|sent; each later line prints",
            '  {"outcome":"not_applied","reason":"not_reached"}.',
            "",
            "Exit codes:",
            *(f"  {line}" for line in EXIT_TABLE),
        ]
    )


def resource_help(prefix: str) -> str:
    names = [name for name in views.names() if name.startswith(prefix + ".")]
    verbs = {name: name[len(prefix) + 1 :].replace(".", " ") for name in names}
    width = max(map(len, verbs.values()))
    lines = [f"  {verb:<{width}}  {summary(views.method(name)['description'])}" for name, verb in verbs.items()]
    return "\n".join([f"bitwig-agent {prefix} <verb>:", *lines, "", f"Details: bitwig-agent {prefix} <verb> --help"])


def method_help(name: str) -> str:
    info = views.method(name)
    table = [
        f"  --{f['name']:<20} {f['type']}{' (required)' if f['required'] else ''}"
        + (f"  {summary(f['description'])}" if f["description"] else "")
        for f in views.fields(name)
    ]
    lines = [
        f"{command_line(name)} [--field=value]...    ({views.effect(name)})",
        "",
        info["description"],
        "",
        "Example:",
        f"  {example_command(name, info['examples'][0])}",
        "",
        "Fields:",
        *table,
    ]
    if info["requires"]:
        lines += ["", "Requires: " + ", ".join(info["requires"])]
    if views.records(name):
        lines += [
            "",
            f"Rows: --format=jsonl|csv|tsv [--fields=a,b.c] prints the {views.records(name)} records, one per line",
        ]
    for view in views.text_views_of(name):
        lines += [f"View: --format={view} prints {text_views.summary(name, view)}"]
    return "\n".join([*lines, "", f"Full JSON schema: bitwig-agent help {name} --schema"])


@dataclass
class Flag:
    name: str
    kind: Any
    equals: bool
    value: str


def bind(flag: Flag, tokens: list[str], inputs: Inputs) -> Any:
    """The value of one flag: a bare boolean is true; otherwise `=VALUE` or the next token, as a literal string or JSON.

    A field whose union admits a string takes a value that is not JSON as that string."""
    if flag.kind == "boolean" and not flag.equals:
        if tokens and tokens[0] in ("true", "false"):
            raise ValueError(f"--{flag.name} is a bare flag: write --{flag.name}={tokens[0]}")
        return True
    value = flag.value
    if not flag.equals:
        if not tokens or (tokens[0] != "-" and tokens[0].startswith("-")):
            raise ValueError(f"--{flag.name} needs a value: --{flag.name}=VALUE")
        value = tokens.pop(0)
    if flag.kind == "string":
        return value
    try:
        return inputs.json(value, f"--{flag.name}")
    except ValueError:
        if flag.kind != views.STRING_UNION or value == "-" or value.startswith("@"):
            raise
        return value


def set_view(view: Shape, name: str, value: Any) -> Shape:
    if name == "format":
        return dataclasses.replace(view, format=str(value))
    return dataclasses.replace(view, fields=shaping.comma_fields(str(value)))


def unknown_flag(method: str, name: str) -> str:
    extras = ["--timeoutMs", *(["--format", "--fields"] if views.shapeable(method) else [])]
    close = difflib.get_close_matches(f"--{name}", extras, n=1, cutoff=0.6)
    hint = f" (did you mean {close[0]}?)" if close else ""
    return f"{views.unknown_field_message(method, name).rstrip('.')}. Also accepted: {', '.join(extras)}{hint}"


def parse_options(method: str, rest: list[str], inputs: Inputs) -> Parsed:
    types = views.field_types(method)
    flags = {**{k: v for k, v in OWN_FLAGS.items() if k not in types}, **types}
    parsed, operands, tokens = Parsed(), list[str](), list(rest)
    while tokens:
        token = tokens.pop(0)
        if token == "--":
            operands, tokens = operands + tokens, []
        elif not token.startswith("--"):
            if token != "-" and token.startswith("-"):
                raise ValueError(f"Unknown option {token}: fields are written --name=value")
            operands.append(token)
        else:
            name, equals, value = token[2:].partition("=")
            if name not in flags:
                subject = views.subject(method)
                bound = kind_ref(Flag(name, "object", bool(equals), value), tokens, inputs) if subject else None
                if bound is None:
                    raise ValueError(unknown_flag(method, name))
                store(parsed, True, str(subject), bound)
                continue
            bound = bind(Flag(name, flags[name], bool(equals), value), tokens, inputs)
            store(parsed, name in types, name, bound)
    merge_operand(parsed.arguments, operands, inputs)
    return parsed


def kind_ref(flag: Flag, tokens: list[str], inputs: Inputs) -> dict[str, Any] | None:
    """The ref of `--<kind>=REF` when REF's own kind is <kind>; None for any other value, which stays an unknown flag."""
    try:
        value = bind(flag, tokens, inputs)
    except ValueError:
        return None
    return value if isinstance(value, dict) and value.get("kind") == flag.name else None


def store(parsed: Parsed, is_field: bool, name: str, bound: Any) -> None:
    if not is_field:
        if name in parsed.seen:
            raise ValueError(f"Duplicate argument: --{name}")
        parsed.seen.add(name)
        parsed.view = set_view(parsed.view, name, bound)
    elif name in parsed.arguments:
        raise ValueError(f"Duplicate argument: --{name}")
    else:
        parsed.arguments[name] = bound


def merge_operand(arguments: dict[str, Any], operands: list[str], inputs: Inputs) -> None:
    if not operands:
        return
    if len(operands) > 1:
        raise ValueError(f"Expected one ARGS object, got {len(operands)} operands: {' '.join(operands)}")
    values = inputs.json(operands[0], "ARGS")
    if not isinstance(values, dict):
        raise ValueError("ARGS must be a JSON object")
    repeated = sorted(set(values) & set(arguments))
    if repeated:
        raise ValueError(f"Given both as flag and in ARGS: {', '.join(repeated)}")
    arguments.update(values)


def respond(method: str, envelope: Envelope, view: Shape, pretty: bool) -> Output:
    if isinstance(envelope, Error):
        return failure(envelope)
    try:
        shaped = shaping.apply(method, envelope.result, view)
    except ValueError as exc:
        return failure(invalid_params("", exc))
    stdout = shaped.text if shaped.text is not None else text_line(shaped.result, pretty)
    return Output(stdout=stdout, stderr=shaping.warning_lines(shaped), code=exit_code(envelope))


@dataclass
class Progress:
    """What has happened so far, for an interrupt: finished output, finished calls, the write in flight."""

    inflight: str | None = None
    completed: int = 0
    stdout: list[str] = field(default_factory=list)
    emit: Callable[[str], None] | None = None

    def put(self, line: str) -> None:
        """A finished result line: streamed at once when there is a sink, else collected for the Output."""
        if self.emit is not None:
            self.emit(line)
        else:
            self.stdout.append(line)


async def run(method: str, arguments: dict[str, Any], progress: Progress) -> Envelope:
    progress.inflight = None if views.read_only(method) else method
    envelope = await invoke(method, arguments)
    progress.inflight = None
    progress.completed += 1
    return envelope


def stop_output(envelope: Envelope, code: int, progress: Progress) -> Output:
    """A cut-short call as output: an unknown write is a result line on stdout, a refusal an error on stderr."""
    if isinstance(envelope, Error):
        return Output(stdout="".join(progress.stdout), stderr=text_line(envelope.error), code=code)
    return Output(stdout="".join(progress.stdout) + text_line(envelope.result), code=code)


def interrupted(progress: Progress) -> Output:
    method = progress.inflight
    done = f"; {progress.completed} call(s) finished" if progress.completed else ""
    message = (
        f"Interrupted: {method} was in flight and its outcome is unknown: inspect the real state before retrying{done}"
        if method
        else f"Interrupted: no write was in flight, nothing changed{done}"
    )
    return stop_output(stopped(method, "cancelled", message), EXIT_INTERRUPTED, progress)


async def call(method: str, rest: list[str], inputs: Inputs, pretty: bool, progress: Progress) -> Output:
    parsed = parse_options(method, rest, inputs)
    shaping.check(method, parsed.view)
    return respond(method, await run(method, parsed.arguments, progress), parsed.view, pretty)


def parse_batch(text: str) -> list[tuple[int, str, dict[str, Any]]]:
    calls = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = decode(line)
        except ValueError as exc:
            raise ValueError(f"line {number}: not strict JSON ({exc})") from exc
        params = item.get("params", {}) if isinstance(item, dict) else None
        if not isinstance(params, dict) or set(item) - {"method", "params"} or not isinstance(item.get("method"), str):
            raise ValueError(
                f'line {number}: expected {{"method":"a.b","params":{{...}}}} (params optional, an object)'
            )
        calls.append((number, item["method"], params))
    if not calls:
        raise ValueError("batch: no calls on input")
    return calls


def at_line(number: int, error: Error) -> Error:
    return Error({**error.error, "message": f"line {number}: {error.error['message']}"})


def pipe_closed(method: str, number: int) -> Output:
    message = (
        f"stdout closed: no further call was sent; line {number} ({method}) ran but its result was not delivered, "
        "so its outcome is unknown: inspect the real state before retrying"
    )
    envelope = stopped(method, "not_observed", message)
    payload = envelope.error if isinstance(envelope, Error) else envelope.result
    return Output(stderr=text_line(payload), code=EXIT_PIPE_CLOSED)


@dataclass
class BatchRows:
    """The core batch as rows while it runs: a result line on stdout, an error on stderr naming its input line."""

    numbers: list[int]
    methods: list[str]
    progress: Progress
    stderr: str = ""
    seen: int = 0
    ran: int = 0
    """The last call that ran: every row after a stop is not_reached."""
    verdict: Envelope | None = None
    """The stopping call's envelope, else the last one: it sets the exit code."""

    def start(self, index: int) -> None:
        self.progress.inflight = self.methods[index]

    def put(self, envelope: Envelope) -> None:
        index, self.seen = self.seen, self.seen + 1
        if self.verdict is None or not self.verdict.failed:  # a call that ran, not a not_reached row
            self.ran, self.verdict = index, envelope
            self.progress.inflight = None
            self.progress.completed += 1
        if isinstance(envelope, Error):
            self.stderr = failure(at_line(self.numbers[index], envelope)).stderr
        else:
            self.progress.put(text_line(envelope.result))


async def batch(rest: list[str], inputs: Inputs, progress: Progress) -> Output:
    """JSON Lines in, the core batch out: one row per line, as the MCP batch tool returns its entries."""
    if len(rest) > 1 or (rest and rest[0] != "-" and not rest[0].startswith("@")):
        raise ValueError("batch takes - (stdin, the default) or @file")
    numbered = parse_batch(inputs.text(rest[0] if rest else "-"))
    calls: list[dict[str, Any]] = [{"method": method, "params": params} for _, method, params in numbered]
    shape = check("batch", {"calls": calls})
    if shape is not None:
        return failure(shape)
    rows = BatchRows([number for number, _, _ in numbered], [method for _, method, _ in numbered], progress)
    try:
        envelope = await core_batch.run(calls, emit=rows.put, start=rows.start)
    except BrokenPipeError:
        return pipe_closed(calls[rows.ran]["method"], rows.numbers[rows.ran])
    if isinstance(envelope, Error):  # refused before anything ran: the first refused line, as one error
        index, error = next((i, e) for i, e in enumerate(envelope.error["entries"]) if "code" in e)
        return failure(at_line(rows.numbers[index], Error(error)))
    return Output(stdout="".join(progress.stdout), stderr=rows.stderr, code=exit_code(rows.verdict or envelope))


def is_resource(path: str) -> bool:
    return any(name.startswith(path + ".") for name in views.names())


def known(path: str) -> bool:
    return views.exists(path) or is_resource(path)


def split_path(argv: list[str]) -> tuple[int, int]:
    """Bounds of the words naming a method or resource; a first word that names none takes every leading word."""
    start = 1 if argv[:1] == ["help"] else 0
    count = start
    while count < len(argv) and WORD.fullmatch(argv[count]) and known(".".join(argv[start : count + 1])):
        count += 1
    if count == start:
        while count < len(argv) and WORD.fullmatch(argv[count]):
            count += 1
    return start, count


def wants_help(argv: list[str], rest: list[str]) -> bool:
    options = rest[: rest.index("--")] if "--" in rest else rest
    return not argv or "-h" in options or "--help" in options


def verb_needed(path: str) -> Error:
    verbs = ", ".join(name[len(path) + 1 :].replace(".", " ") for name in views.names() if name.startswith(path + "."))
    return invalid_params(
        "", ValueError(f"{path} is a resource: choose a verb ({verbs}); see bitwig-agent {path} --help")
    )


def odd_first_token(argv: list[str]) -> Error | None:
    """The error for a first argument that is neither a word nor a help/version flag, naming it."""
    token = argv[0] if argv else "bitwig-agent"
    if WORD.fullmatch(token) or token in START_FLAGS or not argv:
        return None
    if token.startswith("-"):
        close = difflib.get_close_matches(token, START_FLAGS, n=1, cutoff=0.6)
        hint = f" (did you mean {close[0]}?)" if close else ""
        return invalid_params(
            "", ValueError(f"Unknown option {token}{hint}: start with a resource, see bitwig-agent --help")
        )
    spaced = ".".join(token.split())
    hint = (
        f"give the words as separate arguments: bitwig-agent {' '.join(token.split())}"
        if known(spaced)
        else "see bitwig-agent --help"
    )
    return invalid_params("", ValueError(f"Unknown command {token!r}: {hint}"))


def refused(argv: list[str], path: str, rest: list[str]) -> Output | None:
    """Usage mistakes that need no host: a bad first token, --version, an unknown verb of a known resource."""
    odd = odd_first_token(argv)
    if odd is not None:
        return failure(odd)
    if argv == ["--version"]:
        return text_out(f"bitwig-agent {views.version()}")
    if is_resource(path) and rest and WORD.fullmatch(rest[0]):
        verbs = list(itertools.takewhile(WORD.fullmatch, rest))
        return failure(unknown_method(".".join([path, *verbs])))
    return None


async def dispatch(argv: list[str], inputs: Inputs, pretty: bool, progress: Progress) -> Output:
    start, count = split_path(argv)
    path, rest = ".".join(argv[start:count]), argv[count:]
    early = refused(argv, path, rest)
    if early is not None:
        return early
    if argv[:1] == ["batch"] and not wants_help(argv, argv[1:]):
        return await batch(argv[1:], inputs, progress)
    if start or wants_help(argv, rest):
        return describe(path, "--schema" in rest, pretty)
    if is_resource(path):
        return failure(verb_needed(path))
    if not views.exists(path):
        return failure(unknown_method(path))
    return await call(path, rest, inputs, pretty, progress)


def describe(path: str, schema: bool, pretty: bool) -> Output:
    if not path:
        return text_out(overview())
    if path == "batch":
        return text_out(BATCH_HELP)
    if path in views.help()["topics"]:
        return text_out(views.help(path)["text"])
    if views.exists(path):
        return Output(stdout=text_line(views.method(path), pretty)) if schema else text_out(method_help(path))
    if is_resource(path):
        return text_out(resource_help(path))
    return failure(unknown_method(path))


async def execute(
    argv: list[str], stdin: Callable[[], str] | None = None, pretty: bool = False, progress: Progress | None = None
) -> Output:
    """Everything the process would print and its exit code; the only side effects are the host call and reading stdin."""
    try:
        return await dispatch(argv, Inputs(stdin or sys.stdin.read), pretty, progress or Progress())
    except (ValueError, TypeError, RecursionError) as exc:
        return failure(invalid_params("", exc))


def write_stdout(text: str) -> None:
    """Write and flush; a closed reader is dropped quietly, and re-raised so a batch can stop."""
    try:
        sys.stdout.write(text)
        sys.stdout.flush()
    except BrokenPipeError:
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
        raise


def write_final(text: str) -> None:
    with contextlib.suppress(BrokenPipeError):
        write_stdout(text)


def main(argv: list[str] | None = None) -> int:
    progress = Progress(emit=write_stdout)
    try:
        out = asyncio.run(
            execute(sys.argv[1:] if argv is None else argv, pretty=sys.stdout.isatty(), progress=progress)
        )
    except KeyboardInterrupt:
        out = interrupted(progress)
    write_final(out.stdout)
    sys.stderr.write(out.stderr)
    return out.code


if __name__ == "__main__":
    sys.exit(main())
