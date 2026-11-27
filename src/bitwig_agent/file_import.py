"""clips.import: a local audio or MIDI file into an empty launcher slot, or onto the arranger at a beat.

The public API has no arranger insertion point, so an arranger import goes through a launcher slot: clips.import into
the track's first empty slot, clips.copy of that clip onto the timeline at start, then clips.delete of the slot clip.
Each runs as the standalone call would, with its own verification and holds, one entry each (three Undo steps); the
first that does not succeed stops the rest and nothing is rolled back. Once the copy applied the result keeps the
arranger clip's ref; a slot clip left behind is named in next.
"""

from __future__ import annotations

from pathlib import PurePath
from typing import Any

from bitwig_agent.configured_insert import Call, run
from bitwig_agent.core.serialize import dumps
from bitwig_agent.errors import NOT_REACHED, bridge_error
from bitwig_agent.protocol.base import SUCCEEDED, Outcome, shared_verification, worst


def describe(path: str) -> dict[str, Any]:
    """What a clip made from the file is called, and the audio's length in seconds (None for MIDI or unreadable)."""
    return {"name": PurePath(path).stem, "seconds": seconds(path)}


def seconds(path: str) -> float | None:
    try:
        import soundfile  # noqa: PLC0415 - the optional [audio] extra loads on first use
    except ImportError:
        return None
    try:
        return float(soundfile.info(path).duration)
    except (OSError, RuntimeError, ValueError):  # not an audio file soundfile reads, or not readable
        return None


async def empty_slot(client: Any, track: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    """The ref of the track's first empty launcher slot, reading every scene window."""
    args: dict[str, Any] = {"target": track}
    while True:
        listed = await client.call("launcher.list", args, timeout_ms)
        for row in listed["slots"]:
            if not row["hasContent"]:
                return dict(row["ref"])
        if listed.get("complete", True) or "next" not in listed:
            raise bridge_error(
                "limit_reached",
                "An arranger import passes through an empty launcher slot of the track, and it has none.",
                next="Add a scene with scenes.insert, then import again.",
            )
        args = {"target": track, **listed["next"]}


def finish(entries: list[dict[str, Any]], clip: dict[str, Any] | None, track: dict[str, Any]) -> str:
    """What is left to do after the first entry that did not succeed; refs are JSON to pass back as they are."""
    failed = next(e for e in entries if Outcome(e["outcome"]) not in SUCCEEDED)
    if failed["step"] == "import":
        return str(failed.get("next", "Inspect the slot with launcher.list before any further write."))
    unknown = failed["outcome"] == Outcome.UNKNOWN
    own = f" The {failed['step']} step said: {failed['next']}" if unknown and "next" in failed else ""
    if failed["step"] == "copy" and unknown:
        return (
            f"The copy is unknown and the clip may already be on the arranger: inspect arranger.list "
            f"{dumps({'target': track})} before any further write, then ack arranger.quarantine. Only if the clip is "
            f"there, delete the launcher clip {dumps(clip)} with clips.delete; never resend clips.copy or clips.import."
            + own
        )
    if failed["step"] == "copy":
        return (
            f"The file is in the launcher clip {dumps(clip)}, not on the arranger: copy it with clips.copy and delete "
            "it with clips.delete, or delete it; do not repeat clips.import."
        )
    if unknown:
        return (
            f"The arranger clip is in this result; the delete of the launcher clip {dumps(clip)} is unknown: inspect "
            f"launcher.list {dumps({'target': track})} before any further write; never resend clips.delete." + own
        )
    return f"The arranger clip is in this result; the launcher clip {dumps(clip)} remains: delete it with clips.delete."


async def clips_import(client: Any, args: dict[str, Any], timeout_ms: int) -> dict[str, Any]:
    to = args["to"]
    if "kind" in to:  # a slot: the host's own import
        return dict(await client.call("clips.import", args, timeout_ms))
    file = describe(args["path"])
    slot = await empty_slot(client, to["track"], timeout_ms)
    # an error from the import changed nothing: it stays the call's error
    imported: dict[str, Any] = await client.call("clips.import", {"to": slot, "path": args["path"]}, timeout_ms)
    entries = [{"step": "import", **imported}]
    if Outcome(imported["outcome"]) is Outcome.APPLIED:
        clip = imported["ref"]
        copied = await run(client, Call("copy", "clips.copy", {"from": clip, "to": to}), timeout_ms)
        entries.append(copied)
        if Outcome(copied["outcome"]) is Outcome.APPLIED:
            entries.append(await run(client, Call("delete", "clips.delete", {"target": clip}), timeout_ms))
        else:
            entries.append({"step": "delete", **NOT_REACHED})
    else:
        entries += [{"step": "copy", **NOT_REACHED}, {"step": "delete", **NOT_REACHED}]
    return report(entries, imported, file, to["track"])


def report(
    entries: list[dict[str, Any]], imported: dict[str, Any], file: dict[str, Any], track: dict[str, Any]
) -> dict[str, Any]:
    """Outcome the worst entry, verification by the leaf rule, the arranger clip's ref and the file."""
    outcome = worst(entry["outcome"] for entry in entries)
    result: dict[str, Any] = {"outcome": outcome.value}
    shared = shared_verification(entries) if outcome is Outcome.APPLIED else None
    if shared is not None:
        result["verification"] = shared.value
    copied = entries[1]
    if Outcome(copied["outcome"]) is Outcome.APPLIED:
        result["ref"] = copied["ref"]
        if "clip" in copied:
            result["clip"] = copied["clip"]
    if outcome not in SUCCEEDED:
        failed = [e for e in entries if Outcome(e["outcome"]) not in SUCCEEDED]
        result["reason"] = next((e for e in failed if e["reason"] != "not_reached"), failed[0])["reason"]
        result["next"] = finish(entries, imported.get("ref"), track)
    return result | {"file": file, "entries": entries}
