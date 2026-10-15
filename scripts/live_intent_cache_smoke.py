from __future__ import annotations

import argparse
import asyncio
import json
import subprocess
from typing import Any

from sma.device.mobilerun_adapter import MobilerunAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_runtime import CachedIntentExecutor
from sma.memory.database import Database
from sma.memory.intent_cache import (
    BindingStatus,
    CompatibilityContext,
    IntentCache,
)
from sma.memory.skill_engine import Skill, SkillEngine, SkillStep
from sma.perception.state_tracker import StateTracker


def adb(serial: str, *arguments: str, check: bool = True) -> str:
    completed = subprocess.run(
        ["adb", "-s", serial, *arguments],
        check=check,
        capture_output=True,
        text=True,
        timeout=15,
    )
    return completed.stdout.strip()


def extract_auth_token(provider_output: str) -> str:
    candidate: Any = provider_output
    if "result=" in provider_output:
        candidate = provider_output.split("result=", 1)[1].strip()
    for _ in range(4):
        if isinstance(candidate, dict):
            candidate = candidate.get("result", candidate.get("data", candidate.get("token")))
        if not isinstance(candidate, str):
            break
        try:
            decoded = json.loads(candidate)
        except json.JSONDecodeError:
            if len(candidate) >= 16 and not any(char.isspace() for char in candidate):
                return candidate
            break
        candidate = decoded
    raise RuntimeError("Portal returned an unreadable auth-token envelope")


async def exercise_cache(serial: str, local_port: int) -> dict[str, object]:
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
    android_api = int(adb(serial, "shell", "getprop", "ro.build.version.sdk"))
    adapter = MobilerunAdapter(f"http://127.0.0.1:{local_port}", token, timeout=5)
    try:
        if not await adapter.ping():
            raise RuntimeError("Portal ping failed")
        tracker = StateTracker()
        tracker.observe(await adapter.get_ui_state())
        skill = Skill(
            id="open_android_settings_live",
            version=1,
            approved=True,
            steps=[SkillStep(action="launch_app", package="com.android.settings")],
        )
        with Database(":memory:") as database:
            cache = IntentCache(database.connection)
            cache.save_skill(skill)
            cache.bind(
                "android.settings.open",
                skill.id,
                skill.version,
                status=BindingStatus.ACTIVE,
                confidence=0.99,
            )
            result = await CachedIntentExecutor(
                cache,
                SkillEngine(SemanticExecutor(adapter, tracker), database.connection),
            ).execute(
                "Open Android Settings",
                context=CompatibilityContext(android_api=android_api),
            )
        if result.status.value == "success":
            await adapter.press_back()
        return {
            "device": serial,
            "android_api": android_api,
            "portal_ping": True,
            "cache_status": result.status.value,
            "canonical_intent": result.canonical_intent,
            "model_calls": result.model_calls,
            "steps_completed": result.skill_run.steps_completed if result.skill_run else 0,
            "screenshots": 0,
            "raw_ui_persisted": False,
        }
    finally:
        await adapter.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Privacy-safe live cache-first smoke test")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--local-port", type=int, default=18080)
    args = parser.parse_args()
    serial = str(args.serial)
    local_port = int(args.local_port)
    remote_port = 8080

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
        print(json.dumps(asyncio.run(exercise_cache(serial, local_port)), indent=2))
    finally:
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


if __name__ == "__main__":
    main()
