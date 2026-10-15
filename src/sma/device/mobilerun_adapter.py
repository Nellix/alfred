from __future__ import annotations

import base64
import json
from typing import Any

import httpx

from sma.device.interface import AuthenticationError, DeviceDisconnected, DeviceError


class MobilerunAdapter:
    """Adapter for Mobilerun Portal's documented localhost HTTP API.

    Portal action endpoints have evolved; the adapter uses current local API names and
    isolates those details from the semantic runtime.
    """

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8080",
        token: str | None = None,
        *,
        timeout: float = 10.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        self._client = httpx.AsyncClient(base_url=self.base_url, headers=headers, timeout=timeout)

    async def __aenter__(self) -> MobilerunAdapter:
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.close()

    async def close(self) -> None:
        await self._client.aclose()

    async def _request(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        try:
            response = await self._client.request(method, path, **kwargs)
        except httpx.ConnectError as exc:
            raise DeviceDisconnected(f"Portal is unreachable at {self.base_url}") from exc
        if response.status_code in {401, 403}:
            raise AuthenticationError("Portal rejected the authentication token")
        try:
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            raise DeviceError(f"Portal {path} failed: HTTP {response.status_code}") from exc
        return response

    async def ping(self) -> bool:
        return (await self._request("GET", "/ping")).status_code == 200

    async def get_ui_state(self) -> dict[str, Any]:
        response = await self._request("GET", "/a11y_tree_full", params={"filter": "false"})
        return _unwrap(response.json())

    async def get_device_state(self) -> dict[str, Any]:
        response = await self._request("GET", "/state")
        return _unwrap(response.json())

    async def _post(self, path: str, data: dict[str, Any]) -> None:
        response = await self._request("POST", path, data=data)
        if "application/json" in response.headers.get("content-type", ""):
            body = response.json()
            if isinstance(body, dict) and body.get("status") == "error":
                raise DeviceError(
                    str(body.get("error") or body.get("result") or "Portal action failed")
                )

    async def tap(self, x: int, y: int) -> None:
        await self._post("/tap", {"x": x, "y": y})

    async def input_text(self, text: str) -> None:
        encoded = base64.b64encode(text.encode()).decode()
        await self._post("/keyboard/input", {"base64_text": encoded, "clear": "true"})

    async def press_back(self) -> None:
        await self._post("/global", {"action": 1})

    async def launch_app(self, package: str) -> None:
        await self._post("/app", {"package": package, "stopBeforeLaunch": "false"})

    async def open_deep_link(self, uri: str, package: str | None = None) -> None:
        data = {"deepLink": uri, "displayId": 0}
        if package:
            data["package"] = package
        await self._post("/app/deep-link", data)

    async def swipe(
        self, start: tuple[int, int], end: tuple[int, int], duration_ms: int = 300
    ) -> None:
        await self._post(
            "/swipe",
            {
                "startX": start[0],
                "startY": start[1],
                "endX": end[0],
                "endY": end[1],
                "duration": duration_ms,
            },
        )

    async def screenshot(self) -> bytes:
        return (await self._request("GET", "/screenshot", params={"hideOverlay": "true"})).content


def _unwrap(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise DeviceError("Portal returned a non-object JSON response")
    result = _decode_result(value)
    if not isinstance(result, dict):
        raise DeviceError("Portal response did not contain an object result")
    return result


def _decode_result(value: Any) -> Any:
    """Decode Portal's object, JSON-string, or base64-JSON result envelope."""
    if not isinstance(value, dict):
        raise DeviceError("Portal returned a non-object JSON response")
    result = value.get("result", value)
    if isinstance(result, str):
        stripped = result.strip()
        if stripped.startswith(("{", "[")):
            try:
                result = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise DeviceError("Portal returned malformed JSON in its result envelope") from exc
    if isinstance(result, str):
        try:
            decoded = base64.b64decode(result, validate=True)
            if decoded.startswith((b"{", b"[")):
                result = json.loads(decoded)
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            pass
    return result
