"""devices.insert {params}, modulators.insert {params, map} and chains.insert {insert}: create, then configure or fill,
in one call.

The insert, each parameters.set, each modulators.map and the devices.insert that fills a new chain run as the
standalone calls would, one entry each, under
the batch rule: every step is resolved first, then they run in order and stop at the first that does not succeed.
Once the insert applied, the result keeps the new object's ref and, after a failed step, a next naming the calls
that finish the job on it: the object exists, so repeating the insert would duplicate it. Nothing is rolled back.
"""

from __future__ import annotations

import dataclasses
import difflib
from collections.abc import Awaitable, Callable, Mapping
from typing import Any

from bitwig_agent.errors import NOT_REACHED, BridgeError, not_applied
from bitwig_agent.protocol import TOOLS
from bitwig_agent.protocol.base import SUCCEEDED, Outcome, shared_verification, worst

MAX_CANDIDATES = 8
PARAMETER_READ = {
    "devices.insert": "parameters.list",
    "modulators.insert": "modulators.get",
    "chains.insert": "chains.list",
}
"""Where the refs of the new object, or of its parameters, are read, by insert method."""
CREATED_STEP = {"chains.insert": "chain"}
"""The entry name of the create step where it is not "insert" (chains.insert's own insert is the fill step)."""


@dataclasses.dataclass(frozen=True)
class Call:
    """A resolved configure step: its entry name and the call that makes it."""

    name: str
    method: str
    params: dict[str, Any]


@dataclasses.dataclass(frozen=True)
class Refused:
    """A configure step that cannot be made, and why."""

    name: str
    method: str
    refusal: dict[str, Any]


Step = Call | Refused


Planner = Callable[[Any, dict[str, Any], dict[str, Any], int], Awaitable[list[Step]]]


def refuse_all(extra: Mapping[str, Any], why: dict[str, Any]) -> list[Step]:
    """Every step the request asks for, in run order, refused for one reason."""
    fill = [("insert", "devices.insert")] if "insert" in extra else []
    params = [(f"params.{name}", "parameters.set") for name in extra.get("params", {})]
    maps = [(f"map[{i}]", "modulators.map") for i in range(len(extra.get("map", [])))]
    return [Refused(name, method, why) for name, method in fill + params + maps]


def param_steps(params: Mapping[str, Any], rows: list[Mapping[str, Any]]) -> list[Step]:
    """A parameters.set per name, resolved against the new object's parameter rows; a missing or shared name refuses."""
    settable = [row for row in rows if "ref" in row]
    names = list(dict.fromkeys(str(row.get("name")) for row in settable))
    steps: list[Step] = []
    for name, value in params.items():
        step, method = f"params.{name}", "parameters.set"
        hits = [row["ref"] for row in settable if row.get("name") == name]
        if len(hits) == 1:
            steps.append(Call(step, method, {"target": hits[0], "value": value}))
        elif hits:
            why = not_applied("ambiguous_name", f"{len(hits)} parameters are called {name!r}.", candidates=hits)
            steps.append(Refused(step, method, why))
        else:
            close = difflib.get_close_matches(name, names, MAX_CANDIDATES, 0.5) or names[:MAX_CANDIDATES]
            steps.append(
                Refused(
                    step, method, not_applied("unknown_name", f"No parameter is called {name!r}.", candidates=close)
                )
            )
    return steps


async def device_steps(client: Any, created: dict[str, Any], extra: dict[str, Any], timeout_ms: int) -> list[Step]:
    try:
        listed = await client.call("parameters.list", {"target": created["ref"]}, timeout_ms)
    except BridgeError as exc:
        return refuse_all(extra, not_applied(exc.error["reason"], exc.error["message"], error=exc.error))
    return param_steps(extra["params"], listed["parameters"])


async def modulator_steps(client: Any, created: dict[str, Any], extra: dict[str, Any], timeout_ms: int) -> list[Step]:
    """Controls resolve against the row the insert returned; the new modulator is every mapping's source."""
    steps = param_steps(extra.get("params", {}), created["modulator"]["parameters"])
    return steps + [
        Call(f"map[{i}]", "modulators.map", {"source": created["ref"], **mapping})
        for i, mapping in enumerate(extra.get("map", []))
    ]


async def chain_steps(client: Any, created: dict[str, Any], extra: dict[str, Any], timeout_ms: int) -> list[Step]:
    """The new chain is filled by a devices.insert into it."""
    return [Call("insert", "devices.insert", {"to": created["ref"], "insert": extra["insert"]})]


async def run(client: Any, step: Call, timeout_ms: int) -> dict[str, Any]:
    """The step's entry: the call's own result, or its error (nothing changed) as not_applied with that reason."""
    try:
        return {"step": step.name, **await client.call(step.method, step.params, timeout_ms)}
    except BridgeError as exc:
        return {"step": step.name, **not_applied(exc.error["reason"], exc.error["message"], error=exc.error)}
    except ValueError as exc:  # a ref the host returned fails the client's own validation
        return {"step": step.name, **not_applied("invalid_params", str(exc))}


