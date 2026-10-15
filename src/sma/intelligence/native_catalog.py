from __future__ import annotations

import re
from enum import StrEnum
from urllib.parse import urlencode, urlparse

from pydantic import BaseModel, Field

from sma.execution.action_executor import SemanticExecutor
from sma.protocol.intents import SemanticIntent
from sma.protocol.models import ActionResult, ActionStatus, SemanticAction

GOOGLE_MAPS = "com.google.android.apps.maps"
YOUTUBE = "com.google.android.youtube"
GMAIL = "com.google.android.gm"
OUTLOOK = "com.microsoft.office.outlook"
OUTLOOK_LITE = "com.microsoft.outlooklite"
WHATSAPP = "com.whatsapp"
TELEGRAM = "org.telegram.messenger"


class NativePlanStatus(StrEnum):
    SUCCESS = "success"
    PARTIAL = "partial"
    FAILED = "failed"


class CatalogContext(BaseModel):
    installed_packages: set[str] = Field(default_factory=set)
    allow_browser_fallback: bool = False

    def select(self, *packages: str) -> str | None:
        return next((package for package in packages if package in self.installed_packages), None)


class NativePlan(BaseModel):
    canonical_intent: str
    strategy_id: str
    actions: list[SemanticAction] = Field(min_length=1, max_length=3)
    terminal: bool = True
    completion_confirmation_required: bool = False
    note: str | None = None


class NativePlanResult(BaseModel):
    status: NativePlanStatus
    canonical_intent: str
    strategy_id: str
    action_results: list[ActionResult]
    model_calls: int = 0
    fallback_recommended: bool = False
    completion_confirmation_required: bool = False
    message: str | None = None


class NativeActionCatalog:
    """Resolve semantic intents to allowlisted Android app launches and universal links."""

    def resolve(
        self, intent: SemanticIntent, context: CatalogContext | None = None
    ) -> NativePlan | None:
        current = context or CatalogContext()
        try:
            if intent.canonical_intent == "android.settings.open":
                return NativePlan(
                    canonical_intent=intent.canonical_intent,
                    strategy_id="android.settings.launch",
                    actions=[SemanticAction(action="launch_app", package="com.android.settings")],
                )
            if intent.canonical_intent == "navigation.route":
                return self._maps_directions(intent, current)
            if intent.canonical_intent == "media.youtube.play":
                return self._youtube(intent, current)
            if intent.canonical_intent == "email.inbox.check":
                return self._mail(intent, current)
            if intent.canonical_intent == "message.compose":
                return self._message_draft(intent, current)
        except ValueError:
            return None
        return None

    @staticmethod
    def _maps_directions(intent: SemanticIntent, context: CatalogContext) -> NativePlan | None:
        destination = _string_parameter(intent, "destination", max_length=512)
        package = context.select(GOOGLE_MAPS)
        if package is None and not context.allow_browser_fallback:
            return None
        uri = "https://www.google.com/maps/dir/?" + urlencode(
            {"api": "1", "destination": destination}
        )
        return NativePlan(
            canonical_intent=intent.canonical_intent,
            strategy_id="google_maps.directions",
            actions=[SemanticAction(action="open_deep_link", uri=uri, package=package)],
            note="opens directions preview; it does not start turn-by-turn guidance",
        )

    @staticmethod
    def _youtube(intent: SemanticIntent, context: CatalogContext) -> NativePlan | None:
        query = _string_parameter(intent, "query", max_length=512)
        package = context.select(YOUTUBE)
        if package is None and not context.allow_browser_fallback:
            return None
        if _is_youtube_url(query):
            uri = query
            terminal = True
            note = "opens the requested YouTube URL"
        else:
            uri = "https://www.youtube.com/results?" + urlencode({"search_query": query})
            terminal = False
            note = "opens YouTube results; local semantic selection is still required"
        return NativePlan(
            canonical_intent=intent.canonical_intent,
            strategy_id="youtube.open_or_search",
            actions=[SemanticAction(action="open_deep_link", uri=uri, package=package)],
            terminal=terminal,
            note=note,
        )

    @staticmethod
    def _mail(intent: SemanticIntent, context: CatalogContext) -> NativePlan | None:
        package = context.select(GMAIL, OUTLOOK, OUTLOOK_LITE)
        if package is None:
            return None
        return NativePlan(
            canonical_intent=intent.canonical_intent,
            strategy_id="mail.launch_inbox",
            actions=[SemanticAction(action="launch_app", package=package)],
        )

    @staticmethod
    def _message_draft(intent: SemanticIntent, context: CatalogContext) -> NativePlan | None:
        channel = _optional_string(intent, "channel").casefold()
        recipient = _optional_string(intent, "recipient")
        message = _optional_string(intent, "message", max_length=1000)
        if channel == "whatsapp" and WHATSAPP in context.installed_packages:
            uri = _whatsapp_uri(recipient, message)
            if uri is None:
                return NativePlan(
                    canonical_intent=intent.canonical_intent,
                    strategy_id="whatsapp.launch_for_draft",
                    actions=[SemanticAction(action="launch_app", package=WHATSAPP)],
                    terminal=False,
                    completion_confirmation_required=True,
                    note="recipient requires local semantic selection; sending is never automatic",
                )
            return NativePlan(
                canonical_intent=intent.canonical_intent,
                strategy_id="whatsapp.prefill_draft",
                actions=[SemanticAction(action="open_deep_link", uri=uri, package=WHATSAPP)],
                terminal=False,
                completion_confirmation_required=True,
                note="opens a draft only; sending is never automatic",
            )
        if channel == "telegram" and TELEGRAM in context.installed_packages:
            uri = _telegram_uri(recipient, message)
            if uri is None:
                return NativePlan(
                    canonical_intent=intent.canonical_intent,
                    strategy_id="telegram.launch_for_draft",
                    actions=[SemanticAction(action="launch_app", package=TELEGRAM)],
                    terminal=False,
                    completion_confirmation_required=True,
                    note="recipient requires local semantic selection; sending is never automatic",
                )
            return NativePlan(
                canonical_intent=intent.canonical_intent,
                strategy_id="telegram.prefill_draft",
                actions=[SemanticAction(action="open_deep_link", uri=uri, package=TELEGRAM)],
                terminal=False,
                completion_confirmation_required=True,
                note="opens a draft only; sending is never automatic",
            )
        return None


