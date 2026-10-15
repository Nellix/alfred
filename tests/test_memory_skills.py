from __future__ import annotations

import asyncio

import pytest

from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.memory.database import Database
from sma.memory.matcher import screen_similarity
from sma.memory.screen_graph import ScreenGraph
from sma.memory.skill_compiler import SuccessfulTrace, TraceStep, compile_proposal
from sma.memory.skill_engine import Skill, SkillEngine
from sma.perception.canonicalizer import canonicalize
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import SemanticAction


@pytest.mark.asyncio
async def test_approved_workflow_executes_with_zero_model_calls(settings_fixture, tmp_path) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    skill = Skill.load(settings_fixture.parent / "open_settings_search.yaml")
    with Database(tmp_path / "memory.db") as database:
        result = await SkillEngine(SemanticExecutor(device, tracker), database.connection).run(
            skill, {"query": "Wi-Fi"}
        )
        history = database.connection.execute("SELECT * FROM skill_runs").fetchone()
    assert result.status == "success"
    assert result.steps_completed == 5 and result.model_calls == 0
    assert history["status"] == "success"
    assert device.current_id == "results"


@pytest.mark.asyncio
async def test_skill_parameter_validation_and_approval(settings_fixture) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    skill = Skill.load(settings_fixture.parent / "open_settings_search.yaml")
    engine = SkillEngine(SemanticExecutor(device, tracker))
    assert (await engine.run(skill, {"query": "x; rm -rf /"})).status == "failed"
    assert (
        await engine.run(skill.model_copy(update={"approved": False}), {"query": "Wi-Fi"})
    ).status == "failed"


def test_screen_graph_persists_observations_and_transitions(settings_data: dict, tmp_path) -> None:
    source = canonicalize(settings_data["states"]["settings_home"])
    destination = canonicalize(settings_data["states"]["search"])
    with Database(tmp_path / "graph.db") as database:
        graph = ScreenGraph(database)
        first_id = graph.observe(source)
        assert graph.observe(source) == first_id
        graph.record_transition(
            source, SemanticAction(action="click", target="x"), destination, success=True
        )
        row = database.connection.execute(
            "SELECT observation_count FROM screens WHERE id=?", (first_id,)
        ).fetchone()
        assert row[0] >= 3
        assert graph.transitions_from(source, min_confidence=0.6)


def test_tolerant_screen_matching_ignores_dynamic_numbers() -> None:
    a = canonicalize({"package": "p", "elements": [{"class": "TextView", "text": "Updated 10:31"}]})
    b = canonicalize({"package": "p", "elements": [{"class": "TextView", "text": "Updated 10:32"}]})
    assert screen_similarity(a, b) == 1.0


@pytest.mark.asyncio
async def test_skill_timeout_stops_execution(settings_fixture) -> None:
    class SlowReplay(ReplayDeviceAdapter):
        async def launch_app(self, package: str) -> None:
            await asyncio.sleep(0.05)

    device = SlowReplay(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    skill = Skill.model_validate(
        {
            "id": "timeout_test",
            "version": 1,
            "approved": True,
            "steps": [
                {
                    "action": "launch_app",
                    "package": "com.android.settings",
                    "timeout_seconds": 0.01,
                }
            ],
        }
    )
    result = await SkillEngine(SemanticExecutor(device, tracker)).run(skill, {})
    assert result.status == "failed"
    assert "timed out" in (result.error or "")


def test_trace_compiler_proposes_unapproved_skill() -> None:
    trace = SuccessfulTrace(
        goal="open settings",
        steps=[
            TraceStep(
                action=SemanticAction(action="launch_app", package="com.android.settings"),
                source_screen="launcher.home",
                destination_screen="settings.home",
                verified=True,
            )
        ],
    )
    proposal = compile_proposal(trace, "open_settings")
    assert proposal.approved is False
    assert proposal.precondition_screen == "launcher.home"


def test_screen_graph_tolerant_recognition(settings_data: dict, tmp_path) -> None:
    original = canonicalize(settings_data["states"]["settings_home"])
    changed_raw = settings_data["states"]["settings_home"] | {"screen_id": "dynamic-name"}
    changed = canonicalize(changed_raw)
    with Database(tmp_path / "recognition.db") as database:
        graph = ScreenGraph(database)
        graph.observe(original)
        match = graph.recognize(changed)
    assert match is not None
    assert match["match_confidence"] == 1.0
