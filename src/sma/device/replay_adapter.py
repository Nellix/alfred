from __future__ import annotations

import asyncio
import json
from copy import deepcopy
from pathlib import Path
from typing import Any

from sma.device.interface import DeviceDisconnected, DeviceError


class ReplayDeviceAdapter:
    """Deterministic state machine backed by a fixture JSON document."""

    def __init__(self, fixture: str | Path | dict[str, Any]) -> None:
        data = (
            json.loads(Path(fixture).read_text()) if isinstance(fixture, (str, Path)) else fixture
        )
        self.states: dict[str, dict[str, Any]] = deepcopy(data["states"])
        self.transitions: dict[str, dict[str, str]] = deepcopy(data.get("transitions", {}))
        self.current_id: str = data["initial_state"]
        self.connected = True
        self.interrupted = False
        self.actions: list[dict[str, Any]] = []

    async def ping(self) -> bool:
        return self.connected

    def _check(self) -> None:
        if not self.connected:
            raise DeviceDisconnected("replay device disconnected")
        if self.interrupted:
            raise asyncio.CancelledError("user interrupted replay")

    async def get_ui_state(self) -> dict[str, Any]:
        self._check()
        return deepcopy(self.states[self.current_id])

    async def get_device_state(self) -> dict[str, Any]:
        self._check()
        state = self.states[self.current_id]
        return {"package": state.get("package"), "screen": self.current_id, "connected": True}

    async def _transition(self, key: str, payload: dict[str, Any]) -> None:
        self._check()
        self.actions.append({"key": key, **payload})
        target = self.transitions.get(self.current_id, {}).get(key)
        if target is None:
            raise DeviceError(f"no replay transition from {self.current_id!r} for {key!r}")
        if target not in self.states:
            raise DeviceError(f"replay transition references unknown state {target!r}")
        self.current_id = target

    async def tap(self, x: int, y: int) -> None:
        state = self.states[self.current_id]
        for node in _all_nodes(state):
            bounds = node.get("bounds")
            if bounds and bounds[0] <= x <= bounds[2] and bounds[1] <= y <= bounds[3]:
                identity = (
                    node.get("resource_id") or node.get("text") or node.get("content_description")
                )
                key = f"click:{identity}"
                await self._transition(key, {"x": x, "y": y})
                return
        raise DeviceError(f"tap at ({x}, {y}) did not hit a replay node")

    async def input_text(self, text: str) -> None:
        await self._transition("input:*", {"text": text})

    async def press_back(self) -> None:
        await self._transition("back", {})

    async def launch_app(self, package: str) -> None:
        await self._transition(f"launch:{package}", {"package": package})

    async def open_deep_link(self, uri: str, package: str | None = None) -> None:
        await self._transition(f"deep_link:{uri}", {"uri": uri, "package": package})

    async def swipe(
        self, start: tuple[int, int], end: tuple[int, int], duration_ms: int = 300
    ) -> None:
        direction = "down" if end[1] > start[1] else "up"
        await self._transition(f"scroll:{direction}", {"start": start, "end": end})

    async def screenshot(self) -> bytes:
        raise DeviceError("replay fixtures do not contain screenshots")


def _all_nodes(value: Any) -> list[dict[str, Any]]:
    found: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if "class" in value or "className" in value:
            found.append(value)
        for child in value.values():
            found.extend(_all_nodes(child))
    elif isinstance(value, list):
        for child in value:
            found.extend(_all_nodes(child))
    return found
