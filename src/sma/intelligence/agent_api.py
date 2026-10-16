from __future__ import annotations

import contextlib
import json
import os
import re
import time
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from sma.execution.action_resolver import AmbiguousTarget, TargetNotFound, resolve_target
from sma.execution.policy import ActionPolicy, PolicyViolation
from sma.intelligence.intent_parser import IntentParser
from sma.intelligence.model_client import (
    ChatCompletionsClient,
    FallbackChatCompletionsClient,
    ModelUnavailable,
    model_provider_chain_from_environment,
)
from sma.intelligence.native_catalog import CatalogContext, NativeActionCatalog
from sma.intelligence.planner import LLMPlanner
from sma.memory.database import Database
from sma.memory.intent_cache import CompatibilityContext, IntentCache, IntentMatch, IntentSafety
from sma.memory.llm_result_cache import (
    GeneralizedLLMResultCache,
    LLMCacheProvenance,
    PlannedSemanticAction,
    safety_for_planned_action,
)
from sma.memory.skill_engine import Skill, render_skill_actions
from sma.protocol.models import (
    ActionResult,
    ActionStatus,
    CanonicalNode,
    CanonicalState,
    SemanticAction,
)


class AgentPlanRequest(BaseModel):
    goal: str = Field(min_length=1, max_length=4000)
    state: CanonicalState
    confirmed: bool = False
    android_api: int | None = Field(default=None, ge=26, le=100)
    locale: str | None = Field(default=None, max_length=32)
    installed_packages: set[str] = Field(default_factory=set, max_length=500)
    profile_id: str = Field(default="device", pattern=r"^[A-Za-z0-9_.:-]{1,128}$")


class AgentPlanResponse(BaseModel):
    source: str
    actions: list[SemanticAction] = Field(default_factory=list)
    canonical_intent: str | None = None
    attempt_id: str | None = None
    confirmation_required: bool = False
    model_calls: int = 0
    model_provider: str | None = None
    model_id: str | None = None
    message: str | None = None
    local_artifact: LocalCacheArtifact | None = None
    terminal: bool = False


class AgentOutcomeRequest(BaseModel):
    attempt_id: str
    status: ActionStatus
    verified: bool
    message: str | None = Field(default=None, max_length=1000)
    final_screen: str | None = Field(default=None, max_length=500)


class AgentOutcomeResponse(BaseModel):
    accepted: bool
    cached: bool = False
    binding_id: int | None = None
    reason: str | None = None
    local_artifact: LocalCacheArtifact | None = None


class LocalCacheArtifact(BaseModel):
    canonical_intent: str
    goal_template: str | None = None
    goal_fingerprint: str | None = None
    parameter_types: dict[str, str]
    source_screen: str
    safety: IntentSafety
    confidence: float = Field(ge=0.0, le=1.0)
    actions: list[SemanticAction] = Field(min_length=1, max_length=50)
    terminal: bool = False


class MemoryClearRequest(BaseModel):
    profile_id: str = Field(pattern=r"^[A-Za-z0-9_.:-]{1,128}$")


class MemoryClearResponse(BaseModel):
    templates_deleted: int


@dataclass
class _PendingAttempt:
    created_at: float
    profile_id: str
    goal: str | None = None
    plan: PlannedSemanticAction | None = None
    before: CanonicalState | None = None
    provenance: LLMCacheProvenance | None = None
    binding_id: int | None = None


