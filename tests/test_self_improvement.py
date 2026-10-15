from __future__ import annotations

import json

import pytest
from typer.testing import CliRunner

from sma.cli import app
from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_parser import IntentParser
from sma.intelligence.intent_runtime import CachedIntentExecutor, CachedIntentStatus
from sma.memory.database import Database
from sma.memory.intent_cache import IntentCache, IntentSafety
from sma.memory.self_improvement import LearningStatus, SkillLearner, safety_for_intent
from sma.memory.skill_compiler import (
    SuccessfulTrace,
    TraceStep,
    compile_parameterized_proposal,
)
from sma.memory.skill_engine import SkillEngine
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import SemanticAction


def _settings_trace(query: str, *, goal: str | None = None) -> SuccessfulTrace:
    return SuccessfulTrace(
        goal=goal or f"Search Settings for {query}",
        steps=[
            TraceStep(
                action=SemanticAction(action="launch_app", package="com.android.settings"),
                source_screen="launcher.home",
                destination_screen="settings.home",
                verified=True,
            ),
            TraceStep(
                action=SemanticAction(action="wait", expected_screen="settings.home"),
                source_screen="settings.home",
                destination_screen="settings.home",
                verified=True,
            ),
            TraceStep(
                action=SemanticAction(action="click", target="label:Search settings"),
                source_screen="settings.home",
                destination_screen="settings.search",
                verified=True,
            ),
            TraceStep(
                action=SemanticAction(
                    action="set_text", target="label:Search settings", value=query
                ),
                source_screen="settings.search",
                destination_screen="settings.search_results",
                verified=True,
            ),
            TraceStep(
                action=SemanticAction(action="wait", expected_screen="settings.search_results"),
                source_screen="settings.search_results",
                destination_screen="settings.search_results",
                verified=True,
            ),
        ],
    )


def _parse_settings(query: str):
    intent = IntentParser().parse(f"Search Settings for {query}")
    assert intent is not None
    return intent


async def _replay_engine(settings_fixture, connection) -> tuple[SkillEngine, ReplayDeviceAdapter]:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    return SkillEngine(SemanticExecutor(device, tracker), connection), device


@pytest.mark.asyncio
async def test_repeated_private_traces_promote_a_zero_model_skill(
    settings_fixture, tmp_path
) -> None:
    with Database(tmp_path / "learning.db") as database:
        learner = SkillLearner(database.connection)
        candidate = learner.observe(
            _parse_settings("Wi-Fi"),
            _settings_trace("Wi-Fi", goal="raw prompt must not be retained"),
            session_id="session-1",
        )
        duplicate = learner.observe(
            _parse_settings("Wi-Fi"),
            _settings_trace("Wi-Fi"),
            session_id="session-1",
        )
        assert duplicate.observations == 1

        for number, query in enumerate(("Bluetooth", "VPN"), start=2):
            candidate = learner.observe(
                _parse_settings(query),
                _settings_trace(query),
                session_id=f"session-{number}",
            )

        assert candidate.status is LearningStatus.CANDIDATE
        assert candidate.observations == 3
        assert candidate.skill.steps[3].value == "${query}"
        stored = database.connection.execute(
            "SELECT document_json FROM learning_candidates WHERE id=?", (candidate.id,)
        ).fetchone()[0]
        assert all(
            private not in stored
            for private in ("Wi-Fi", "Bluetooth", "VPN", "raw prompt must not be retained")
        )

        first_engine, _ = await _replay_engine(settings_fixture, database.connection)
        first = await learner.replay_validate(
            candidate.id, first_engine, {"query": "Wi-Fi"}, replay_id="replay-1"
        )
        assert first.run.status == "success"
        assert first.candidate.status is LearningStatus.CANDIDATE
        assert database.connection.execute("SELECT COUNT(*) FROM skill_runs").fetchone()[0] == 0

        second_engine, _ = await _replay_engine(settings_fixture, database.connection)
        second = await learner.replay_validate(
            candidate.id, second_engine, {"query": "Wi-Fi"}, replay_id="replay-2"
        )
        assert second.run.status == "success"
        assert second.candidate.status is LearningStatus.PROMOTED
        assert second.promoted_binding_id is not None

        runtime_engine, runtime_device = await _replay_engine(settings_fixture, database.connection)
        result = await CachedIntentExecutor(
            IntentCache(database.connection), runtime_engine
        ).execute("Search Settings for Wi-Fi")
        history = database.connection.execute(
            "SELECT parameters_json FROM skill_runs ORDER BY id DESC LIMIT 1"
        ).fetchone()[0]

    assert result.status is CachedIntentStatus.SUCCESS
    assert result.model_calls == 0
    assert runtime_device.current_id == "results"
    assert "Wi-Fi" not in history
    assert json.loads(history) == {"query": {"redacted": True, "type": "str"}}


