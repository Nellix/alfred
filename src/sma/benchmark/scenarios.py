from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field


class Scenario(BaseModel):
    id: str
    goal: str
    fixture: str
    state_id: str
    expected_label: str | None = None
    expected_resource_id: str | None = None
    previous_state_id: str | None = None
    warm_skill: bool = False
    actions: int = Field(default=1, ge=0)


class Suite(BaseModel):
    name: str
    scenarios: list[Scenario] = Field(min_length=1)

    @classmethod
    def load(cls, path: str | Path) -> Suite:
        path = Path(path)
        data = yaml.safe_load(path.read_text())
        suite = cls.model_validate(data)
        for scenario in suite.scenarios:
            fixture = Path(scenario.fixture)
            if not fixture.is_absolute():
                scenario.fixture = str((path.parent / fixture).resolve())
        return suite


def load_state(scenario: Scenario) -> tuple[dict[str, Any], dict[str, Any] | None]:
    import json

    data = json.loads(Path(scenario.fixture).read_text())
    states: dict[str, dict[str, Any]] = data["states"]
    return states[scenario.state_id], states.get(
        scenario.previous_state_id
    ) if scenario.previous_state_id else None
