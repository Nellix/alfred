from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import time
import unicodedata
from typing import Any

from pydantic import BaseModel, Field

from sma.execution.action_resolver import resolve_target
from sma.execution.policy import ActionPolicy
from sma.intelligence.intent_parser import IntentParser
from sma.memory.intent_cache import (
    BindingSource,
    BindingStatus,
    CompatibilityContext,
    IntentCache,
    IntentMatch,
    IntentSafety,
    SkillCompatibility,
)
from sma.memory.self_improvement import safety_for_intent
from sma.memory.skill_compiler import (
    SuccessfulTrace,
    TraceStep,
    compile_parameterized_proposal,
)
from sma.memory.skill_engine import ParameterSpec, Skill, SkillStep
from sma.protocol.intents import IntentValue, SemanticIntent
from sma.protocol.models import (
    ActionResult,
    ActionStatus,
    CanonicalState,
    SemanticAction,
    Sensitivity,
)

_PLACEHOLDER = re.compile(r"\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}")
_PRIVATE_LITERAL = re.compile(
    r"(?:\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b|\b\+?\d[\d .()-]{6,}\d\b|https?://)",
    re.I,
)
_STRUCTURAL_PARAMETERS = {"channel", "provider"}


class PlannedSemanticAction(BaseModel):
    """Model result that separates reusable intent structure from the concrete action."""

    canonical_intent: str = Field(pattern=r"^[a-z][a-z0-9_.]*$")
    parameters: dict[str, IntentValue] = Field(default_factory=dict)
    intent_confidence: float = Field(default=0.8, ge=0.0, le=1.0)
    action: SemanticAction

    def to_intent(self, request_fingerprint: str) -> SemanticIntent:
        return SemanticIntent(
            canonical_intent=self.canonical_intent,
            parameters=self.parameters,
            confidence=self.intent_confidence,
            request_fingerprint=request_fingerprint,
        )


class LLMCacheProvenance(BaseModel):
    model_id: str
    prompt_version: str = "semantic-intent-action-v1"
    action_schema_version: str = "1"


class LearnedLLMResult(BaseModel):
    template_id: int
    binding_id: int
    canonical_intent: str
    skill_id: str
    safety: IntentSafety
    generalized_template: str | None = None
    exact_fingerprint_only: bool = False


class GeneralizedIntentParser(IntentParser):
    """Use built-in rules first, then privacy-safe templates learned from verified LLM work."""

    def __init__(self, connection: sqlite3.Connection, *, scope_id: str = "device") -> None:
        self.connection = connection
        self.scope_id = scope_id

    def parse(self, goal: str) -> SemanticIntent | None:
        if built_in := super().parse(goal):
            return built_in
        normalized = _normalize(goal)
        if not normalized:
            return None
        fingerprint = _fingerprint(normalized)
        matches = [
            intent
            for _, intent in _template_matches(
                self.connection, normalized, fingerprint, self.scope_id
            )
        ]
        if not matches:
            return None
        first = matches[0]
        if any(
            item.canonical_intent != first.canonical_intent or item.parameters != first.parameters
            for item in matches[1:]
        ):
            return None
        return first


