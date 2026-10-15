from __future__ import annotations

from dataclasses import dataclass

from sma.protocol.models import CanonicalNode, CanonicalState


class TargetNotFound(LookupError):
    pass


class AmbiguousTarget(LookupError):
    pass


@dataclass(frozen=True)
class ResolvedTarget:
    node: CanonicalNode
    confidence: float


def resolve_target(state: CanonicalState, target: str, action: str) -> ResolvedTarget:
    if target.startswith("label:"):
        label = target.removeprefix("label:").strip().casefold()
        candidates = [
            node for node in state.nodes if node.visible and node.label.casefold() == label
        ]
    else:
        candidates = [node for node in state.nodes if node.handle == target]
    if not candidates:
        raise TargetNotFound(target)
    if len(candidates) > 1:
        raise AmbiguousTarget(target)
    node = candidates[0]
    required = {"click": "click", "set_text": "set_text", "scroll": "scroll"}.get(action)
    if required and required not in node.actions:
        raise ValueError(f"target {target!r} does not support {required!r}")
    return ResolvedTarget(node=node, confidence=node.confidence)
