from __future__ import annotations

import hashlib
import json
import re
import sqlite3
import time
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field

from sma.memory.intent_cache import (
    BindingSource,
    BindingStatus,
    IntentCache,
    IntentSafety,
)
from sma.memory.skill_compiler import SuccessfulTrace, compile_parameterized_proposal
from sma.memory.skill_engine import Skill, SkillEngine, SkillRun
from sma.protocol.intents import SemanticIntent


class LearningStatus(StrEnum):
    COLLECTING = "collecting"
    CANDIDATE = "candidate"
    VALIDATED = "validated"
    PROMOTED = "promoted"
    REJECTED = "rejected"


class LearningPolicy(BaseModel):
    minimum_observations: int = Field(default=3, ge=2, le=100)
    minimum_replay_successes: int = Field(default=2, ge=1, le=20)
    reject_after_failures: int = Field(default=1, ge=1, le=20)
    auto_promote: bool = True


class LearningCandidate(BaseModel):
    id: int
    canonical_intent: str
    cluster_key: str
    skill_id: str
    skill_version: int
    safety: IntentSafety
    status: LearningStatus
    observations: int
    replay_successes: int
    replay_failures: int
    skill: Skill


class ReplayValidation(BaseModel):
    candidate: LearningCandidate
    run: SkillRun
    duplicate_replay: bool = False
    promoted_binding_id: int | None = None


