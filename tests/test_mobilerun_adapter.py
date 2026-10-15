from __future__ import annotations

import base64

import pytest

from sma.device.mobilerun_adapter import MobilerunAdapter, _decode_result, _unwrap


@pytest.mark.asyncio
async def test_portal_keyboard_and_deep_link_match_documented_contract() -> None:
    adapter = MobilerunAdapter()
    calls: list[tuple[str, dict[str, object]]] = []

    async def capture(path: str, data: dict[str, object]) -> None:
        calls.append((path, data))

    adapter._post = capture  # type: ignore[method-assign]
    await adapter.input_text("Torino")
    await adapter.open_deep_link("geo:0,0?q=Torino", "com.example.maps")
    await adapter.close()
    assert calls[0] == (
        "/keyboard/input",
        {"base64_text": base64.b64encode(b"Torino").decode(), "clear": "true"},
    )
    assert calls[1] == (
        "/app/deep-link",
        {"deepLink": "geo:0,0?q=Torino", "displayId": 0, "package": "com.example.maps"},
    )


def test_portal_json_string_result_is_unwrapped() -> None:
    assert _unwrap({"status": "success", "result": '{"package":"com.android.settings"}'}) == {
        "package": "com.android.settings"
    }


def test_portal_json_list_result_is_decoded() -> None:
    assert _decode_result({"status": "success", "result": '[{"text":"Settings"}]'}) == [
        {"text": "Settings"}
    ]
