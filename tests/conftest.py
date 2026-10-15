from __future__ import annotations

import json
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def settings_data() -> dict:
    return json.loads((ROOT / "fixtures/settings/replay.json").read_text())


@pytest.fixture
def settings_fixture() -> Path:
    return ROOT / "fixtures/settings/replay.json"