class SkillLearner:
    """Turn repeated verified traces into guarded local skill bindings."""

    def __init__(
        self, connection: sqlite3.Connection, policy: LearningPolicy | None = None
    ) -> None:
        self.connection = connection
        self.policy = policy or LearningPolicy()

    def observe(
        self,
        intent: SemanticIntent,
        trace: SuccessfulTrace,
        *,
        session_id: str,
    ) -> LearningCandidate:
        if not session_id:
            raise ValueError("session_id is required")
        proposal = compile_parameterized_proposal(intent, trace, "learned_candidate")
        cluster_key = self._cluster_key(intent.canonical_intent, proposal)
        skill_id = self._skill_id(intent.canonical_intent, cluster_key)
        proposal = proposal.model_copy(update={"id": skill_id})
        safety = safety_for_intent(intent.canonical_intent)
        now = time.time()
        row = self.connection.execute(
            "SELECT id FROM learning_candidates WHERE cluster_key=?", (cluster_key,)
        ).fetchone()
        with self.connection:
            if row is None:
                cursor = self.connection.execute(
                    """INSERT INTO learning_candidates(
                      canonical_intent, cluster_key, skill_id, skill_version, document_json,
                      safety, status, created_at, updated_at, last_observed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'collecting', ?, ?, ?)""",
                    (
                        intent.canonical_intent,
                        cluster_key,
                        skill_id,
                        proposal.version,
                        proposal.model_dump_json(),
                        safety.value,
                        now,
                        now,
                        now,
                    ),
                )
                if cursor.lastrowid is None:
                    raise RuntimeError("learning candidate was not stored")
                candidate_id = cursor.lastrowid
            else:
                candidate_id = int(row["id"])
            session_hash = self._private_hash(cluster_key, session_id)
            cursor = self.connection.execute(
                """INSERT OR IGNORE INTO learning_observations(
                  candidate_id, session_hash, observed_at
                ) VALUES (?, ?, ?)""",
                (candidate_id, session_hash, now),
            )
            if cursor.rowcount:
                self.connection.execute(
                    """UPDATE learning_candidates SET observations=observations+1,
                    status=CASE
                      WHEN observations+1>=? AND status='collecting' THEN 'candidate'
                      ELSE status END,
                    updated_at=?, last_observed_at=? WHERE id=?""",
                    (self.policy.minimum_observations, now, now, candidate_id),
                )
        return self.get(candidate_id)

    async def replay_validate(
        self,
        candidate_id: int,
        engine: SkillEngine,
        parameters: dict[str, Any],
        *,
        replay_id: str,
    ) -> ReplayValidation:
        if not replay_id:
            raise ValueError("replay_id is required")
        candidate = self.get(candidate_id)
        if candidate.status not in {
            LearningStatus.CANDIDATE,
            LearningStatus.VALIDATED,
        }:
            raise ValueError(f"candidate cannot be replayed in state {candidate.status.value!r}")
        replay_hash = self._private_hash(candidate.cluster_key, replay_id)
        existing = self.connection.execute(
            """SELECT success FROM candidate_replays
            WHERE candidate_id=? AND replay_hash=?""",
            (candidate_id, replay_hash),
        ).fetchone()
        if existing is not None:
            return ReplayValidation(
                candidate=candidate,
                run=SkillRun(
                    status="success" if existing["success"] else "failed",
                    steps_completed=0,
                    error="duplicate replay was not counted",
                ),
                duplicate_replay=True,
            )

        run = await engine.run(
            candidate.skill.model_copy(update={"approved": True}),
            parameters,
            record_history=False,
        )
        success = run.status == "success"
        now = time.time()
        with self.connection:
            self.connection.execute(
                """INSERT INTO candidate_replays(candidate_id, replay_hash, success, occurred_at)
                VALUES (?, ?, ?, ?)""",
                (candidate_id, replay_hash, int(success), now),
            )
            counter = "replay_successes" if success else "replay_failures"
            self.connection.execute(
                f"""UPDATE learning_candidates SET {counter}={counter}+1, updated_at=?
                WHERE id=?""",
                (now, candidate_id),
            )
            updated = self.connection.execute(
                """SELECT replay_successes, replay_failures
                FROM learning_candidates WHERE id=?""",
                (candidate_id,),
            ).fetchone()
            status = LearningStatus.CANDIDATE
            if int(updated["replay_failures"]) >= self.policy.reject_after_failures:
                status = LearningStatus.REJECTED
            elif int(updated["replay_successes"]) >= self.policy.minimum_replay_successes:
                status = LearningStatus.VALIDATED
            self.connection.execute(
                "UPDATE learning_candidates SET status=? WHERE id=?",
                (status.value, candidate_id),
            )

        promoted_binding_id = None
        candidate = self.get(candidate_id)
        if (
            candidate.status is LearningStatus.VALIDATED
            and candidate.safety is not IntentSafety.BLOCKED
            and self.policy.auto_promote
        ):
            promoted_binding_id = self.promote(candidate_id)
            candidate = self.get(candidate_id)
        return ReplayValidation(
            candidate=candidate,
            run=run,
            promoted_binding_id=promoted_binding_id,
        )

    def promote(self, candidate_id: int) -> int:
        candidate = self.get(candidate_id)
        if candidate.status is not LearningStatus.VALIDATED:
            raise ValueError("only replay-validated candidates can be promoted")
        if candidate.safety is IntentSafety.BLOCKED:
            raise ValueError("unclassified or blocked intents cannot be promoted")
        skill = candidate.skill.model_copy(update={"approved": True})
        cache = IntentCache(self.connection)
        cache.save_skill(skill)
        confidence = min(
            0.98,
            0.90 + 0.005 * candidate.observations + 0.01 * candidate.replay_successes,
        )
        binding_id = cache.bind(
            candidate.canonical_intent,
            skill.id,
            skill.version,
            safety=candidate.safety,
            source=BindingSource.LOCAL,
            status=BindingStatus.ACTIVE,
            confidence=confidence,
        )
        with self.connection:
            self.connection.execute(
                """UPDATE learning_candidates SET status='promoted', document_json=?,
                updated_at=? WHERE id=?""",
                (skill.model_dump_json(), time.time(), candidate_id),
            )
        return binding_id

    def get(self, candidate_id: int) -> LearningCandidate:
        row = self.connection.execute(
            "SELECT * FROM learning_candidates WHERE id=?", (candidate_id,)
        ).fetchone()
        if row is None:
            raise ValueError(f"unknown learning candidate {candidate_id}")
        return LearningCandidate(
            id=row["id"],
            canonical_intent=row["canonical_intent"],
            cluster_key=row["cluster_key"],
            skill_id=row["skill_id"],
            skill_version=row["skill_version"],
            safety=row["safety"],
            status=row["status"],
            observations=row["observations"],
            replay_successes=row["replay_successes"],
            replay_failures=row["replay_failures"],
            skill=Skill.model_validate_json(row["document_json"]),
        )

    @staticmethod
    def _cluster_key(canonical_intent: str, skill: Skill) -> str:
        skeleton = skill.model_dump(mode="json", exclude={"id", "version", "approved"})
        encoded = json.dumps(
            {"intent": canonical_intent, "skill": skeleton},
            sort_keys=True,
            separators=(",", ":"),
        ).encode()
        return hashlib.sha256(encoded).hexdigest()

    @staticmethod
    def _skill_id(canonical_intent: str, cluster_key: str) -> str:
        base = re.sub(r"[^a-z0-9]+", "_", canonical_intent.casefold()).strip("_")
        return f"learned_{base}_{cluster_key[:10]}"

    @staticmethod
    def _private_hash(namespace: str, identifier: str) -> str:
        return hashlib.sha256(f"{namespace}:{identifier}".encode()).hexdigest()


def safety_for_intent(canonical_intent: str) -> IntentSafety:
    if canonical_intent == "message.compose":
        return IntentSafety.CONFIRM
    if canonical_intent in {
        "android.settings.open",
        "android.settings.search",
        "email.inbox.check",
        "media.youtube.play",
        "navigation.route",
    }:
        return IntentSafety.LOW
    return IntentSafety.BLOCKED