async def execute(client: Any, steps: list[Step], timeout_ms: int) -> list[dict[str, Any]]:
    """Nothing runs when a step was refused; otherwise in order, up to the first step that does not succeed."""
    calls = [step for step in steps if isinstance(step, Call)]
    if len(calls) < len(steps):
        return [{"step": s.name, **(s.refusal if isinstance(s, Refused) else NOT_REACHED)} for s in steps]
    entries: list[dict[str, Any]] = []
    for step in calls:
        stopped = entries and Outcome(entries[-1]["outcome"]) not in SUCCEEDED
        entries.append({"step": step.name, **NOT_REACHED} if stopped else await run(client, step, timeout_ms))
    return entries


def finish_hint(method: str, created: dict[str, Any], steps: list[Step], entries: list[dict[str, Any]]) -> str:
    """The calls that finish the job on the object the insert created."""
    if Outcome(created["outcome"]) is Outcome.ALREADY_MATCHED:
        return f"{method} found it already there, so nothing ran on it: finish with {steps[0].method} on its ref if you still want it."
    if Outcome(created["outcome"]) is not Outcome.APPLIED:
        return str(created.get("next", f"Inspect with {TOOLS[method].inspect} before any further write."))
    read = PARAMETER_READ[method] if "ref" in created else TOOLS[method].inspect
    open_steps = [(s, e) for s, e in zip(steps, entries, strict=True) if Outcome(e["outcome"]) not in SUCCEEDED]
    unknown = [s for s, e in open_steps if e["outcome"] == Outcome.UNKNOWN]
    found = "its ref is in this result" if "ref" in created else f"it was not identified: find it with {read}"
    hint = f"The insert applied and {found}; do not repeat {method}."
    if unknown:
        hint += (
            f" {unknown[0].name} is unknown: inspect with {TOOLS[unknown[0].method].inspect} before any further write."
        )
    calls = " and ".join(dict.fromkeys(s.method for s, _ in open_steps))  # in run order
    return f"{hint} Not done: {', '.join(s.name for s, _ in open_steps)}; finish with {calls} on the new object (refs from {read})."


def report(method: str, created: dict[str, Any], steps: list[Step], entries: list[dict[str, Any]]) -> dict[str, Any]:
    """Outcome the worst entry, verification by the leaf rule, the new ref, and the next that finishes the job."""
    every = [{"step": CREATED_STEP.get(method, "insert"), **created}, *entries]
    outcome = worst(entry["outcome"] for entry in every)
    result: dict[str, Any] = {"outcome": outcome.value}
    shared = shared_verification(every) if outcome is Outcome.APPLIED else None
    if shared is not None:
        result["verification"] = shared.value
    if "ref" in created:
        result["ref"] = created["ref"]
    if outcome not in SUCCEEDED:
        failed = [e for e in every if Outcome(e["outcome"]) not in SUCCEEDED]
        result["reason"] = next((e for e in failed if e["reason"] != "not_reached"), failed[0])["reason"]
        result["next"] = finish_hint(method, created, steps, entries)
    return result | {"entries": every}


async def insert_then(
    planner: Planner, method: str, client: Any, args: dict[str, Any], timeout_ms: int
) -> dict[str, Any]:
    spec = TOOLS[method]
    composed = spec.model.model_fields.keys() - spec.host_model.model_fields.keys()
    extra: dict[str, Any] = {key: args.pop(key) for key in sorted(composed) if key in args}
    # an error from the insert changed nothing: it stays the call's error
    created: dict[str, Any] = await client.call(method, args, timeout_ms)
    if not extra:
        return created
    outcome = Outcome(created["outcome"])
    if outcome is Outcome.APPLIED and "ref" in created:
        steps = await planner(client, created, extra, timeout_ms)
    elif outcome is Outcome.ALREADY_MATCHED:
        steps = refuse_all(
            extra, not_applied("target_exists", f"{method} found it already there; nothing was added to it.")
        )
    else:
        steps = refuse_all(extra, NOT_REACHED)
    return report(method, created, steps, await execute(client, steps, timeout_ms))


async def devices_insert(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await insert_then(device_steps, "devices.insert", client, args, timeout_ms)


async def modulators_insert(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    return await insert_then(modulator_steps, "modulators.insert", client, args, timeout_ms)


async def chains_insert(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    """Bitwig renames a chain after the file it is filled with: the filled device's ref carries the chain as read then."""
    result = await insert_then(chain_steps, "chains.insert", client, args, timeout_ms)
    filled = result.get("entries", [{}, {}])[1]
    if filled.get("outcome") == Outcome.APPLIED:
        result["ref"] = filled["ref"]["chain"]
    return result
