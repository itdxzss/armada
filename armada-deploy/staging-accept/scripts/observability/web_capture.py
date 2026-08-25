"""直接读取 Web 协议流量目录，保留每个 PM2 worker 的完整窗口。"""

from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path
from typing import Any


MEASURES = ("proxy_wire", "noise_frame", "node_plain")


def load_capture_directory(directory: str, now_ms: int, window_seconds: int) -> dict[str, Any]:
    root = Path(directory)
    if not root.is_absolute() or not root.is_dir():
        raise ValueError("capture directory unavailable")
    live = _live_snapshots(root)
    rows = _summary_rows(root, now_ms, window_seconds)
    return _overview(rows, live)


def _live_snapshots(root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for path in sorted(root.glob("live-*.json")):
        worker = path.name[len("live-") : -len(".json")]
        if not worker:
            continue
        payload = _json_object(path)
        result.append({"workerId": worker, "snapshot": payload})
    return result


def _summary_rows(root: Path, now_ms: int, window_seconds: int) -> list[dict[str, Any]]:
    cutoff = now_ms - max(60, window_seconds) * 1000
    result: list[dict[str, Any]] = []
    for path in sorted(root.glob("summary-*.jsonl")):
        worker = _worker_from_summary_name(path.name)
        if not worker:
            continue
        try:
            handle = path.open("r", encoding="utf-8")
        except (OSError, UnicodeError):
            raise ValueError("summary unavailable") from None
        with handle:
            for raw_line in handle:
                if len(raw_line) > 1024 * 1024:
                    raise ValueError("summary row too large")
                try:
                    row = json.loads(raw_line)
                except json.JSONDecodeError:
                    continue
                if not isinstance(row, dict):
                    continue
                minute = row.get("minute")
                if not isinstance(minute, int) or isinstance(minute, bool) or minute < cutoff or minute > now_ms:
                    continue
                normalized = _normalize_row(row)
                normalized["workerId"] = worker
                result.append(normalized)
    return result


def _worker_from_summary_name(name: str) -> str:
    prefix = "summary-"
    suffix = ".jsonl"
    if not name.startswith(prefix) or not name.endswith(suffix):
        return ""
    body = name[len(prefix) : -len(suffix)]
    worker, separator, stamp = body.rpartition("-")
    if not separator or len(stamp) != len("20270115T07Z") or stamp[8] != "T" or not stamp.endswith("Z"):
        return ""
    return worker


def _normalize_row(row: dict[str, Any]) -> dict[str, Any]:
    required_strings = ("scope", "category", "frameKind", "direction", "measure", "channel")
    if any(not isinstance(row.get(field), str) for field in required_strings):
        raise ValueError("summary row invalid")
    if row["direction"] not in ("up", "down") or row["measure"] not in MEASURES:
        raise ValueError("summary row invalid")
    for field in ("minute", "bytes", "count"):
        if not isinstance(row.get(field), int) or isinstance(row.get(field), bool) or row[field] < 0:
            raise ValueError("summary row invalid")
    return {
        field: row[field]
        for field in (
            "minute",
            "scope",
            "category",
            "frameKind",
            "direction",
            "measure",
            "channel",
            "bytes",
            "count",
        )
    }


def _overview(rows: list[dict[str, Any]], live: list[dict[str, Any]]) -> dict[str, Any]:
    totals = _measure_totals(rows)
    by_worker: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_worker[row["workerId"]].append(row)
    worker_totals = {worker: _measure_totals(worker_rows) for worker, worker_rows in sorted(by_worker.items())}
    worker_coverage = {worker: _coverage(worker_rows) for worker, worker_rows in sorted(by_worker.items())}
    worker_minutes = {
        worker: sorted({row["minute"] for row in worker_rows})
        for worker, worker_rows in sorted(by_worker.items())
    }
    return {
        "reconciliation": totals,
        "timeline": _timeline(rows),
        "byCategory": _rank(rows, "category"),
        "byScope": _rank(rows, "scope"),
        "health": live,
        "_captureMode": "capture-directory",
        "_workerReconciliation": worker_totals,
        "_workerCoverage": worker_coverage,
        "_workerMinuteEvidence": worker_minutes,
    }


def _measure_totals(rows: list[dict[str, Any]]) -> dict[str, Any]:
    totals = {measure: 0 for measure in MEASURES}
    for row in rows:
        totals[row["measure"]] += row["bytes"]
    proxy = totals["proxy_wire"]
    noise = totals["noise_frame"]
    plain = totals["node_plain"]
    return {
        "proxyWire": proxy,
        "noiseFrame": noise,
        "nodePlain": plain,
        "transportOverhead": max(0, proxy - noise),
        "protocolOverhead": max(0, noise - plain),
        "attributedShare": 0 if proxy == 0 else noise / proxy,
    }


def _coverage(rows: list[dict[str, Any]]) -> int:
    minutes = [row["minute"] for row in rows]
    return 0 if not minutes else (max(minutes) - min(minutes)) // 1000 + 60


def _timeline(rows: list[dict[str, Any]]) -> list[dict[str, int]]:
    points: dict[int, dict[str, int]] = {}
    for row in rows:
        if row["measure"] != "noise_frame":
            continue
        point = points.setdefault(row["minute"], {"minute": row["minute"], "up": 0, "down": 0, "total": 0})
        point[row["direction"]] += row["bytes"]
        point["total"] += row["bytes"]
    return [points[minute] for minute in sorted(points)]


def _rank(rows: list[dict[str, Any]], dimension: str) -> list[dict[str, Any]]:
    buckets: dict[str, dict[str, Any]] = {}
    grand_total = 0
    for row in rows:
        if row["measure"] != "noise_frame":
            continue
        key = row[dimension]
        bucket = buckets.setdefault(key, {"key": key, "up": 0, "down": 0, "total": 0, "count": 0})
        bucket[row["direction"]] += row["bytes"]
        bucket["total"] += row["bytes"]
        bucket["count"] += row["count"]
        grand_total += row["bytes"]
    result = []
    for bucket in buckets.values():
        result.append({**bucket, "share": 0 if grand_total == 0 else bucket["total"] / grand_total})
    return sorted(result, key=lambda row: (-row["total"], row["key"]))[:20]


def _json_object(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise ValueError("live snapshot unavailable") from None
    if not isinstance(payload, dict):
        raise ValueError("live snapshot invalid")
    return payload
