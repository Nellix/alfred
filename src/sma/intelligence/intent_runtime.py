from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel

from sma.intelligence.intent_parser import IntentParser
from sma.memory.intent_cache import CompatibilityContext, IntentCache, IntentSafety
from sma.memory.skill_engine import SkillEngine, SkillRun


class CachedIntentStatus(StrEnum):
    PARSE_MISS = "parse_miss"
    CACHE_MISS = "cache_miss"
    CONFIRMATION_REQUIRED = "confirmation_required"
    BLOCKED = "blocked"
    SUCCESS = "success"
    FAILED = "failed"


class CachedIntentResult(BaseModel):
    status: CachedIntentStatus
    canonical_intent: str | None = None
    binding_id: int | None = None
    skill_id: str | None = None
    parser_confidence: float | None = None
    cache_confidence: float | None = None
    model_calls: int = 0
    fallback_recommended: bool = False
    skill_run: SkillRun | None = None
    message: str | None = None


class CachedIntentExecutor:
    """Resolve and execute a common goal without contacting an LLM."""

    def __init__(
        self,
        cache: IntentCache,
        skill_engine: SkillEngine,
        parser: IntentParser | None = None,
    ) -> None:
        self.cache = cache
        self.skill_engine = skill_engine
        self.parser = parser or IntentParser()

    async def execute(
        self,
        goal: str,
        *,
        context: CompatibilityContext | None = None,
        confirmed: bool = False,
    ) -> CachedIntentResult:
        intent = self.parser.parse(goal)
        if intent is None:
            return CachedIntentResult(
                status=CachedIntentStatus.PARSE_MISS,
                fallback_recommended=True,
                message="local parser did not recognize the goal",
            )
        match = self.cache.lookup(intent, context or self._current_context())
        if match is None:
            return CachedIntentResult(
                status=CachedIntentStatus.CACHE_MISS,
                canonical_intent=intent.canonical_intent,
                parser_confidence=intent.confidence,
                fallback_recommended=True,
                message="no compatible high-confidence local skill",
            )
        if match.binding.safety is IntentSafety.BLOCKED:
            return CachedIntentResult(
                status=CachedIntentStatus.BLOCKED,
                canonical_intent=intent.canonical_intent,
                binding_id=match.binding.id,
                skill_id=match.skill.id,
                parser_confidence=intent.confidence,
                cache_confidence=match.binding.confidence,
                fallback_recommended=False,
                message="the cached intent is blocked by policy",
            )
        if match.binding.safety is IntentSafety.CONFIRM and not confirmed:
            return CachedIntentResult(
                status=CachedIntentStatus.CONFIRMATION_REQUIRED,
                canonical_intent=intent.canonical_intent,
                binding_id=match.binding.id,
                skill_id=match.skill.id,
                parser_confidence=intent.confidence,
                cache_confidence=match.binding.confidence,
                fallback_recommended=False,
                message="explicit user confirmation is required",
            )

        run = await self.skill_engine.run(match.skill, match.parameters)
        success = run.status == "success"
        self.cache.record_outcome(
            match.binding.id,
            success=success,
            reason_code=None if success else "skill_execution_failed",
        )
        return CachedIntentResult(
            status=CachedIntentStatus.SUCCESS if success else CachedIntentStatus.FAILED,
            canonical_intent=intent.canonical_intent,
            binding_id=match.binding.id,
            skill_id=match.skill.id,
            parser_confidence=intent.confidence,
            cache_confidence=match.binding.confidence,
            fallback_recommended=not success,
            skill_run=run,
            message=run.error,
        )

    def _current_context(self) -> CompatibilityContext:
        current = self.skill_engine.executor.tracker.current
        if current is None:
            return CompatibilityContext()
        return CompatibilityContext(
            current_package=current.package,
            current_screen=current.screen_id,
        )
