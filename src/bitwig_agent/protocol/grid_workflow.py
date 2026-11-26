from __future__ import annotations

from typing import Annotated, Literal, Self

from pydantic import Field, field_validator, model_validator

from bitwig_agent.protocol.audio import wav_path
from bitwig_agent.protocol.base import (
    MAX_STRING_UNITS,
    TARGET,
    TRACK,
    Nonempty,
    ParameterArgs,
    StatusArgs,
    StrictModel,
    ToolSpec,
    TrackId,
    long_timeout,
    omitted,
    tool,
    validate_local_path,
)
from bitwig_agent.protocol.grid import SAFE, TYPE_ID_DESCRIPTION, UNK, GridVoicingFields
from bitwig_agent.protocol.limits import DEVICE_WINDOW

MAX_APPLY_CONNECTIONS = 4096  # a full 1024-module Grid typically needs more cables than modules


class GridApplyTarget(StrictModel):
    trackId: TrackId
    deviceIndex: int = Field(ge=0, le=DEVICE_WINDOW - 1)
    expectedDeviceName: Nonempty


class GridApplyDisplay(StrictModel):
    display: str = Field(
        min_length=1, max_length=64, description="Text with explicit unit, e.g. '1.2 kHz'; float parameters only."
    )


class GridApplySteps(StrictModel):
    steps: list[float] | Annotated[str, Field(pattern=r"^\$\{[A-Za-z0-9_]+\}$")] = Field(
        description="Step-array parameter (Steps/Pitches/Gates/Probabilities/Accents): entries from step 0, 1..256 finite numbers within -128..128, at most the live arrayLength; or a whole-value '${name}' block placeholder for such a list."
    )


class GridApplyLabel(StrictModel):
    label: str = Field(min_length=1, max_length=MAX_STRING_UNITS, description="Enum option label, e.g. '16th'.")


