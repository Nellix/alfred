from __future__ import annotations

import argparse
import asyncio
import json
import subprocess

from live_intent_cache_smoke import adb, extract_auth_token

from sma.device.mobilerun_adapter import MobilerunAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_runtime import CachedIntentExecutor
from sma.intelligence.local_router import LocalIntentRouter
from sma.intelligence.native_catalog import GOOGLE_MAPS, CatalogContext, NativePlanExecutor
from sma.memory.database import Database
from sma.memory.intent_cache import IntentCache
from sma.memory.skill_engine import SkillEngine
from sma.perception.state_tracker import StateTracker


async def exercise_native_route(serial: str, local_port: int) -> dict[str, object]:
    if not adb(serial, "shell", "pm", "path", GOOGLE_MAPS).startswith("package:"):
        raise RuntimeError("Google Maps is not installed")
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
        tracker = StateTracker()
        tracker.observe(await adapter.get_ui_state())
        executor = SemanticExecutor(adapter, tracker)
        with Database(":memory:") as database:
            skill_engine = SkillEngine(executor, database.connection)
            result = await LocalIntentRouter(
                CachedIntentExecutor(IntentCache(database.connection), skill_engine),
                NativePlanExecutor(executor),
            ).route(
                "Give me directions to Colosseum, Rome",
                catalog_context=CatalogContext(installed_packages={GOOGLE_MAPS}),
            )
        strategy = result.native_result.strategy_id if result.native_result else None
        actions = len(result.native_result.action_results) if result.native_result else 0
        return {
            "device": serial,
            "portal_ping": True,
            "route": result.route.value,
            "status": result.status,
            "strategy": strategy,
            "actions_completed": actions,
            "model_calls": result.model_calls,
            "screenshots": 0,
            "raw_ui_persisted": False,
        }
    finally:
        await adapter.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Privacy-safe live native-route smoke test")
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
        print(json.dumps(asyncio.run(exercise_native_route(serial, local_port)), indent=2))
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
