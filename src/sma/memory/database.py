from __future__ import annotations

import sqlite3
from pathlib import Path

# Versions 4, 5, 7, and 8 are intentionally retained as no-ops. Earlier PoC builds used them
# for a hosted registry, contribution queue, and account vault. Keeping the version numbers lets
# existing local development databases open safely while new databases create only local-agent data.
MIGRATIONS = (
    """
CREATE TABLE IF NOT EXISTS screens (
  id INTEGER PRIMARY KEY,
  package TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  structure TEXT NOT NULL,
  controls_json TEXT NOT NULL,
  observation_count INTEGER NOT NULL DEFAULT 1,
  last_verified REAL NOT NULL,
  app_version TEXT,
  UNIQUE(package, fingerprint)
);
CREATE TABLE IF NOT EXISTS transitions (
  id INTEGER PRIMARY KEY,
  source_id INTEGER NOT NULL REFERENCES screens(id),
  action_json TEXT NOT NULL,
  destination_id INTEGER NOT NULL REFERENCES screens(id),
  preconditions_json TEXT NOT NULL,
  postconditions_json TEXT NOT NULL,
  successes INTEGER NOT NULL DEFAULT 0,
  failures INTEGER NOT NULL DEFAULT 0,
  confidence REAL NOT NULL,
  last_verified REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS skills (
  id TEXT NOT NULL,
  version INTEGER NOT NULL,
  document_json TEXT NOT NULL,
  approved INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  PRIMARY KEY(id, version)
);
CREATE TABLE IF NOT EXISTS skill_runs (
  id INTEGER PRIMARY KEY,
  skill_id TEXT NOT NULL,
  skill_version INTEGER NOT NULL,
  started_at REAL NOT NULL,
  finished_at REAL,
  status TEXT NOT NULL,
  parameters_json TEXT NOT NULL,
  steps_completed INTEGER NOT NULL DEFAULT 0,
  error TEXT
);
""",
    """
CREATE TABLE IF NOT EXISTS intent_bindings (
  id INTEGER PRIMARY KEY,
  canonical_intent TEXT NOT NULL,
  skill_id TEXT NOT NULL,
  skill_version INTEGER NOT NULL,
  parameter_schema_json TEXT NOT NULL,
  compatibility_json TEXT NOT NULL,
  safety TEXT NOT NULL CHECK(safety IN ('low', 'confirm', 'blocked')),
  source TEXT NOT NULL CHECK(source IN ('local', 'account', 'provider')),
  status TEXT NOT NULL CHECK(status IN ('candidate', 'active', 'disabled', 'revoked')),
  confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0),
  successes INTEGER NOT NULL DEFAULT 0,
  failures INTEGER NOT NULL DEFAULT 0,
  consecutive_failures INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL,
  last_used_at REAL,
  FOREIGN KEY(skill_id, skill_version) REFERENCES skills(id, version),
  UNIQUE(canonical_intent, skill_id, skill_version, source)
);
CREATE INDEX IF NOT EXISTS intent_bindings_lookup
  ON intent_bindings(canonical_intent, status, confidence);
CREATE TABLE IF NOT EXISTS intent_events (
  id INTEGER PRIMARY KEY,
  binding_id INTEGER NOT NULL REFERENCES intent_bindings(id),
  outcome TEXT NOT NULL CHECK(outcome IN ('success', 'failure')),
  reason_code TEXT,
  occurred_at REAL NOT NULL
);
""",
    """
CREATE TABLE IF NOT EXISTS learning_candidates (
  id INTEGER PRIMARY KEY,
  canonical_intent TEXT NOT NULL,
  cluster_key TEXT NOT NULL UNIQUE,
  skill_id TEXT NOT NULL,
  skill_version INTEGER NOT NULL,
  document_json TEXT NOT NULL,
  safety TEXT NOT NULL CHECK(safety IN ('low', 'confirm', 'blocked')),
  status TEXT NOT NULL CHECK(status IN (
    'collecting', 'candidate', 'validated', 'promoted', 'rejected'
  )),
  observations INTEGER NOT NULL DEFAULT 0,
  replay_successes INTEGER NOT NULL DEFAULT 0,
  replay_failures INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL,
  last_observed_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS learning_candidates_status
  ON learning_candidates(status, canonical_intent);
CREATE TABLE IF NOT EXISTS learning_observations (
  id INTEGER PRIMARY KEY,
  candidate_id INTEGER NOT NULL REFERENCES learning_candidates(id),
  session_hash TEXT NOT NULL,
  observed_at REAL NOT NULL,
  UNIQUE(candidate_id, session_hash)
);
CREATE TABLE IF NOT EXISTS candidate_replays (
  id INTEGER PRIMARY KEY,
  candidate_id INTEGER NOT NULL REFERENCES learning_candidates(id),
  replay_hash TEXT NOT NULL,
  success INTEGER NOT NULL CHECK(success IN (0, 1)),
  occurred_at REAL NOT NULL,
  UNIQUE(candidate_id, replay_hash)
);
""",
    "SELECT 1; -- retired hosted provider registry",
    "SELECT 1; -- retired automatic contribution queue",
    """
CREATE TABLE IF NOT EXISTS llm_result_templates (
  id INTEGER PRIMARY KEY,
  template_key TEXT NOT NULL UNIQUE,
  binding_id INTEGER NOT NULL REFERENCES intent_bindings(id) ON DELETE CASCADE,
  canonical_intent TEXT NOT NULL,
  goal_template TEXT,
  goal_fingerprint TEXT,
  parameter_types_json TEXT NOT NULL,
  scope_id TEXT NOT NULL,
  source_screen TEXT NOT NULL,
  model_id TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  action_schema_version TEXT NOT NULL,
  created_at REAL NOT NULL,
  last_verified_at REAL NOT NULL,
  CHECK((goal_template IS NULL) <> (goal_fingerprint IS NULL))
);
CREATE INDEX IF NOT EXISTS llm_result_templates_lookup
  ON llm_result_templates(scope_id, source_screen, canonical_intent);
""",
    "SELECT 1; -- retired account skill vault",
    "SELECT 1; -- retired registry runtime status",
)


class Database:
    def __init__(self, path: str | Path = "sma.db") -> None:
        self.path = str(path)
        self.connection = sqlite3.connect(self.path)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys = ON")
        self._migrate()

    def _migrate(self) -> None:
        current = int(self.connection.execute("PRAGMA user_version").fetchone()[0])
        if current > len(MIGRATIONS):
            raise RuntimeError(
                f"database schema version {current} is newer than supported {len(MIGRATIONS)}"
            )
        for version, migration in enumerate(MIGRATIONS[current:], start=current + 1):
            with self.connection:
                self.connection.executescript(migration)
                self.connection.execute(f"PRAGMA user_version = {version}")

    def close(self) -> None:
        self.connection.close()

    def __enter__(self) -> Database:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()
