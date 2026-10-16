from __future__ import annotations

import pytest

from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_runtime import CachedIntentExecutor, CachedIntentStatus
from sma.memory.database import MIGRATIONS, Database
from sma.memory.intent_cache import IntentCache, IntentSafety
from sma.memory.llm_result_cache import (
    GeneralizedIntentParser,
    GeneralizedLLMResultCache,
    LLMCacheProvenance,
    PlannedSemanticAction,
)
from sma.memory.skill_engine import SkillEngine
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import ActionResult, ActionStatus, SemanticAction


async def _settings_home(settings_fixture):
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    executor = SemanticExecutor(device, tracker)
    launched = await executor.execute(
        SemanticAction(action="launch_app", package="com.android.settings")
    )
    assert launched.status is ActionStatus.SUCCESS
    return device, tracker, executor


@pytest.mark.asyncio
async def test_verified_llm_result_is_generalized_and_reused_without_model(
    settings_fixture, tmp_path
) -> None:
    device, tracker, executor = await _settings_home(settings_fixture)
    before = tracker.current
    assert before is not None
    search = next(node for node in before.nodes if node.label == "Search settings")
    plan = PlannedSemanticAction(
        canonical_intent="android.settings.search",
        parameters={"query": "Wi-Fi"},
        intent_confidence=0.94,
        action=SemanticAction(
            action="click",
            target=search.handle,
            expected_epoch=before.epoch,
            expected_revision=before.revision,
        ),
    )
    outcome = await executor.execute(plan.action)
    assert outcome.status is ActionStatus.SUCCESS and outcome.verified

    with Database(tmp_path / "cache.db") as database:
        learned = GeneralizedLLMResultCache(database.connection).learn_verified(
            "Look for Wi-Fi inside Settings",
            plan,
            outcome,
            before,
            after=tracker.current,
            provenance=LLMCacheProvenance(model_id="test-model"),
        )
        row = database.connection.execute(
            "SELECT * FROM llm_result_templates WHERE id=?", (learned.template_id,)
        ).fetchone()
        skill_json = database.connection.execute(
            "SELECT document_json FROM skills WHERE id=?", (learned.skill_id,)
        ).fetchone()[0]

        assert row["goal_template"] == "look for ${query} inside settings"
        assert "Wi-Fi" not in row["goal_template"]
        assert "Wi-Fi" not in skill_json
        assert row["model_id"] == "test-model"

        second_device, second_tracker, second_executor = await _settings_home(settings_fixture)
        parser = GeneralizedIntentParser(database.connection)
        parsed = parser.parse("Look for Bluetooth inside Settings")
        assert parsed is not None
        assert parsed.canonical_intent == "android.settings.search"
        assert parsed.parameters == {"query": "Bluetooth"}
        result = await CachedIntentExecutor(
            IntentCache(database.connection),
            SkillEngine(second_executor, database.connection),
            parser,
        ).execute("Look for Bluetooth inside Settings", confirmed=True)

    assert result.status is CachedIntentStatus.SUCCESS
    assert result.model_calls == 0
    assert second_device.current_id == "search"


def test_unverified_or_blocked_model_result_is_never_cached(settings_data, tmp_path) -> None:
    from sma.perception.canonicalizer import canonicalize

    state = canonicalize(settings_data["states"]["settings_home"])
    plan = PlannedSemanticAction(
        canonical_intent="bank.transfer",
        parameters={"recipient": "Alice"},
        action=SemanticAction(action="back"),
    )
    failed = ActionResult(status=ActionStatus.VERIFICATION_FAILED, action="back", verified=False)
    with Database(tmp_path / "rejected.db") as database:
        cache = GeneralizedLLMResultCache(database.connection)
        with pytest.raises(ValueError, match="successfully executed and verified"):
            cache.learn_verified(
                "transfer to Alice",
                plan,
                failed,
                state,
                provenance=LLMCacheProvenance(model_id="test-model"),
            )
        success = failed.model_copy(update={"status": ActionStatus.SUCCESS, "verified": True})
        with pytest.raises(ValueError, match="blocked intent"):
            cache.learn_verified(
                "transfer to Alice",
                plan,
                success,
                state,
                provenance=LLMCacheProvenance(model_id="test-model"),
            )
        assert (
            database.connection.execute("SELECT COUNT(*) FROM llm_result_templates").fetchone()[0]
            == 0
        )


def test_verified_novel_low_risk_intent_can_be_learned(settings_data, tmp_path) -> None:
    from sma.perception.canonicalizer import canonicalize

    state = canonicalize(settings_data["states"]["settings_home"])
    plan = PlannedSemanticAction(
        canonical_intent="productivity.settings.lookup",
        parameters={"query": "Battery optimization"},
        action=SemanticAction(action="back"),
    )
    success = ActionResult(status=ActionStatus.SUCCESS, action="back", verified=True)

    with Database(tmp_path / "novel.db") as database:
        learned = GeneralizedLLMResultCache(database.connection).learn_verified(
            "Find Battery optimization quickly",
            plan,
            success,
            state,
            provenance=LLMCacheProvenance(model_id="test-model"),
        )

    assert learned.safety is IntentSafety.LOW
    assert learned.generalized_template == "find ${query} quickly"


def test_llm_result_cache_migration_is_present(tmp_path) -> None:
    with Database(tmp_path / "schema.db") as database:
        assert database.connection.execute("PRAGMA user_version").fetchone()[0] == len(MIGRATIONS)
        columns = {
            row[1]
            for row in database.connection.execute(
                "PRAGMA table_info(llm_result_templates)"
            ).fetchall()
        }
    assert {"binding_id", "goal_template", "goal_fingerprint", "model_id"} <= columns
