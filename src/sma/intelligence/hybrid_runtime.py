from __future__ import annotations

from pydantic import BaseModel

from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.local_router import LocalIntentRouter, LocalRoute, LocalRouteResult
from sma.intelligence.planner import LLMPlanner
from sma.memory.intent_cache import CompatibilityContext
from sma.memory.llm_result_cache import (
    GeneralizedLLMResultCache,
    LearnedLLMResult,
    LLMCacheProvenance,
)
from sma.protocol.models import ActionResult, ActionStatus


class HybridStepResult(BaseModel):
    source: str
    model_calls: int
    local: LocalRouteResult | None = None
    action_result: ActionResult | None = None
    learned: LearnedLLMResult | None = None
    cache_rejection: str | None = None


class HybridAgentRuntime:
    """Try native/cache execution, then call a model and retain only a verified result."""

    def __init__(
        self,
        local: LocalIntentRouter,
        planner: LLMPlanner,
        executor: SemanticExecutor,
        result_cache: GeneralizedLLMResultCache,
    ) -> None:
        self.local = local
        self.planner = planner
        self.executor = executor
        self.result_cache = result_cache

    async def execute_step(
        self,
        goal: str,
        *,
        compatibility: CompatibilityContext | None = None,
        confirmed: bool = False,
    ) -> HybridStepResult:
        local = await self.local.route(
            goal,
            compatibility_context=compatibility,
            confirmed=confirmed,
        )
        if local.route is not LocalRoute.FALLBACK:
            return HybridStepResult(source=local.route.value, model_calls=0, local=local)
        before = self.executor.tracker.current
        if before is None:
            raise RuntimeError("a canonical device state is required before model planning")
        plan, response = await self.planner.plan_with_intent(goal, before)
        outcome = await self.executor.execute(plan.action)
        learned = None
        rejection = None
        if outcome.status is ActionStatus.SUCCESS and outcome.verified:
            try:
                learned = self.result_cache.learn_verified(
                    goal,
                    plan,
                    outcome,
                    before,
                    after=self.executor.tracker.current,
                    provenance=LLMCacheProvenance(model_id=response.model),
                    compatibility=compatibility,
                )
            except ValueError as exc:
                rejection = str(exc)
        return HybridStepResult(
            source="llm",
            model_calls=1,
            local=local,
            action_result=outcome,
            learned=learned,
            cache_rejection=rejection,
        )