class GridApplyModule(StrictModel):
    name: str = Field(
        min_length=1,
        max_length=MAX_STRING_UNITS,
        description="Identity: the module's customName, unique and non-empty within the device.",
    )
    type: str = omitted(
        min_length=1,
        max_length=128,
        description="Catalog module name (e.g. 'Multiply'); exactly one of type or typeId.",
    )
    typeId: TrackId = omitted(description=TYPE_ID_DESCRIPTION)
    x: int = Field(ge=-(2**31), le=2**31 - 1)
    y: int = Field(ge=-(2**31), le=2**31 - 1)
    params: dict[str, bool | int | float | str | GridApplyDisplay | GridApplyLabel | GridApplySteps] = omitted(
        description=f"parameterId -> raw value, {{display:'1.2 kHz'}}, {{label:'16th'}}, {{steps:[1,0,0.5]}} (step arrays: first entries written, rest unchanged) or a string (string panels, e.g. Comment TEXT; at most {MAX_STRING_UNITS} UTF-16 units, multi-line ok)."
    )
    replace: bool = omitted(
        description="If a module with this name exists with another type, delete it and insert this type under the same name (default: refuse)."
    )

    @model_validator(mode="after")
    def validate_one_type(self) -> Self:
        if len({"type", "typeId"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of type or typeId")
        return self


class GridApplyConnection(StrictModel):
    from_: str = Field(
        alias="from",
        min_length=3,
        max_length=2048,
        description="Source output 'module name.PORT_KEY' (split on the last '.').",
    )
    to: str = Field(min_length=3, max_length=2048, description="Target input 'module name.PORT_KEY'.")


class GridApplyBlock(StrictModel):
    params: dict[str, bool | int | float | str | list[float]] = omitted(
        description="Parameter name -> default (a list for step arrays); params values of the form '${name}' (whole value) take it, or the instance's args value."
    )
    modules: Annotated[
        list[GridApplyModule],
        Field(
            min_length=1,
            max_length=MAX_STRING_UNITS,
            description="Modules with local names and positions relative to the instance (no nesting).",
        ),
    ]
    connections: Annotated[list[GridApplyConnection], Field(max_length=MAX_APPLY_CONNECTIONS)] = omitted(
        description="Cables between the block's own modules, by local names."
    )


class GridApplyInstance(StrictModel):
    block: str = Field(min_length=1, max_length=MAX_STRING_UNITS, description="Key of a block in blocks.")
    prefix: str = omitted(
        max_length=MAX_STRING_UNITS,
        description="Prepended to every local module name (e.g. 'D1: '); instances need distinct prefixes.",
    )
    dx: int = omitted(ge=-(2**31), le=2**31 - 1, description="Offset added to every module x (default 0).")
    dy: int = omitted(ge=-(2**31), le=2**31 - 1, description="Offset added to every module y (default 0).")
    args: dict[str, bool | int | float | str | list[float]] = omitted(description="Values for the block's params.")


class GridRenderArgs(ParameterArgs):
    outPath: Nonempty = Field(
        description="Absolute normalized path of the image to write; refused if it exists unless overwrite=true."
    )
    format: Literal["svg", "png"] = omitted(description="svg (default) or png (needs rsvg-convert or magick on PATH).")
    overwrite: bool = omitted(description="Replace an existing file at outPath.")
    namespace: str = omitted(
        min_length=1,
        max_length=MAX_STRING_UNITS,
        description="customName prefix to highlight; other modules are dimmed.",
    )
    showConnections: bool = omitted(description="Draw cables (default true).")
    scale: float = omitted(ge=0.25, le=4, description="Image scale factor (default 1).")

    @model_validator(mode="after")
    def validate_path(self) -> Self:
        validate_local_path(self.outPath)
        return self


class GridExportArgs(ParameterArgs):
    namespace: str = omitted(
        min_length=1,
        max_length=MAX_STRING_UNITS,
        description="Export only modules whose customName starts with this; cables to/from other modules become `external` names.",
    )
    includeParams: Literal["changed", "all", "none"] = omitted(
        description="changed (default): params differing from the bundled 6.1.4 defaults; all; none."
    )
    outPath: Nonempty = omitted(
        description="Absolute path to write the spec JSON to (then the result omits the spec); refused if it exists unless overwrite=true."
    )
    overwrite: bool = omitted(description="Replace an existing file at outPath.")

    @model_validator(mode="after")
    def validate_path(self) -> Self:
        if self.outPath is not None:
            validate_local_path(self.outPath)
        return self


class GridGraphArgs(ParameterArgs):
    namespace: str = omitted(
        min_length=1,
        max_length=MAX_STRING_UNITS,
        description="Only modules whose customName starts with this (plus cables touching them).",
    )
    includeParams: Literal["changed", "all", "none"] = omitted(
        description="none (default), changed (differing from bundled 6.1.4 defaults) or all."
    )


class GridProbeArgs(ParameterArgs):
    timeoutMs: int = long_timeout()
    port: Annotated[str, Field(min_length=3, max_length=MAX_STRING_UNITS)] = Field(
        description="'<module name>.<OUT port key>' of a module in this device, e.g. 'H: lead.OUT'; the name is the customName (or generated name) from grid.graph."
    )
    seconds: float = Field(ge=0.2, le=20, description="Recording length.")
    mode: Literal["probe", "mono_pitch", "onsets", "level", "bands"] = Field(
        description="audio.analyze mode run on the recording. bands = band balance plus spectral peaks (Hz, note name, cents) for steady or noisy sources that mono_pitch cannot pitch."
    )
    allowAudible: bool = omitted(description="Record without audio.route silent (the probed signal is audible).")
    outPath: Nonempty = omitted(
        description="Keep the recorded WAV here (absolute .wav path); otherwise it is deleted after analysis."
    )
    overwrite: bool = omitted(description="With outPath: replace an existing file; refused by default.")
    key: str = omitted(
        pattern=r"^[A-Ga-g][#b]?:(ionian|dorian|phrygian|lydian|mixolydian|aeolian|locrian|harmonic_minor)$",
        description="bands: e.g. 'F:aeolian', adds in-key chroma share.",
    )
    bpm: Annotated[float, Field(ge=20, le=666)] = omitted(description="mono_pitch: adds 16th-grid positions.")
    skipSeconds: Annotated[float, Field(ge=0, le=3600)] = omitted(description="Ignore audio before this time.")
    method: Literal["flux", "envelope"] = omitted(description="onsets/mono_pitch onset detector (default flux).")
    threshold: Annotated[float, Field(ge=0.01, le=0.9)] = omitted(description="onsets/mono_pitch sensitivity.")
    windowSeconds: Annotated[float, Field(ge=0.01, le=60)] = omitted(
        description="level: window length; bands: window length (default 8)."
    )

    @field_validator("outPath")
    @classmethod
    def validate_out_path(cls, value: str) -> str:
        return wav_path(value)

    @model_validator(mode="after")
    def validate_options(self) -> Self:
        if self.overwrite and not self.outPath:
            raise ValueError("overwrite applies with outPath only")
        if self.key and self.mode != "bands":
            raise ValueError("key applies to mode=bands only")
        return self


class GridApplyArgs(StatusArgs):
    target: GridApplyTarget
    modules: Annotated[
        list[GridApplyModule],
        Field(
            max_length=MAX_STRING_UNITS,
            description="Declared modules; [] only with prune=true and a namespace (empties the namespace), or with instances.",
        ),
    ] = omitted()
    blocks: dict[str, GridApplyBlock] = omitted(
        description="Reusable sub-patches: {name: {params?, modules, connections?}}; expanded by instances before validation."
    )
    instances: Annotated[list[GridApplyInstance], Field(max_length=1024)] = omitted(
        description="Copies of blocks: {block, prefix?, dx?, dy?, args?}. Module names become prefix+local name, positions shift by dx/dy; cables from/to an instance use the expanded names."
    )
    connections: Annotated[list[GridApplyConnection], Field(max_length=MAX_APPLY_CONNECTIONS)] = omitted(
        description="Cables between declared modules; one source per target input."
    )
    namespace: str = omitted(
        min_length=1,
        max_length=MAX_STRING_UNITS,
        description="customName prefix (e.g. 'H: ') that prune may delete from.",
    )
    prune: bool = omitted(
        description="Also disconnect unlisted inputs of declared modules inside the namespace and delete namespace modules absent from the spec. Inputs of declared modules outside the namespace are kept (warning names both ends) unless allowOutsideDisconnect."
    )
    pruneAll: bool = omitted(
        description="Opt in to managing the WHOLE device (mutually exclusive with namespace): delete every module the spec does not declare (unnamed ones too; `external` excepted), replace same-named modules of another type (as replace:true), and disconnect every undeclared input. Deletes run first; all of it is listed in the dryRun plan and warnings. Use dryRun first."
    )
    allowOutsideDisconnect: bool = omitted(
        description="With prune and a namespace: also disconnect unlisted inputs of declared modules outside the namespace (listed by name in warnings and the dryRun plan)."
    )
    external: Annotated[
        list[Annotated[str, Field(min_length=1, max_length=MAX_STRING_UNITS)]], Field(max_length=1024)
    ] = omitted(
        description="Names of live modules this spec only wires to or from (e.g. 'X: sum'); they must exist, are used in connections like declared modules, and their type, position and params are never touched; never pruned."
    )
    dryRun: bool = omitted(description="Return the plan without writing anything.")
    replace: bool = omitted(
        description="Default for every module: replace an existing same-named module of another type instead of refusing."
    )
    voicing: GridVoicingFields = omitted(
        description="Poly Grid device voicing to converge to (voices, voiceStacking, monoMode, ...); only differing fields are written; refused before any write on Note/FX Grid."
    )

    @model_validator(mode="after")
    def validate_spec(self) -> Self:
        if "voicing" in self.model_fields_set and not self.voicing.model_fields_set:
            raise ValueError("voicing must name at least one field")
        if self.pruneAll and (self.namespace or self.allowOutsideDisconnect):
            raise ValueError(
                "pruneAll manages the whole device: do not combine it with namespace or allowOutsideDisconnect"
            )
        if not self.modules and not self.instances and not (self.prune and self.namespace) and not self.pruneAll:
            raise ValueError(
                "modules may be empty only with prune=true and a non-empty namespace, pruneAll=true, or with instances"
            )
        if self.instances and not self.blocks:
            raise ValueError("instances need blocks")
        names = [m.name for m in self.modules or []]
        dupes = sorted({n for n in names if names.count(n) > 1})
        if dupes:
            raise ValueError(f"module names must be unique: {dupes}")
        ext = self.external or []
        dupes = sorted({n for n in ext if ext.count(n) > 1})
        if dupes:
            raise ValueError(f"external names must be unique: {dupes}")
        targets = [c.to for c in self.connections or []]
        dupes = sorted({t for t in targets if targets.count(t) > 1})
        if dupes:
            raise ValueError(f"an input takes one source; repeated targets: {dupes}")
        return self


class GridDescribeArgs(StatusArgs):
    type: str = omitted(
        min_length=1,
        max_length=128,
        description="Module display name (e.g. 'Select In') or typeId; exactly one of type or query.",
    )
    query: str = omitted(
        min_length=1, max_length=256, description="Keywords ranked against module names, descriptions and keywords."
    )
    lang: Literal["en", "de", "es", "fr", "ja", "ko", "zh"] = omitted(
        description="Documentation language (default en)."
    )
    limit: int = omitted(ge=1, le=50, description="Maximum query matches (default 10).")
    installationRoot: str = omitted(
        min_length=1,
        max_length=4096,
        description="Bitwig install directory containing resources/localization; default $BITWIG_INSTALLATION_ROOT, the install of $BITWIG_API_JAR, then /opt/bitwig-studio.",
    )

    @model_validator(mode="after")
    def validate_one_selector(self) -> Self:
        if len({"type", "query"} & self.model_fields_set) != 1:
            raise ValueError("supply exactly one of type or query")
        if "limit" in self.model_fields_set and "query" not in self.model_fields_set:
            raise ValueError("limit applies only to query")
        return self


GRID_DESCRIBE_DESCRIPTION = "Bitwig's own built-in documentation for Grid modules, read offline from the local Bitwig install (client-side; no host call, nothing bundled). Read it before using an unfamiliar module: description, keywords, category, per-port and per-parameter notes (best-effort match to grid.catalog ports/parameters) and rawEntries (every resource entry for the module id). type=<display name or typeId> returns one module; query=<keywords> returns ranked matches (limit 1..50, default 10). lang en|de|es|fr|ja|ko|zh. installationRoot overrides $BITWIG_INSTALLATION_ROOT, the install of $BITWIG_API_JAR, then /opt/bitwig-studio. Also merges the bundled schema: footprint {width,height}, per-parameter kind/unit/minimum/maximum/default/options and rawScale (how raw maps to the display; status verified|measured|unverified). Query matches carry category and match category names (query=filter lists the Filter modules). Covers the bundled 6.1.4 module set; unmapped modules say so."
GRID_APPLY_DESCRIPTION = (
    "Declarative, re-appliable patch for ONE Grid device, composed client-side from the grid.* methods (not a host method). Spec: target, modules=[{name, type|typeId, x, y, params?}], connections=[{from:'module name.OUT', to:'module name.IN'}] (split on the last '.'), optional namespace, prune, dryRun. Identity is the unique non-empty customName: existing modules are reused (type must match), missing ones inserted by typeId (batches of 16), renamed, then moved to x/y after all inserts. params: raw value, {display:'1.2 kHz'} or {label:'16th'} (enum); equal values are skipped. Wrong cables are set (one source per input); unlisted inputs are left alone. prune=true disconnects them and deletes modules whose customName starts with namespace but are absent from the spec; unnamed and out-of-namespace modules are never deleted. Prune never cuts inputs of declared modules outside the namespace unless allowOutsideDisconnect=true (warnings name both ends). external=['X: sum',...] lists live modules the cables may reference without declaring them (must exist; never moved, retyped or pruned); grid.export produces a spec of an existing patch, unnamed modules match by their generated name '_<type>_<x>_<y>'. modules:[] with prune+namespace empties the namespace. pruneAll=true (no namespace) manages the whole device instead: deletes every undeclared module (unnamed too; external excepted), replaces retyped names and disconnects undeclared inputs, deletes first, all listed in the dryRun plan and warnings; use dryRun first. String params (Comment TEXT) are written too. replace=true (spec or per module) swaps an existing same-name module of another type instead of refusing (cables on it not declared in the spec are dropped; see dryRun plan.droppedConnections). Use a delimited namespace like 'H: ' (prefix match). Reusable blocks: blocks={name:{params?:{p:default}, modules, connections?}} + instances=[{block, prefix?, dx?, dy?, args?}] expand before validation (names prefix+local, positions +dx/dy, whole-value '${p}' in params filled from args/defaults, numbers stay numbers; no nesting; cables to/from instances use expanded names; result maps instances to module names). {display:'3 ms'}/'0.5 s' also works for the listed time parameters (AD/ADSR/AR/Pluck envelopes, Logic Delay, Gate Length, Lag, ...; converted to raw client-side), others need raw. Also converted client-side per grid.describe parameters[].rawScale: '-12 st' (Transpose, oscillator PITCH/PITCH_MOD), '-6 dB'/'50 %' (Amplify, Mixer LEVEL), Hz (Clock; LFO/S&H LFO RATE with TIMEBASE Hertz/Kilohertz set in the same module), % (Chance, SVF RESONANCE, Mod Delay FEEDBACK), ms (All-pass Delay); works for zero defaults and range-checks. Result notes carry measured behaviour for the types used. The whole spec is validated first (types, ports, parameter ids, did-you-mean) and a failure writes nothing; offline against the bundled Bitwig 6.1.4 schema, else live grid.catalog/grid.get. Stops at the first error or non-success outcome, never retries; returns outcome (worst of what it did; already_matched when nothing needed writing), applied, alreadyMatched, steps, moduleIds, plus reason, failure and notReached when stopped (an unknown outcome is inspected with grid.get, a held topology change released with ack <id>). dryRun carries no outcome. Params may be step arrays {steps:[...]} (Steps/Pitches/Gates/Probabilities/Accents; Pitches in Grid pitch units: 0 = MIDI 60, 1 semitone = 1/120, 1 octave = 0.1) and top-level voicing:{voices,voiceStacking,monoMode,...} converges a Poly Grid's voicing (only differing fields written). Warnings flag known traps (ignored phase cable with DEVICE_PHASE on, filter KEYTRACK in a Poly Grid, unconnected or dead-end modules, PITCH_MOD 0); a real build returns diagnosticsMarker: pass it as host.diagnostics sinceMarker to see rejections after the build (grid_rejected bursts DURING the build are expected). dryRun returns the plan only (read-only, dryRun:true) plus wouldFail: what a real apply would be refused for ({reason, message}: transport playing/recording, host-converted display writes, unreachable target). After a failure fix the cause and re-apply. "
    + SAFE
    + UNK
)
GRID_PROBE_DESCRIPTION = "Listen to one Grid output port (client-side, Linux/PipeWire; composed from grid.get, grid.connections.set, audio.capture and audio.analyze). Snapshots the cables into every Audio Out of the device, rewires the probe port '<module name>.<OUT port>' to one existing Audio Out (others are disconnected), records seconds (0.2..20) from the start of the arrangement, restores every cable by compare-and-set and returns audio.analyze of mode probe|mono_pitch|onsets|level|bands (bands = band balance + spectral peaks with note names: use it for steady/noisy sources mono_pitch cannot pitch). outPath (+overwrite) keeps the recorded WAV, otherwise it is deleted. Needs audio.route silent unless allowAudible, recording and automation write off (a playing transport is stopped; it stays stopped). A restore that cannot finish returns outcome unknown (reason job_failed) listing the inputs still changed; never retried. Returns outcome applied with port, mode, audioOut, cablesChanged, capture and analysis. Read the Grid first (grid.graph) for names."
GRID_EXPORT_DESCRIPTION = "Read ONE Grid device (all pages) and return a grid.apply spec of it, composed client-side from grid.get/grid.voicing.get (not a host method; read-only toward Bitwig, writes only outPath). Use it to work on an existing patch: edit the spec, apply it back. Modules by customName (unnamed ones get a stable generated name '_<type>_<x>_<y>', listed in `unnamed`; grid.apply matches unnamed modules by it), type, x, y, params (raw values, enum as {label}, string and step arrays included), cables as 'name.PORT' pairs, voicing on Poly Grid. includeParams: changed (default; differs from the bundled 6.1.4 defaults) | all | none. namespace exports only that prefix; cables to/from other modules turn into `external` names (grid.apply references them without declaring them). Applying the export to the same device is already_matched. outPath writes the spec JSON instead of returning it (outcome applied)."
GRID_GRAPH_DESCRIPTION = "Read ONE Grid device as names, no UUID joins (client-side over grid.get, read-only): modules [{name, type, x, y, size, params?}] and connections [{from:'name.OUT', to:'name.IN'}]; unnamed modules carry a generated name and unnamed:true. Optional namespace prefix filter (cables to outside modules listed in `external`), includeParams none (default) | changed | all. Pages internally; fails with graph_incomplete rather than return part of a device."
GRID_RENDER_DESCRIPTION = "Draw ONE Grid device's layout to an image file, composed client-side from grid.get (not a host method; read-only toward Bitwig, writes only outPath). Use it to see what you built when the Grid editor is not visible. Target as grid.get plus outPath (absolute; refused if it exists unless overwrite=true), format svg (default) or png (rsvg-convert, else magick, else error), optional namespace (customName prefix to highlight, others dimmed), showConnections (default true), scale 0.25..4. Modules are coloured by category, Label modules are section headers, Comment modules show their text, cables run from output to input side. Returns outcome applied, outPath, bytes, imageSize and a layout summary: moduleCount, connectionCount, boundingBox (cells), categories, overlaps (pairs of modules sharing cells, capped at 50, with overlapCount), unconnected modules (capped), sections inferred from 'P: ' name prefixes with bounding boxes, density (occupied cells / bbox cells). Reads all pages; fails with not_ready rather than draw part of a device."


SPECS: dict[str, ToolSpec] = {
    "grid.describe": tool(
        GridDescribeArgs,
        GRID_DESCRIBE_DESCRIPTION,
        True,
        {"type": "Select In"},
        {"query": "random trigger", "lang": "en"},
        host=False,
    ),
    "grid.apply": tool(
        GridApplyArgs,
        GRID_APPLY_DESCRIPTION,
        False,
        {
            "target": TARGET,
            "modules": [
                {"name": "H: mul", "type": "Multiply", "x": 0, "y": 0},
                {"name": "H: out", "type": "Audio Out", "x": 3, "y": 0},
            ],
            "connections": [{"from": "H: mul.OUT", "to": "H: out.IN"}],
            "dryRun": True,
        },
        host=False,
    ),
    "grid.probe": tool(
        GridProbeArgs,
        GRID_PROBE_DESCRIPTION,
        False,
        {**TARGET, "port": "H: osc.OUT", "seconds": 4, "mode": "mono_pitch"},
        host=False,
    ),
    "grid.export": tool(
        GridExportArgs, GRID_EXPORT_DESCRIPTION, True, {**TARGET, "includeParams": "changed"}, host=False
    ),
    "grid.graph": tool(GridGraphArgs, GRID_GRAPH_DESCRIPTION, True, TARGET, host=False),
    "grid.render": tool(
        GridRenderArgs,
        GRID_RENDER_DESCRIPTION,
        True,
        {"trackId": TRACK, "deviceIndex": 0, "expectedDeviceName": "Poly Grid", "outPath": "/tmp/grid.svg"},
        host=False,
    ),
}
