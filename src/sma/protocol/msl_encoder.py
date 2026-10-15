from __future__ import annotations

import json

from sma.perception.redaction import redact
from sma.perception.relevance_filter import relevant_nodes
from sma.protocol.models import CanonicalNode, CanonicalState, Sensitivity, UIDelta


def _quote(text: str) -> str:
    return json.dumps(text, ensure_ascii=False, separators=(",", ":"))


def _node_line(node: CanonicalNode) -> str:
    label = redact(node.label, node.sensitivity)
    flags = list(node.actions)
    if node.checked is not None:
        flags.append(f"checked={str(node.checked).lower()}")
    if node.selected:
        flags.append("selected")
    if not node.enabled:
        flags.append("disabled")
    return f"{node.handle}:{node.role}:{_quote(label)}:{','.join(flags) or '-'}"


def encode_msl(state: CanonicalState, goal: str | None = None, *, include_all: bool = False) -> str:
    nodes = state.nodes if include_all else relevant_nodes(state, goal)
    safe = [node for node in nodes if node.sensitivity is not Sensitivity.EXCLUDED]
    lines = [
        f"APP {state.package}",
        f"SCREEN {state.screen_id}",
        f"WINDOW {state.window_id}",
        f"EPOCH {state.epoch}",
        f"REV {state.revision}",
        "",
    ]
    lines.extend(_node_line(node) for node in safe)
    return "\n".join(lines)


def encode_delta(delta: UIDelta) -> str:
    lines = [
        f"DELTA E{delta.epoch} R{delta.base_revision}->{delta.revision}",
        f"APP {delta.package}",
        f"SCREEN {delta.screen_id}",
        f"WINDOW {delta.window_id}",
    ]
    lines.extend(f"+ {_node_line(node)}" for node in delta.added)
    lines.extend(f"- {handle}" for handle in delta.removed)
    for update in delta.updated:
        fields = ",".join(f"{key}={_quote(str(value))}" for key, value in update.changes.items())
        lines.append(f"~ {update.handle}:{fields}")
    return "\n".join(lines)
