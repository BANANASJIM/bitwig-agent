import difflib
import re
import typing
from collections.abc import Sequence
from typing import Any

from pydantic import BaseModel

from bitwig_agent.protocol.registry import TOOLS

# Names an agent tends to guess (often the output-side name) -> the accepted input field(s). Hint only, never an alias:
# one spelling per field keeps the strict contract identical for MCP, CLI, TCP and Java, and a wrong guess costs a
# retry instead of a mis-dispatched write (offset/limit are also real fields of other tools).
SYNONYMS = {
    "offset": ("moduleOffset",),
    "limit": ("moduleLimit",),
    "tempo": ("tempoBpm",),
    "bpm": ("tempoBpm",),
    "instanceId": ("moduleId", "targetModuleId", "sourceModuleId"),
    "moduleInstanceId": ("moduleId",),
    "instanceIds": ("moduleIds",),
    "outputPath": ("outPath",),
    "path": ("outPath",),
}


def _models_in(annotation: Any) -> list[type[BaseModel]]:
    """Every pydantic model reachable through Optional/Union/list/dict/Annotated wrappers of one annotation."""
    if isinstance(annotation, type) and issubclass(annotation, BaseModel):
        return [annotation]
    return [m for arg in typing.get_args(annotation) for m in _models_in(arg)]


def _fields_at(method: str, parent: tuple[Any, ...]) -> list[str]:
    """Accepted field names of the model(s) found by walking `parent` (a pydantic loc minus its last key) from the tool's args."""
    models = [TOOLS[method].model]
    for part in parent:
        if isinstance(part, int):
            continue
        if any(part in model.model_fields for model in models):
            models = [
                m
                for model in models
                if part in model.model_fields
                for m in _models_in(model.model_fields[part].annotation)
            ]
        else:  # a union member appears in the loc by its class name
            models = [model for model in models if model.__name__ == part] or models
    return list(dict.fromkeys(name for model in models for name in model.model_fields if name != "timeoutMs"))


def _path(loc: tuple[Any, ...]) -> str:
    return "".join(f"[{p}]" if isinstance(p, int) else (f".{p}" if i else str(p)) for i, p in enumerate(loc))


def _suggest(key: str, fields: list[str]) -> list[str]:
    close = difflib.get_close_matches(key, fields, n=1, cutoff=0.5)
    return close or [name for name in fields if key.lower() in name.lower()][:1]


def unknown_arguments_message(method: str, keys: Sequence[str | tuple[Any, ...]]) -> str:
    """Name the accepted fields and the closest match, so a mistyped field is fixable in one retry.
    A key is a top-level name or the full pydantic loc (tuple) of a nested one: the full path and the siblings at that level are named."""
    top = [name for name in TOOLS[method].model.model_fields if name != "timeoutMs"]
    hints, accepted = [], []
    for key in keys:
        loc = (key,) if isinstance(key, str) else tuple(key)
        if len(loc) == 1:
            close = [name for name in SYNONYMS.get(loc[0], ()) if name in top]
            if close:
                hints.append(f"{loc[0]} (use {' or '.join(close)}; no alias is accepted)")
            else:
                close = _suggest(loc[0], top)
                hints.append(f"{loc[0]} (did you mean {close[0]}?)" if close else loc[0])
            accepted.append(f"Accepted: {', '.join(top)}")
            continue
        siblings = _fields_at(method, loc[:-1])
        close = _suggest(str(loc[-1]), siblings)
        hints.append(f"{_path(loc)} (did you mean {close[0]}?)" if close else _path(loc))
        level = _path(tuple(p for p in loc[:-1]))
        level = re.sub(r"\[\d+\]", "[]", level)
        accepted.append(f"Accepted at {level}: {', '.join(siblings)}")
    return f"Unknown argument for {method}: {', '.join(hints)}. " + "; ".join(dict.fromkeys(accepted))


def invalid_params_message(method: str, exc: Exception) -> str:
    """Compact validation text: unknown fields get the closest accepted name at their own level; no pydantic URLs or input echoes."""
    errors = getattr(exc, "errors", None)
    if method not in TOOLS or not callable(errors):
        return str(exc)
    items = errors(include_url=False, include_input=False)
    unknown = [tuple(e["loc"]) for e in items if e.get("type") == "extra_forbidden" and e.get("loc")]
    parts = [unknown_arguments_message(method, unknown)] if unknown else []
    parts += [f"{_path(tuple(e['loc'])) or method}: {e['msg']}" for e in items if e.get("type") != "extra_forbidden"]
    return "; ".join(parts) or str(exc)
