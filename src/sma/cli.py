from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated

import typer
import uvicorn

from sma.benchmark.reporting import load_runs, write_csv, write_html
from sma.benchmark.runner import BenchmarkRunner, write_jsonl
from sma.benchmark.scenarios import Suite
from sma.device.interface import AuthenticationError, DeviceError
from sma.device.mobilerun_adapter import MobilerunAdapter
from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.intent_runtime import CachedIntentExecutor
from sma.intelligence.local_router import LocalIntentRouter, LocalRoute
from sma.intelligence.model_client import model_provider_chain_from_environment
from sma.intelligence.native_catalog import CatalogContext, NativePlanExecutor
from sma.memory.database import Database
from sma.memory.intent_cache import BindingStatus, IntentCache, IntentSafety
from sma.memory.skill_engine import Skill, SkillEngine
from sma.perception.state_tracker import StateTracker
from sma.protocol.msl_encoder import encode_delta, encode_msl
from sma.tracing import JsonlTrace

app = typer.Typer(no_args_is_help=True, help="Alfred semantic mobile-agent tools")
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FIXTURE = ROOT / "fixtures/settings/replay.json"


def _run(coro: object) -> object:
    return asyncio.run(coro)  # type: ignore[arg-type]


def _adb_diagnostics() -> dict[str, object]:
    adb = shutil.which("adb")
    if not adb:
        return {"status": "missing", "required_for_live_device": True}
    serial = os.getenv("DEVICE_SERIAL")
    prefix = [adb, "-s", serial] if serial else [adb]
    try:
        output = subprocess.run(
            [adb, "devices"], capture_output=True, text=True, timeout=3, check=True
        ).stdout
        connected = [
            line.split("\t", 1)[0]
            for line in output.splitlines()[1:]
            if line.endswith("\tdevice")
        ]
        if serial and serial not in connected:
            return {"status": "device_missing", "requested_serial": serial}
        if not connected:
            return {"status": "device_missing"}
        installed = subprocess.run(
            [*prefix, "shell", "pm", "path", "com.mobilerun.portal"],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        ).stdout.startswith("package:")
        services = subprocess.run(
            [*prefix, "shell", "settings", "get", "secure", "enabled_accessibility_services"],
            capture_output=True,
            text=True,
            timeout=3,
            check=False,
        ).stdout.casefold()
    except (subprocess.SubprocessError, OSError) as exc:
        return {"status": "error", "detail": str(exc)}
    return {
        "status": "ok",
        "device": serial or connected[0],
        "portal_installation": "installed" if installed else "missing",
        "accessibility_service": "enabled" if "mobilerun.portal" in services else "disabled",
    }


@app.command()
def doctor(
    portal_url: Annotated[str, typer.Option()] = os.getenv(
        "PORTAL_BASE_URL", "http://127.0.0.1:8080"
    ),
    token: Annotated[str | None, typer.Option()] = os.getenv("PORTAL_TOKEN"),
) -> None:
    """Check host, Portal, accessibility, authentication, and model prerequisites."""
    try:
        providers = model_provider_chain_from_environment()
        model: dict[str, object] = {
            "status": "configured" if providers else "optional_unavailable",
            "providers": [
                {"provider": item.provider.value, "model": item.model} for item in providers
            ],
        }
    except ValueError as exc:
        model = {"status": "invalid", "detail": str(exc)}
    result: dict[str, object] = {
        "python": {"status": "ok"},
        "adb": _adb_diagnostics(),
        "portal": {"status": "unknown"},
        "authentication": {"status": "not_tested"},
        "model": model,
    }

    async def check() -> None:
        adapter = MobilerunAdapter(portal_url, token, timeout=2)
        try:
            await adapter.ping()
            result["portal"] = {"status": "available", "url": portal_url}
            try:
                await adapter.get_ui_state()
                result["authentication"] = {"status": "valid"}
            except AuthenticationError:
                result["authentication"] = {"status": "invalid_or_missing"}
            except DeviceError as exc:
                result["authentication"] = {"status": "unavailable", "detail": str(exc)}
        except DeviceError:
            result["portal"] = {"status": "unreachable"}
        finally:
            await adapter.close()

    _run(check())
    typer.echo(json.dumps(result, indent=2))