class AgentPlanningGateway:
    """Headless planning boundary used by the clean-room Android frontend."""

    ATTEMPT_TTL_SECONDS = 300

    def __init__(
        self,
        database: Database,
        planner: LLMPlanner | None = None,
        *,
        model_configuration_error: str | None = None,
    ) -> None:
        self.database = database
        self.planner = planner
        self.model_configuration_error = model_configuration_error
        self.intent_cache = IntentCache(database.connection)
        self.result_cache = GeneralizedLLMResultCache(database.connection)
        self.native = NativeActionCatalog()
        self._pending: dict[str, _PendingAttempt] = {}

    async def plan(self, request: AgentPlanRequest) -> AgentPlanResponse:
        self._expire_attempts()
        context = CompatibilityContext(
            android_api=request.android_api,
            locale=request.locale,
            current_package=request.state.package,
            current_screen=request.state.screen_id,
        )
        parsed = IntentParser().parse(request.goal)
        native = (
            self.native.resolve(
                parsed,
                CatalogContext(installed_packages=request.installed_packages),
            )
            if parsed
            else None
        )
        if native is not None and native.terminal:
            return AgentPlanResponse(
                source="native",
                actions=native.actions,
                canonical_intent=native.canonical_intent,
                confirmation_required=native.completion_confirmation_required,
                message=native.note,
                terminal=native.terminal,
            )

        scoped = self.result_cache.resolve(
            request.goal,
            context,
            scope_id=request.profile_id,
        )
        if scoped is not None:
            return self._cached_response(scoped, request)

        generic = self.intent_cache.lookup(parsed, context) if parsed is not None else None
        if generic is not None:
            return self._cached_response(generic, request)

        if self.planner is None:
            raise HTTPException(
                503,
                self.model_configuration_error
                or (
                    "no local mapping matched and no Gemini, OpenRouter, "
                    "or local model is configured"
                ),
            )
        try:
            plan, response = await self.planner.plan_with_intent(request.goal, request.state)
        except ModelUnavailable as exc:
            raise HTTPException(503, str(exc)) from exc
        plan = self._replace_premature_stop(plan, request.goal, request.state)
        plan = self._validated_plan(plan, request.state)
        plan = self._infer_control_parameter(plan, request.goal)
        safety = safety_for_planned_action(plan.canonical_intent, plan.action)
        if safety is IntentSafety.BLOCKED:
            raise HTTPException(422, "the planned intent is blocked by policy")
        attempt_id = uuid4().hex
        self._pending[attempt_id] = _PendingAttempt(
            created_at=time.time(),
            profile_id=request.profile_id,
            goal=request.goal,
            plan=plan,
            before=request.state,
            provenance=LLMCacheProvenance(model_id=response.model),
        )
        return AgentPlanResponse(
            source="llm",
            actions=[plan.action],
            canonical_intent=plan.canonical_intent,
            attempt_id=attempt_id,
            confirmation_required=(
                safety is IntentSafety.CONFIRM and not request.confirmed
            ),
            model_calls=1,
            model_provider=response.provider,
            model_id=response.model,
            message=(
                self._confirmation_message(plan, request.state)
                if safety is IntentSafety.CONFIRM and not request.confirmed
                else "The result will be generalized only after verified device execution."
            ),
            terminal=plan.action.action == "stop",
        )

    @staticmethod
    def _validated_plan(
        plan: PlannedSemanticAction,
        state: CanonicalState,
    ) -> PlannedSemanticAction:
        action = plan.action
        node = None
        if action.action in {"click", "set_text", "scroll"}:
            required = {"click": "click", "set_text": "set_text", "scroll": "scroll"}[
                action.action
            ]
            if not action.target:
                raise HTTPException(422, "the model omitted the semantic action target")
            try:
                node = resolve_target(state, action.target, action.action).node
            except TargetNotFound as exc:
                raw = action.target.removeprefix("label:").strip().casefold()
                candidates = [
                    candidate
                    for candidate in state.nodes
                    if candidate.visible
                    and required in candidate.actions
                    and (
                        candidate.label.casefold() == raw
                        or (candidate.resource_id or "").casefold() == raw
                    )
                ]
                if len(candidates) != 1:
                    raise HTTPException(
                        422,
                        "the model selected a control absent from the current screen",
                    ) from exc
                node = candidates[0]
                action = action.model_copy(update={"target": node.handle})
            except AmbiguousTarget as exc:
                raise HTTPException(422, "the model selected an ambiguous control") from exc
            except ValueError as exc:
                assert action.target is not None
                try:
                    selected = resolve_target(state, action.target, "").node
                except (TargetNotFound, AmbiguousTarget, ValueError):
                    raise HTTPException(
                        422,
                        "the selected control does not support that action",
                    ) from exc
                node = AgentPlanningGateway._nearest_actionable_node(
                    state,
                    selected,
                    required,
                )
                if node is None:
                    raise HTTPException(
                        422,
                        "the selected control does not support that action",
                    ) from exc
                action = action.model_copy(update={"target": node.handle})
            stable_label = node.content_description or node.text or node.resource_id or node.role
            action = action.model_copy(update={"target": f"label:{stable_label.strip()}"})
        try:
            ActionPolicy().validate(action, node)
        except PolicyViolation as exc:
            raise HTTPException(422, str(exc)) from exc
        return plan.model_copy(update={"action": action})

    @staticmethod
    def _nearest_actionable_node(
        state: CanonicalState,
        selected: CanonicalNode,
        required: str,
    ) -> CanonicalNode | None:
        by_handle = {node.handle: node for node in state.nodes}
        parent_handle = selected.parent_handle
        while parent_handle is not None:
            node = by_handle.get(parent_handle)
            if node is None:
                break
            if node.visible and node.enabled and required in node.actions:
                return node
            parent_handle = node.parent_handle
        candidates = [
            candidate
            for candidate in state.nodes
            if candidate.visible and candidate.enabled and required in candidate.actions
        ]
        return candidates[0] if len(candidates) == 1 else None

    @staticmethod
    def _replace_premature_stop(
        plan: PlannedSemanticAction,
        goal: str,
        state: CanonicalState,
    ) -> PlannedSemanticAction:
        """Reveal a requested offscreen control instead of accepting an unsupported stop."""
        if plan.action.action != "stop":
            return plan
        ignored = {
            "activate",
            "alfred",
            "choose",
            "click",
            "details",
            "explanation",
            "into",
            "navigate",
            "open",
            "press",
            "read",
            "select",
            "settings",
            "show",
            "then",
        }
        goal_terms = {
            token
            for token in re.findall(r"[a-z0-9]+", goal.casefold())
            if len(token) >= 4 and token not in ignored
        }
        if not goal_terms:
            return plan

        def matches_goal(node: CanonicalNode) -> bool:
            label_terms = set(re.findall(r"[a-z0-9]+", node.label.casefold()))
            return bool(goal_terms & label_terms)

        visible_matches = [node for node in state.nodes if node.visible and matches_goal(node)]
        hidden_targets = [
            node
            for node in state.nodes
            if not node.visible and node.enabled and node.actions and matches_goal(node)
        ]
        scroll_nodes = [
            node
            for node in state.nodes
            if node.visible and node.enabled and "scroll" in node.actions
        ]
        if visible_matches or not hidden_targets or len(scroll_nodes) != 1:
            return plan
        return plan.model_copy(
            update={
                "action": plan.action.model_copy(
                    update={"action": "scroll", "target": scroll_nodes[0].handle}
                )
            }
        )

    @staticmethod
    def _infer_control_parameter(
        plan: PlannedSemanticAction,
        goal: str,
    ) -> PlannedSemanticAction:
        """Recover a reusable control slot when a model emitted a fixed UI label."""
        target = plan.action.target
        if plan.parameters or target is None or not target.startswith("label:"):
            return plan
        label = target.removeprefix("label:").strip()
        reusable = label
        for prefix in ("open ", "select ", "tap ", "click ", "choose ", "press "):
            if reusable.casefold().startswith(prefix):
                reusable = reusable[len(prefix) :].strip()
                break
        if len(reusable.split()) < 2 or reusable.casefold() not in goal.casefold():
            return plan
        return plan.model_copy(update={"parameters": {"control": reusable}})

    @staticmethod
    def _confirmation_message(
        plan: PlannedSemanticAction,
        state: CanonicalState,
    ) -> str:
        action = plan.action
        action_target = action.target
        target = (
            f"“{action_target.removeprefix('label:')}”"
            if action_target is not None and action_target.startswith("label:")
            else "the selected control"
        )
        if action.target:
            with contextlib.suppress(TargetNotFound, AmbiguousTarget, ValueError):
                target = f"“{resolve_target(state, action.target, action.action).node.label}”"
        if action.action == "click":
            effect = f"click {target}"
        elif action.action == "set_text":
            effect = f"enter the requested value in {target}"
        else:
            effect = f"perform {action.action.replace('_', ' ')} on {target}"
        return (
            f"Gemini proposes to {effect}. If it succeeds, Alfred will save only a generalized "
            "intent-to-action pattern on this phone. Continue?"
        )

    def outcome(self, request: AgentOutcomeRequest) -> AgentOutcomeResponse:
        self._expire_attempts()
        pending = self._pending.pop(request.attempt_id, None)
        if pending is None:
            raise HTTPException(404, "unknown or expired planning attempt")
        success = request.status is ActionStatus.SUCCESS and request.verified
        if pending.binding_id is not None:
            self.intent_cache.record_outcome(
                pending.binding_id,
                success=success,
                reason_code=None if success else request.status.value,
            )
            return AgentOutcomeResponse(
                accepted=True,
                cached=False,
                binding_id=pending.binding_id,
                reason="cached binding outcome recorded",
            )
        if not success:
            return AgentOutcomeResponse(
                accepted=True,
                reason="unverified or failed model output was not cached",
            )
        if not all((pending.goal, pending.plan, pending.before, pending.provenance)):
            raise HTTPException(500, "planning attempt is incomplete")
        assert pending.goal is not None
        assert pending.plan is not None
        assert pending.before is not None
        assert pending.provenance is not None
        after = pending.before.model_copy(
            update={"screen_id": request.final_screen or pending.before.screen_id}
        )
        action_result = ActionResult(
            status=request.status,
            action=pending.plan.action.action,
            verified=request.verified,
            message=request.message,
        )
        try:
            learned = self.result_cache.learn_verified(
                pending.goal,
                pending.plan,
                action_result,
                pending.before,
                after=after,
                provenance=pending.provenance,
                compatibility=CompatibilityContext(
                    current_package=pending.before.package,
                    current_screen=pending.before.screen_id,
                ),
                scope_id=pending.profile_id,
            )
        except ValueError as exc:
            return AgentOutcomeResponse(accepted=True, reason=str(exc))
        return AgentOutcomeResponse(
            accepted=True,
            cached=True,
            binding_id=learned.binding_id,
            local_artifact=self._local_artifact(
                learned.binding_id,
                pending.profile_id,
            ),
        )

    def clear_memory(self, profile_id: str) -> int:
        """Delete only verified LLM-result mappings in the requested device/account scope."""
        rows = self.database.connection.execute(
            """SELECT t.id, t.binding_id, b.skill_id, b.skill_version
            FROM llm_result_templates t
            JOIN intent_bindings b ON b.id=t.binding_id
            WHERE t.scope_id=?""",
            (profile_id,),
        ).fetchall()
        with self.database.connection:
            self.database.connection.execute(
                "DELETE FROM llm_result_templates WHERE scope_id=?", (profile_id,)
            )
            for row in rows:
                self.database.connection.execute(
                    "DELETE FROM intent_events WHERE binding_id=?", (row["binding_id"],)
                )
                self.database.connection.execute(
                    "DELETE FROM intent_bindings WHERE id=?", (row["binding_id"],)
                )
                remaining = self.database.connection.execute(
                    """SELECT 1 FROM intent_bindings
                    WHERE skill_id=? AND skill_version=? LIMIT 1""",
                    (row["skill_id"], row["skill_version"]),
                ).fetchone()
                if remaining is None:
                    self.database.connection.execute(
                        "DELETE FROM skills WHERE id=? AND version=?",
                        (row["skill_id"], row["skill_version"]),
                    )
        self._pending = {
            key: value for key, value in self._pending.items() if value.profile_id != profile_id
        }
        return len(rows)

    def _cached_response(self, match: IntentMatch, request: AgentPlanRequest) -> AgentPlanResponse:
        confirmation = match.binding.safety is IntentSafety.CONFIRM
        if match.binding.safety is IntentSafety.BLOCKED:
            return AgentPlanResponse(
                source="cache",
                canonical_intent=match.binding.canonical_intent,
                message="This cached intent is blocked by policy.",
            )
        if confirmation and not request.confirmed:
            return AgentPlanResponse(
                source="cache",
                canonical_intent=match.binding.canonical_intent,
                confirmation_required=True,
                message="Confirm before Alfred prepares this draft.",
            )
        attempt_id = uuid4().hex
        self._pending[attempt_id] = _PendingAttempt(
            created_at=time.time(),
            profile_id=request.profile_id,
            binding_id=match.binding.id,
        )
        artifact = self._local_artifact(match.binding.id, request.profile_id)
        return AgentPlanResponse(
            source="cache",
            actions=render_skill_actions(match.skill, match.parameters),
            canonical_intent=match.binding.canonical_intent,
            attempt_id=attempt_id,
            confirmation_required=confirmation,
            model_calls=0,
            message="Resolved from a verified generalized intent template.",
            local_artifact=artifact,
            terminal=artifact.terminal if artifact is not None else True,
        )

    def _local_artifact(self, binding_id: int, profile_id: str) -> LocalCacheArtifact | None:
        row = self.database.connection.execute(
            """SELECT t.canonical_intent, t.goal_template, t.goal_fingerprint,
            t.parameter_types_json,
            t.source_screen, b.safety, b.confidence, s.document_json
            FROM llm_result_templates t
            JOIN intent_bindings b ON b.id=t.binding_id
            JOIN skills s ON s.id=b.skill_id AND s.version=b.skill_version
            WHERE t.binding_id=? AND t.scope_id=?
            ORDER BY t.last_verified_at DESC LIMIT 1""",
            (binding_id, profile_id),
        ).fetchone()
        if row is None:
            return None
        skill = Skill.model_validate_json(row["document_json"])
        aliases = {"wait_for_screen": "wait", "verify": "wait"}
        actions = [
            SemanticAction.model_validate(
                {
                    "action": aliases.get(step.action, step.action),
                    "target": step.target,
                    "value": step.value,
                    "package": step.package,
                    "uri": step.uri,
                    "expected_screen": (
                        step.screen if aliases.get(step.action, step.action) == "wait" else None
                    ),
                    "timeout_seconds": step.timeout_seconds,
                }
            )
            for step in skill.steps
        ]
        return LocalCacheArtifact(
            canonical_intent=row["canonical_intent"],
            goal_template=row["goal_template"],
            goal_fingerprint=row["goal_fingerprint"],
            parameter_types=json.loads(row["parameter_types_json"]),
            source_screen=row["source_screen"],
            safety=row["safety"],
            confidence=row["confidence"],
            actions=actions,
            terminal=all(action.action == "stop" for action in actions),
        )

    def _expire_attempts(self) -> None:
        cutoff = time.time() - self.ATTEMPT_TTL_SECONDS
        self._pending = {
            key: value for key, value in self._pending.items() if value.created_at >= cutoff
        }


