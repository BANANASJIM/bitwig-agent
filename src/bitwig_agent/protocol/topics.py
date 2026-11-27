"""Help topics: guidance that spans methods; each method's own long text is its doc."""

from bitwig_agent.protocol.recipes import RECIPES

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
    "(for a Grid device, grid.export; for a clip, notes.get; for a job, jobs.get). status.holds "
    "lists every unresolved hold and the next of a refusal names the one in the way. Once you have looked, "
    "ack the hold by its id: ack changes nothing, it only lets writes in that domain proceed. A long job "
    "(export.audio, tracks.bounce, project.saveAs) that outlives its request returns unknown with "
    "reason deadline and job=<id>; follow it with jobs.get."
)

REFS = (
    "A ref is a JSON object with a kind that names one host object: track, scene, slot, clip, device, chain, "
    "parameter, remotePage, send, sidechain, modulator, gridModule or marker. Take refs from read (or write) results "
    "and pass them back unchanged: that is the safe way, since a ref is checked against what the host finds. A ref "
    "you build by hand (from a help example, or a slot or parameter ref assembled from known ids) works exactly when "
    "its identity fields are correct, and is refused as stale otherwise; a Grid parameter is {kind: parameter, "
    "owner: <a gridModule ref as read>, id: <the parameter key in its exported spec>}. A ref carries the fields that find the object "
    "(locate) and the ones the host re-checks against what it finds there (compared). A ref nests its ancestors "
    "(a device holds its track or chain, a parameter its owner) and each ancestor is compared on identity: "
    "a scene, device, remotePage, send or marker by its position and name (a marker also by beat), a clip by where "
    "it sits (slot, or lane and start) and its name, a slot by its track and scene, tracks, chains, modulators "
    "and Grid modules by id. The name of a track, chain, modulator, Grid module or parameter is compared only when "
    "that object is itself the target, so renaming a track does not invalidate the refs of its devices. Errors: stale with target_not_found when nothing "
    "is at the location (next names the read to repeat); stale with target_changed when another object now sits "
    "there, with current = the ref found there (decide whether your intent still holds; nothing is retried); "
    "unavailable with not_ready while the host has not observed it yet. Keep refs within one working session and "
    "read again after reopening a project: a chain ref from an earlier session is target_not_found "
    "(next: chains.list), because Bitwig assigns new layer ids per session. An unnamed scene has no rename "
    "protection: name scenes you address repeatedly. What refs cannot detect: a Remote Control remapped to another "
    "parameter of the same name; a device replaced by one of the same type and name; a preset loaded onto a layer "
    "device, which keeps its chains and their ids, so a chain ref read before the load resolves to the new content. "
    "Read again after you or the user changed such things."
)

VALUES = (
    "Write the bare value a read returned as value: a host parameter (volume, pan, send, device or Remote Control "
    "parameter) is normalized 0..1, so pan 0.5 is centre and pan 0 is hard left; Bitwig displays centre as "
    '"0.00 %". Where the read carries display you may write {display: "-6 dB"} instead (pan: "C", "25% L" and "-25 %" all parse); a '
    'text display ("LP 24") is unsupported_unit, so write the value you read. Where the read carries options, write '
    'one option value; {label: "Bipolar"} only for enums whose label differs from their value. Beats, tempo, '
    "booleans, names and colours are bare scalars. expect is optional: pass it, with the bare current values you "
    "last read keyed like the write, when someone else may have changed them since: the user turning a knob while "
    "you work, a lane or a mapping edited by hand. A mismatch is stale with target_changed and observed, and nothing "
    "is written, so a manual change is not overwritten; skip it when you just read the value and nothing else moves "
    "it. A batch checks every entry first and refuses all of them, entry by entry in entries, if any fails; then it "
    "writes in order and stops at the first entry that is neither applied, already_matched nor sent."
)

TOPICS: dict[str, str] = {
    "loop": LOOP,
    "outcomes": OUTCOMES,
    "recovery": RECOVERY,
    "refs": REFS,
    "values": VALUES,
    "recipes": RECIPES,
}
