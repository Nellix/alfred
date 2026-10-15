from __future__ import annotations

import pytest

from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_parser import IntentParser
from sma.intelligence.intent_runtime import CachedIntentExecutor, CachedIntentStatus
from sma.memory.database import MIGRATIONS, Database
from sma.memory.intent_cache import (
    BindingStatus,
    CompatibilityContext,
    IntentCache,
    IntentSafety,
    SkillCompatibility,
)
from sma.memory.skill_engine import Skill, SkillEngine
from sma.perception.state_tracker import StateTracker


def test_common_intents_are_parsed_locally_without_retaining_prompt() -> None:
    parser = IntentParser()
    settings = parser.parse("  Search Android Settings for Wi-Fi  ")
    youtube = parser.parse("Open YouTube and play lo-fi music")
    navigation = parser.parse("Take me to Rome using Google Maps")

    assert settings is not None
    assert settings.canonical_intent == "android.settings.search"
    assert settings.parameters == {"query": "Wi-Fi"}
    assert "request_fingerprint" not in settings.model_dump()
    assert youtube is not None and youtube.parameters["query"] == "lo-fi music"
    assert navigation is not None and navigation.parameters["destination"] == "Rome"
    assert parser.parse("do an entirely novel task") is None


def test_database_applies_versioned_intent_cache_migration(tmp_path) -> None:
    with Database(tmp_path / "cache.db") as database:
        version = database.connection.execute("PRAGMA user_version").fetchone()[0]
        tables = {
            row[0]
            for row in database.connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            ).fetchall()
        }
    assert version == len(MIGRATIONS)
    assert {
        "intent_bindings",
        "intent_events",
        "learning_candidates",
        "learning_observations",
        "candidate_replays",
        "llm_result_templates",
    } <= tables
    assert "provider_artifacts" not in tables
    assert "contribution_batches" not in tables
    assert "account_vault_items" not in tables


@pytest.mark.asyncio
async def test_cache_hit_executes_parameterized_skill_with_zero_model_calls(
    settings_fixture, tmp_path
) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    skill = Skill.load(settings_fixture.parent / "open_settings_search.yaml")

    with Database(tmp_path / "cache.db") as database:
        cache = IntentCache(database.connection)
        cache.save_skill(skill)
        binding_id = cache.bind(
            "android.settings.search",
            skill.id,
            skill.version,
            status=BindingStatus.ACTIVE,
            confidence=0.98,
        )
        runtime = CachedIntentExecutor(
            cache,
            SkillEngine(SemanticExecutor(device, tracker), database.connection),
        )
        result = await runtime.execute("Search Android Settings for Wi-Fi")
        binding = database.connection.execute(
            "SELECT successes, failures, confidence FROM intent_bindings WHERE id=?",
            (binding_id,),
        ).fetchone()
        event = database.connection.execute("SELECT * FROM intent_events").fetchone()

    assert result.status is CachedIntentStatus.SUCCESS
    assert result.model_calls == 0
    assert result.skill_run is not None and result.skill_run.steps_completed == 5
    assert device.current_id == "results"
    assert binding["successes"] == 1 and binding["failures"] == 0
    assert binding["confidence"] > 0.98
    event_columns = set(event.keys())
    assert "prompt" not in event_columns and "parameters" not in event_columns


@pytest.mark.asyncio
async def test_confirmation_gate_does_not_touch_device(settings_fixture, tmp_path) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    skill = Skill.load(settings_fixture.parent / "open_settings_search.yaml")
    with Database(tmp_path / "confirm.db") as database:
        cache = IntentCache(database.connection)
        cache.save_skill(skill)
        cache.bind(
            "android.settings.search",
            skill.id,
            skill.version,
            safety=IntentSafety.CONFIRM,
            status=BindingStatus.ACTIVE,
            confidence=0.99,
        )
        result = await CachedIntentExecutor(
            cache, SkillEngine(SemanticExecutor(device, tracker), database.connection)
        ).execute("Search Settings for Wi-Fi")

    assert result.status is CachedIntentStatus.CONFIRMATION_REQUIRED
    assert result.fallback_recommended is False
    assert device.actions == []


