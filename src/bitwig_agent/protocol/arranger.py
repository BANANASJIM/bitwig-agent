from __future__ import annotations

from bitwig_agent.protocol.base import StatusArgs, ToolSpec, omitted
from bitwig_agent.protocol.launcher import TRACK_REF
from bitwig_agent.protocol.refs import TrackRef


class ArrangerListArgs(StatusArgs):
    target: TrackRef = omitted(
        description="One track; omitted reads every audio/instrument/hybrid track in the window."
    )


ARRANGER_LIST_DESCRIPTION = (
    "Read the arranger clips on the main clip lane of one track (target) or, without target, of every "
    "audio/instrument/hybrid track in the track window, in track order and by start within a track: rows {ref, "
    "start, length, end, bar, name, kind (instrument|audio|other), loopEnabled, loop {start, length}, playStart, "
    "playStop}; the track is ref.track. The result also has markers [{ref, name, beat}], markersComplete, tempo "
    "(bpm), timeSignature and window. Beats are quarter notes from the arrangement start; bar is 1 + start / bar "
    "length at the current time signature (fractional off the bar line; time-signature changes are not applied); "
    "bar, tempo and timeSignature are null until observed. "
    "Automation clips and take/comp lanes are not listed. Native read (inspected Bitwig 6.1.4 JAR); no selection."
)


SPECS: dict[str, ToolSpec] = {
    "arranger.list": ToolSpec(
        ArrangerListArgs, ARRANGER_LIST_DESCRIPTION, True, examples=({}, {"target": TRACK_REF}), records="clips"
    ),
}
