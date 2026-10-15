from typing import TYPE_CHECKING

from .database import Database
from .intent_cache import (
    BindingSource,
    BindingStatus,
    CompatibilityContext,
    IntentCache,
    IntentSafety,
    SkillCompatibility,
)
from .screen_graph import ScreenGraph
from .self_improvement import (
    LearningCandidate,
    LearningPolicy,
    LearningStatus,
    ReplayValidation,
    SkillLearner,
)
from .skill_compiler import (
    SuccessfulTrace,
    TraceStep,
    compile_parameterized_proposal,
    compile_proposal,
)
from .skill_engine import Skill, SkillEngine

if TYPE_CHECKING:
    from .llm_result_cache import (
        GeneralizedIntentParser,
        GeneralizedLLMResultCache,
        LLMCacheProvenance,
        PlannedSemanticAction,
    )

__all__ = [
    "Database",
    "BindingSource",
    "BindingStatus",
    "CompatibilityContext",
    "IntentCache",
    "IntentSafety",
    "LearningCandidate",
    "LearningPolicy",
    "LearningStatus",
    "ReplayValidation",
    "ScreenGraph",
    "Skill",
    "SkillEngine",
    "SuccessfulTrace",
    "SkillCompatibility",
    "SkillLearner",
    "TraceStep",
    "compile_parameterized_proposal",
    "compile_proposal",
    "GeneralizedIntentParser",
    "GeneralizedLLMResultCache",
    "LLMCacheProvenance",
    "PlannedSemanticAction",
]


def __getattr__(name: str) -> object:
    if name in {
        "GeneralizedIntentParser",
        "GeneralizedLLMResultCache",
        "LLMCacheProvenance",
        "PlannedSemanticAction",
    }:
        from . import llm_result_cache

        return getattr(llm_result_cache, name)
    raise AttributeError(name)