def test_candidates_and_incompatible_skills_are_not_auto_executed(
    settings_fixture, tmp_path
) -> None:
    skill = Skill.load(settings_fixture.parent / "open_settings_search.yaml")
    intent = IntentParser().parse("Search Settings for Wi-Fi")
    assert intent is not None
    with Database(tmp_path / "compatibility.db") as database:
        cache = IntentCache(database.connection)
        cache.save_skill(skill)
        cache.bind(
            "android.settings.search",
            skill.id,
            skill.version,
            compatibility=SkillCompatibility(min_android_api=35),
            status=BindingStatus.ACTIVE,
            confidence=0.99,
        )
        assert cache.lookup(intent, CompatibilityContext(android_api=33)) is None

        cache.bind(
            "android.settings.search",
            skill.id,
            skill.version,
            status=BindingStatus.CANDIDATE,
            confidence=0.99,
        )
        assert cache.lookup(intent, CompatibilityContext(android_api=35)) is None


def test_repeated_failures_decay_then_disable_binding(settings_fixture, tmp_path) -> None:
    skill = Skill.load(settings_fixture.parent / "open_settings_search.yaml")
    with Database(tmp_path / "decay.db") as database:
        cache = IntentCache(database.connection)
        cache.save_skill(skill)
        binding_id = cache.bind(
            "android.settings.search",
            skill.id,
            skill.version,
            status=BindingStatus.ACTIVE,
            confidence=0.98,
        )
        cache.record_outcome(binding_id, success=False, reason_code="verification_failed")
        once = database.connection.execute(
            "SELECT confidence, status FROM intent_bindings WHERE id=?", (binding_id,)
        ).fetchone()
        cache.record_outcome(binding_id, success=False, reason_code="verification_failed")
        twice = database.connection.execute(
            "SELECT confidence, status, consecutive_failures FROM intent_bindings WHERE id=?",
            (binding_id,),
        ).fetchone()

    assert once["confidence"] < IntentCache.AUTO_RUN_CONFIDENCE
    assert once["status"] == BindingStatus.ACTIVE.value
    assert twice["status"] == BindingStatus.DISABLED.value
    assert twice["consecutive_failures"] == 2


def test_stale_confidence_decay_and_manual_rollback(settings_fixture, tmp_path) -> None:
    first = Skill.load(settings_fixture.parent / "open_settings_search.yaml")
    second = first.model_copy(update={"version": 2})
    with Database(tmp_path / "maintenance.db") as database:
        cache = IntentCache(database.connection)
        cache.save_skill(first)
        cache.save_skill(second)
        first_id = cache.bind(
            "android.settings.search",
            first.id,
            first.version,
            status=BindingStatus.ACTIVE,
            confidence=0.96,
        )
        second_id = cache.bind(
            "android.settings.search",
            second.id,
            second.version,
            status=BindingStatus.ACTIVE,
            confidence=0.98,
        )
        database.connection.execute("UPDATE intent_bindings SET updated_at=1, last_used_at=NULL")
        assert cache.rollback(second_id) == first_id
        assert cache.decay_stale(older_than=50, factor=0.5, now=100) == 1
        rows = {
            row["id"]: row
            for row in database.connection.execute(
                "SELECT id, confidence, status FROM intent_bindings"
            ).fetchall()
        }
        event = database.connection.execute(
            "SELECT reason_code FROM intent_events WHERE binding_id=?", (second_id,)
        ).fetchone()

    assert rows[first_id]["confidence"] == pytest.approx(0.48)
    assert rows[second_id]["status"] == BindingStatus.DISABLED.value
    assert event["reason_code"] == "version_rollback"