@app.command("inspect")
def inspect_command(
    mode: Annotated[str, typer.Option(help="semantic, delta, or canonical")] = "semantic",
    fixture: Annotated[Path, typer.Option(exists=True)] = DEFAULT_FIXTURE,
    goal: Annotated[str | None, typer.Option()] = None,
) -> None:
    """Inspect a replay fixture without contacting a model."""

    async def inspect_replay() -> str:
        device = ReplayDeviceAdapter(fixture)
        tracker = StateTracker()
        first = tracker.observe(await device.get_ui_state())
        if mode == "canonical":
            return first.state.model_dump_json(indent=2)
        if mode == "semantic":
            return encode_msl(first.state, goal)
        if mode == "delta":
            await device.launch_app("com.android.settings")
            second = tracker.observe(await device.get_ui_state())
            return encode_delta(second.delta) if second.delta else encode_msl(second.state, goal)
        raise typer.BadParameter("mode must be semantic, delta, or canonical")

    typer.echo(_run(inspect_replay()))


@app.command("run")
def run_goal(
    goal: Annotated[str, typer.Option()],
    fixture: Annotated[Path, typer.Option(exists=True)] = DEFAULT_FIXTURE,
    skill: Annotated[Path | None, typer.Option(exists=True)] = None,
    parameter: Annotated[
        list[str] | None, typer.Option(help="Skill parameter as key=value")
    ] = None,
    database: Annotated[Path, typer.Option()] = Path("sma.db"),
    confirmed: Annotated[bool, typer.Option()] = False,
    installed_package: Annotated[list[str] | None, typer.Option()] = None,
) -> None:
    """Run a built-in, cached, or explicitly supplied skill without a model."""

    async def execute_goal() -> dict[str, object]:
        device = ReplayDeviceAdapter(fixture)
        tracker = StateTracker()
        tracker.observe(await device.get_ui_state())
        executor = SemanticExecutor(device, tracker)
        trace = JsonlTrace(
            Path("runs") / f"trace-{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}.jsonl"
        )
        with Database(database) as db:
            engine = SkillEngine(executor, db.connection)
            if skill:
                values = dict(item.split("=", 1) for item in (parameter or []))
                result = await engine.run(Skill.load(skill), values)
                trace.record("skill_result", result=result.model_dump())
                return {"goal": goal, "result": result.model_dump(), "actions": device.actions}
            routed = await LocalIntentRouter(
                CachedIntentExecutor(IntentCache(db.connection), engine),
                NativePlanExecutor(executor),
            ).route(
                goal,
                catalog_context=CatalogContext(installed_packages=set(installed_package or [])),
                confirmed=confirmed,
            )
        if routed.route is LocalRoute.FALLBACK:
            raise typer.BadParameter(
                "no local action matched; install a skill or configure the optional LLM planner"
            )
        trace.record("local_route_result", result=routed.model_dump(mode="json"))
        return {
            "goal": goal,
            "route": routed.route.value,
            "result": routed.model_dump(mode="json"),
            "actions": device.actions,
        }

    typer.echo(json.dumps(_run(execute_goal()), indent=2))


@app.command("cache-add")
def cache_add(
    intent: Annotated[str, typer.Option()],
    skill: Annotated[Path, typer.Option(exists=True)],
    database: Annotated[Path, typer.Option()] = Path("sma.db"),
    confidence: Annotated[float, typer.Option()] = 0.95,
    safety: Annotated[str, typer.Option(help="low, confirm, or blocked")] = "low",
) -> None:
    """Install an approved skill in the private local cache."""
    try:
        safety_level = IntentSafety(safety)
    except ValueError as exc:
        raise typer.BadParameter("safety must be low, confirm, or blocked") from exc
    document = Skill.load(skill)
    with Database(database) as db:
        cache = IntentCache(db.connection)
        cache.save_skill(document)
        binding_id = cache.bind(
            intent,
            document.id,
            document.version,
            safety=safety_level,
            status=BindingStatus.ACTIVE,
            confidence=confidence,
        )
    typer.echo(json.dumps({"binding_id": binding_id, "storage": "private_local"}, indent=2))