@pytest.mark.asyncio
async def test_failed_replay_rejects_candidate(settings_fixture, tmp_path) -> None:
    with Database(tmp_path / "rejected.db") as database:
        learner = SkillLearner(database.connection)
        candidate = None
        for number, query in enumerate(("Wi-Fi", "Bluetooth", "VPN"), start=1):
            candidate = learner.observe(
                _parse_settings(query),
                _settings_trace(query),
                session_id=f"session-{number}",
            )
        assert candidate is not None

        device = ReplayDeviceAdapter(settings_fixture)
        await device.launch_app("com.android.settings")
        tracker = StateTracker()
        tracker.observe(await device.get_ui_state())
        failed = await learner.replay_validate(
            candidate.id,
            SkillEngine(SemanticExecutor(device, tracker), database.connection),
            {"query": "Wi-Fi"},
            replay_id="bad-start-screen",
        )

    assert failed.run.status == "failed"
    assert failed.candidate.status is LearningStatus.REJECTED
    assert failed.promoted_binding_id is None


def test_encoded_deep_link_slots_are_generalized_without_retaining_value() -> None:
    intent = IntentParser().parse("Navigate to New York using Google Maps")
    assert intent is not None
    trace = SuccessfulTrace(
        goal="Navigate to New York using Google Maps",
        steps=[
            TraceStep(
                action=SemanticAction(
                    action="open_deep_link",
                    uri="https://www.google.com/maps/dir/?api=1&destination=New+York",
                ),
                source_screen="launcher.home",
                destination_screen="maps.directions",
                verified=True,
            )
        ],
    )

    proposal = compile_parameterized_proposal(intent, trace, "learned_navigation")

    assert proposal.steps[0].uri is not None
    assert "New York" not in proposal.model_dump_json()
    assert "New+York" not in proposal.model_dump_json()
    assert "${destination|query}" in proposal.steps[0].uri


@pytest.mark.parametrize(
    "action,error",
    [
        (SemanticAction(action="click", target="h:temporary"), "ephemeral"),
        (SemanticAction(action="click", target="label:Send"), "commit"),
    ],
)
def test_unsafe_trace_targets_are_not_learnable(action: SemanticAction, error: str) -> None:
    intent = _parse_settings("Wi-Fi")
    trace = SuccessfulTrace(
        goal="private",
        steps=[
            TraceStep(
                action=action,
                source_screen="settings.home",
                destination_screen="settings.search",
                verified=True,
            )
        ],
    )
    with pytest.raises(ValueError, match=error):
        compile_parameterized_proposal(intent, trace, "unsafe_candidate")


def test_missing_content_parameter_and_unknown_intent_are_blocked() -> None:
    intent = _parse_settings("Wi-Fi")
    trace = SuccessfulTrace(
        goal="private",
        steps=[
            TraceStep(
                action=SemanticAction(action="launch_app", package="com.android.settings"),
                source_screen="launcher.home",
                destination_screen="settings.home",
                verified=True,
            )
        ],
    )
    with pytest.raises(ValueError, match="did not parameterize"):
        compile_parameterized_proposal(intent, trace, "missing_parameter")
    assert safety_for_intent("message.compose") is IntentSafety.CONFIRM
    assert safety_for_intent("bank.transfer") is IntentSafety.BLOCKED


def test_learning_cli_exposes_counts_but_not_private_content(tmp_path) -> None:
    database_path = tmp_path / "inspect.db"
    with Database(database_path) as database:
        SkillLearner(database.connection).observe(
            _parse_settings("Secret private query"),
            _settings_trace("Secret private query", goal="private original prompt"),
            session_id="private-session-id",
        )

    result = CliRunner().invoke(app, ["learning-list", "--database", str(database_path)])

    assert result.exit_code == 0
    assert '"observations": 1' in result.stdout
    assert "android.settings.search" in result.stdout
    assert "Secret private query" not in result.stdout
    assert "private original prompt" not in result.stdout
    assert "private-session-id" not in result.stdout
