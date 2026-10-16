from __future__ import annotations

import pytest

from sma.intelligence.agent_api import (
    AgentOutcomeRequest,
    AgentPlanningGateway,
    AgentPlanRequest,
)
from sma.intelligence.model_client import ModelResponse, ModelUsage
from sma.memory.database import Database
from sma.memory.llm_result_cache import PlannedSemanticAction
from sma.perception.canonicalizer import canonicalize
from sma.protocol.models import ActionStatus, SemanticAction


class FakeIntentPlanner:
    def __init__(self) -> None:
        self.calls = 0

    async def plan_with_intent(self, goal, state):
        self.calls += 1
        search = next(node for node in state.nodes if node.label == "Search settings")
        query = "Bluetooth" if "Bluetooth" in goal else "Wi-Fi"
        return (
            PlannedSemanticAction(
                canonical_intent="android.settings.search",
                parameters={"query": query},
                intent_confidence=0.96,
                action=SemanticAction(action="click", target=search.handle),
            ),
            ModelResponse(content="{}", usage=ModelUsage(), model="fake-cloud"),
        )


class FakeStopPlanner:
    async def plan_with_intent(self, goal, state):
        return (
            PlannedSemanticAction(
                canonical_intent="android.settings.open",
                parameters={},
                intent_confidence=0.96,
                action=SemanticAction(action="stop"),
            ),
            ModelResponse(content="{}", usage=ModelUsage(), model="fake-cloud"),
        )


class FakeMispointedScrollPlanner:
    async def plan_with_intent(self, goal, state):
        title = next(node for node in state.nodes if node.label == "Settings")
        return (
            PlannedSemanticAction(
                canonical_intent="android.settings.scroll",
                parameters={},
                intent_confidence=0.96,
                action=SemanticAction(action="scroll", target=title.handle),
            ),
            ModelResponse(content="{}", usage=ModelUsage(), model="fake-cloud"),
        )


@pytest.mark.asyncio
async def test_gateway_learns_verified_model_result_then_returns_zero_call_plan(
    settings_data, tmp_path
) -> None:
    state = canonicalize(settings_data["states"]["settings_home"])
    planner = FakeIntentPlanner()
    with Database(tmp_path / "gateway.db") as database:
        gateway = AgentPlanningGateway(database, planner)  # type: ignore[arg-type]
        first = await gateway.plan(
            AgentPlanRequest(
                goal="Look for Wi-Fi inside Settings",
                state=state,
                profile_id="account-a",
            )
        )
        assert first.source == "llm" and first.model_calls == 1
        assert first.confirmation_required is True
        assert first.attempt_id is not None
        assert "Search settings" in (first.message or "")
        assert planner.calls == 1
        recorded = gateway.outcome(
            AgentOutcomeRequest(
                attempt_id=first.attempt_id,
                status=ActionStatus.SUCCESS,
                verified=True,
                final_screen="com.android.settings:SearchActivity",
            )
        )
        assert recorded.cached is True
        assert recorded.local_artifact is not None
        assert recorded.local_artifact.goal_template == "look for ${query} inside settings"
        assert "Wi-Fi" not in recorded.local_artifact.model_dump_json()

        warm = await gateway.plan(
            AgentPlanRequest(
                goal="Look for Bluetooth inside Settings",
                state=state,
                profile_id="account-a",
                confirmed=True,
            )
        )
        other_account = await gateway.plan(
            AgentPlanRequest(
                goal="Look for Bluetooth inside Settings",
                state=state,
                profile_id="account-b",
            )
        )
        stored = database.connection.execute(
            "SELECT goal_template, scope_id FROM llm_result_templates"
        ).fetchone()
        assert gateway.clear_memory("account-b") == 0
        assert gateway.clear_memory("account-a") == 1
        assert (
            database.connection.execute("SELECT COUNT(*) FROM llm_result_templates").fetchone()[0]
            == 0
        )

    assert warm.source == "cache" and warm.model_calls == 0
    assert warm.local_artifact is not None
    assert warm.actions[0].target == "label:Search settings"
    assert other_account.source == "llm" and other_account.model_calls == 1
    assert planner.calls == 2
    assert stored["goal_template"] == "look for ${query} inside settings"
    assert stored["scope_id"] == "account-a"


@pytest.mark.asyncio
async def test_terminal_model_step_is_learned_as_screen_scoped_completion(
    settings_data, tmp_path
) -> None:
    state = canonicalize(settings_data["states"]["settings_home"])
    with Database(tmp_path / "terminal.db") as database:
        gateway = AgentPlanningGateway(database, FakeStopPlanner())  # type: ignore[arg-type]
        first = await gateway.plan(AgentPlanRequest(goal="finish this settings task", state=state))
        assert first.terminal is True and first.attempt_id is not None
        outcome = gateway.outcome(
            AgentOutcomeRequest(
                attempt_id=first.attempt_id,
                status=ActionStatus.SUCCESS,
                verified=True,
            )
        )
        warm = await gateway.plan(AgentPlanRequest(goal="finish this settings task", state=state))

    assert outcome.local_artifact is not None and outcome.local_artifact.terminal is True
    assert outcome.local_artifact.actions[0].action == "stop"
    assert warm.source == "cache" and warm.terminal is True and warm.model_calls == 0


@pytest.mark.asyncio
async def test_gateway_recovers_mispointed_scroll_when_screen_has_one_scroll_container(
    settings_data, tmp_path
) -> None:
    state = canonicalize(settings_data["states"]["settings_home"])
    with Database(tmp_path / "scroll-recovery.db") as database:
        gateway = AgentPlanningGateway(
            database,
            FakeMispointedScrollPlanner(),  # type: ignore[arg-type]
        )
        response = await gateway.plan(
            AgentPlanRequest(goal="Scroll down in Settings", state=state)
        )

    assert response.source == "llm"
    assert response.actions[0].action == "scroll"
    assert response.actions[0].target == "label:settings/list"


@pytest.mark.asyncio
async def test_gateway_replaces_stop_when_requested_control_is_offscreen(
    settings_data, tmp_path
) -> None:
    state = canonicalize(settings_data["states"]["settings_home"])
    state = state.model_copy(
        update={
            "nodes": [
                node.model_copy(update={"visible": False})
                if node.label == "Network & internet"
                else node
                for node in state.nodes
            ]
        }
    )
    with Database(tmp_path / "premature-stop.db") as database:
        gateway = AgentPlanningGateway(database, FakeStopPlanner())  # type: ignore[arg-type]
        response = await gateway.plan(
            AgentPlanRequest(goal="Open Network and internet", state=state)
        )

    assert response.terminal is False
    assert response.actions[0].action == "scroll"
    assert response.actions[0].target == "label:settings/list"
