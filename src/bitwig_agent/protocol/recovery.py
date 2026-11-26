from __future__ import annotations

from bitwig_agent.protocol.annotations import STEER
from bitwig_agent.protocol.base import Nonempty, StatusArgs, ToolSpec, long_timeout


class IdArgs(StatusArgs):
    id: Nonempty


class AckArgs(IdArgs):
    # A Grid device hold re-reads the whole graph before it is released.
    timeoutMs: int = long_timeout()


ACK_DESCRIPTION = (
    "Release one unresolved hold after you have inspected the state it covers. A write whose outcome is unknown "
    "leaves a hold that refuses further writes in its domain (the refusal's next names the id; status lists every "
    "hold). ack does not undo or repeat anything; it records that you looked. A Grid device hold (id grid:...) "
    "first re-reads the device graph and stays if it is inconsistent. In-flight holds (ackable=false) are refused "
    "as busy. A long job's hold becomes ackable once its request returned: ack then gives the job up so the domain "
    "admits writes again (jobs.get keeps reporting it)."
)
JOBS_GET_DESCRIPTION = (
    "State of a long operation (export.audio, bounce.inPlace, project.saveAs) that outlived its request: the request "
    "returned outcome unknown, reason deadline, with job=<id>. Returns job {id, method, state "
    "running|completed|unknown|dispatching, outcome (only once completed: applied when verified, not_applied when "
    "nothing changed, else unknown with a reason), ...}; a completed job is verified now (files, arranger readback, "
    "project file and name). Only the current job of its kind is known. A bounce whose first verification is unknown "
    "refuses further bounces until you inspect arranger.clips.list and ack the hold. Read-only."
)

SPECS: dict[str, ToolSpec] = {
    "ack": ToolSpec(AckArgs, ACK_DESCRIPTION, False, examples=({"id": "routing.quarantine"},), annotations=STEER),
    "jobs.get": ToolSpec(IdArgs, JOBS_GET_DESCRIPTION, True, examples=({"id": "render:1"},)),
}
