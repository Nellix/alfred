from __future__ import annotations

import re

from sma.protocol.models import CanonicalNode, CanonicalState, Sensitivity


def relevant_nodes(state: CanonicalState, goal: str | None = None) -> list[CanonicalNode]:
    candidates = [
        node
        for node in state.nodes
        if node.visible and node.sensitivity not in {Sensitivity.SECRET, Sensitivity.EXCLUDED}
    ]
    if not goal:
        return [node for node in candidates if node.actionable]
    terms = {term for term in re.findall(r"[\w.-]+", goal.casefold()) if len(term) > 2}
    scored: list[tuple[int, CanonicalNode]] = []
    for node in candidates:
        haystack = f"{node.label} {node.resource_id or ''} {node.role}".casefold()
        score = sum(term in haystack for term in terms) * 4 + int(node.actionable) * 2
        if score:
            scored.append((score, node))
    return [node for _, node in sorted(scored, key=lambda item: (-item[0], item[1].handle))[:30]]
