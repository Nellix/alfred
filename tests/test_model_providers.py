from __future__ import annotations

import json

import httpx
import pytest

from sma.intelligence.agent_api import AgentPlanningGateway, AgentPlanRequest
from sma.intelligence.model_client import (
    ChatCompletionsClient,
    FallbackChatCompletionsClient,
    ModelProvider,
    ModelUnavailable,
    model_provider_chain_from_environment,
    model_provider_from_environment,
)
from sma.intelligence.planner import LLMPlanner
from sma.memory.database import Database
from sma.perception.canonicalizer import canonicalize
from sma.service import health


def test_gemini_free_tier_configuration() -> None:
    config = model_provider_from_environment(
        {"LLM_PROVIDER": "gemini", "GEMINI_API_KEY": "test-gemini-key"}
    )

    assert config is not None
    assert config.provider is ModelProvider.GEMINI
    assert config.base_url == "https://generativelanguage.googleapis.com/v1beta"
    assert config.model == "gemini-3.5-flash-lite"
    assert config.headers["x-goog-api-client"].startswith("alfred-sma-native/")
    assert "test-gemini-key" not in repr(config.headers)


def test_openrouter_free_configuration_and_attribution() -> None:
    config = model_provider_from_environment(
        {
            "LLM_PROVIDER": "openrouter",
            "OPENROUTER_API_KEY": "test-openrouter-key",
            "OPENROUTER_SITE_URL": "https://alfred.example",
        }
    )

    assert config is not None
    assert config.provider is ModelProvider.OPENROUTER
    assert config.model == "openrouter/free"
    assert config.require_structured_output_support is True
    assert config.headers == {
        "X-OpenRouter-Title": "Alfred",
        "HTTP-Referer": "https://alfred.example",
    }


@pytest.mark.parametrize(
    ("environment", "message"),
    [
        ({"LLM_PROVIDER": "gemini"}, "GEMINI_API_KEY"),
        ({"LLM_PROVIDER": "openrouter"}, "OPENROUTER_API_KEY"),
        ({"LLM_PROVIDER": "custom"}, "LLM_BASE_URL"),
        ({"LLM_PROVIDER": "unknown"}, "gemini, openrouter, or custom"),
        (
            {"LLM_PROVIDER_CHAIN": "gemini,gemini", "GEMINI_API_KEY": "key"},
            "duplicate",
        ),
    ],
)
def test_invalid_provider_configuration_fails_closed(
    environment: dict[str, str], message: str
) -> None:
    with pytest.raises(ValueError, match=message):
        model_provider_from_environment(environment)


@pytest.mark.asyncio
async def test_openrouter_request_requires_structured_output_provider() -> None:
    requests: list[httpx.Request] = []

    async def handle(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": '{"action":"stop"}'}}],
                "model": "free-structured-model",
                "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 4,
                    "prompt_tokens_details": {"cached_tokens": 3},
                },
            },
        )

    config = model_provider_from_environment(
        {"LLM_PROVIDER": "openrouter", "OPENROUTER_API_KEY": "test-key"}
    )
    assert config is not None
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handle), base_url=config.base_url
    ) as http:
        client = ChatCompletionsClient(config, client=http)
        response = await client.complete(
            [{"role": "user", "content": "stop"}],
            {
                "type": "object",
                "properties": {"action": {"type": "string"}},
                "required": ["action"],
                "additionalProperties": False,
            },
        )

    payload = json.loads(requests[0].content)
    assert requests[0].url.path == "/api/v1/chat/completions"
    assert payload["model"] == "openrouter/free"
    assert payload["provider"] == {"require_parameters": True}
    assert payload["response_format"]["type"] == "json_schema"
    assert response.model == "free-structured-model"
    assert response.provider == "openrouter"
    assert response.usage.cached_input_tokens == 3


@pytest.mark.asyncio
async def test_free_tier_rate_limit_has_provider_specific_error() -> None:
    async def handle(_: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"error": "quota"})

    config = model_provider_from_environment(
        {"LLM_PROVIDER": "gemini", "GEMINI_API_KEY": "test-key"}
    )
    assert config is not None
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handle), base_url=config.base_url
    ) as http:
        client = ChatCompletionsClient(config, client=http)
        with pytest.raises(ModelUnavailable, match="gemini free-tier rate limit"):
            await client.complete([{"role": "user", "content": "hello"}])


