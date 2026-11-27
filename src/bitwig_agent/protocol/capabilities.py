"""What a producer can do in Bitwig (the capability taxonomy) and which exposed methods do it (the tag table)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from bitwig_agent.protocol.base import ToolSpec

CHAPTERS = {
    "concepts": "Concepts and the window",
    "arrange": "Arrange view and tracks",
    "browser": "Browser",
    "arranger-clips": "Arranger clips",
    "launcher": "Clip launcher",
    "mix": "Mix view",
    "devices": "Devices, containers, modulators and plug-ins",
    "automation": "Automation",
    "audio": "Audio events",
    "notes": "Notes",
    "operators": "Note operators",
    "notes-audio": "Notes and audio",
    "project": "Projects and export",
    "grid": "The Grid",
}
"""Capability id prefix to its Bitwig User Guide chapter, in guide order."""


@dataclass(frozen=True)
class Capability:
    id: str
    """`<chapter>.<object>[.<action>]`; published ids are never renamed."""
    title: str
    """In the User Guide's terms, as a producer would ask for it."""
    tier: Literal["high", "medium", "low"] = "high"
    """How often producers use it; only high-tier capabilities count in the headline."""
    weight: int = 1
    """Its weight in the weighted task list: how many common tasks it merges (145 in all)."""


@dataclass(frozen=True)
class Partial:
    reason: Literal["scope", "fields"]
    """scope: only some targets; fields: only some options."""
    missing: str
    """What is missing, in a producer's words."""


