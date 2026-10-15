from __future__ import annotations

import json
import re
import sqlite3
import time
from enum import StrEnum

from pydantic import BaseModel, Field

from sma.memory.skill_engine import Skill
from sma.protocol.intents import SemanticIntent


class IntentSafety(StrEnum):
    LOW = "low"
    CONFIRM = "confirm"
    BLOCKED = "blocked"


class BindingSource(StrEnum):
    LOCAL = "local"
    ACCOUNT = "account"
    PROVIDER = "provider"


class BindingStatus(StrEnum):
    CANDIDATE = "candidate"
    ACTIVE = "active"
    DISABLED = "disabled"
    REVOKED = "revoked"


class CompatibilityContext(BaseModel):
    android_api: int | None = None
    locale: str | None = None
    current_package: str | None = None
    current_screen: str | None = None
    app_versions: dict[str, str] = Field(default_factory=dict)


class SkillCompatibility(BaseModel):
    min_android_api: int | None = None
    max_android_api: int | None = None
    locales: tuple[str, ...] = ()
    start_screens: tuple[str, ...] = ()
    app_versions: dict[str, tuple[str, ...]] = Field(default_factory=dict)

    def accepts(self, context: CompatibilityContext) -> bool:
        if self.min_android_api is not None and (
            context.android_api is None or context.android_api < self.min_android_api
        ):
            return False
        if self.max_android_api is not None and (
            context.android_api is None or context.android_api > self.max_android_api
        ):
            return False
        if self.locales and (
            context.locale is None
            or not any(self._locale_matches(context.locale, item) for item in self.locales)
        ):
            return False
        if self.start_screens and context.current_screen not in self.start_screens:
            return False
        for package, allowed_versions in self.app_versions.items():
            if context.app_versions.get(package) not in allowed_versions:
                return False
        return True

    @staticmethod
    def _locale_matches(actual: str, expected: str) -> bool:
        normalized_actual = actual.replace("_", "-").casefold()
        normalized_expected = expected.replace("_", "-").casefold()
        return (
            normalized_actual == normalized_expected
            or normalized_actual.partition("-")[0] == (normalized_expected.partition("-")[0])
        )


class IntentBinding(BaseModel):
    id: int
    canonical_intent: str
    skill_id: str
    skill_version: int
    safety: IntentSafety
    source: BindingSource
    status: BindingStatus
    confidence: float = Field(ge=0.0, le=1.0)
    successes: int = 0
    failures: int = 0
    consecutive_failures: int = 0
    compatibility: SkillCompatibility = Field(default_factory=SkillCompatibility)


class IntentMatch(BaseModel):
    binding: IntentBinding
    skill: Skill
    parameters: dict[str, str | int | bool]


