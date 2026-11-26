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
)

RECOVERY = (
    "After outcome unknown the write may have happened, so never repeat it. Read the state it touched "
    "(for a Grid device, grid.export; for a clip, notes.read; for a job, jobs.get). status.holds "
    "lists every unresolved hold and the next of a refusal names the one in the way. Once you have looked, "
    "ack the hold by its id: ack changes nothing, it only lets writes in that domain proceed. A long job "
    "(export.audio, tracks.bounce, project.saveAs) that outlives its request returns unknown with "
    "reason deadline and job=<id>; follow it with jobs.get."
)

REFS = (
    "A ref is a JSON object with a kind that names one host object: track, scene, slot, clip, device, chain, "
    "parameter, remotePage, send, sidechain, modulator, gridModule or marker. Take refs from read (or write) results "
    "and pass them back unchanged; never build one, except a Grid parameter: {kind: parameter, owner: <a gridModule "
    "ref as read>, id: <the parameter key in its exported spec>}. A ref holds no session state, so it stays valid "
    "across sessions until its object changes. Errors: stale with target_not_found when the object is gone (read "
    "again); stale with target_changed when another object now sits there, with current = the ref found there "
    "(decide whether your intent still holds; nothing is retried); unavailable with not_ready while the host has not "
    "observed it yet. Names of positional objects (scene, slot, clip, device, remotePage, send, marker) are always "
    "compared; renaming a track, chain, modulator or Grid module only invalidates refs that write that object itself. "
    "An unnamed scene has no rename protection: name scenes you address repeatedly. A Remote Control remapped to "
    "another parameter of the same name, and a device replaced by one of the same type and name, are not detected."
)

VALUES = (
    "Write the bare value a read returned as value: a host parameter (volume, pan, send, device or Remote Control "
    "parameter) is normalized 0..1, so pan 0.5 is centre and pan 0 is hard left. Where the read carries display you "
    'may write {display: "-6 dB"} instead; a text display ("LP 24") is unsupported_unit, so write the value you '
    'read. Where the read carries options, write one option value; {label: "Bipolar"} only for enums whose label '
    "differs from their value. Beats, tempo, booleans, names and colours are bare scalars. A write may carry expect "
    "with bare current values keyed like the write; a mismatch is stale with target_changed and observed. A batch "
    "checks every entry first and refuses all of them, entry by entry in entries, if any fails; then it writes in "
    "order and stops at the first entry that is neither applied, already_matched nor sent."
)

TOPICS: dict[str, str] = {"loop": LOOP, "outcomes": OUTCOMES, "recovery": RECOVERY, "refs": REFS, "values": VALUES}