TAXONOMY = (
    Capability("concepts.transport", "Set tempo, time signature, position, metronome, pre-roll and record buttons"),
    Capability("concepts.undo", "Undo and redo"),
    Capability("concepts.actions", "Run any Bitwig command", tier="medium"),
    Capability("arrange.tracks.list", "List tracks and the track tree, read track state"),
    Capability("arrange.tracks.insert", "Add an instrument, audio or effect track at a position"),
    Capability("arrange.tracks.group", "Group tracks"),
    Capability("arrange.tracks.duplicate", "Duplicate a track"),
    Capability("arrange.tracks.delete", "Delete a track, including groups"),
    Capability("arrange.tracks.rename", "Rename a track"),
    Capability("arrange.tracks.header", "Track mute, solo, arm, volume and pan", weight=3),
    Capability("arrange.tracks.routing", "Route a track's input or output to another track"),
    Capability("arrange.time-selection", "Make a time selection or clip selection", weight=2),
    Capability("arrange.loop-punch", "Arranger loop and punch in/out", weight=2),
    Capability("arrange.markers", "Add, rename, delete and list cue markers"),
    Capability("arrange.transport-lanes", "Tempo, time signature, key and scale automation lanes", weight=2),
    Capability("arrange.tracks.convert", "Convert a track to another type", tier="low"),
    Capability("arrange.tracks.ungroup", "Ungroup tracks", tier="medium"),
    Capability("arrange.tracks.select", "Select a track", tier="medium"),
    Capability("arrange.tracks.meter", "Read track level meters", tier="medium"),
    Capability("browser.preset-search", "Search preset files by name, folder, creator and category"),
    Capability("browser.device-catalog", "Browse the device catalog"),
    Capability("browser.insert-device", "Insert a device or preset", weight=3),
    Capability("browser.sample-to-sampler", "Drop a sample on a track to get a Sampler", weight=3),
    Capability("arranger-clips.list", "Read the clips on a track's timeline"),
    Capability("arranger-clips.create-midi", "Create a MIDI clip with notes on the timeline", weight=2),
    Capability("arranger-clips.place-file", "Place an audio or MIDI file on the timeline", weight=2),
    Capability("arranger-clips.move", "Move a clip on the timeline"),
    Capability("arranger-clips.copy", "Copy a timeline clip, also to another track"),
    Capability("arranger-clips.resize", "Resize a timeline clip", weight=2),
    Capability("arranger-clips.delete-rename", "Delete or rename a timeline clip"),
    Capability("arranger-clips.split", "Split a timeline clip"),
    Capability("arranger-clips.loop", "Set a timeline clip's loop on/off, start and length"),
    Capability("arranger-clips.edit-notes", "Edit the notes of an existing timeline clip", weight=2),
    Capability("arranger-clips.quantize", "Quantize a timeline clip"),
    Capability("arranger-clips.quantize-audio", "Quantize audio to beat markers"),
    Capability("arranger-clips.transpose", "Transpose a timeline clip"),
    Capability("arranger-clips.record", "Record into the arranger"),
    Capability("arranger-clips.alias", "Make alias clips, make a clip unique"),
    Capability("launcher.read", "Read launcher slots and clip properties"),
    Capability("launcher.launch-clip", "Launch a clip"),
    Capability("launcher.launch-scene", "Launch a scene"),
    Capability("launcher.stop-track", "Stop the clips on a track"),
    Capability("launcher.stop-all", "Stop all clips"),
    Capability("launcher.return-to-arranger", "Switch playback back to the arranger"),
    Capability("launcher.scenes", "Add, rename, duplicate and delete scenes"),
    Capability("launcher.clip-name-color", "Set a launcher clip's name and color"),
    Capability("launcher.create-import", "Create an empty clip or import a file into a launcher slot", weight=2),
    Capability("launcher.record-slot", "Record into a launcher slot", weight=2),
    Capability("launcher.to-arranger", "Copy a launcher clip to the arranger"),
    Capability("launcher.arranger-copy", "Copy between arranger and launcher both ways, whole scenes too"),
    Capability("launcher.double-content", "Double a launcher clip's content", tier="medium"),
    Capability("launcher.clip-timing", "Set a launcher clip's loop timing and Next Action", tier="medium"),
    Capability("mix.sends", "Set send levels and turn sends on or off", weight=2),
    Capability("mix.send-mode", "Choose pre-fader, post-fader or auto sends"),
    Capability("mix.track-io-hardware", "Route track inputs and outputs to hardware and host buses", weight=2),
    Capability("mix.layer-strips", "Mix the layers of a container", weight=2),
    Capability("devices.enable", "Enable or bypass a device"),
    Capability("devices.list-select", "List a device chain, read device state, select a device"),
    Capability("devices.delete", "Delete a device"),
    Capability("devices.duplicate", "Duplicate or copy a device", weight=2),
    Capability("devices.parameters", "Read and set device parameters, including mix knobs"),
    Capability("devices.preset-load", "Load a preset onto a new or existing device"),
    Capability("devices.synths", "Use the synths: Polysynth, FM-4, Phase-4, Polymer, Sampler"),
    Capability("devices.drum-machine", "Use Drum Machine pads mapped to notes", weight=2),
    Capability("devices.effects", "Use the effect devices: dynamics, EQ, filters and more"),
    Capability("devices.grid-device", "Insert and edit Grid devices", weight=2),
    Capability("devices.layers-read", "Read Layer and Selector chains"),
    Capability("devices.layers-insert-device", "Insert a device into an existing layer"),
    Capability("devices.layers-new", "Create a layer or chain"),
    Capability("devices.selector-switch", "Switch a Selector's active chain"),
    Capability("devices.drum-pad-content", "Put a device or sample on a drum pad"),
    Capability("devices.drum-chain-mix", "Mute, solo and mix a drum pad's chain"),
    Capability("devices.modulators-read", "Read a device's modulators"),
    Capability("devices.modulators-insert-delete", "Add and delete modulators, on Grid devices too", weight=3),
    Capability("devices.modulator-params", "Set modulator parameters"),
    Capability("devices.modulator-map-internal", "Map a modulator to a Grid control or another modulator"),
    Capability("devices.modulator-map-native", "Map a modulator to a device parameter"),
    Capability("devices.remote-pages", "Read and set Remote Controls pages"),
    Capability("devices.remote-define", "Create a Remote Controls page and assign its controls"),
    Capability("devices.sidechain", "Choose a device's sidechain source", weight=2),
    Capability("devices.plugin-insert", "Insert VST2, VST3 and CLAP plug-ins"),
    Capability("devices.plugin-params", "Read and set plug-in parameters"),
    Capability("devices.sampler-sliced", "Configure a sliced Sampler"),
    Capability("devices.move", "Move a device in its chain", tier="medium"),
    Capability("automation.read", "Read arranger automation"),
    Capability("automation.write", "Draw and replace arranger automation, linear and hold"),
    Capability("automation.any-parameter", "Automate any device or Remote Controls parameter"),
    Capability("automation.write-mode", "Arm automation writing in write, latch or touch mode", weight=3),
    Capability("automation.overdub", "Overdub automation"),
    Capability("automation.clips", "Create, wrap and flatten automation clips"),
    Capability("audio.event-tempo", "Detect an audio event's tempo, set it as the project tempo"),
    Capability("audio.slice-in-place", "Slice audio in place"),
    Capability("audio.fades", "Set fades and fade curves"),
    Capability("notes.draw", "Draw notes in a launcher clip"),
    Capability("notes.read", "Read a launcher clip's notes"),
    Capability("notes.velocity", "Set note velocity in a launcher clip"),
    Capability("notes.chance", "Set note chance in a launcher clip", weight=2),
    Capability("notes.expression", "Edit note expressions in a launcher clip, by MIDI channel too"),
    Capability("notes.transpose", "Transpose a launcher clip"),
    Capability("notes.quantize", "Quantize a launcher clip"),
    Capability("notes.quantize-to-key", "Quantize notes to the key"),
    Capability("operators.repeats", "Set note repeats in a launcher clip"),
    Capability("operators.readback", "Read note operator values in a launcher clip"),
    Capability("notes-audio.bounce-in-place", "Bounce in place", weight=2),
    Capability("notes-audio.slice-to-drum", "Slice audio to a Drum Machine"),
    Capability("notes-audio.slice-to-sampler", "Slice audio to a sliced Sampler"),
    Capability("project.status", "Read the project state and whether it has unsaved changes"),
    Capability("project.save", "Save the project"),
    Capability("project.save-as", "Save the project as a new file"),
    Capability("project.key-signature", "Read and set the project key signature"),
    Capability("project.export-audio", "Export audio: formats, sources, range, sample rate, real time", weight=2),
    Capability("project.engine", "Activate the audio engine", tier="medium"),
    Capability("grid.palette", "Browse and search the module palette"),
    Capability("grid.modules-insert", "Add Grid modules"),
    Capability("grid.modules-delete", "Delete Grid modules"),
    Capability("grid.connections", "Patch Grid cords"),
    Capability("grid.declarative", "Edit a whole Grid patch in one step"),
    Capability("grid.export-render", "Read a Grid patch and see its layout as an image"),
    Capability("grid.parameters", "Set module parameters"),
    Capability("grid.steps", "Edit step sequences"),
    Capability("grid.voicing", "Set voicing and voice stacking"),
    Capability("grid.voice-lifetime", "Control voice lifetime"),
    Capability("grid.pre-cords", "Use module pre-cords"),
    Capability("grid.modulator-out", "Send Grid signals to modulator outputs"),
    Capability("grid.remote-pages", "Map Grid controls to Remote Controls pages"),
)
"""The capabilities a coverage map counts, in User Guide order: tags name these ids."""

