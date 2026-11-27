"""batch: N writes in order under the batch rule, exactly N entries out, for every front end.

Every call is checked first; one refusal refuses the batch and runs nothing. Then each call runs through the single
call path, in order: applied, already_matched and sent continue; the first error or other outcome stops it, and
every later call is not_reached. The result is the calls' own results in input order, under the worst outcome.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from typing import Any

from bitwig_agent.core import invoke as call_path  # invoke dispatches batch itself: a module reference, not names
from bitwig_agent.core import views
from bitwig_agent.errors import NOT_REACHED, BridgeError, bridge_error, not_applied
from bitwig_agent.protocol.base import REASONS, ErrorCode, Outcome, shared_verification, worst

Emit = Callable[["call_path.Envelope"], None]
Start = Callable[[int], None]

UNBATCHABLE = {
    "batch": "batch cannot contain batch: put its calls in this one",
    "ack": "ack cannot be batched: it releases a hold after you inspected what it covers; call it on its own",
}


def refusal(method: str, params: Mapping[str, Any]) -> call_path.Error | None:
    """Why one call cannot run in a batch: not a write the batch rule can judge, or not a valid call."""
    if method in UNBATCHABLE:
        return call_path.invalid_params_error(UNBATCHABLE[method])
    if views.exists(method) and views.read_only(method):
        return call_path.invalid_params_error(
            f"{method} is a read: a batch runs writes (a read has no outcome to continue or stop on, and later "
            "calls cannot use what it returns); call it on its own"
        )
    return call_path.check(method, params)


def refused(calls: Sequence[Mapping[str, Any]], refusals: list[call_path.Error | None]) -> call_path.Error:
    """Nothing ran: one entry per call, each the refusal or not_reached; the first refusal heads the message."""
    at, first = next((i, why.error) for i, why in enumerate(refusals) if why is not None)
    count = sum(why is not None for why in refusals)
    reason = first["reason"] if REASONS[first["reason"]] is ErrorCode.INVALID_PARAMS else "invalid_params"
    message = (
        f"{count} of {len(calls)} calls refused, nothing ran. Call {at + 1} ({calls[at]['method']}): {first['message']}"
    )
    entries = [why.error if why is not None else NOT_REACHED for why in refusals]
    return call_path.Error(
        bridge_error(
            reason, message, "Fix the refused calls and send the batch again.", fields={"entries": entries}
        ).error
    )


def entry(envelope: call_path.Envelope) -> dict[str, Any]:
    """A call's own result; an error (nothing changed) as not_applied with its reason, the error kept whole."""
    if isinstance(envelope, call_path.Error):
        return not_applied(envelope.error["reason"], envelope.error["message"], error=envelope.error)
    return envelope.result


def remaining(calls: Sequence[Mapping[str, Any]], stop: int) -> str:
    after = range(stop + 2, len(calls) + 1)
    return f"call {after[0]}" if len(after) == 1 else f"calls {after[0]}-{after[-1]}" if after else ""


def next_hint(calls: Sequence[Mapping[str, Any]], stop: int, stopped: Mapping[str, Any]) -> str:
    """What to do after the stop: the stopping call's own next when it has one (a composed call that already created
    something says not to repeat it), else fix or inspect it; the calls after it were not run."""
    method, left = calls[stop]["method"], remaining(calls, stop)
    unknown = stopped["outcome"] == Outcome.UNKNOWN
    never = " Never resend it." if unknown else ""
    if "next" in stopped:
        not_run = (
            f" {left[0].upper()}{left[1:]} {'was' if left.startswith('call ') else 'were'} not run." if left else ""
        )
        return f"Call {stop + 1} ({method}) stopped: {stopped['next']}{never}{not_run}"
    if unknown:
        then = f" Then send only {left}." if left else ""
        return f"Call {stop + 1} ({method}) is unknown: inspect the real state before any further write.{never}{then}"
    with_rest = f" with {left}" if left else ""
    return f"Call {stop + 1} ({method}) was not applied: fix it and send it again{with_rest}; the calls before it are done."


def report(calls: Sequence[Mapping[str, Any]], envelopes: list[call_path.Envelope]) -> dict[str, Any]:
    """Outcome the worst entry, verification by the leaf rule, reason and next at a stop, then the N entries."""
    entries = [entry(envelope) for envelope in envelopes]
    result: dict[str, Any] = {}
    outcomes = [e["outcome"] for e in entries if "outcome" in e]  # a dry run carries none
    if outcomes:
        outcome = worst(outcomes)
        result["outcome"] = outcome.value
        shared = shared_verification(entries) if outcome is Outcome.APPLIED else None
        if shared is not None:
            result["verification"] = shared.value
    stop = next((i for i, envelope in enumerate(envelopes) if envelope.failed), None)
    if stop is not None:
        stopped = entries[stop]
        if "reason" in stopped:
            result["reason"] = stopped["reason"]
        result["next"] = next_hint(calls, stop, stopped)
    return result | {"entries": entries}


async def run(
    calls: Sequence[Mapping[str, Any]], client: Any = None, emit: Emit | None = None, start: Start | None = None
) -> call_path.Envelope:
    """The batch's envelope. A front end streaming rows gets start(index) as a call is sent and emit(envelope) for
    every row as it is final, the not_reached ones included."""
    refusals = [refusal(call["method"], call.get("params", {})) for call in calls]
    if any(why is not None for why in refusals):
        return refused(calls, refusals)
    envelopes: list[call_path.Envelope] = []
    for call in calls:
        if envelopes and envelopes[-1].failed:
            envelope: call_path.Envelope = call_path.Result(dict(NOT_REACHED))
        else:
            if start is not None:
                start(len(envelopes))
            envelope = await call_path.invoke(call["method"], call.get("params", {}), client)
        envelopes.append(envelope)
        if emit is not None:
            emit(envelope)
    return call_path.Result(report(calls, envelopes))


def refuse_call_timeouts(args: Mapping[str, Any]) -> call_path.Error | None:
    """For a front end that leaves every wait to the method's default (MCP): a call's own timeoutMs is refused too."""
    calls = args.get("calls")
    for index, call in enumerate(calls if isinstance(calls, list) else []):
        params = call.get("params") if isinstance(call, dict) else None
        if isinstance(params, dict) and "timeoutMs" in params:
            return call_path.invalid_params_error(
                f"Call {index + 1} ({call.get('method')}): timeoutMs is not accepted here; each call waits for its "
                "method's default"
            )
    return None


async def handle(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    """The composed method: a refused batch is the call's error (nothing ran), else its result."""
    envelope = await run(args["calls"], client)
    if isinstance(envelope, call_path.Error):
        raise BridgeError(envelope.error)
    return envelope.result
