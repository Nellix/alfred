from __future__ import annotations

from copy import deepcopy
from typing import Any

from sma.protocol.models import CanonicalNode, CanonicalState, NodeChange, UIDelta

_TRACKED = (
    "text",
    "content_description",
    "enabled",
    "visible",
    "selected",
    "checked",
    "bounds",
    "actions",
    "semantic_signature",
)


class RevisionError(ValueError):
    pass


def diff_states(previous: CanonicalState, current: CanonicalState) -> UIDelta:
    if previous.epoch != current.epoch:
        raise RevisionError("cannot create a delta across epochs")
    if current.revision != previous.revision + 1:
        raise RevisionError("delta revision must directly follow the base revision")
    old = {node.handle: node for node in previous.nodes}
    new = {node.handle: node for node in current.nodes}
    added = [new[key] for key in sorted(new.keys() - old.keys())]
    removed = sorted(old.keys() - new.keys())
    updated: list[NodeChange] = []
    for handle in sorted(old.keys() & new.keys()):
        changes: dict[str, Any] = {}
        for field in _TRACKED:
            before, after = getattr(old[handle], field), getattr(new[handle], field)
            if before != after:
                changes[field] = after.model_dump() if hasattr(after, "model_dump") else after
        if changes:
            updated.append(NodeChange(handle=handle, changes=changes))
    major = previous.package != current.package or previous.window_id != current.window_id
    return UIDelta(
        epoch=current.epoch,
        base_revision=previous.revision,
        revision=current.revision,
        package=current.package,
        screen_id=current.screen_id,
        window_id=current.window_id,
        rotation=current.rotation,
        captured_at=current.captured_at,
        added=added,
        removed=removed,
        updated=updated,
        major_transition=major or previous.screen_id != current.screen_id,
    )


def apply_delta(base: CanonicalState, delta: UIDelta) -> CanonicalState:
    if base.epoch != delta.epoch or base.revision != delta.base_revision:
        raise RevisionError("delta does not apply to this base state; request a full snapshot")
    nodes = {node.handle: node.model_copy(deep=True) for node in base.nodes}
    for handle in delta.removed:
        nodes.pop(handle, None)
    for node in delta.added:
        if node.handle in nodes:
            raise RevisionError(f"added handle already exists: {node.handle}")
        nodes[node.handle] = node.model_copy(deep=True)
    for update in delta.updated:
        if update.handle not in nodes:
            raise RevisionError(f"updated handle not found: {update.handle}")
        payload = nodes[update.handle].model_dump()
        payload.update(deepcopy(update.changes))
        nodes[update.handle] = CanonicalNode.model_validate(payload)
    result = base.model_copy(deep=True)
    result.revision = delta.revision
    result.package = delta.package
    result.screen_id = delta.screen_id
    result.window_id = delta.window_id
    result.rotation = delta.rotation
    result.captured_at = delta.captured_at
    result.nodes = sorted(nodes.values(), key=lambda node: node.handle)
    return result
