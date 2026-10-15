from __future__ import annotations

import json
import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Protocol

import httpx
from pydantic import BaseModel


class ModelUsage(BaseModel):
    input_tokens: int = 0
    output_tokens: int = 0
    cached_input_tokens: int = 0


class ModelResponse(BaseModel):
    content: str
    usage: ModelUsage
    model: str
    provider: str | None = None


class ModelUnavailable(RuntimeError):
    pass


class ModelProvider(StrEnum):
    GEMINI = "gemini"
    OPENROUTER = "openrouter"
    CUSTOM = "custom"


@dataclass(frozen=True)
class ModelProviderConfig:
    provider: ModelProvider
    base_url: str
    api_key: str
    model: str
    headers: dict[str, str] = field(default_factory=dict)
    require_structured_output_support: bool = False


def model_provider_from_environment(
    environment: Mapping[str, str] | None = None,
) -> ModelProviderConfig | None:
    """Resolve the first configured provider for compatibility with single-provider callers."""
    chain = model_provider_chain_from_environment(environment)
    return chain[0] if chain else None


def model_provider_chain_from_environment(
    environment: Mapping[str, str] | None = None,
) -> list[ModelProviderConfig]:
    """Resolve an ordered backend-only provider chain without exposing its credentials."""
    env = os.environ if environment is None else environment
    chain_value = env.get("LLM_PROVIDER_CHAIN", "").strip()
    selected = [item.strip().casefold() for item in chain_value.split(",") if item.strip()]
    if not selected:
        single = env.get("LLM_PROVIDER", "").strip().casefold()
        if not single and env.get("LLM_BASE_URL") and env.get("LLM_MODEL"):
            single = ModelProvider.CUSTOM.value
        selected = [single] if single else []
    if not selected:
        return []
    if len(selected) != len(set(selected)):
        raise ValueError("LLM_PROVIDER_CHAIN cannot contain duplicate providers")
    return [_provider_config(name, env) for name in selected]


def _provider_config(selected: str, env: Mapping[str, str]) -> ModelProviderConfig:
    try:
        provider = ModelProvider(selected)
    except ValueError as exc:
        raise ValueError(
            "LLM_PROVIDER_CHAIN entries must be gemini, openrouter, or custom"
        ) from exc

    if provider is ModelProvider.GEMINI:
        api_key = _required(env, "GEMINI_API_KEY", provider)
        return ModelProviderConfig(
            provider=provider,
            base_url=env.get(
                "GEMINI_BASE_URL",
                "https://generativelanguage.googleapis.com/v1beta",
            ),
            api_key=api_key,
            model=env.get("GEMINI_MODEL", "gemini-3.5-flash-lite"),
            headers={"x-goog-api-client": "alfred-sma-native/0.1.0"},
        )

    if provider is ModelProvider.OPENROUTER:
        api_key = _required(env, "OPENROUTER_API_KEY", provider)
        headers = {"X-OpenRouter-Title": env.get("OPENROUTER_APP_NAME", "Alfred")}
        site_url = env.get("OPENROUTER_SITE_URL", "").strip()
        if site_url:
            headers["HTTP-Referer"] = site_url
        return ModelProviderConfig(
            provider=provider,
            base_url=env.get("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"),
            api_key=api_key,
            model=env.get("OPENROUTER_MODEL", "openrouter/free"),
            headers=headers,
            require_structured_output_support=True,
        )

    base_url = _required(env, "LLM_BASE_URL", provider)
    model = _required(env, "LLM_MODEL", provider)
    return ModelProviderConfig(
        provider=provider,
        base_url=base_url,
        api_key=env.get("LLM_API_KEY", "local-no-key"),
        model=model,
    )


def _required(env: Mapping[str, str], name: str, provider: ModelProvider) -> str:
    value = env.get(name, "").strip()
    if not value:
        raise ValueError(f"{name} is required when LLM_PROVIDER={provider.value}")
    return value


