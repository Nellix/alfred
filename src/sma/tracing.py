from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any


class JsonlTrace:
    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def record(self, event: str, **data: Any) -> None:
        safe = _scrub(data)
        with self.path.open("a") as handle:
            handle.write(
                json.dumps({"timestamp": time.time(), "event": event, **safe}, sort_keys=True)
                + "\n"
            )


def _scrub(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: (
                "[REDACTED]"
                if any(term in key.casefold() for term in ("token", "key", "password"))
                else _scrub(item)
            )
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_scrub(item) for item in value]
    return value
