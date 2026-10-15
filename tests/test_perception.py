from __future__ import annotations

from copy import deepcopy

import pytest

from sma.perception.canonicalizer import canonicalize
from sma.perception.diff_engine import RevisionError, apply_delta, diff_states
from sma.perception.fingerprint import screen_fingerprint
from sma.perception.redaction import redact
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import Sensitivity
from sma.protocol.msl_encoder import encode_msl


def test_canonicalization_with_missing_text_and_ids() -> None:
    state = canonicalize(
        {
            "package": "example",
            "elements": [{"class": "android.view.View", "clickable": True, "bounds": "[1,2][3,4]"}],
        }
    )
    node = state.nodes[0]
    assert node.label == "view"
    assert node.resource_id is None
    assert node.bounds and node.bounds.center == (2, 3)
    assert "click" in node.actions


def test_inverted_offscreen_bounds_are_rejected_without_losing_node() -> None:
    state = canonicalize(
        {
            "package": "example",
            "elements": [
                {
                    "class": "android.widget.Button",
                    "text": "Offscreen",
                    "clickable": True,
                    "bounds": {"left": 216, "top": 2457, "right": 936, "bottom": 2400},
                }
            ],
        }
    )
    assert len(state.nodes) == 1
    assert state.nodes[0].bounds is None


def test_duplicate_labels_get_distinct_handles_and_remain_ambiguous_by_label(
    settings_data: dict,
) -> None:
    state = canonicalize(settings_data["states"]["duplicate"])
    assert len({node.handle for node in state.nodes}) == 2
    assert [node.label for node in state.nodes] == ["Continue", "Continue"]


def test_dynamic_text_updates_preserve_resource_handle() -> None:
    old = {
        "package": "p",
        "elements": [{"class": "TextView", "resource_id": "clock", "text": "10:31"}],
    }
    new = deepcopy(old)
    new["elements"][0]["text"] = "10:32"
    a, b = canonicalize(old, revision=1), canonicalize(new, revision=2)
    assert a.nodes[0].handle == b.nodes[0].handle
    delta = diff_states(a, b)
    assert not delta.added and not delta.removed
    assert delta.updated[0].changes["text"] == "10:32"


def test_reordering_with_resource_ids_preserves_screen_fingerprint() -> None:
    nodes = [
        {"class": "Button", "resource_id": "a", "text": "A", "clickable": True},
        {"class": "Button", "resource_id": "b", "text": "B", "clickable": True},
    ]
    a = canonicalize({"package": "p", "elements": nodes})
    b = canonicalize({"package": "p", "elements": list(reversed(nodes))})
    assert screen_fingerprint("p", a.nodes) == screen_fingerprint("p", b.nodes)
    assert {n.handle for n in a.nodes} == {n.handle for n in b.nodes}


def test_delta_round_trip_and_revision_guards(settings_data: dict) -> None:
    a = canonicalize(settings_data["states"]["search"], revision=1)
    b = canonicalize(settings_data["states"]["results"], revision=2)
    delta = diff_states(a, b)
    rebuilt = apply_delta(a, delta)
    assert rebuilt.revision == 2
    assert rebuilt.screen_id == b.screen_id
    assert {node.model_dump_json() for node in rebuilt.nodes} == {
        node.model_dump_json() for node in b.nodes
    }
    with pytest.raises(RevisionError):
        apply_delta(b, delta)
    with pytest.raises(RevisionError):
        diff_states(a, b.model_copy(update={"revision": 4}))


def test_epoch_changes_on_window_app_and_rotation(settings_data: dict) -> None:
    tracker = StateTracker()
    first = tracker.observe(settings_data["states"]["settings_home"])
    dialog = tracker.observe(settings_data["states"]["unexpected_dialog"])
    assert first.state.epoch == 1
    assert dialog.state.epoch == 2 and dialog.full_snapshot and dialog.delta is None
    rotated = deepcopy(settings_data["states"]["unexpected_dialog"])
    rotated["rotation"] = 1
    assert tracker.observe(rotated).state.epoch == 3


def test_sensitive_content_is_redacted_and_excluded(settings_data: dict) -> None:
    state = canonicalize(settings_data["states"]["sensitive"])
    assert state.nodes[0].sensitivity is Sensitivity.EXCLUDED
    assert "123456" not in encode_msl(state, include_all=True)
    assert redact("Verification code 123456", Sensitivity.SECRET) == "[REDACTED]"