router = APIRouter(prefix="/v1/agent", tags=["agent"])
_gateway: AgentPlanningGateway | None = None


def configure_agent_gateway(gateway: AgentPlanningGateway) -> None:
    global _gateway
    _gateway = gateway


def _configured_gateway() -> AgentPlanningGateway:
    global _gateway
    if _gateway is not None:
        return _gateway
    database = Database(Path(os.getenv("SMA_DATABASE", "sma.db")))
    planner = None
    configuration_error = None
    try:
        model_configs = model_provider_chain_from_environment()
    except ValueError as exc:
        model_configs = []
        configuration_error = str(exc)
    if model_configs:
        clients = [ChatCompletionsClient(config) for config in model_configs]
        model_client = clients[0] if len(clients) == 1 else FallbackChatCompletionsClient(clients)
        planner = LLMPlanner(model_client)
    _gateway = AgentPlanningGateway(
        database,
        planner,
        model_configuration_error=configuration_error,
    )
    return _gateway


@router.post("/plan", response_model=AgentPlanResponse)
async def plan_endpoint(request: AgentPlanRequest) -> AgentPlanResponse:
    return await _configured_gateway().plan(request)


@router.post("/outcome", response_model=AgentOutcomeResponse)
async def outcome_endpoint(request: AgentOutcomeRequest) -> AgentOutcomeResponse:
    return _configured_gateway().outcome(request)


@router.post("/memory/clear", response_model=MemoryClearResponse)
async def clear_memory_endpoint(request: MemoryClearRequest) -> MemoryClearResponse:
    return MemoryClearResponse(
        templates_deleted=_configured_gateway().clear_memory(request.profile_id)
    )
