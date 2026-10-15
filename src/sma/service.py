from __future__ import annotations

from fastapi import FastAPI, HTTPException

from sma.device.replay_adapter import ReplayDeviceAdapter
from sma.execution.action_executor import SemanticExecutor
from sma.intelligence.agent_api import router as agent_router
from sma.intelligence.model_client import model_provider_chain_from_environment
from sma.perception.state_tracker import StateTracker
from sma.protocol.models import ActionResult, SemanticAction
from sma.protocol.msl_encoder import encode_msl

app = FastAPI(title="Alfred optional LLM planner", version="0.1.0")
app.include_router(agent_router)
_device: ReplayDeviceAdapter | None = None
_tracker: StateTracker | None = None


def configure_replay(fixture: str) -> None:
    global _device, _tracker
    _device = ReplayDeviceAdapter(fixture)
    _tracker = StateTracker()


@app.get("/health")
async def health() -> dict[str, object]:
    try:
        models = model_provider_chain_from_environment()
        model_status: dict[str, object] = (
            {
                "configured": True,
                "providers": [
                    {"provider": model.provider.value, "model": model.model} for model in models
                ],
            }
            if models
            else {"configured": False}
        )
    except ValueError as exc:
        model_status = {"configured": False, "error": str(exc)}
    return {
        "status": "ok",
        "device_configured": _device is not None,
        "model": model_status,
    }


@app.get("/inspect")
async def inspect() -> dict[str, object]:
    if _device is None or _tracker is None:
        raise HTTPException(503, "device adapter is not configured")
    state = _tracker.observe(await _device.get_ui_state()).state
    return {"state": state.model_dump(mode="json"), "msl": encode_msl(state)}


@app.post("/actions", response_model=ActionResult)
async def execute(action: SemanticAction) -> ActionResult:
    if _device is None or _tracker is None:
        raise HTTPException(503, "device adapter is not configured")
    return await SemanticExecutor(_device, _tracker).execute(action)
