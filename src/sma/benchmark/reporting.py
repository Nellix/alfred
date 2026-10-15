from __future__ import annotations

import csv
import html
import json
from pathlib import Path

from sma.benchmark.metrics import RunMetrics, summarize, token_reduction


def load_runs(path: str | Path) -> list[RunMetrics]:
    path = Path(path)
    files = sorted(path.glob("*.jsonl")) if path.is_dir() else [path]
    runs: list[RunMetrics] = []
    for file in files:
        runs.extend(
            RunMetrics(**json.loads(line)) for line in file.read_text().splitlines() if line.strip()
        )
    return runs


def write_csv(runs: list[RunMetrics], path: str | Path) -> None:
    if not runs:
        return
    with Path(path).open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(runs[0].as_dict()))
        writer.writeheader()
        writer.writerows(run.as_dict() for run in runs)


def write_html(runs: list[RunMetrics], path: str | Path) -> None:
    summary = summarize(runs)
    baseline = summary.get("baseline", summary.get("raw", {})).get("total_input_tokens", 0)
    rows = []
    for mode, values in summary.items():
        reduction = token_reduction(baseline, values["total_input_tokens"])
        interval = values["success_rate_95pct_wilson"]
        rows.append(
            "<tr>"
            f"<td>{html.escape(mode)}</td><td>{values['runs']}</td>"
            f"<td>{values['success_rate']:.1%}</td>"
            f"<td>{interval[0]:.1%}–{interval[1]:.1%}</td>"
            f"<td>{values['total_input_tokens']}</td>"
            f"<td>{'n/a' if reduction is None else f'{reduction:.1f}%'}</td>"
            f"<td>{values['p50_latency_seconds'] * 1000:.3f}</td>"
            "</tr>"
        )
    document = f"""<!doctype html><html><head><meta charset="utf-8">
<title>SMA offline benchmark</title><style>
body{{font:16px system-ui;max-width:1050px;margin:40px auto;padding:0 20px;color:#18202a}}
table{{border-collapse:collapse;width:100%}}
th,td{{padding:9px;border:1px solid #ccd4dd;text-align:right}}
th:first-child,td:first-child{{text-align:left}}.note{{background:#fff4cf;padding:14px;border-radius:8px}}
</style></head><body><h1>Semantic Mobile Agent offline benchmark</h1>
<p class="note"><strong>Scope:</strong> deterministic fixtures. Token counts are tokenizer estimates
(byte heuristic when tiktoken is unavailable), not provider-reported usage.
Success means the expected
semantic target was uniquely resolvable; it is not a real-device or LLM accuracy claim.</p>
<table><thead><tr><th>Mode</th><th>Runs</th><th>Success</th><th>95% Wilson CI</th>
<th>Input tokens</th><th>Reduction</th><th>p50 ms</th></tr></thead><tbody>
{"".join(rows)}</tbody></table></body></html>"""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(document)
