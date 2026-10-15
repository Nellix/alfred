from __future__ import annotations

import asyncio

import pytest

from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.execution.action_resolver import AmbiguousTarget, resolve_target
from sma.perception.canonicalizer import canonicalize
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import ActionStatus, SemanticAction


@pytest.mark.asyncio
async def test_end_to_end_semantic_action_and_verified_transition(settings_fixture) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    await device.launch_app("com.android.settings")
    state = tracker.observe(await device.get_ui_state()).state
    search = next(node for node in state.nodes if node.resource_id == "settings/search")
    result = await SemanticExecutor(device, tracker).execute(
        SemanticAction(
            action="click",
            target=search.handle,
            expected_epoch=state.epoch,
            expected_revision=state.revision,
        )
    )
    assert result.status is ActionStatus.SUCCESS and result.verified
    assert tracker.current and tracker.current.screen_id == "settings.search"


@pytest.mark.asyncio
async def test_stale_handle_rejected_before_action(settings_fixture) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    await device.launch_app("com.android.settings")
    tracker = StateTracker()
    state = tracker.observe(await device.get_ui_state()).state
    target = next(node for node in state.nodes if node.resource_id == "settings/search")
    result = await SemanticExecutor(device, tracker).execute(
        SemanticAction(
            action="click", target=target.handle, expected_epoch=state.epoch, expected_revision=999
        )
    )
    assert result.status is ActionStatus.STALE_STATE
    assert len(device.actions) == 1  # launch only


def test_conflicting_label_is_ambiguous(settings_data: dict) -> None:
    state = canonicalize(settings_data["states"]["duplicate"])
    with pytest.raises(AmbiguousTarget):
        resolve_target(state, "label:Continue", "click")


@pytest.mark.asyncio
async def test_cross_window_epoch_invalidates_action(settings_fixture) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    device.current_id = "settings_home"
    tracker = StateTracker()
    old = tracker.observe(await device.get_ui_state()).state
    device.current_id = "unexpected_dialog"
    result = await SemanticExecutor(device, tracker).execute(
        SemanticAction(action="back", expected_epoch=old.epoch, expected_revision=old.revision)
    )
    assert result.status is ActionStatus.STALE_STATE


@pytest.mark.asyncio
async def test_policy_blocks_high_risk_action(settings_fixture) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    result = await SemanticExecutor(device, tracker).execute(
        SemanticAction(action="open_deep_link", uri="javascript:delete account")
    )
    assert result.status is ActionStatus.PERMISSION_DENIED


@pytest.mark.asyncio
async def test_device_disconnection_is_structured(settings_fixture) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    device.connected = False
    result = await SemanticExecutor(device, tracker).execute(SemanticAction(action="back"))
    assert result.status is ActionStatus.DEVICE_DISCONNECTED


@pytest.mark.asyncio
async def test_user_interruption_is_structured(settings_fixture) -> None:
    device = ReplayDeviceAdapter(settings_fixture)
    tracker = StateTracker()
    tracker.observe(await device.get_ui_state())
    result = await SemanticExecutor(device, tracker, interrupted=lambda: True).execute(
        SemanticAction(action="back")
    )
    assert result.status is ActionStatus.USER_INTERRUPTED


@pytest.mark.asyncio
async def test_executor_waits_for_delayed_post_action_state(settings_fixture) -> None:
    class DelayedReplay(ReplayDeviceAdapter):
        async def tap(self, x: int, y: int) -> None:
            async def transition_later() -> None:
                await asyncio.sleep(0.05)
                await super(DelayedReplay, self).tap(x, y)

            asyncio.create_task(transition_later())

    device = DelayedReplay(settings_fixture)
    await device.launch_app("com.android.settings")
    tracker = StateTracker()
    state = tracker.observe(await device.get_ui_state()).state
    target = next(node for node in state.nodes if node.resource_id == "settings/search")
    result = await SemanticExecutor(device, tracker).execute(
        SemanticAction(
            action="click",
            target=target.handle,
            expected_epoch=state.epoch,
            expected_revision=state.revision,
            timeout_seconds=1,
        )
    )
    assert result.status is ActionStatus.SUCCESS
    assert tracker.current and tracker.current.screen_id == "settings.search"
