import json
from pathlib import Path
from typing import Any

from bitwig_agent.protocol.registry import TOOLS


def export_bundle() -> dict[str, Any]:
    return {
        "methods": {
            name: {
                "host": spec.host,
                "write": not spec.read_only,
                "projectWrite": spec.project_write,
                "requires": list(spec.requires),
                "defaultTimeoutMs": spec.default_timeout_ms,
                "maxTimeoutMs": spec.max_timeout_ms,
                "description": spec.description,
                "inputSchema": spec.model.model_json_schema(),
            }
            for name, spec in TOOLS.items()
        }
    }


def write_bundle(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(export_bundle(), indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
