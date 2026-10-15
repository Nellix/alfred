from __future__ import annotations

from sma.protocol.models import CanonicalState, SemanticAction


class ProtocolError(ValueError):
    pass


def validate_action_revision(action: SemanticAction, state: CanonicalState) -> None:
    if action.expected_epoch is not None and action.expected_epoch != state.epoch:
        raise ProtocolError(
            f"epoch {action.expected_epoch} is stale; current epoch is {state.epoch}"
        )
    if action.expected_revision is not None and action.expected_revision != state.revision:
        raise ProtocolError(
            f"revision {action.expected_revision} is stale; current revision is {state.revision}"
        )
