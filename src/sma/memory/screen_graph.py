from __future__ import annotations

import json
import time
from typing import Any

from sma.memory.database import Database
from sma.perception.fingerprint import screen_fingerprint, structural_fingerprint
from sma.protocol.models import CanonicalState, SemanticAction


class ScreenGraph:
    def __init__(self, database: Database) -> None:
        self.db = database

    def observe(self, state: CanonicalState, app_version: str | None = None) -> int:
        fingerprint = screen_fingerprint(state.package, state.nodes)
        structure = structural_fingerprint(state.nodes)
        controls = [
            {"role": n.role, "resource_id": n.resource_id, "label": n.label}
            for n in state.nodes
            if n.actionable
        ]
        now = time.time()
        self.db.connection.execute(
            """
            INSERT INTO screens(
              package, fingerprint, structure, controls_json, last_verified, app_version
            )
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(package, fingerprint) DO UPDATE SET
              observation_count = observation_count + 1,
              structure = excluded.structure,
              controls_json = excluded.controls_json,
              last_verified = excluded.last_verified,
              app_version = COALESCE(excluded.app_version, screens.app_version)
            """,
            (state.package, fingerprint, structure, json.dumps(controls), now, app_version),
        )
        self.db.connection.commit()
        row = self.db.connection.execute(
            "SELECT id FROM screens WHERE package=? AND fingerprint=?", (state.package, fingerprint)
        ).fetchone()
        assert row is not None
        return int(row["id"])

    def record_transition(
        self,
        source: CanonicalState,
        action: SemanticAction,
        destination: CanonicalState,
        *,
        success: bool,
        preconditions: dict[str, Any] | None = None,
        postconditions: dict[str, Any] | None = None,
    ) -> int:
        source_id, destination_id = self.observe(source), self.observe(destination)
        now = time.time()
        existing = self.db.connection.execute(
            "SELECT * FROM transitions WHERE source_id=? AND action_json=? AND destination_id=?",
            (source_id, action.model_dump_json(), destination_id),
        ).fetchone()
        if existing:
            successes = int(existing["successes"]) + int(success)
            failures = int(existing["failures"]) + int(not success)
            confidence = (successes + 1) / (successes + failures + 2)
            self.db.connection.execute(
                """UPDATE transitions
                SET successes=?, failures=?, confidence=?, last_verified=? WHERE id=?""",
                (successes, failures, confidence, now, existing["id"]),
            )
            edge_id = int(existing["id"])
        else:
            cursor = self.db.connection.execute(
                """INSERT INTO transitions(
                source_id, action_json, destination_id, preconditions_json, postconditions_json,
                successes, failures, confidence, last_verified)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    source_id,
                    action.model_dump_json(),
                    destination_id,
                    json.dumps(preconditions or {}),
                    json.dumps(postconditions or {}),
                    int(success),
                    int(not success),
                    2 / 3 if success else 1 / 3,
                    now,
                ),
            )
            if cursor.lastrowid is None:
                raise RuntimeError("SQLite did not return a transition identifier")
            edge_id = cursor.lastrowid
        self.db.connection.commit()
        return edge_id

    def transitions_from(
        self, state: CanonicalState, min_confidence: float = 0.7
    ) -> list[dict[str, Any]]:
        fingerprint = screen_fingerprint(state.package, state.nodes)
        rows = self.db.connection.execute(
            """SELECT t.* FROM transitions t JOIN screens s ON s.id=t.source_id
            WHERE s.package=? AND s.fingerprint=? AND t.confidence>=?
            ORDER BY t.confidence DESC""",
            (state.package, fingerprint, min_confidence),
        ).fetchall()
        return [dict(row) for row in rows]

    def recognize(
        self, state: CanonicalState, min_confidence: float = 0.75
    ) -> dict[str, Any] | None:
        current = {
            (node.role, node.resource_id or node.label.casefold())
            for node in state.nodes
            if node.visible and node.actionable
        }
        rows = self.db.connection.execute(
            "SELECT * FROM screens WHERE package=?", (state.package,)
        ).fetchall()
        best: tuple[float, dict[str, Any]] | None = None
        for row in rows:
            controls = json.loads(row["controls_json"])
            known = {
                (item["role"], item.get("resource_id") or item["label"].casefold())
                for item in controls
            }
            confidence = len(current & known) / len(current | known) if current | known else 1.0
            candidate = {**dict(row), "match_confidence": confidence}
            if best is None or confidence > best[0]:
                best = (confidence, candidate)
        return best[1] if best and best[0] >= min_confidence else None
