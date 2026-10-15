from __future__ import annotations

import asyncio
import json
import re
import time
from pathlib import Path
from typing import Any, Literal
from urllib.parse import quote, quote_plus

import yaml
from pydantic import BaseModel, Field

from sma.execution.action_executor import SemanticExecutor
from sma.protocol.models import ActionStatus, SemanticAction


class ParameterSpec(BaseModel):
    type: Literal["string", "integer", "boolean"]
    required: bool = True
    pattern: str | None = None


class SkillStep(BaseModel):
    action: str
    target: str | None = None
    value: str | None = None
    package: str | None = None
    uri: str | None = None
    screen: str | None = None
    condition: str | None = None
    timeout_seconds: float = Field(default=5.0, gt=0, le=60)
    retries: int = Field(default=0, ge=0, le=3)


class Skill(BaseModel):
    id: str = Field(pattern=r"^[a-z][a-z0-9_]*$")
    version: int = Field(ge=1)
    parameters: dict[str, ParameterSpec] = Field(default_factory=dict)
    precondition_screen: str | None = None
    steps: list[SkillStep] = Field(min_length=1, max_length=50)
    on_mismatch: Literal["stop", "stop_and_escalate"] = "stop_and_escalate"
    approved: bool = False

    @classmethod
    def load(cls, path: str | Path) -> Skill:
        return cls.model_validate(yaml.safe_load(Path(path).read_text()))

    def validate_parameters(self, parameters: dict[str, Any]) -> dict[str, Any]:
        unexpected = parameters.keys() - self.parameters.keys()
        if unexpected:
            raise ValueError(f"unexpected parameters: {sorted(unexpected)}")
        checked: dict[str, Any] = {}
        types = {"string": str, "integer": int, "boolean": bool}
        for name, spec in self.parameters.items():
            if name not in parameters:
                if spec.required:
                    raise ValueError(f"missing parameter {name!r}")
                continue
            value = parameters[name]
            if type(value) is not types[spec.type]:
                raise ValueError(f"parameter {name!r} must be {spec.type}")
            if spec.pattern and isinstance(value, str) and not re.fullmatch(spec.pattern, value):
                raise ValueError(f"parameter {name!r} does not match its allowed pattern")
            checked[name] = value
        return checked


class SkillRun(BaseModel):
    status: Literal["success", "failed", "interrupted"]
    steps_completed: int
    model_calls: int = 0
    error: str | None = None


class SkillEngine:
    def __init__(self, executor: SemanticExecutor, connection: Any | None = None) -> None:
        self.executor = executor
        self.connection = connection

    async def run(
        self,
        skill: Skill,
        parameters: dict[str, Any],
        *,
        record_history: bool = True,
    ) -> SkillRun:
        if not skill.approved:
            return SkillRun(status="failed", steps_completed=0, error="skill is not approved")
        try:
            values = skill.validate_parameters(parameters)
        except ValueError as exc:
            return SkillRun(status="failed", steps_completed=0, error=str(exc))
        current = self.executor.tracker.current
        if skill.precondition_screen and (
            current is None or current.screen_id != skill.precondition_screen
        ):
            return SkillRun(status="failed", steps_completed=0, error="screen precondition failed")
        run_id = self._start_history(skill, values) if record_history else None
        completed = 0
        try:
            for step in skill.steps:
                action = render_skill_step(step, values)
                result = None
                for attempt in range(step.retries + 1):
                    result = await asyncio.wait_for(
                        self.executor.execute(action), timeout=step.timeout_seconds + 0.1
                    )
                    if result.status is ActionStatus.SUCCESS:
                        break
                    if attempt < step.retries:
                        await asyncio.sleep(0)
                if result is None or result.status is not ActionStatus.SUCCESS:
                    error = result.message if result else "step returned no result"
                    self._finish_history(run_id, "failed", completed, error)
                    return SkillRun(status="failed", steps_completed=completed, error=error)
                completed += 1
        except (asyncio.CancelledError, TimeoutError):
            self._finish_history(run_id, "interrupted", completed, "interrupted or timed out")
            return SkillRun(
                status="interrupted", steps_completed=completed, error="interrupted or timed out"
            )
        self._finish_history(run_id, "success", completed, None)
        return SkillRun(status="success", steps_completed=completed, model_calls=0)

    @staticmethod
    def _substitute(value: str | None, parameters: dict[str, Any]) -> str | None:
        if value is None:
            return None

        def replace(match: re.Match[str]) -> str:
            rendered = str(parameters[match.group(1)])
            if match.group(2) == "url":
                return quote(rendered, safe="")
            if match.group(2) == "query":
                return quote_plus(rendered)
            return rendered

        return re.sub(
            r"\$\{([a-zA-Z_][a-zA-Z0-9_]*)(?:\|(url|query))?\}",
            replace,
            value,
        )

    def _start_history(self, skill: Skill, parameters: dict[str, Any]) -> int | None:
        if self.connection is None:
            return None
        parameter_metadata = {
            name: {"type": type(value).__name__, "redacted": True}
            for name, value in parameters.items()
        }
        cursor = self.connection.execute(
            """INSERT INTO skill_runs(skill_id, skill_version, started_at, status, parameters_json)
            VALUES (?, ?, ?, 'running', ?)""",
            (skill.id, skill.version, time.time(), json.dumps(parameter_metadata, sort_keys=True)),
        )
        self.connection.commit()
        return int(cursor.lastrowid)

    def _finish_history(
        self, run_id: int | None, status: str, completed: int, error: str | None
    ) -> None:
        if self.connection is None or run_id is None:
            return
        self.connection.execute(
            "UPDATE skill_runs SET finished_at=?, status=?, steps_completed=?, error=? WHERE id=?",
            (time.time(), status, completed, error, run_id),
        )
        self.connection.commit()


def render_skill_step(step: SkillStep, parameters: dict[str, Any]) -> SemanticAction:
    """Render a validated skill step for an on-device executor without running it here."""
    aliases = {"wait_for_screen": "wait", "verify": "wait"}
    action = aliases.get(step.action, step.action)
    expected_screen = step.screen if action == "wait" else None
    return SemanticAction.model_validate(
        {
            "action": action,
            "target": SkillEngine._substitute(step.target, parameters),
            "value": SkillEngine._substitute(step.value, parameters),
            "package": SkillEngine._substitute(step.package, parameters),
            "uri": SkillEngine._substitute(step.uri, parameters),
            "expected_screen": expected_screen,
            "timeout_seconds": step.timeout_seconds,
        }
    )


def render_skill_actions(skill: Skill, parameters: dict[str, Any]) -> list[SemanticAction]:
    values = skill.validate_parameters(parameters)
    return [render_skill_step(step, values) for step in skill.steps]
