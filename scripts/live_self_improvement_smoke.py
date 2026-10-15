from __future__ import annotations

import argparse
import asyncio
import json
import subprocess

from live_intent_cache_smoke import adb, extract_auth_token

from sma.device.mobilerun_adapter import MobilerunAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_parser import IntentParser
from sma.intelligence.intent_runtime import CachedIntentExecutor
from sma.memory.database import Database
from sma.memory.intent_cache import IntentCache
from sma.memory.self_improvement import SkillLearner
from sma.memory.skill_compiler import SuccessfulTrace, TraceStep
from sma.memory.skill_engine import SkillEngine
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import ActionStatus, SemanticAction

PORTAL_PACKAGE = "com.mobilerun.portal"
SETTINGS_PACKAGE = "com.android.settings"
ACCESSIBILITY_COMPONENT = (
    "com.mobilerun.portal/com.mobilerun.portal.service.MobilerunAccessibilityService"
)


async def _portal_tracker(serial: str, adapter: MobilerunAdapter) -> StateTracker:
    adb(
        serial,
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        f"{PORTAL_PACKAGE}/.ui.MainActivity",
    )
    tracker = StateTracker()
    for _ in range(4):
        await asyncio.sleep(0.25)
        tracker.observe(await adapter.get_ui_state())
    return tracker


async def exercise_learning(serial: str, local_port: int) -> dict[str, object]:
    token = extract_auth_token(
        adb(
            serial,
            "shell",
            "content",
            "query",
            "--uri",
            "content://com.mobilerun.portal/auth_token",
        )
    )
    adapter = MobilerunAdapter(f"http://127.0.0.1:{local_port}", token, timeout=5)
    try:
        if not await adapter.ping():
            raise RuntimeError("Portal ping failed")
        intent = IntentParser().parse("Open Android Settings")
        if intent is None:
            raise RuntimeError("local parser did not recognize the smoke-test intent")

        with Database(":memory:") as database:
            learner = SkillLearner(database.connection)
            candidate = None
            for observation in range(1, 4):
                tracker = await _portal_tracker(serial, adapter)
                source = tracker.current
                if source is None:
                    raise RuntimeError("Portal source state was not observed")
                action = SemanticAction(action="launch_app", package=SETTINGS_PACKAGE)
                outcome = await SemanticExecutor(adapter, tracker).execute(action)
                destination = tracker.current
                if (
                    outcome.status is not ActionStatus.SUCCESS
                    or not outcome.verified
                    or destination is None
                ):
                    raise RuntimeError(f"observation {observation} was not verified: {outcome}")
                candidate = learner.observe(
                    intent,
                    SuccessfulTrace(
                        goal="Open Android Settings",
                        steps=[
                            TraceStep(
                                action=action,
                                source_screen=source.screen_id,
                                destination_screen=destination.screen_id,
                                verified=True,
                            )
                        ],
                    ),
                    session_id=f"live-observation-{observation}",
                )

            if candidate is None:
                raise RuntimeError("no learning candidate was produced")
            candidate_id = candidate.id
            replay_statuses: list[str] = []
            for replay in range(1, 3):
                tracker = await _portal_tracker(serial, adapter)
                validation = await learner.replay_validate(
                    candidate_id,
                    SkillEngine(SemanticExecutor(adapter, tracker), database.connection),
                    {},
                    replay_id=f"live-replay-{replay}",
                )
                replay_statuses.append(validation.run.status)

            tracker = await _portal_tracker(serial, adapter)
            runtime_result = await CachedIntentExecutor(
                IntentCache(database.connection),
                SkillEngine(SemanticExecutor(adapter, tracker), database.connection),
            ).execute("Open Android Settings")
            final_candidate = learner.get(candidate_id)
            learning_rows = database.connection.execute(
                "SELECT COUNT(*) FROM learning_observations"
            ).fetchone()[0]
            replay_rows = database.connection.execute(
                "SELECT COUNT(*) FROM candidate_replays"
            ).fetchone()[0]

        return {
            "device": serial,
            "portal_ping": True,
            "candidate_status": final_candidate.status.value,
            "observations": learning_rows,
            "replay_rows": replay_rows,
            "replay_statuses": replay_statuses,
            "runtime_status": runtime_result.status.value,
            "model_calls": runtime_result.model_calls,
            "screenshots": 0,
            "raw_ui_persisted": False,
            "raw_prompt_or_slot_persisted": False,
        }
    finally:
        await adapter.close()


def _enable_accessibility(serial: str, initial_services: str) -> None:
    current = [] if initial_services in {"", "null"} else initial_services.split(":")
    if ACCESSIBILITY_COMPONENT not in current:
        current.append(ACCESSIBILITY_COMPONENT)
    adb(
        serial,
        "shell",
        "settings",
        "put",
        "secure",
        "enabled_accessibility_services",
        ":".join(current),
    )
    adb(serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1")


def _restore_accessibility(serial: str, initial_services: str, initial_enabled: str) -> None:
    if initial_services in {"", "null"}:
        adb(
            serial,
            "shell",
            "settings",
            "delete",
            "secure",
            "enabled_accessibility_services",
            check=False,
        )
    else:
        adb(
            serial,
            "shell",
            "settings",
            "put",
            "secure",
            "enabled_accessibility_services",
            initial_services,
            check=False,
        )
    adb(
        serial,
        "shell",
        "settings",
        "put",
        "secure",
        "accessibility_enabled",
        initial_enabled or "0",
        check=False,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Privacy-safe live self-improvement smoke test")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--local-port", type=int, default=18080)
    args = parser.parse_args()
    serial = str(args.serial)
    local_port = int(args.local_port)
    remote_port = 8080
    initial_services = adb(
        serial, "shell", "settings", "get", "secure", "enabled_accessibility_services"
    )
    initial_enabled = adb(serial, "shell", "settings", "get", "secure", "accessibility_enabled")

    _enable_accessibility(serial, initial_services)
    adb(
        serial,
        "shell",
        "content",
        "insert",
        "--uri",
        "content://com.mobilerun.portal/toggle_socket_server",
        "--bind",
        "enabled:b:true",
        "--bind",
        f"port:i:{remote_port}",
    )
    subprocess.run(
        ["adb", "-s", serial, "forward", f"tcp:{local_port}", f"tcp:{remote_port}"],
        check=True,
        capture_output=True,
        timeout=10,
    )
    try:
        print(json.dumps(asyncio.run(exercise_learning(serial, local_port)), indent=2))
    finally:
        adb(serial, "shell", "input", "keyevent", "HOME", check=False)
        subprocess.run(
            ["adb", "-s", serial, "forward", "--remove", f"tcp:{local_port}"],
            check=False,
            capture_output=True,
            timeout=10,
        )
        adb(
            serial,
            "shell",
            "content",
            "insert",
            "--uri",
            "content://com.mobilerun.portal/toggle_socket_server",
            "--bind",
            "enabled:b:false",
            check=False,
        )
        adb(serial, "shell", "am", "force-stop", PORTAL_PACKAGE, check=False)
        _restore_accessibility(serial, initial_services, initial_enabled)


if __name__ == "__main__":
    main()
