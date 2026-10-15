from __future__ import annotations

import pytest

from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_parser import IntentParser
from sma.intelligence.intent_runtime import CachedIntentExecutor
from sma.intelligence.local_router import LocalIntentRouter, LocalRoute
from sma.intelligence.native_catalog import (
    GMAIL,
    GOOGLE_MAPS,
    TELEGRAM,
    WHATSAPP,
    YOUTUBE,
    CatalogContext,
    NativeActionCatalog,
    NativePlanExecutor,
    NativePlanStatus,
)
from sma.memory.database import Database
from sma.memory.intent_cache import BindingStatus, IntentCache
from sma.memory.skill_engine import ParameterSpec, Skill, SkillEngine, SkillStep
from sma.perception.state_tracker import StateTracker


def _resolve(goal: str, packages: set[str]):
    intent = IntentParser().parse(goal)
    assert intent is not None
    return NativeActionCatalog().resolve(intent, CatalogContext(installed_packages=packages))


def test_maps_uses_encoded_official_directions_url() -> None:
    plan = _resolve("Take me to Rome, Italy using Google Maps", {GOOGLE_MAPS})
    assert plan is not None and plan.terminal
    action = plan.actions[0]
    assert action.action == "open_deep_link"
    assert action.package == GOOGLE_MAPS
    assert action.uri == "https://www.google.com/maps/dir/?api=1&destination=Rome%2C+Italy"
    assert "dir_action=navigate" not in action.uri


def test_youtube_url_is_terminal_but_search_requires_local_followup() -> None:
    direct = _resolve("Play https://youtu.be/dQw4w9WgXcQ on YouTube", {YOUTUBE})
    search = _resolve("Open YouTube and play lo-fi music", {YOUTUBE})
    assert direct is not None and direct.terminal
    assert direct.actions[0].uri == "https://youtu.be/dQw4w9WgXcQ"
    assert search is not None and not search.terminal
    assert search.actions[0].uri is not None
    assert "search_query=lo-fi+music" in search.actions[0].uri


def test_mail_uses_first_installed_allowlisted_provider() -> None:
    plan = _resolve("Check my email", {"unknown.mail", GMAIL})
    assert plan is not None
    assert plan.actions[0].action == "launch_app"
    assert plan.actions[0].package == GMAIL
    assert _resolve("Check my email", {"unknown.mail"}) is None


def test_message_links_create_drafts_and_never_send() -> None:
    whatsapp = _resolve("Send a message to +39 123 456 789 via WhatsApp: Hello there", {WHATSAPP})
    telegram = _resolve("Send a message to @alice via Telegram: Hi", {TELEGRAM})
    assert whatsapp is not None and not whatsapp.terminal
    assert whatsapp.completion_confirmation_required
    assert whatsapp.actions[0].uri == "https://wa.me/39123456789?text=Hello+there"
    assert telegram is not None and telegram.actions[0].uri == "https://t.me/alice?text=Hi"
    assert all(action.action in {"launch_app", "open_deep_link"} for action in whatsapp.actions)
    assert all(action.action in {"launch_app", "open_deep_link"} for action in telegram.actions)


def test_uninstalled_apps_and_oversized_parameters_cleanly_miss() -> None:
    assert _resolve("Open YouTube and play lo-fi music", set()) is None
    intent = IntentParser().parse("Take me to " + "x" * 600)
    assert intent is not None
    assert (
        NativeActionCatalog().resolve(intent, CatalogContext(installed_packages={GOOGLE_MAPS}))
        is None
    )


@pytest.mark.asyncio
async def test_complete_native_route_executes_before_empty_cache(
    settings_fixture, tmp_path
) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    executor = SemanticExecutor(device, tracker)
    with Database(tmp_path / "native.db") as database:
        skill_engine = SkillEngine(executor, database.connection)
        result = await LocalIntentRouter(
            CachedIntentExecutor(IntentCache(database.connection), skill_engine),
            NativePlanExecutor(executor),
        ).route("Open Android Settings")

    assert result.route is LocalRoute.NATIVE
    assert result.status == NativePlanStatus.SUCCESS.value
    assert result.model_calls == 0
    assert device.current_id == "settings_home"


@pytest.mark.asyncio
async def test_full_cached_skill_precedes_partial_native_strategy(
    settings_fixture, tmp_path
) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    executor = SemanticExecutor(device, tracker)
    skill = Skill(
        id="open_youtube_result_locally",
        version=1,
        approved=True,
        parameters={"query": ParameterSpec(type="string")},
        steps=[SkillStep(action="launch_app", package="com.android.settings")],
    )
    with Database(tmp_path / "partial.db") as database:
        cache = IntentCache(database.connection)
        cache.save_skill(skill)
        cache.bind(
            "media.youtube.play",
            skill.id,
            skill.version,
            status=BindingStatus.ACTIVE,
            confidence=0.99,
        )
        skill_engine = SkillEngine(executor, database.connection)
        result = await LocalIntentRouter(
            CachedIntentExecutor(cache, skill_engine), NativePlanExecutor(executor)
        ).route(
            "Open YouTube and play lo-fi music",
            catalog_context=CatalogContext(installed_packages={YOUTUBE}),
        )

    assert result.route is LocalRoute.INTENT_CACHE
    assert result.status == "success"
    assert result.model_calls == 0
    assert device.current_id == "settings_home"


@pytest.mark.asyncio
async def test_failed_complete_native_strategy_is_not_retried(settings_fixture, tmp_path) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    device.current_id = "settings_home"
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    executor = SemanticExecutor(device, tracker)
    with Database(tmp_path / "no-retry.db") as database:
        skill_engine = SkillEngine(executor, database.connection)
        result = await LocalIntentRouter(
            CachedIntentExecutor(IntentCache(database.connection), skill_engine),
            NativePlanExecutor(executor),
        ).route("Open Android Settings")

    assert result.route is LocalRoute.NATIVE
    assert result.status == NativePlanStatus.FAILED.value
    assert len(device.actions) == 1
