from __future__ import annotations

from sma.protocol.models import CanonicalState, SemanticAction


def verify_transition(
    action: SemanticAction, before: CanonicalState, after: CanonicalState
) -> tuple[bool, str]:
    if action.action == "stop":
        return True, "task stopped"
    if action.expected_screen and after.screen_id != action.expected_screen:
        return False, f"expected screen {action.expected_screen!r}, observed {after.screen_id!r}"
    changed = (
        before.package != after.package
        or before.window_id != after.window_id
        or before.screen_id != after.screen_id
        or {n.semantic_signature for n in before.nodes}
        != {n.semantic_signature for n in after.nodes}
    )
    if action.action == "wait":
        return (
            action.expected_screen is None or after.screen_id == action.expected_screen
        ), "wait condition"
    if action.action in {"back", "click", "set_text", "scroll", "launch_app", "open_deep_link"}:
        return changed, "semantic state changed" if changed else "no semantic state change observed"
    return True, "no transition verification required"