class IntentCache:
    AUTO_RUN_CONFIDENCE = 0.90

    def __init__(self, connection: sqlite3.Connection) -> None:
        self.connection = connection

    def save_skill(self, skill: Skill) -> None:
        now = time.time()
        with self.connection:
            self.connection.execute(
                """INSERT INTO skills(id, version, document_json, approved, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id, version) DO UPDATE SET
                  document_json=excluded.document_json,
                  approved=excluded.approved""",
                (skill.id, skill.version, skill.model_dump_json(), int(skill.approved), now),
            )

    def bind(
        self,
        canonical_intent: str,
        skill_id: str,
        skill_version: int,
        *,
        compatibility: SkillCompatibility | None = None,
        safety: IntentSafety = IntentSafety.LOW,
        source: BindingSource = BindingSource.LOCAL,
        status: BindingStatus = BindingStatus.CANDIDATE,
        confidence: float = 0.5,
    ) -> int:
        if not re.fullmatch(r"[a-z][a-z0-9_.]*", canonical_intent):
            raise ValueError("canonical intent must be a dotted lowercase identifier")
        if not 0.0 <= confidence <= 1.0:
            raise ValueError("confidence must be between zero and one")
        skill = self._load_skill(skill_id, skill_version)
        if skill is None:
            raise ValueError(f"skill {skill_id!r} version {skill_version} is not registered")
        if status is BindingStatus.ACTIVE and not skill.approved:
            raise ValueError("an active intent binding requires an approved skill")
        now = time.time()
        parameter_schema = {
            name: spec.model_dump(mode="json") for name, spec in skill.parameters.items()
        }
        compatibility_json = (compatibility or SkillCompatibility()).model_dump_json()
        with self.connection:
            self.connection.execute(
                """INSERT INTO intent_bindings(
                  canonical_intent, skill_id, skill_version, parameter_schema_json,
                  compatibility_json, safety, source, status, confidence, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(canonical_intent, skill_id, skill_version, source) DO UPDATE SET
                  parameter_schema_json=excluded.parameter_schema_json,
                  compatibility_json=excluded.compatibility_json,
                  safety=excluded.safety,
                  status=excluded.status,
                  confidence=excluded.confidence,
                  updated_at=excluded.updated_at""",
                (
                    canonical_intent,
                    skill_id,
                    skill_version,
                    json.dumps(parameter_schema, sort_keys=True, separators=(",", ":")),
                    compatibility_json,
                    safety.value,
                    source.value,
                    status.value,
                    confidence,
                    now,
                    now,
                ),
            )
        row = self.connection.execute(
            """SELECT id FROM intent_bindings
            WHERE canonical_intent=? AND skill_id=? AND skill_version=? AND source=?""",
            (canonical_intent, skill_id, skill_version, source.value),
        ).fetchone()
        if row is None:
            raise RuntimeError("intent binding was not stored")
        return int(row["id"])

    def lookup(
        self,
        intent: SemanticIntent,
        context: CompatibilityContext | None = None,
        *,
        min_confidence: float = AUTO_RUN_CONFIDENCE,
    ) -> IntentMatch | None:
        rows = self.connection.execute(
            """SELECT b.*, s.document_json, s.approved
            FROM intent_bindings b
            JOIN skills s ON s.id=b.skill_id AND s.version=b.skill_version
            WHERE b.canonical_intent=? AND b.status='active'
              AND b.confidence>=? AND s.approved=1
            ORDER BY CASE b.source
              WHEN 'local' THEN 0 WHEN 'account' THEN 1 ELSE 2 END,
              b.confidence DESC, b.updated_at DESC""",
            (intent.canonical_intent, min_confidence),
        ).fetchall()
        current_context = context or CompatibilityContext()
        for row in rows:
            compatibility = SkillCompatibility.model_validate_json(row["compatibility_json"])
            if not compatibility.accepts(current_context):
                continue
            skill = Skill.model_validate_json(row["document_json"])
            try:
                parameters = skill.validate_parameters(intent.parameters)
            except ValueError:
                continue
            binding = IntentBinding(
                id=row["id"],
                canonical_intent=row["canonical_intent"],
                skill_id=row["skill_id"],
                skill_version=row["skill_version"],
                safety=row["safety"],
                source=row["source"],
                status=row["status"],
                confidence=row["confidence"],
                successes=row["successes"],
                failures=row["failures"],
                consecutive_failures=row["consecutive_failures"],
                compatibility=compatibility,
            )
            return IntentMatch(binding=binding, skill=skill, parameters=parameters)
        return None

    def lookup_binding(
        self,
        intent: SemanticIntent,
        binding_id: int,
        context: CompatibilityContext | None = None,
        *,
        min_confidence: float = AUTO_RUN_CONFIDENCE,
    ) -> IntentMatch | None:
        """Resolve one explicitly selected binding, used for account-scoped template lookup."""
        row = self.connection.execute(
            """SELECT b.*, s.document_json, s.approved
            FROM intent_bindings b
            JOIN skills s ON s.id=b.skill_id AND s.version=b.skill_version
            WHERE b.id=? AND b.canonical_intent=? AND b.status='active'
              AND b.confidence>=? AND s.approved=1""",
            (binding_id, intent.canonical_intent, min_confidence),
        ).fetchone()
        if row is None:
            return None
        compatibility = SkillCompatibility.model_validate_json(row["compatibility_json"])
        if not compatibility.accepts(context or CompatibilityContext()):
            return None
        skill = Skill.model_validate_json(row["document_json"])
        try:
            parameters = skill.validate_parameters(intent.parameters)
        except ValueError:
            return None
        return IntentMatch(
            binding=IntentBinding(
                id=row["id"],
                canonical_intent=row["canonical_intent"],
                skill_id=row["skill_id"],
                skill_version=row["skill_version"],
                safety=row["safety"],
                source=row["source"],
                status=row["status"],
                confidence=row["confidence"],
                successes=row["successes"],
                failures=row["failures"],
                consecutive_failures=row["consecutive_failures"],
                compatibility=compatibility,
            ),
            skill=skill,
            parameters=parameters,
        )

    def record_outcome(self, binding_id: int, *, success: bool, reason_code: str | None) -> None:
        row = self.connection.execute(
            "SELECT confidence, consecutive_failures FROM intent_bindings WHERE id=?",
            (binding_id,),
        ).fetchone()
        if row is None:
            raise ValueError(f"unknown intent binding {binding_id}")
        confidence = float(row["confidence"])
        consecutive_failures = int(row["consecutive_failures"])
        if success:
            new_confidence = min(0.999, confidence + 0.03 * (1.0 - confidence))
            status = BindingStatus.ACTIVE.value
            consecutive_failures = 0
        else:
            new_confidence = confidence * 0.65
            consecutive_failures += 1
            status = (
                BindingStatus.DISABLED.value
                if consecutive_failures >= 2
                else BindingStatus.ACTIVE.value
            )
        now = time.time()
        counter = "successes" if success else "failures"
        with self.connection:
            self.connection.execute(
                f"""UPDATE intent_bindings SET {counter}={counter}+1, confidence=?,
                consecutive_failures=?, status=?, updated_at=?, last_used_at=? WHERE id=?""",
                (new_confidence, consecutive_failures, status, now, now, binding_id),
            )
            self.connection.execute(
                """INSERT INTO intent_events(binding_id, outcome, reason_code, occurred_at)
                VALUES (?, ?, ?, ?)""",
                (binding_id, "success" if success else "failure", reason_code, now),
            )

    def decay_stale(
        self,
        *,
        older_than: float,
        factor: float = 0.98,
        now: float | None = None,
    ) -> int:
        if older_than <= 0:
            raise ValueError("older_than must be positive")
        if not 0.0 < factor < 1.0:
            raise ValueError("decay factor must be between zero and one")
        current_time = time.time() if now is None else now
        rows = self.connection.execute(
            """SELECT id, confidence FROM intent_bindings
            WHERE status='active' AND COALESCE(last_used_at, updated_at)<?""",
            (current_time - older_than,),
        ).fetchall()
        with self.connection:
            for row in rows:
                self.connection.execute(
                    "UPDATE intent_bindings SET confidence=?, updated_at=? WHERE id=?",
                    (max(0.0, float(row["confidence"]) * factor), current_time, row["id"]),
                )
        return len(rows)

    def rollback(self, binding_id: int) -> int | None:
        row = self.connection.execute(
            "SELECT canonical_intent FROM intent_bindings WHERE id=?", (binding_id,)
        ).fetchone()
        if row is None:
            raise ValueError(f"unknown intent binding {binding_id}")
        now = time.time()
        with self.connection:
            self.connection.execute(
                """UPDATE intent_bindings SET status='disabled', updated_at=? WHERE id=?""",
                (now, binding_id),
            )
            self.connection.execute(
                """INSERT INTO intent_events(binding_id, outcome, reason_code, occurred_at)
                VALUES (?, 'failure', 'version_rollback', ?)""",
                (binding_id, now),
            )
        fallback = self.connection.execute(
            """SELECT id FROM intent_bindings
            WHERE canonical_intent=? AND status='active' AND confidence>=? AND id<>?
            ORDER BY confidence DESC, skill_version DESC LIMIT 1""",
            (row["canonical_intent"], self.AUTO_RUN_CONFIDENCE, binding_id),
        ).fetchone()
        return int(fallback["id"]) if fallback else None

    def _load_skill(self, skill_id: str, skill_version: int) -> Skill | None:
        row = self.connection.execute(
            "SELECT document_json FROM skills WHERE id=? AND version=?",
            (skill_id, skill_version),
        ).fetchone()
        return Skill.model_validate_json(row["document_json"]) if row else None
