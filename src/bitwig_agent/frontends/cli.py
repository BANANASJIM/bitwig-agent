"""Offline discovery and explicit one-shot calls; never starts/configures Bitwig."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Any, NoReturn

from bitwig_agent.core import views
from bitwig_agent.core.invoke import Error, decode, invalid_params, invoke


def load_file(value: str) -> Any:
    """Strict JSON from '@path'."""
    try:
        with open(value[1:], encoding="utf-8") as handle:
            return decode(handle.read())
    except OSError as exc:
        raise ValueError(f"Cannot read {value[1:]}: {exc.strerror}") from exc


class ArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> NoReturn:
        raise ValueError(message)


def parser() -> ArgumentParser:
    result = ArgumentParser(add_help=False, allow_abbrev=False)
    result.add_argument("command", nargs="?", default="help")
    result.add_argument("arguments", nargs="*")
    result.add_argument("--params", help="Strict JSON object; cannot be mixed with key=value arguments")
    result.add_argument("--timeout-ms", type=int)
    result.add_argument("--help", "-h", action="store_true")
    return result


def help_payload() -> dict[str, Any]:
    return {
        "usage": [
            "bitwig-agent-rpc list [domain]",
            "bitwig-agent-rpc describe <method>",
            "bitwig-agent-rpc help [method]",
            "bitwig-agent-rpc <method> key=value ... [--timeout-ms 5000]",
            'bitwig-agent-rpc <method> --params \'{"key":"value"}\'',
            "bitwig-agent-rpc grid.apply spec=@patch.json [dryRun=true]  (also --params @file; key=@file loads a JSON value)",
            "bitwig-agent-rpc grid.export trackId=<uuid> deviceIndex=0 expectedDeviceName='Poly Grid' [includeParams=changed|all|none] [namespace='H: '] [outPath=/abs/spec.json]",
            "bitwig-agent-rpc grid.render trackId=<uuid> deviceIndex=0 expectedDeviceName='Poly Grid' outPath=/abs/grid.svg [format=png] [overwrite=true]",
            "bitwig-agent-rpc grid.probe trackId=<uuid> deviceIndex=0 expectedDeviceName='Poly Grid' port='H: lead.OUT' seconds=4 mode=mono_pitch  (reroute, record, restore cables)",
            "bitwig-agent-rpc audio.route mode=silent|restore|status   (Linux/PipeWire)",
            "bitwig-agent-rpc audio.capture seconds=8 outPath=/abs/out.wav [fromStart=false] [overwrite=true]",
            "bitwig-agent-rpc audio.analyze path=/abs/out.wav mode=level|onsets|mono_pitch|probe|bands [bpm=120] [skipSeconds=2]",
            "bitwig-agent-rpc host.diagnostics [sinceSeconds=300 | sinceMarker=<marker>] [maxEvents=50]  (Bitwig log: grid_rejected, engine, extension errors)",
            'bitwig-agent-rpc grid.describe type="Select In" | query="random trigger" [lang=de]  (offline Bitwig module docs)',
        ],
        "discovery": "list, help and describe are offline. capabilities queries the running host.",
        "arguments": "Strings are unquoted values (shell-quote spaces); numbers/booleans/arrays/objects use strict JSON. Booleans are true/false. Explicit null is accepted only where the method schema allows it; required nullable fields must still be supplied. Duplicate/extra fields are rejected. Raw --params is equally strict.",
        "targets": "Discover track UUIDs with tracks.list and exact device names/zero-based indices with devices.list. Clip slots and MIDI channels are zero-based. Example UUIDs are placeholders.",
        "safety": "One request, no retries. A timeout is not cancellation; inspect actual host state before another write after an unknown outcome. Output preserves host results/errors without upgrading dispatched to observed.",
        "exitCodes": {
            "0": "success or offline discovery",
            "1": "host/transport error",
            "2": "invalid arguments/configuration; not dispatched",
        },
        "methods": views.names(),
    }


def parse_arguments(method: str, pairs: list[str], raw: str | None, timeout_ms: int | None) -> dict[str, Any]:
    if raw is not None:
        values = raw_values(raw, pairs)
    else:
        types = views.field_types(method)
        values = {}
        for pair in pairs:
            add_pair(values, pair, method, types)
    if timeout_ms is not None:
        if "timeoutMs" in values:
            raise ValueError("Specify timeout once: timeoutMs or --timeout-ms")
        values["timeoutMs"] = timeout_ms
    return values


def raw_values(raw: str, pairs: list[str]) -> dict[str, Any]:
    if pairs:
        raise ValueError("--params cannot be mixed with key=value arguments")
    values = load_file(raw) if raw.startswith("@") else decode(raw)
    if not isinstance(values, dict):
        raise ValueError("--params must be a JSON object")
    return values


def add_pair(values: dict[str, Any], pair: str, method: str, types: dict[str, Any]) -> None:
    key, separator, value = pair.partition("=")
    if not separator or not key:
        raise ValueError(f"Expected key=value, got {pair!r}")
    if key in values:
        raise ValueError(f"Duplicate argument: {key}")
    if key == "spec" and views.is_composed(method) and value.startswith("@"):
        spec = load_file(value)
        if not isinstance(spec, dict) or set(spec) & set(values):
            raise ValueError("spec must be a JSON object whose fields are not also given as key=value")
        values.update(spec)
    elif key not in types:
        raise ValueError(views.unknown_field_message(method, key))
    elif types[key] == "string":
        values[key] = decode(value) if value.startswith('"') else value
    elif value.startswith("@"):
        values[key] = load_file(value)
    else:
        values[key] = decode(value)


def discover(command: str, arguments: list[str]) -> dict[str, Any]:
    if command == "list":
        if len(arguments) > 1:
            raise ValueError("Usage: list [domain]")
        entries = views.index(arguments[0] + "." if arguments else "")
        if not entries:
            raise ValueError(f"Unknown domain: {arguments[0]}")
        return {"methods": entries}
    if command == "help" and not arguments:
        return help_payload()
    if len(arguments) != 1 or not views.exists(arguments[0]):
        raise ValueError("Usage: describe <known-method> (use list to discover methods)")
    return views.method(arguments[0])


async def execute(argv: list[str]) -> tuple[dict[str, Any], int]:
    """Return the exact JSON envelope and exit code; no output side effects."""
    command = ""
    try:
        args = parser().parse_intermixed_args(argv)
        command = args.command
        arguments = args.arguments
        if args.help:
            arguments = [] if command == "help" else [command]
            command = "help"
        if command in {"help", "list", "describe"}:
            if args.params is not None or args.timeout_ms is not None:
                raise ValueError("Offline discovery does not accept --params or --timeout-ms")
            return {"result": discover(command, arguments)}, 0
        envelope = await invoke(command, parse_arguments(command, arguments, args.params, args.timeout_ms))
    except (ValueError, TypeError, RecursionError) as exc:
        envelope = invalid_params(command, exc)
    if isinstance(envelope, Error) and envelope.usage:
        return envelope.payload, 2
    return envelope.payload, 1 if envelope.failed else 0


def main(argv: list[str] | None = None) -> int:
    payload, exit_code = asyncio.run(execute(sys.argv[1:] if argv is None else argv))
    print(json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