NO_CAPABILITY: tuple[str, ...] = ()
"""The tag of an exposed method that does no producer task on its own (holds, jobs, batches, diagnostics, audio)."""

CAPABILITIES: dict[str, tuple[str, ...]] = {
    "status": ("project.status",),
    "context.get": ("project.status",),
    "project.save": ("project.save",),
    "project.saveAs": ("project.save-as",),
    "project.activateEngine": ("project.engine",),
    "project.key.get": ("project.key-signature",),
    "project.key.set": ("project.key-signature",),
    "diagnostics.get": NO_CAPABILITY,
    "ack": NO_CAPABILITY,
    "jobs.get": NO_CAPABILITY,
    "tracks.list": ("arrange.tracks.list",),
    "tracks.get": ("arrange.tracks.list",),
    "tracks.set": ("arrange.tracks.rename", "arrange.tracks.header"),
    "tracks.sends.set": ("mix.sends", "mix.send-mode"),
    "meters.get": ("arrange.tracks.meter",),
    "tracks.select": ("arrange.tracks.select",),
    "tracks.insert": ("arrange.tracks.insert", "browser.sample-to-sampler"),
    "tracks.group": ("arrange.tracks.group",),
    "tracks.delete": ("arrange.tracks.delete",),
    "tracks.duplicate": ("arrange.tracks.duplicate",),
    "tracks.convert": ("arrange.tracks.convert",),
    "tracks.ungroup": ("arrange.tracks.ungroup",),
    "markers.list": ("arrange.markers",),
    "markers.insert": ("arrange.markers",),
    "markers.set": ("arrange.markers",),
    "markers.delete": ("arrange.markers",),
    "tracks.routing.get": ("arrange.tracks.routing", "mix.track-io-hardware"),
    "tracks.routing.set": ("arrange.tracks.routing", "mix.track-io-hardware"),
    "devices.sidechain.get": ("devices.sidechain",),
    "devices.sidechain.set": ("devices.sidechain",),
    "transport.get": ("concepts.transport",),
    "transport.set": (
        "concepts.transport",
        "arrange.loop-punch",
        "arranger-clips.record",
        "automation.write-mode",
        "automation.overdub",
    ),
    "devices.list": ("devices.list-select",),
    "devices.get": ("devices.list-select",),
    "chains.list": ("mix.layer-strips", "devices.drum-machine", "devices.layers-read"),
    "chains.set": ("mix.layer-strips", "devices.selector-switch", "devices.drum-chain-mix"),
    "chains.insert": ("devices.drum-machine", "devices.layers-new", "devices.drum-pad-content"),
    "devices.set": ("devices.enable",),
    "devices.select": ("devices.list-select",),
    "devices.catalog": ("browser.device-catalog",),
    "devices.insert": (
        "browser.insert-device",
        "browser.sample-to-sampler",
        "devices.preset-load",
        "devices.synths",
        "devices.effects",
        "devices.grid-device",
        "devices.layers-insert-device",
        "devices.drum-pad-content",
        "devices.plugin-insert",
    ),
    "devices.delete": ("devices.delete",),
    "devices.move": ("devices.move",),
    "devices.duplicate": ("devices.duplicate",),
    "devices.replace": ("devices.preset-load",),
    "devices.remote.get": ("devices.remote-pages", "grid.remote-pages"),
    "parameters.list": ("devices.parameters", "devices.plugin-params"),
    "parameters.set": (
        "devices.parameters",
        "devices.synths",
        "devices.effects",
        "devices.modulator-params",
        "devices.remote-pages",
        "devices.plugin-params",
        "devices.sampler-sliced",
    ),
    "presets.search": ("browser.preset-search",),
    "devices.remote.define": ("devices.remote-define", "grid.remote-pages"),
    "devices.remote.delete": ("devices.remote-define",),
    "grid.describe": ("grid.palette",),
    "grid.apply": (
        "devices.grid-device",
        "grid.modules-insert",
        "grid.modules-delete",
        "grid.connections",
        "grid.declarative",
        "grid.parameters",
        "grid.steps",
        "grid.voicing",
        "grid.voice-lifetime",
        "grid.pre-cords",
        "grid.modulator-out",
    ),
    "grid.probe": NO_CAPABILITY,
    "grid.export": ("grid.export-render",),
    "grid.render": ("grid.export-render",),
    "modulators.list": ("devices.modulators-read",),
    "modulators.describe": ("devices.modulators-read",),
    "modulators.get": ("devices.modulators-read",),
    "modulators.insert": ("devices.modulators-insert-delete",),
    "modulators.delete": ("devices.modulators-insert-delete",),
    "modulators.map": ("devices.modulator-map-internal", "grid.modulator-out"),
    "modulators.unmap": ("devices.modulator-map-internal",),
    "launcher.list": ("launcher.read",),
    "launcher.stop": ("launcher.stop-track", "launcher.stop-all"),
    "launcher.returnToArrangement": ("launcher.return-to-arranger",),
    "clips.get": ("launcher.clip-timing",),
    "clips.insert": ("arranger-clips.create-midi", "launcher.create-import"),
    "clips.import": ("arranger-clips.place-file", "launcher.create-import"),
    "clips.set": (
        "arranger-clips.resize",
        "arranger-clips.delete-rename",
        "launcher.clip-name-color",
        "launcher.clip-timing",
    ),
    "clips.delete": ("arranger-clips.delete-rename",),
    "clips.copy": ("launcher.to-arranger", "launcher.arranger-copy"),
    "clips.move": ("arranger-clips.move",),
    "clips.launch": ("launcher.launch-clip",),
    "clips.record": ("launcher.record-slot",),
    "clips.transpose": ("notes.transpose",),
    "clips.quantize": ("notes.quantize",),
    "clips.transform": ("launcher.double-content",),
    "notes.get": ("notes.read", "notes.expression", "operators.readback"),
    "notes.set": ("notes.draw", "notes.velocity", "notes.chance", "notes.expression", "operators.repeats"),
    "scenes.list": ("launcher.scenes",),
    "scenes.insert": ("launcher.scenes",),
    "scenes.set": ("launcher.scenes",),
    "scenes.launch": ("launcher.launch-scene",),
    "scenes.duplicate": ("launcher.scenes",),
    "scenes.delete": ("launcher.scenes",),
    "arranger.list": ("arranger-clips.list",),
    "automation.points.get": ("automation.read",),
    "automation.points.set": ("automation.write", "automation.any-parameter"),
    "export.audio": ("project.export-audio",),
    "tracks.bounce": ("notes-audio.bounce-in-place",),
    "audio.route": NO_CAPABILITY,
    "audio.capture": NO_CAPABILITY,
    "audio.analyze": NO_CAPABILITY,
    "actions.list": ("concepts.actions",),
    "actions.invoke": ("concepts.undo", "concepts.actions"),
    "batch": NO_CAPABILITY,
}
"""By exposed method, the capabilities it does (a composer like grid.apply carries its inner methods' tags)."""

