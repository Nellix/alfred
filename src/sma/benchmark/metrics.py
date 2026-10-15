from __future__ import annotations

import math
from dataclasses import dataclass
from statistics import mean
from typing import Any


@dataclass
class RunMetrics:
    task_id: str
    mode: str
    success: bool
    execution_time_seconds: float
    device_actions: int = 0
    model_calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0
    cached_input_tokens: int = 0
    image_calls: int = 0
    inference_cost: float = 0.0
    ui_payload_bytes: int = 0
    full_snapshots: int = 0
    delta_updates: int = 0
    screenshot_count: int = 0
    stale_target_errors: int = 0
    recovery_attempts: int = 0
    human_intervention: bool = False
    policy_violations: int = 0
    device_api_version: str = "fixture"
    model_identifier: str = "offline-estimator"
    token_measurement: str = "estimate"
    cold: bool = True
    notes: str = ""

    def as_dict(self) -> dict[str, Any]:
        return self.__dict__.copy()


def token_reduction(baseline: int, optimized: int) -> float | None:
    return None if baseline <= 0 else 100.0 * (1.0 - optimized / baseline)


def success_rate(runs: list[RunMetrics]) -> float:
    return sum(run.success for run in runs) / len(runs) if runs else 0.0


def cost_per_success(runs: list[RunMetrics]) -> float | None:
    successes = sum(run.success for run in runs)
    return sum(run.inference_cost for run in runs) / successes if successes else None


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = (len(ordered) - 1) * p
    lower, upper = math.floor(index), math.ceil(index)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] * (upper - index) + ordered[upper] * (index - lower)


def wilson_interval(successes: int, total: int, z: float = 1.96) -> tuple[float, float] | None:
    if total == 0:
        return None
    phat = successes / total
    denominator = 1 + z * z / total
    center = (phat + z * z / (2 * total)) / denominator
    margin = z * math.sqrt((phat * (1 - phat) + z * z / (4 * total)) / total) / denominator
    return max(0.0, center - margin), min(1.0, center + margin)


def summarize(runs: list[RunMetrics]) -> dict[str, dict[str, Any]]:
    modes = sorted({run.mode for run in runs})
    result: dict[str, dict[str, Any]] = {}
    for mode in modes:
        group = [run for run in runs if run.mode == mode]
        successes = sum(item.success for item in group)
        result[mode] = {
            "runs": len(group),
            "success_rate": success_rate(group),
            "success_rate_95pct_wilson": wilson_interval(successes, len(group)),
            "mean_input_tokens": mean(item.input_tokens for item in group),
            "total_input_tokens": sum(item.input_tokens for item in group),
            "p50_latency_seconds": percentile([item.execution_time_seconds for item in group], 0.5),
            "p95_latency_seconds": percentile(
                [item.execution_time_seconds for item in group], 0.95
            ),
            "cost_per_success": cost_per_success(group),
        }
    return result
