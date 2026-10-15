from __future__ import annotations

from dataclasses import dataclass

from sma.perception.fingerprint import normalize_dynamic
from sma.protocol.models import CanonicalState


@dataclass(frozen=True)
class Match:
    fingerprint: str
    confidence: float


def screen_similarity(left: CanonicalState, right: CanonicalState) -> float:
    if left.package != right.package:
        return 0.0
    a = {
        (node.role, node.resource_id or normalize_dynamic(node.label))
        for node in left.nodes
        if node.visible
    }
    b = {
        (node.role, node.resource_id or normalize_dynamic(node.label))
        for node in right.nodes
        if node.visible
    }
    if not a and not b:
        return 1.0
    return len(a & b) / len(a | b)