class ChatCompletionsClient:
    """Small provider-neutral client for the common chat-completions protocol."""

    def __init__(
        self,
        config: ModelProviderConfig,
        *,
        timeout: float = 30.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.provider = config.provider
        self.base_url = config.base_url.rstrip("/")
        self.model = config.model
        self.require_structured_output_support = config.require_structured_output_support
        self._owns_client = client is None
        auth_headers = (
            {"x-goog-api-key": config.api_key}
            if self.provider is ModelProvider.GEMINI
            else {"Authorization": f"Bearer {config.api_key}"}
        )
        self.client = client or httpx.AsyncClient(
            base_url=self.base_url,
            headers={**auth_headers, **config.headers},
            timeout=timeout,
        )

    async def close(self) -> None:
        if self._owns_client:
            await self.client.aclose()

    async def health(self) -> bool:
        try:
            response = await self.client.get("/models")
            return response.status_code < 500
        except httpx.HTTPError:
            return False

    async def complete(
        self, messages: list[dict[str, str]], schema: dict[str, Any] | None = None
    ) -> ModelResponse:
        try:
            if self.provider is ModelProvider.GEMINI:
                return await self._complete_gemini(messages, schema)
            return await self._complete_openai(messages, schema)
        except httpx.HTTPStatusError as exc:
            status = exc.response.status_code
            if status in {401, 403}:
                message = f"{self.provider.value} rejected the model credentials"
            elif status == 402:
                message = f"{self.provider.value} requires credits for the selected model"
            elif status == 429:
                message = f"{self.provider.value} free-tier rate limit was reached"
            else:
                message = f"{self.provider.value} model endpoint returned HTTP {status}"
            raise ModelUnavailable(message) from exc
        except (httpx.HTTPError, KeyError, IndexError, json.JSONDecodeError) as exc:
            raise ModelUnavailable("model endpoint failed or returned an invalid response") from exc

    async def _complete_openai(
        self, messages: list[dict[str, str]], schema: dict[str, Any] | None
    ) -> ModelResponse:
        payload: dict[str, Any] = {"model": self.model, "messages": messages, "temperature": 0}
        if schema:
            payload["response_format"] = {
                "type": "json_schema",
                "json_schema": {"name": "semantic_action", "strict": True, "schema": schema},
            }
            if self.require_structured_output_support:
                payload["provider"] = {"require_parameters": True}
        response = await self.client.post("/chat/completions", json=payload)
        response.raise_for_status()
        data = response.json()
        content = data["choices"][0]["message"]["content"]
        usage = data.get("usage", {})
        details = usage.get("prompt_tokens_details", {})
        return ModelResponse(
            content=content,
            model=data.get("model", self.model),
            provider=self.provider.value,
            usage=ModelUsage(
                input_tokens=usage.get("prompt_tokens", 0),
                output_tokens=usage.get("completion_tokens", 0),
                cached_input_tokens=details.get("cached_tokens", 0),
            ),
        )

    async def _complete_gemini(
        self, messages: list[dict[str, str]], schema: dict[str, Any] | None
    ) -> ModelResponse:
        system_text = "\n\n".join(item["content"] for item in messages if item["role"] == "system")
        contents = [
            {
                "role": "model" if item["role"] == "assistant" else "user",
                "parts": [{"text": item["content"]}],
            }
            for item in messages
            if item["role"] != "system"
        ]
        payload: dict[str, Any] = {
            "contents": contents,
            "generationConfig": {"temperature": 0},
        }
        if system_text:
            payload["systemInstruction"] = {"parts": [{"text": system_text}]}
        if schema:
            payload["generationConfig"].update(
                {"responseMimeType": "application/json", "responseJsonSchema": schema}
            )
        response = await self.client.post(f"/models/{self.model}:generateContent", json=payload)
        response.raise_for_status()
        data = response.json()
        content = data["candidates"][0]["content"]["parts"][0]["text"]
        usage = data.get("usageMetadata", {})
        return ModelResponse(
            content=content,
            model=data.get("modelVersion", self.model),
            provider=self.provider.value,
            usage=ModelUsage(
                input_tokens=usage.get("promptTokenCount", 0),
                output_tokens=usage.get("candidatesTokenCount", 0),
                cached_input_tokens=usage.get("cachedContentTokenCount", 0),
            ),
        )


class ModelClient(Protocol):
    async def complete(
        self, messages: list[dict[str, str]], schema: dict[str, Any] | None = None
    ) -> ModelResponse: ...


class FallbackChatCompletionsClient:
    """Try configured providers in order when quota or availability blocks a request."""

    def __init__(self, clients: list[ChatCompletionsClient]) -> None:
        if not clients:
            raise ValueError("at least one model client is required")
        self.clients = clients

    async def close(self) -> None:
        for client in self.clients:
            await client.close()

    async def health(self) -> bool:
        for client in self.clients:
            if await client.health():
                return True
        return False

    async def complete(
        self, messages: list[dict[str, str]], schema: dict[str, Any] | None = None
    ) -> ModelResponse:
        failures: list[str] = []
        for client in self.clients:
            try:
                return await client.complete(messages, schema)
            except ModelUnavailable as exc:
                failures.append(f"{client.provider.value}: {exc}")
        raise ModelUnavailable("all configured model providers failed; " + "; ".join(failures))
