"""Help topics: guidance that spans methods; each method's own long text is its doc."""

LOOP = (
    "Discover with status and tracks.list; indices are zero-based; use exact discovered targets. "
    "Every write returns outcome: applied, already_matched, sent (dispatched, nothing to observe), "
    "not_applied, or unknown. An error always means nothing changed. On unknown stop: inspect the state, "
    "then ack the hold that status.holds or the next refusal names; never retry an unknown write, by API or "
    "GUI. A long job returns unknown with job=<id>: follow it with jobs.get. No automatic retries."
)

OUTCOMES = (
    "A write's outcome is the worst of what it did, best to worst: already_matched (nothing needed writing), "
    "applied (written and read back), sent (dispatched; there is nothing to observe), not_applied (nothing "
    "changed; reason says why), unknown (it may or may not have happened). Reads carry no outcome. An error "
    "means the call was refused before anything changed; its code says what you can do: invalid_params "
    "(fix the call), stale (re-read the target), conflict, pending (wait or ack), unsafe_state (change the "
    "state it names, see next), unavailable, refused, incompatible, unknown_method, internal. "
    "In MCP, isError is true for every error and for outcome not_applied or unknown."
)

RECOVERY = (
    "After outcome unknown the write may have happened, so never repeat it. Read the state it touched "
    "(for a Grid device, grid.export or grid.get; for a clip, notes.read; for a job, jobs.get). status.holds "
    "lists every unresolved hold and the next of a refusal names the one in the way. Once you have looked, "
    "ack the hold by its id: ack changes nothing, it only lets writes in that domain proceed. A long job "
    "(export.audio, bounce.inPlace, project.saveAs) that outlives its request returns unknown with "
    "reason deadline and job=<id>; follow it with jobs.get."
)

TOPICS: dict[str, str] = {"loop": LOOP, "outcomes": OUTCOMES, "recovery": RECOVERY}