@pytest.mark.asyncio
async def test_unrecognized_intent_reaches_gemini_protocol_path(settings_data, tmp_path) -> None:
    async def handle(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        assert request.url.path.endswith("/models/gemini-3.5-flash-lite:generateContent")
        assert payload["generationConfig"]["responseMimeType"] == "application/json"
        assert payload["generationConfig"]["responseJsonSchema"]["type"] == "object"
        assert payload["systemInstruction"]["parts"]
        return httpx.Response(
            200,
            json={
                "candidates": [
                    {
                        "content": {
                            "parts": [
                                {
                                    "text": json.dumps(
                                        {
                                            "canonical_intent": "android.settings.finish",
                                            "parameters": {},
                                            "intent_confidence": 0.93,
                                            "action": {"action": "stop"},
                                        }
                                    )
                                }
                            ]
                        }
                    }
                ],
                "modelVersion": "gemini-3.5-flash-lite",
                "usageMetadata": {"promptTokenCount": 30, "candidatesTokenCount": 12},
            },
        )

    config = model_provider_from_environment(
        {"LLM_PROVIDER": "gemini", "GEMINI_API_KEY": "test-key"}
    )
    assert config is not None
    state = canonicalize(settings_data["states"]["settings_home"])
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handle), base_url=config.base_url
    ) as http:
        model = ChatCompletionsClient(config, client=http)
        with Database(tmp_path / "gemini-path.db") as database:
            response = await AgentPlanningGateway(database, LLMPlanner(model)).plan(
                AgentPlanRequest(goal="finish this unusual settings task", state=state)
            )

    assert response.source == "llm"
    assert response.model_calls == 1
    assert response.canonical_intent == "android.settings.finish"
    assert response.model_provider == "gemini"
    assert response.model_id == "gemini-3.5-flash-lite"
    assert response.terminal is True


def test_legacy_local_configuration_remains_available() -> None:
    config = model_provider_from_environment(
        {
            "LLM_BASE_URL": "http://127.0.0.1:8000/v1",
            "LLM_MODEL": "local-model",
        }
    )

    assert config is not None
    assert config.provider is ModelProvider.CUSTOM
    assert config.api_key == "local-no-key"


def test_provider_chain_preserves_failover_order() -> None:
    configs = model_provider_chain_from_environment(
        {
            "LLM_PROVIDER_CHAIN": "gemini,openrouter",
            "GEMINI_API_KEY": "gemini-key",
            "OPENROUTER_API_KEY": "openrouter-key",
        }
    )

    assert [item.provider for item in configs] == [
        ModelProvider.GEMINI,
        ModelProvider.OPENROUTER,
    ]


@pytest.mark.asyncio
async def test_rate_limited_gemini_falls_back_to_openrouter() -> None:
    calls: list[str] = []

    async def gemini_handler(_: httpx.Request) -> httpx.Response:
        calls.append("gemini")
        return httpx.Response(429, json={"error": "quota"})

    async def openrouter_handler(_: httpx.Request) -> httpx.Response:
        calls.append("openrouter")
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": '{"action":"stop"}'}}],
                "model": "openrouter/free-model",
                "usage": {"prompt_tokens": 4, "completion_tokens": 2},
            },
        )

    configs = model_provider_chain_from_environment(
        {
            "LLM_PROVIDER_CHAIN": "gemini,openrouter",
            "GEMINI_API_KEY": "gemini-key",
            "OPENROUTER_API_KEY": "openrouter-key",
        }
    )
    async with (
        httpx.AsyncClient(
            transport=httpx.MockTransport(gemini_handler), base_url=configs[0].base_url
        ) as gemini_http,
        httpx.AsyncClient(
            transport=httpx.MockTransport(openrouter_handler), base_url=configs[1].base_url
        ) as openrouter_http,
    ):
        client = FallbackChatCompletionsClient(
            [
                ChatCompletionsClient(configs[0], client=gemini_http),
                ChatCompletionsClient(configs[1], client=openrouter_http),
            ]
        )
        response = await client.complete(
            [{"role": "user", "content": "stop"}],
            {"type": "object"},
        )

    assert calls == ["gemini", "openrouter"]
    assert response.model == "openrouter/free-model"
    assert response.provider == "openrouter"


@pytest.mark.asyncio
async def test_health_reports_provider_without_exposing_key(monkeypatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "gemini")
    monkeypatch.setenv("GEMINI_API_KEY", "must-not-appear")

    response = await health()

    assert response["model"] == {
        "configured": True,
        "providers": [{"provider": "gemini", "model": "gemini-3.5-flash-lite"}],
    }
    assert "must-not-appear" not in json.dumps(response)
