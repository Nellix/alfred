from __future__ import annotations

from enum import IntEnum


class InferenceLevel(IntEnum):
    NATIVE = 0
    SKILL = 1
    SEMANTIC = 2
    TEXT_LLM = 3
    VISION = 4


def choose_level(
    *,
    native_supported: bool = False,
    skill_confidence: float = 0.0,
    semantic_confidence: float = 0.0,
    safety_sensitive: bool = False,
    recent_failures: int = 0,
    vision_authorized: bool = False,
    accessibility_sufficient: bool = True,
) -> InferenceLevel:
    if safety_sensitive:
        return InferenceLevel.TEXT_LLM
    if native_supported:
        return InferenceLevel.NATIVE
    if skill_confidence >= 0.9 and recent_failures == 0:
        return InferenceLevel.SKILL
    if semantic_confidence >= 0.85 and recent_failures < 2:
        return InferenceLevel.SEMANTIC
    if accessibility_sufficient or not vision_authorized:
        return InferenceLevel.TEXT_LLM
    return InferenceLevel.VISION
