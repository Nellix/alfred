from __future__ import annotations

import asyncio
from collections.abc import Callable

from sma.device.interface import DeviceAdapter, DeviceDisconnected, DeviceError
from sma.execution.action_resolver import AmbiguousTarget, TargetNotFound, resolve_target
from sma.execution.policy import ActionPolicy, PolicyViolation
from sma.execution.verifier import verify_transition
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import (
    ActionResult,
    ActionStatus,
    CanonicalNode,
    CanonicalState,
    SemanticAction,
)
from sma.protocol.validators import ProtocolError, validate_action_revision


class SemanticExecutor:
    def __init__(
        self,
        device: DeviceAdapter,
        tracker: StateTracker,
        *,
        policy: ActionPolicy | None = None,
        interrupted: Callable[[], bool] | None = None,
    ) -> None:
        self.device = device
        self.tracker = tracker
        self.policy = policy or ActionPolicy()
        self.interrupted = interrupted or (lambda: False)

    async def execute(self, action: SemanticAction) -> ActionResult:
        if self.interrupted():
            return self._result(ActionStatus.USER_INTERRUPTED, action, "user interrupted task")
        before = self.tracker.current
        if before is None:
            before = self.tracker.observe(await self.device.get_ui_state()).state
        try:
            validate_action_revision(action, before)
        except ProtocolError as exc:
            return self._result(ActionStatus.STALE_STATE, action, str(exc), before.revision)

        node = None
        if action.target:
            try:
                node = resolve_target(before, action.target, action.action).node
            except TargetNotFound:
                return self._result(
                    ActionStatus.TARGET_NOT_FOUND, action, action.target, before.revision
                )
            except AmbiguousTarget:
                return self._result(
                    ActionStatus.AMBIGUOUS_TARGET, action, action.target, before.revision
                )
            except ValueError as exc:
                return self._result(
                    ActionStatus.ACTION_NOT_SUPPORTED, action, str(exc), before.revision
                )
        try:
            self.policy.validate(action, node)
        except PolicyViolation as exc:
            return self._result(ActionStatus.PERMISSION_DENIED, action, str(exc), before.revision)

        # Re-observe immediately before coordinate execution. This narrows, but cannot make
        # atomic, the Portal validation-to-tap race.
        try:
            fresh = self.tracker.observe(await self.device.get_ui_state()).state
            if fresh.epoch != before.epoch:
                return self._result(
                    ActionStatus.STALE_STATE, action, "screen epoch changed", before.revision
                )
            if node:
                candidates = [item for item in fresh.nodes if item.handle == node.handle]
                if (
                    len(candidates) != 1
                    or candidates[0].semantic_signature != node.semantic_signature
                ):
                    return self._result(
                        ActionStatus.STALE_STATE,
                        action,
                        "target identity changed during validation",
                        before.revision,
                    )
                node = candidates[0]
            await asyncio.wait_for(self._perform(action, node), timeout=action.timeout_seconds)
            observed, verified, message = await self._observe_until_verified(
                action, before, action.timeout_seconds
            )
        except TimeoutError:
            return self._result(ActionStatus.TIMEOUT, action, "action timed out", before.revision)
        except asyncio.CancelledError:
            return self._result(
                ActionStatus.USER_INTERRUPTED, action, "user interrupted", before.revision
            )
        except DeviceDisconnected as exc:
            return self._result(ActionStatus.DEVICE_DISCONNECTED, action, str(exc), before.revision)
        except DeviceError as exc:
            return self._result(ActionStatus.VERIFICATION_FAILED, action, str(exc), before.revision)

        status = ActionStatus.SUCCESS if verified else ActionStatus.VERIFICATION_FAILED
        if action.action == "wait" and not verified:
            status = ActionStatus.TIMEOUT
        return ActionResult(
            status=status,
            action=action.action,
            previous_revision=before.revision,
            current_revision=observed.revision,
            verified=verified,
            message=message,
        )

    async def _observe_until_verified(
        self, action: SemanticAction, before: CanonicalState, timeout_seconds: float
    ) -> tuple[CanonicalState, bool, str]:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_seconds
        while True:
            remaining = deadline - loop.time()
            if remaining <= 0:
                observed = self.tracker.current or before
                verified, message = verify_transition(action, before, observed)
                return observed, verified, message
            raw = await asyncio.wait_for(self.device.get_ui_state(), timeout=remaining)
            observed = self.tracker.observe(raw).state
            verified, message = verify_transition(action, before, observed)
            if verified:
                return observed, verified, message
            if self.interrupted():
                raise asyncio.CancelledError
            await asyncio.sleep(min(0.1, max(0.0, deadline - loop.time())))

    async def _perform(self, action: SemanticAction, node: CanonicalNode | None) -> None:
        if action.action == "click":
            if node is None or node.bounds is None:
                raise DeviceError("click target has no usable bounds")
            bounds = node.bounds
            x, y = bounds.center
            await self.device.tap(x, y)
        elif action.action == "set_text":
            await self.device.input_text(action.value or "")
        elif action.action == "scroll":
            if node is None or node.bounds is None:
                raise DeviceError("scroll target has no usable bounds")
            bounds = node.bounds
            await self.device.swipe(
                ((bounds.left + bounds.right) // 2, bounds.bottom - 10),
                ((bounds.left + bounds.right) // 2, bounds.top + 10),
            )
        elif action.action == "back":
            await self.device.press_back()
        elif action.action == "launch_app":
            if not action.package:
                raise DeviceError("launch_app requires package")
            await self.device.launch_app(action.package)
        elif action.action == "open_deep_link":
            if not action.uri:
                raise DeviceError("open_deep_link requires uri")
            await self.device.open_deep_link(action.uri, action.package)
        elif action.action == "wait":
            await asyncio.sleep(0)
        elif action.action == "stop":
            return

    @staticmethod
    def _result(
        status: ActionStatus,
        action: SemanticAction,
        message: str,
        revision: int | None = None,
    ) -> ActionResult:
        return ActionResult(
            status=status,
            action=action.action,
            previous_revision=revision,
            current_revision=revision,
            verified=False,
            message=message,
        )