class GeneralizedLLMResultCache:
    """Promote one verified, low-risk LLM action into a local zero-call template.

    Only an intent skeleton, typed parameter names, a parameterized action, and provenance are
    persisted. The original utterance and observed parameter values are deliberately omitted.
    """

    INITIAL_CONFIDENCE = 0.91

    def __init__(self, connection: sqlite3.Connection) -> None:
        self.connection = connection

    def learn_verified(
        self,
        goal: str,
        plan: PlannedSemanticAction,
        outcome: ActionResult,
        before: CanonicalState,
        *,
        after: CanonicalState | None = None,
        provenance: LLMCacheProvenance,
        compatibility: CompatibilityContext | None = None,
        scope_id: str = "device",
    ) -> LearnedLLMResult:
        if outcome.status is not ActionStatus.SUCCESS or not outcome.verified:
            raise ValueError("only successfully executed and verified LLM actions can be cached")
        safety = safety_for_intent(plan.canonical_intent)
        if safety is IntentSafety.BLOCKED:
            raise ValueError("unknown or blocked intent classes cannot enter the automatic cache")
        action = self._stable_action(plan.action, before)
        ActionPolicy().validate(action, self._action_node(action, before))
        normalized = _normalize(goal)
        if not normalized:
            raise ValueError("goal is empty")
        goal_template, goal_fingerprint = _generalize_goal(normalized, plan.parameters)
        intent = plan.to_intent(_fingerprint(normalized))
        trace = SuccessfulTrace(
            goal="not-persisted",
            steps=[
                TraceStep(
                    action=action,
                    source_screen=before.screen_id,
                    destination_screen=(after or before).screen_id,
                    verified=True,
                )
            ],
        )
        seed = json.dumps(
            {
                "intent": plan.canonical_intent,
                "screen": before.screen_id,
                "template": goal_template or goal_fingerprint,
                "action": action.model_dump(mode="json"),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        digest = hashlib.sha256(seed.encode()).hexdigest()
        base = re.sub(r"[^a-z0-9]+", "_", plan.canonical_intent.casefold()).strip("_")
        skill_id = f"llm_{base}_{digest[:12]}"
        if action.action == "stop":
            skill = Skill(
                id=skill_id,
                version=1,
                parameters={
                    name: _parameter_spec(value) for name, value in plan.parameters.items()
                },
                precondition_screen=before.screen_id,
                steps=[SkillStep(action="stop")],
                approved=True,
            )
        else:
            proposal = compile_parameterized_proposal(
                intent,
                trace,
                skill_id,
                allow_unreferenced_parameters=True,
            )
            skill = proposal.model_copy(update={"approved": True})
        cache = IntentCache(self.connection)
        cache.save_skill(skill)
        current = compatibility or CompatibilityContext()
        skill_compatibility = SkillCompatibility(
            min_android_api=current.android_api,
            max_android_api=current.android_api,
            locales=(current.locale,) if current.locale else (),
            start_screens=(before.screen_id,),
            app_versions={key: (value,) for key, value in current.app_versions.items()},
        )
        binding_id = cache.bind(
            plan.canonical_intent,
            skill.id,
            skill.version,
            compatibility=skill_compatibility,
            safety=safety,
            source=BindingSource.LOCAL,
            status=BindingStatus.ACTIVE,
            confidence=self.INITIAL_CONFIDENCE,
        )
        parameter_types = {name: spec.type for name, spec in skill.parameters.items()}
        template_key = hashlib.sha256(
            json.dumps(
                {
                    "scope_id": scope_id,
                    "binding_id": binding_id,
                    "goal_template": goal_template,
                    "goal_fingerprint": goal_fingerprint,
                    "types": parameter_types,
                },
                sort_keys=True,
                separators=(",", ":"),
            ).encode()
        ).hexdigest()
        now = time.time()
        with self.connection:
            self.connection.execute(
                """INSERT INTO llm_result_templates(
                  template_key, binding_id, canonical_intent, goal_template, goal_fingerprint,
                  parameter_types_json, scope_id, source_screen, model_id, prompt_version,
                  action_schema_version, created_at, last_verified_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(template_key) DO UPDATE SET
                  model_id=excluded.model_id,
                  prompt_version=excluded.prompt_version,
                  action_schema_version=excluded.action_schema_version,
                  last_verified_at=excluded.last_verified_at""",
                (
                    template_key,
                    binding_id,
                    plan.canonical_intent,
                    goal_template,
                    goal_fingerprint,
                    json.dumps(parameter_types, sort_keys=True, separators=(",", ":")),
                    scope_id,
                    before.screen_id,
                    provenance.model_id,
                    provenance.prompt_version,
                    provenance.action_schema_version,
                    now,
                    now,
                ),
            )
        row = self.connection.execute(
            "SELECT id FROM llm_result_templates WHERE template_key=?", (template_key,)
        ).fetchone()
        if row is None:
            raise RuntimeError("LLM result template was not stored")
        return LearnedLLMResult(
            template_id=int(row["id"]),
            binding_id=binding_id,
            canonical_intent=plan.canonical_intent,
            skill_id=skill.id,
            safety=safety,
            generalized_template=goal_template,
            exact_fingerprint_only=goal_template is None,
        )

    def resolve(
        self,
        goal: str,
        context: CompatibilityContext,
        *,
        scope_id: str = "device",
    ) -> IntentMatch | None:
        """Return only the account/device-scoped binding selected by the matching template."""
        normalized = _normalize(goal)
        if not normalized:
            return None
        candidates: list[IntentMatch] = []
        cache = IntentCache(self.connection)
        for binding_id, intent in _template_matches(
            self.connection, normalized, _fingerprint(normalized), scope_id
        ):
            match = cache.lookup_binding(intent, binding_id, context)
            if match is not None:
                candidates.append(match)
        if not candidates:
            return None
        first = candidates[0]
        if any(
            item.binding.id != first.binding.id or item.parameters != first.parameters
            for item in candidates[1:]
        ):
            return None
        return first

    @staticmethod
    def _action_node(action: SemanticAction, state: CanonicalState) -> Any:
        if action.target is None or action.action not in {"click", "set_text", "scroll"}:
            return None
        return resolve_target(state, action.target, action.action).node

    @staticmethod
    def _stable_action(action: SemanticAction, state: CanonicalState) -> SemanticAction:
        stable = action.model_copy(update={"expected_epoch": None, "expected_revision": None})
        if stable.target is None or stable.target.startswith("label:"):
            return stable
        if not any(node.handle == stable.target for node in state.nodes):
            raise ValueError("the model action target is not a current canonical handle")
        resolved = resolve_target(state, stable.target, stable.action)
        node = resolved.node
        if node.sensitivity in {Sensitivity.SECRET, Sensitivity.EXCLUDED}:
            raise ValueError("actions on secret or excluded nodes cannot be learned")
        label = node.label.strip()
        if not label or len(label) > 120 or "\n" in label:
            raise ValueError("the action target has no stable semantic label")
        duplicates = [
            candidate
            for candidate in state.nodes
            if candidate.visible and candidate.label.casefold() == label.casefold()
        ]
        if len(duplicates) != 1:
            raise ValueError("the action target label is not unique on this screen")
        return stable.model_copy(update={"target": f"label:{label}"})


def _normalize(goal: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", goal).strip())


def _fingerprint(normalized: str) -> str:
    return hashlib.sha256(normalized.casefold().encode()).hexdigest()


def _generalize_goal(
    normalized: str, parameters: dict[str, IntentValue]
) -> tuple[str | None, str | None]:
    if not parameters:
        return None, _fingerprint(normalized)
    template = normalized
    seen: set[str] = set()
    for name, value in sorted(parameters.items(), key=lambda item: -len(str(item[1]))):
        rendered = str(value).strip()
        if not rendered:
            raise ValueError(f"intent parameter {name!r} is empty")
        pattern = re.compile(re.escape(rendered), re.I)
        template, count = pattern.subn(f"${{{name}}}", template)
        if count:
            seen.add(name)
    missing = parameters.keys() - seen - _STRUCTURAL_PARAMETERS
    if missing:
        raise ValueError(f"goal did not expose content slots: {sorted(missing)}")
    if _PRIVATE_LITERAL.search(_PLACEHOLDER.sub("", template)):
        raise ValueError("generalized goal retains a possible private literal")
    if len(template) > 1000:
        raise ValueError("generalized goal template is too long")
    return template.casefold(), None


def _match_template(
    normalized: str,
    template: str | None,
    fingerprint: str | None,
    parameter_types: dict[str, str],
) -> dict[str, IntentValue] | None:
    if template is None:
        return {} if fingerprint == _fingerprint(normalized) else None
    pieces: list[str] = []
    cursor = 0
    seen: set[str] = set()
    for match in _PLACEHOLDER.finditer(template):
        pieces.append(re.escape(template[cursor : match.start()]))
        name = match.group(1)
        if name in seen:
            pieces.append(f"(?P={name})")
        else:
            pieces.append(f"(?P<{name}>.+?)")
            seen.add(name)
        cursor = match.end()
    pieces.append(re.escape(template[cursor:]))
    matched = re.fullmatch("".join(pieces), normalized, re.I)
    if matched is None:
        return None
    values: dict[str, IntentValue] = {}
    try:
        for name, kind in parameter_types.items():
            raw = matched.groupdict().get(name)
            if raw is None:
                return None
            if kind == "integer":
                values[name] = int(raw)
            elif kind == "boolean":
                if raw.casefold() not in {"true", "false"}:
                    return None
                values[name] = raw.casefold() == "true"
            else:
                values[name] = raw.strip()
    except (ValueError, IndexError):
        return None
    return values


def _parameter_spec(value: IntentValue) -> ParameterSpec:
    if type(value) is bool:
        return ParameterSpec(type="boolean")
    if type(value) is int:
        return ParameterSpec(type="integer")
    if not isinstance(value, str) or not value:
        raise ValueError("intent parameters must be non-empty typed values")
    return ParameterSpec(type="string", pattern=r"^[^\r\n]{1,1000}$")


def _template_matches(
    connection: sqlite3.Connection,
    normalized: str,
    fingerprint: str,
    scope_id: str,
) -> list[tuple[int, SemanticIntent]]:
    rows = connection.execute(
        """SELECT t.*, b.confidence, b.status
        FROM llm_result_templates t
        JOIN intent_bindings b ON b.id=t.binding_id
        WHERE t.scope_id=? AND b.status='active' AND b.confidence>=?
        ORDER BY b.confidence DESC, t.last_verified_at DESC""",
        (scope_id, IntentCache.AUTO_RUN_CONFIDENCE),
    ).fetchall()
    matches: list[tuple[int, SemanticIntent]] = []
    for row in rows:
        values = _match_template(
            normalized,
            row["goal_template"],
            row["goal_fingerprint"],
            json.loads(row["parameter_types_json"]),
        )
        if values is None:
            continue
        matches.append(
            (
                int(row["binding_id"]),
                SemanticIntent(
                    canonical_intent=row["canonical_intent"],
                    parameters=values,
                    confidence=min(0.97, float(row["confidence"])),
                    request_fingerprint=fingerprint,
                ),
            )
        )
    return matches
