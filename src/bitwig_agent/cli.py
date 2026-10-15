"""Offline discovery and explicit one-shot calls; never starts/configures Bitwig."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from typing import Any

from bitwig_agent.catalog import TOOLS, validate_call
from bitwig_agent.client import BridgeClient, BridgeError, configured_port, load_json


class ArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
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
        "usage": ["bitwig-agent-rpc list [domain]", "bitwig-agent-rpc describe <method>",
                  "bitwig-agent-rpc help [method]", "bitwig-agent-rpc <method> key=value ... [--timeout-ms 5000]",
                  "bitwig-agent-rpc <method> --params '{\"key\":\"value\"}'"],
        "discovery": "list, help and describe are offline. capabilities queries the running host.",
        "arguments": "Strings are unquoted values (shell-quote spaces); numbers/booleans/arrays/objects use strict JSON. Booleans are true/false. Explicit null is accepted only where the method schema allows it; required nullable fields must still be supplied. Duplicate/extra fields are rejected. Raw --params is equally strict.",
        "targets": "Discover track UUIDs with tracks.list and exact device names/zero-based indices with devices.list. Clip slots and MIDI channels are zero-based. Example UUIDs are placeholders.",
        "safety": "One request, no retries. A timeout is not cancellation; inspect actual host state before another write after an unknown outcome. Output preserves host results/errors without upgrading dispatched to observed.",
        "exitCodes": {"0": "success or offline discovery", "1": "host/transport error", "2": "invalid arguments/configuration; not dispatched"},
        "methods": list(TOOLS),
    }


def parse_arguments(method: str, pairs: list[str], raw: str | None, timeout_ms: int | None) -> tuple[dict[str, Any], int]:
    if method not in TOOLS:
        raise ValueError(f"Unknown method: {method}. Use list or describe <method> offline.")
    if raw is not None:
        if pairs:
            raise ValueError("--params cannot be mixed with key=value arguments")
        values = load_json(raw)
        if not isinstance(values, dict):
            raise ValueError("--params must be a JSON object")
    else:
        properties = TOOLS[method].model.model_json_schema()["properties"]
        values = {}
        for pair in pairs:
            key, separator, value = pair.partition("=")
            if not separator or not key:
                raise ValueError(f"Expected key=value, got {pair!r}")
            if key in values:
                raise ValueError(f"Duplicate argument: {key}")
            if key not in properties:
                raise ValueError(f"Unknown argument for {method}: {key}")
            if properties[key].get("type") == "string":
                values[key] = load_json(value) if value.startswith('"') else value
            else:
                values[key] = load_json(value)
    if timeout_ms is not None:
        if "timeoutMs" in values:
            raise ValueError("Specify timeout once: timeoutMs or --timeout-ms")
        values["timeoutMs"] = timeout_ms
    return validate_call(method, values)


async def execute(argv: list[str]) -> tuple[dict[str, Any], int]:
    """Return the exact JSON envelope and exit code; no output side effects."""
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
            if command == "list":
                if len(arguments) > 1:
                    raise ValueError("Usage: list [domain]")
                prefix = arguments[0] + "." if arguments else ""
                entries = {name: {"description": spec.description, "readOnly": spec.read_only}
                           for name, spec in TOOLS.items() if not prefix or name.startswith(prefix)}
                if not entries:
                    raise ValueError(f"Unknown domain: {arguments[0]}")
                return {"result": {"methods": entries}}, 0
            if command == "help" and not arguments:
                return {"result": help_payload()}, 0
            if len(arguments) != 1 or arguments[0] not in TOOLS:
                raise ValueError("Usage: describe <known-method> (use list to discover methods)")
            name = arguments[0]
            return {"result": {"method": name, **TOOLS[name].describe()}}, 0
        params, timeout_ms = parse_arguments(command, arguments, args.params, args.timeout_ms)
        result = await BridgeClient(configured_port()).call(command, params, timeout_ms)
        return {"result": result}, 0
    except BridgeError as exc:
        return {"error": exc.error}, 1
    except (ValueError, TypeError, RecursionError) as exc:
        return {"error": {"code": "invalid_params", "message": str(exc), "outcome": "not_dispatched"}}, 2


def main(argv: list[str] | None = None) -> int:
    payload, exit_code = asyncio.run(execute(sys.argv[1:] if argv is None else argv))
    print(json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
