from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel

from sma.intelligence.intent_parser import IntentParser
from sma.intelligence.intent_runtime import (
    CachedIntentExecutor,
    CachedIntentResult,
    CachedIntentStatus,
)
from sma.intelligence.native_catalog import (
    CatalogContext,
    NativeActionCatalog,
    NativePlanExecutor,
    NativePlanResult,
    NativePlanStatus,
)
from sma.memory.intent_cache import CompatibilityContext


class LocalRoute(StrEnum):
    NATIVE = "native"
    INTENT_CACHE = "intent_cache"
    FALLBACK = "fallback"


class LocalRouteResult(BaseModel):
    route: LocalRoute
    status: str
    model_calls: int = 0
    fallback_recommended: bool = False
    native_result: NativePlanResult | None = None
    cached_result: CachedIntentResult | None = None
    message: str | None = None


class LocalIntentRouter:
    """Route a goal through native strategies and the private cache before an LLM."""

    def __init__(
        self,
        cached: CachedIntentExecutor,
        native: NativePlanExecutor,
        *,
        parser: IntentParser | None = None,
        catalog: NativeActionCatalog | None = None,
    ) -> None:
        self.cached = cached
        self.native = native
        self.parser = parser or IntentParser()
        self.catalog = catalog or NativeActionCatalog()

    async def route(
        self,
        goal: str,
        *,
        catalog_context: CatalogContext | None = None,
        compatibility_context: CompatibilityContext | None = None,
        confirmed: bool = False,
    ) -> LocalRouteResult:
        intent = self.parser.parse(goal)
        plan = self.catalog.resolve(intent, catalog_context) if intent else None
        attempted_native: NativePlanResult | None = None

        if plan is not None and plan.terminal:
            attempted_native = await self.native.execute(plan)
            if attempted_native.status is NativePlanStatus.SUCCESS:
                return self._native_result(attempted_native)

        cached_result = await self.cached.execute(
            goal, context=compatibility_context, confirmed=confirmed
        )
        if cached_result.status not in {
            CachedIntentStatus.PARSE_MISS,
            CachedIntentStatus.CACHE_MISS,
        }:
            return LocalRouteResult(
                route=LocalRoute.INTENT_CACHE,
                status=cached_result.status.value,
                cached_result=cached_result,
                fallback_recommended=cached_result.fallback_recommended,
                message=cached_result.message,
            )

        if plan is not None:
            if attempted_native is not None:
                return self._native_result(attempted_native)
            return self._native_result(await self.native.execute(plan))

        return LocalRouteResult(
            route=LocalRoute.FALLBACK,
            status="miss",
            fallback_recommended=True,
            cached_result=cached_result,
            message="no compatible native strategy or high-confidence local skill",
        )

    @staticmethod
    def _native_result(result: NativePlanResult) -> LocalRouteResult:
        return LocalRouteResult(
            route=LocalRoute.NATIVE,
            status=result.status.value,
            native_result=result,
            fallback_recommended=result.fallback_recommended,
            message=result.message,
        )