@app.command("cache-list")
def cache_list(database: Annotated[Path, typer.Option()] = Path("sma.db")) -> None:
    """List local bindings without prompts or parameter values."""
    with Database(database) as db:
        rows = db.connection.execute(
            """SELECT id, canonical_intent, skill_id, skill_version, safety, status,
            confidence, successes, failures FROM intent_bindings ORDER BY canonical_intent"""
        ).fetchall()
    typer.echo(json.dumps([dict(row) for row in rows], indent=2))


@app.command("learning-list")
def learning_list(database: Annotated[Path, typer.Option()] = Path("sma.db")) -> None:
    """List privacy-safe local learning status."""
    with Database(database) as db:
        rows = db.connection.execute(
            """SELECT id, canonical_intent, skill_id, skill_version, safety, status,
            observations, replay_successes, replay_failures
            FROM learning_candidates ORDER BY updated_at DESC, id DESC"""
        ).fetchall()
    typer.echo(json.dumps([dict(row) for row in rows], indent=2))


@app.command("cache-decay")
def cache_decay(
    database: Annotated[Path, typer.Option()] = Path("sma.db"),
    older_than_days: Annotated[float, typer.Option()] = 30.0,
    factor: Annotated[float, typer.Option()] = 0.98,
) -> None:
    """Lower confidence for stale local bindings."""
    with Database(database) as db:
        try:
            changed = IntentCache(db.connection).decay_stale(
                older_than=older_than_days * 24 * 60 * 60,
                factor=factor,
            )
        except ValueError as exc:
            raise typer.BadParameter(str(exc)) from exc
    typer.echo(json.dumps({"bindings_decayed": changed}, indent=2))


@app.command("cache-rollback")
def cache_rollback(
    binding_id: Annotated[int, typer.Option()],
    database: Annotated[Path, typer.Option()] = Path("sma.db"),
) -> None:
    """Disable a bad local binding."""
    with Database(database) as db:
        try:
            fallback_id = IntentCache(db.connection).rollback(binding_id)
        except ValueError as exc:
            raise typer.BadParameter(str(exc)) from exc
    typer.echo(
        json.dumps(
            {"disabled_binding_id": binding_id, "fallback_binding_id": fallback_id}, indent=2
        )
    )


@app.command("serve")
def serve(
    host: Annotated[str, typer.Option()] = "127.0.0.1",
    port: Annotated[int, typer.Option(min=1, max=65535)] = 8765,
) -> None:
    """Serve the optional LLM planner; use adb reverse for a USB phone."""
    uvicorn.run("sma.service:app", host=host, port=port, reload=False)


@app.command()
def benchmark(
    suite: Annotated[Path, typer.Option(exists=True)] = ROOT / "fixtures/scenarios.yaml",
    modes: Annotated[str, typer.Option()] = "baseline,semantic,delta,skill",
    output: Annotated[Path, typer.Option()] = Path("runs/offline.jsonl"),
) -> None:
    """Run deterministic offline benchmark fixtures."""
    selected = [mode.strip() for mode in modes.split(",") if mode.strip()]
    runs = BenchmarkRunner().run(Suite.load(suite), selected)
    write_jsonl(runs, output)
    typer.echo(f"wrote {len(runs)} deterministic fixture runs to {output}")


@app.command()
def report(
    input: Annotated[Path, typer.Option(exists=True)] = Path("runs"),
    output: Annotated[Path, typer.Option()] = Path("artifacts/report.html"),
) -> None:
    """Render benchmark JSONL runs as HTML and CSV."""
    runs = load_runs(input)
    if not runs:
        raise typer.BadParameter("no JSONL runs found")
    write_html(runs, output)
    write_csv(runs, output.with_suffix(".csv"))
    typer.echo(f"wrote {output} and {output.with_suffix('.csv')}")


if __name__ == "__main__":
    app()
