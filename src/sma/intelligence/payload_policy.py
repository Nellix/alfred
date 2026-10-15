from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from sma.intelligence.token_budget import estimate_tokens
from sma.perception.diff_engine import RevisionError, diff_states
from sma.protocol.models import CanonicalState
from sma.protocol.msl_encoder import encode_delta, encode_msl


@dataclass(frozen=True)
class SelectedPayload:
    kind: Literal["snapshot", "delta"]
    content: str
    estimated_tokens: int


def select_smaller_update(
    previous: CanonicalState,
    current: CanonicalState,
    goal: str,
    *,
    model: str | None = None,
) -> SelectedPayload:
    """Choose a reconstructable delta only when it costs fewer estimated model tokens."""
    snapshot = encode_msl(current, goal)
    snapshot_tokens = estimate_tokens(snapshot, model)
    try:
        delta = encode_delta(diff_states(previous, current))
    except RevisionError:
        return SelectedPayload("snapshot", snapshot, snapshot_tokens)
    delta_tokens = estimate_tokens(delta, model)
    if delta_tokens < snapshot_tokens:
        return SelectedPayload("delta", delta, delta_tokens)
    return SelectedPayload("snapshot", snapshot, snapshot_tokens)
