"""直接读取 Web 协议流量目录，保留每个 PM2 worker 的完整窗口。"""

from __future__ import annotations

import json
import time
from collections import defaultdict
from pathlib import Path
from typing import Any


MEASURES = ("proxy_wire", "noise_frame", "node_plain")
MINUTE_MS = 60_000
END_WATERMARK_WAIT_SECONDS = 125
WATERMARK_POLL_SECONDS = 1


class WebCaptureError(ValueError):
    """Sanitized capture failure suitable for the Runner evidence contract."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def load_capture_directory(
    directory: str,
    now_ms: int,
    window_seconds: int,
    *,
    phase: str,
    expected_workers: int,
    wait_timeout_seconds: float = END_WATERMARK_WAIT_SECONDS,
    poll_interval_seconds: float = WATERMARK_POLL_SECONDS,
) -> dict[str, Any]:
    root = Path(directory)
    if not root.is_absolute() or not root.is_dir():
        raise WebCaptureError("WEB_CAPTURE_DIRECTORY_UNAVAILABLE")
    if phase not in ("start", "peak", "end") or expected_workers <= 0:
        raise WebCaptureError("WEB_CAPTURE_CONFIGURATION_INVALID")
    # Freeze the first boundary at or after the observation instant. A worker
    # watermark of M only proves records strictly before M, so an end taken at
    # M+30s must wait for M+60 rather than accepting M.
    target_before_ms = ((now_ms + MINUTE_MS - 1) // MINUTE_MS) * MINUTE_MS
    live = _live_snapshots(root)
    fixed_lineage = _worker_lineage(live, expected_workers)
    observed_watermarks = _worker_watermarks(live)
    if phase == "end":
        live = _wait_for_end_watermark(
            root,
            fixed_lineage,
            observed_watermarks,
            target_before_ms,
            expected_workers,
            wait_timeout_seconds,
            poll_interval_seconds,
        )
        observed_watermarks = _worker_watermarks(live)
    rows, legacy_ignored, foreign_ignored = _summary_rows(
        root, now_ms, window_seconds, target_before_ms, live
    )
    final_live = _live_snapshots(root)
    _require_same_lineage(final_live, fixed_lineage, expected_workers)
    final_watermarks = _worker_watermarks(final_live)
    if phase == "end":
        if any(final_watermarks[worker] < observed_watermarks[worker] for worker in fixed_lineage):
            raise WebCaptureError("WEB_SUMMARY_WATERMARK_REGRESSED")
        if not _watermarks_cover(final_live, target_before_ms):
            raise WebCaptureError("WEB_SUMMARY_WATERMARK_REGRESSED")
    return _overview(
        rows,
        final_live,
        target_before_ms,
        legacy_ignored,
        foreign_ignored,
    )


def _live_snapshots(root: Path) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for path in sorted(root.glob("live-*.json")):
        worker = path.name[len("live-") : -len(".json")]
        if not worker:
            continue
        payload = _json_object(path)
        result.append({"workerId": worker, "snapshot": payload})
    return result


def _worker_lineage(
    live: list[dict[str, Any]], expected_workers: int
) -> dict[str, str]:
    if len(live) != expected_workers:
        raise WebCaptureError("WEB_WORKER_COUNT_MISMATCH")
    result: dict[str, str] = {}
    for item in live:
        worker = item.get("workerId")
        snapshot = item.get("snapshot")
        if not isinstance(worker, str) or not worker or not isinstance(snapshot, dict):
            raise WebCaptureError("WEB_WORKER_LINEAGE_INVALID")
        run_id = snapshot.get("runId")
        watermark = snapshot.get("summaryCommittedBeforeMs")
        updated_at = snapshot.get("updatedAt")
        if (
            not isinstance(run_id, str)
            or not run_id
            or len(run_id) > 128
            or any(ord(character) < 32 for character in run_id)
            or isinstance(watermark, bool)
            or not isinstance(watermark, int)
            or watermark < 0
            or watermark % MINUTE_MS != 0
            or isinstance(updated_at, bool)
            or not isinstance(updated_at, int)
            or watermark > updated_at // MINUTE_MS * MINUTE_MS
        ):
            raise WebCaptureError("WEB_WORKER_LINEAGE_INVALID")
        result[worker] = run_id
    return result


def _wait_for_end_watermark(
    root: Path,
    fixed_lineage: dict[str, str],
    initial_watermarks: dict[str, int],
    target_before_ms: int,
    expected_workers: int,
    wait_timeout_seconds: float,
    poll_interval_seconds: float,
) -> list[dict[str, Any]]:
    deadline = time.monotonic() + max(0, wait_timeout_seconds)
    previous_watermarks = initial_watermarks
    while True:
        live = _live_snapshots(root)
        _require_same_lineage(live, fixed_lineage, expected_workers)
        watermarks = _worker_watermarks(live)
        if any(watermarks[worker] < previous_watermarks[worker] for worker in fixed_lineage):
            raise WebCaptureError("WEB_SUMMARY_WATERMARK_REGRESSED")
        if _watermarks_cover(live, target_before_ms):
            return live
        if time.monotonic() >= deadline:
            raise WebCaptureError("WEB_SUMMARY_WATERMARK_TIMEOUT")
        previous_watermarks = watermarks
        time.sleep(max(0, poll_interval_seconds))


def _watermarks_cover(live: list[dict[str, Any]], target_before_ms: int) -> bool:
    return all(
        item["snapshot"]["summaryCommittedBeforeMs"] >= target_before_ms
        for item in live
    )


def _worker_watermarks(live: list[dict[str, Any]]) -> dict[str, int]:
    return {
        item["workerId"]: item["snapshot"]["summaryCommittedBeforeMs"]
        for item in live
    }


def _require_same_lineage(
    live: list[dict[str, Any]], fixed_lineage: dict[str, str], expected_workers: int
) -> None:
    try:
        current = _worker_lineage(live, expected_workers)
    except WebCaptureError as error:
        if error.code == "WEB_WORKER_COUNT_MISMATCH":
            raise WebCaptureError("WEB_WORKER_LINEAGE_CHANGED") from None
        raise
    if current != fixed_lineage:
        raise WebCaptureError("WEB_WORKER_LINEAGE_CHANGED")


def _summary_rows(
    root: Path,
    now_ms: int,
    window_seconds: int,
    target_before_ms: int,
    live: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], int, int]:
    # The summaries are whole minute buckets. Align the requested start down to
    # its bucket boundary so a partially overlapping first minute is retained.
    cutoff = ((now_ms - max(60, window_seconds) * 1000) // 60_000) * 60_000
    lineages = {
        item["workerId"]: (
            item["snapshot"]["runId"],
            min(item["snapshot"]["summaryCommittedBeforeMs"], target_before_ms),
        )
        for item in live
    }
    result: list[dict[str, Any]] = []
    legacy_ignored = 0
    foreign_ignored = 0
    for path in sorted(root.glob("summary-*.jsonl")):
        worker = _worker_from_summary_name(path.name)
        if not worker or worker not in lineages:
            continue
        run_id, committed_before_ms = lineages[worker]
        try:
            handle = path.open("r", encoding="utf-8")
        except (OSError, UnicodeError):
            raise WebCaptureError("WEB_SUMMARY_UNAVAILABLE") from None
        with handle:
            for raw_line in handle:
                if len(raw_line) > 1024 * 1024:
                    raise WebCaptureError("WEB_SUMMARY_ROW_INVALID")
                try:
                    row = json.loads(raw_line)
                except json.JSONDecodeError:
                    raise WebCaptureError("WEB_SUMMARY_ROW_INVALID") from None
                if not isinstance(row, dict):
                    raise WebCaptureError("WEB_SUMMARY_ROW_INVALID")
                minute = row.get("minute")
                row_run_id = row.get("runId")
                if row_run_id is None:
                    legacy_ignored += 1
                    continue
                if not isinstance(row_run_id, str) or not row_run_id:
                    raise WebCaptureError("WEB_SUMMARY_ROW_INVALID")
                if row_run_id != run_id:
                    foreign_ignored += 1
                    continue
                if not isinstance(minute, int) or isinstance(minute, bool):
                    raise WebCaptureError("WEB_SUMMARY_ROW_INVALID")
                if minute < cutoff or minute >= committed_before_ms:
                    continue
                try:
                    normalized = _normalize_row(row)
                except ValueError:
                    raise WebCaptureError("WEB_SUMMARY_ROW_INVALID") from None
                normalized["workerId"] = worker
                result.append(normalized)
    return result, legacy_ignored, foreign_ignored


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


def _overview(
    rows: list[dict[str, Any]],
    live: list[dict[str, Any]],
    target_before_ms: int,
    legacy_ignored: int,
    foreign_ignored: int,
) -> dict[str, Any]:
    totals = _measure_totals(rows)
    by_worker: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_worker[row["workerId"]].append(row)
    live_workers = {
        item.get("workerId")
        for item in live
        if isinstance(item.get("workerId"), str) and item.get("workerId")
    }
    workers = sorted(set(by_worker) | live_workers)
    worker_totals = {worker: _measure_totals(by_worker[worker]) for worker in workers}
    worker_coverage = {worker: _coverage(by_worker[worker]) for worker in workers}
    worker_minutes = {
        worker: sorted({row["minute"] for row in by_worker[worker]})
        for worker in workers
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
        "_summaryLineageMode": "current-run-only",
        "_summaryTargetBeforeMs": target_before_ms,
        "_legacySummaryRowsIgnored": legacy_ignored,
        "_foreignSummaryRowsIgnored": foreign_ignored,
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