PARTIAL = {
    "browser.preset-search": Partial("fields", "Searches preset files by name, folder, creator and category; no tags, collections or favorites."),
    "browser.sample-to-sampler": Partial("fields", "Makes a plain Sampler; the drop modifiers for other Sampler setups are not available."),
    "arranger-clips.list": Partial("scope", "The main clip lane only; automation clips and take or comp lanes are not read."),
    "arranger-clips.move": Partial("scope", "Same track only."),
    "arranger-clips.resize": Partial("fields", "Only the end edge moves."),
    "launcher.arranger-copy": Partial("scope", "Launcher to arranger on the same track only; not arranger to launcher, not whole scenes."),
    "mix.track-io-hardware": Partial("scope", "Hardware audio inputs (named input buses) and controller note inputs only; outputs to hardware and host buses cannot be chosen."),
    "devices.duplicate": Partial("scope", "Top-level devices only, duplicated in place; not devices inside chains, not to another track."),
    "devices.synths": Partial("fields", "Insert and parameters only; sample zones and slice points are not available, and a sample loads only into a new Sampler."),
    "devices.plugin-insert": Partial("fields", "No plug-in list; you need the plug-in's id or a preset saved for it."),
    "devices.preset-load": Partial("scope", "Top-level devices only, not devices inside a chain."),
    "devices.effects": Partial("fields", "Insert and parameters only; graphic editors outside the parameter list are not available."),
    "devices.remote-define": Partial("scope", "Pages on Grid devices only, mapping Grid module parameters."),
    "devices.sampler-sliced": Partial("fields", "Parameters only; slice points cannot be edited."),
    "automation.any-parameter": Partial("scope", "Track volume and pan, device and Grid parameters; not Remote Controls or modulator parameters."),
    "project.export-audio": Partial("fields", "No real-time export."),
}  # fmt: skip
"""The capabilities the tagged methods do only in part, and what is missing."""