class NativePlanExecutor:
    def __init__(self, executor: SemanticExecutor) -> None:
        self.executor = executor

    async def execute(self, plan: NativePlan) -> NativePlanResult:
        results: list[ActionResult] = []
        for action in plan.actions:
            result = await self.executor.execute(action)
            results.append(result)
            if result.status is not ActionStatus.SUCCESS:
                return NativePlanResult(
                    status=NativePlanStatus.FAILED,
                    canonical_intent=plan.canonical_intent,
                    strategy_id=plan.strategy_id,
                    action_results=results,
                    fallback_recommended=True,
                    completion_confirmation_required=plan.completion_confirmation_required,
                    message=result.message,
                )
        return NativePlanResult(
            status=NativePlanStatus.SUCCESS if plan.terminal else NativePlanStatus.PARTIAL,
            canonical_intent=plan.canonical_intent,
            strategy_id=plan.strategy_id,
            action_results=results,
            fallback_recommended=not plan.terminal,
            completion_confirmation_required=plan.completion_confirmation_required,
            message=plan.note,
        )


def _string_parameter(intent: SemanticIntent, name: str, *, max_length: int) -> str:
    value = intent.parameters.get(name)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"intent parameter {name!r} must be a non-empty string")
    stripped = value.strip()
    if len(stripped) > max_length:
        raise ValueError(f"intent parameter {name!r} exceeds {max_length} characters")
    return stripped


def _optional_string(intent: SemanticIntent, name: str, *, max_length: int = 512) -> str:
    value = intent.parameters.get(name, "")
    if not isinstance(value, str):
        raise ValueError(f"intent parameter {name!r} must be a string")
    stripped = value.strip()
    if len(stripped) > max_length:
        raise ValueError(f"intent parameter {name!r} exceeds {max_length} characters")
    return stripped


def _is_youtube_url(value: str) -> bool:
    parsed = urlparse(value)
    return parsed.scheme == "https" and (parsed.hostname or "").casefold() in {
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
    }


def _international_phone(value: str) -> str | None:
    digits = re.sub(r"\D", "", value)
    return digits if value.lstrip().startswith("+") and 7 <= len(digits) <= 15 else None


def _whatsapp_uri(recipient: str, message: str) -> str | None:
    phone = _international_phone(recipient)
    if phone:
        suffix = "?" + urlencode({"text": message}) if message else ""
        return f"https://wa.me/{phone}{suffix}"
    if message:
        return "https://wa.me/?" + urlencode({"text": message})
    return None


def _telegram_uri(recipient: str, message: str) -> str | None:
    username = recipient.removeprefix("@").strip()
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{4,31}", username):
        suffix = "?" + urlencode({"text": message}) if message else ""
        return f"https://t.me/{username}{suffix}"
    phone = _international_phone(recipient)
    if phone:
        suffix = "?" + urlencode({"text": message}) if message else ""
        return f"https://t.me/+{phone}{suffix}"
    return None
