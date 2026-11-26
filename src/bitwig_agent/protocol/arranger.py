from __future__ import annotations

from bitwig_agent.protocol.base import StatusArgs, ToolSpec
from bitwig_agent.protocol.launcher import TRACK_REF
from bitwig_agent.protocol.refs import TrackRef


class ArrangerListArgs(StatusArgs):
    target: TrackRef


ARRANGER_LIST_DESCRIPTION = (
    "Read the arranger clips on one audio/instrument track's main clip lane, in the lane's event order (creation "
    "order on 6.1.4, not start order): rows {ref, startBeats, lengthBeats, endBeats, name, kind (instrument|audio|"
    "other), loopEnabled, loopStartBeats, loopLengthBeats, playStartBeats, playStopBeats}. Beats are quarter notes "
    "from the arrangement start. Automation clips and take/comp lanes are not listed. Native read (inspected "
    "Bitwig 6.1.4 JAR); no selection."
)


SPECS: dict[str, ToolSpec] = {
    "arranger.list": ToolSpec(
        ArrangerListArgs, ARRANGER_LIST_DESCRIPTION, True, examples=({"target": TRACK_REF},), records="clips"
    ),
}