def check_capabilities(
    specs: dict[str, ToolSpec], tags: dict[str, tuple[str, ...]], partial: dict[str, Partial]
) -> None:
    """Fail unless every exposed method is tagged, and only with known capabilities."""
    exposed = {name for name, spec in specs.items() if spec.expose}
    untagged = sorted(exposed - tags.keys())
    if untagged:
        lines = "\n".join(f'    "{name}": ("<capability id>",),' for name in untagged)
        raise ValueError(
            f"Exposed methods without a capability tag: {untagged}. Add to CAPABILITIES in "
            f"src/bitwig_agent/protocol/capabilities.py, with ids from TAXONOMY there (add a Capability if none "
            f"fits), or NO_CAPABILITY for a method that does no producer task on its own:\n{lines}"
        )
    stray = sorted(tags.keys() - exposed)
    if stray:
        raise ValueError(f"CAPABILITIES tags methods that are not exposed (tag their composer instead): {stray}")
    known = {capability.id for capability in TAXONOMY}
    tagged = {id_ for ids in tags.values() for id_ in ids}
    unknown = sorted((tagged | partial.keys()) - known)
    if unknown:
        raise ValueError(f"Capability ids not in TAXONOMY: {unknown}")
    untagged_partial = sorted(partial.keys() - tagged)
    if untagged_partial:
        raise ValueError(f"PARTIAL names capabilities no method does: {untagged_partial}")
