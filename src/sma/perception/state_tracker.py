from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from sma.perception.canonicalizer import canonicalize
from sma.perception.diff_engine import diff_states
from sma.protocol.models import CanonicalState, UIDelta


@dataclass
class Observation:
    state: CanonicalState
    delta: UIDelta | None
    full_snapshot: bool


class StateTracker:
    def __init__(self) -> None:
        self.current: CanonicalState | None = None

    def observe(self, raw: dict[str, Any]) -> Observation:
        package = str(raw.get("package", raw.get("packageName", "unknown")))
        window = str(raw.get("window_id", raw.get("windowId", "0")))
        rotation = int(raw.get("rotation", 0))
        if self.current is None:
            state = canonicalize(raw, epoch=1, revision=1)
            self.current = state
            return Observation(state, None, True)
        boundary = (
            package != self.current.package
            or window != self.current.window_id
            or rotation != self.current.rotation
        )
        epoch = self.current.epoch + 1 if boundary else self.current.epoch
        revision = 1 if boundary else self.current.revision + 1
        state = canonicalize(raw, epoch=epoch, revision=revision)
        if boundary:
            self.current = state
            return Observation(state, None, True)
        delta = diff_states(self.current, state)
        self.current = state
        return Observation(state, delta, False)

    def reset(self) -> None:
        self.current = None
