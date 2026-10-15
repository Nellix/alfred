from __future__ import annotations

import json
import time
from pathlib import Path

from sma.benchmark.metrics import RunMetrics
from sma.benchmark.scenarios import Suite, load_state
from sma.intelligence.token_budget import estimate_tokens
from sma.perception.canonicalizer import canonicalize
from sma.perception.diff_engine import diff_states
from sma.protocol.models import CanonicalState
from sma.protocol.msl_encoder import encode_delta, encode_msl


class BenchmarkRunner:
    """Deterministic offline observation benchmark.

    It measures payload/token estimates and exact target resolvability. It does not pretend
    to measure LLM action-selection accuracy; real model/provider usage is a separate run mode.
    """

    SUPPORTED_MODES = {
        "baseline",
        "raw",
        "canonical",
        "semantic",
        "goal_filtered",
        "delta",
        "skill",
        "adaptive",
    }

    def run(self, suite: Suite, modes: list[str]) -> list[RunMetrics]:
        unknown = set(modes) - self.SUPPORTED_MODES
        if unknown:
            raise ValueError(f"unsupported benchmark modes: {sorted(unknown)}")
        runs: list[RunMetrics] = []
        for scenario in suite.scenarios:
            raw, previous_raw = load_state(scenario)
            state = canonicalize(raw)
            if scenario.expected_resource_id:
                matches = [
                    node
                    for node in state.nodes
                    if node.resource_id == scenario.expected_resource_id
                ]
                target_resolvable = len(matches) == 1
            else:
                matches = [node for node in state.nodes if node.label == scenario.expected_label]
                target_resolvable = scenario.expected_label is None or len(matches) == 1
            for mode in modes:
                started = time.perf_counter()
                payload, calls, snapshots, deltas, notes = self._payload(
                    mode, raw, state, previous_raw, scenario.goal, scenario.warm_skill
                )
                tokens = estimate_tokens(payload) if calls else 0
                success = target_resolvable
                runs.append(
                    RunMetrics(
                        task_id=scenario.id,
                        mode=mode,
                        success=success,
                        execution_time_seconds=time.perf_counter() - started,
                        device_actions=scenario.actions if success else 0,
                        model_calls=calls,
                        input_tokens=tokens,
                        ui_payload_bytes=len(payload.encode()),
                        full_snapshots=snapshots,
                        delta_updates=deltas,
                        cold=not scenario.warm_skill,
                        notes=notes,
                    )
                )
        return runs

    @staticmethod
    def _payload(
        mode: str,
        raw: dict[str, object],
        state: CanonicalState,
        previous_raw: dict[str, object] | None,
        goal: str,
        warm_skill: bool,
    ) -> tuple[str, int, int, int, str]:
        if mode in {"baseline", "raw"}:
            note = (
                "synthetic raw-accessibility stand-in; not a Mobilerun agent measurement"
                if mode == "baseline"
                else "full raw accessibility; token estimate"
            )
            return json.dumps(raw, separators=(",", ":")), 1, 1, 0, note
        if mode == "canonical":
            payload = state.model_dump_json(
                exclude={"nodes": {"__all__": {"bounds", "accessibility_id"}}}
            )
            return payload, 1, 1, 0, "compact canonical JSON; token estimate"
        if mode == "semantic":
            return encode_msl(state, include_all=True), 1, 1, 0, "MSL; token estimate"
        if mode == "goal_filtered":
            return encode_msl(state, goal), 1, 1, 0, "goal-filtered MSL; token estimate"
        if mode == "delta" and previous_raw:
            previous = canonicalize(previous_raw, epoch=1, revision=1)
            current = canonicalize(raw, epoch=1, revision=2)
            try:
                delta = diff_states(previous, current)
                return encode_delta(delta), 1, 0, 1, "MSL delta; token estimate"
            except ValueError:
                return encode_msl(state, goal), 1, 1, 0, "epoch recovery snapshot; token estimate"
        if mode == "skill":
            if warm_skill:
                return "", 0, 0, 0, "approved deterministic skill; no model input"
            return encode_msl(state, goal), 1, 1, 0, "no warm skill; semantic fallback"
        if mode == "adaptive":
            if warm_skill:
                return "", 0, 0, 0, "adaptive router selected approved skill"
            return (
                encode_msl(state, goal),
                1,
                1,
                0,
                "adaptive router selected compact text planning",
            )
        return encode_msl(state, goal), 1, 1, 0, "goal-filtered MSL; token estimate"


def write_jsonl(runs: list[RunMetrics], path: str | Path) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(run.as_dict(), sort_keys=True) + "\n" for run in runs))
