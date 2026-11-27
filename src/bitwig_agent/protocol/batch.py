from __future__ import annotations

from typing import Any

from pydantic import Field

from bitwig_agent.protocol.annotations import EDIT
from bitwig_agent.protocol.base import Nonempty, StatusArgs, StrictModel, ToolSpec, omitted
from bitwig_agent.protocol.tracks import EXAMPLE_TRACK

MAX_CALLS = 1024
"""One call per track of a full track window (a whole-project mix change) fits in one batch."""


class BatchCall(StrictModel):
    method: Nonempty = Field(description="A write method, e.g. tracks.set.")
    params: dict[str, Any] = omitted(description="That method's arguments; omit for none. MCP takes no timeoutMs here.")


class BatchArgs(StatusArgs):
    calls: list[BatchCall] = Field(
        min_length=1,
        max_length=MAX_CALLS,
        description="The calls, run in this order. Each is a write; never batch, a read or ack.",
    )


BATCH_DESCRIPTION = (
    "Run several writes in order as one call: calls=[{method, params}]. Every call is validated first; if any is "
    "refused nothing runs and the error's entries has one row per call (the refusal, or not_applied/not_reached). "
    "Then they run in order, continue past applied, already_matched and sent, and stop at the first error or other "
    "outcome. The result has exactly one entry per call, in order: each call's own result, an error as not_applied "
    "with its reason and error, and not_applied/not_reached for every call after the stop. outcome is the worst "
    "entry; a stop carries reason and next (resend only the calls not done; never an unknown one). Reads, ack and "
    "batch cannot be batched. Later calls cannot use refs an earlier call returns: send those in a second batch."
)

BATCH_SUMMARY = (
    "Run writes in order as one call; all are validated first and one refusal runs none. They continue past "
    "applied/already_matched/sent and stop at the first error or other outcome; entries has one result per call, "
    "in order, not_reached after the stop. No reads, ack or batch; a ref a call returns needs a second batch."
)

SPECS: dict[str, ToolSpec] = {
    "batch": ToolSpec(
        BatchArgs,
        BATCH_DESCRIPTION,
        False,
        examples=(
            {
                "calls": [
                    {"method": "tracks.set", "params": {"target": EXAMPLE_TRACK, "mute": True}},
                    {"method": "transport.set", "params": {"tempo": 120.0}},
                ]
            },
        ),
        host=False,
        annotations=EDIT,
        summary=BATCH_SUMMARY,
    ),
}
