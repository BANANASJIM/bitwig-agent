from __future__ import annotations

from pydantic import Field

from bitwig_agent.protocol.annotations import OPEN_WORLD_EDIT
from bitwig_agent.protocol.base import Nonempty, StatusArgs, ToolSpec, omitted


class ActionArgs(StatusArgs):
    actionId: Nonempty
    discardUnsavedChanges: bool = omitted(
        description="Only with explicit user consent: allow Quit/Close/New/Open when the project is not observed saved."
    )


class ActionListArgs(StatusArgs):
    query: str = omitted(min_length=1, max_length=4096, description="Case-insensitive substring of actionId or name.")
    category: str = omitted(min_length=1, max_length=4096, description="Exact category id or name, case-insensitive.")
    limit: int = Field(default=100, ge=1, le=1000)


ACTIONS_LIST_DESCRIPTION = "List native host Action IDs, names and categories (catalog is >1000 rows: filter with query/category; default limit 100, check total/complete). Availability does not prove completion in the current UI context."
ACTIONS_INVOKE_DESCRIPTION = "Invoke one exact ID from actions.list in the current host/UI context. May edit the project or open a dialog. Quit closes every open tab and only the current tab is observable, so it always needs discardUnsavedChanges=true; Close needs the current tab observed saved (projectModified=false) or discardUnsavedChanges=true. Consent comes from the user only. New/Open open another tab and are not guarded. Returns outcome sent only, never completion; inspect state before another write and do not blindly retry."


SPECS: dict[str, ToolSpec] = {
    "actions.list": ToolSpec(
        ActionListArgs, ACTIONS_LIST_DESCRIPTION, True, examples=({"query": "scene", "limit": 20},)
    ),
    "actions.invoke": ToolSpec(
        ActionArgs,
        ACTIONS_INVOKE_DESCRIPTION,
        False,
        examples=({"actionId": "from-actions.list"},),
        annotations=OPEN_WORLD_EDIT,
    ),
}
