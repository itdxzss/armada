#!/usr/bin/env python3
"""Evaluate start/peak/end observability evidence into PASS, FAIL, or BLOCKED."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
import sys
from pathlib import Path
from typing import Any


PHASES = ("start", "peak", "end")
COLLECTORS = ("kafka", "redis", "host-resource", "web-traffic", "android-traffic")
MAX_INPUT_BYTES = 16 * 1024 * 1024
MAX_FUTURE_SKEW_SECONDS = 30
SAFE_LABEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,248}$")
EVIDENCE_SHA256 = re.compile(r"^sha256:[0-9a-fA-F]{64}$")


class EvidenceError(Exception):
    pass


class Evaluation:
    def __init__(
        self,
        environment: str,
        run_id: str,
        candidate_manifest_sha256: str,
        profile_seconds: int,
        max_evidence_age_seconds: int,
    ):
        self.environment = environment
        self.run_id = run_id
        self.candidate_manifest_sha256 = candidate_manifest_sha256.lower()
        self.profile_seconds = profile_seconds
        self.max_evidence_age_seconds = max_evidence_age_seconds
        self.now = dt.datetime.now(dt.timezone.utc)
        self.failures: list[str] = []
        self.blockers: list[str] = []
        self.metrics: dict[str, Any] = {}

    def fail(self, reason: str) -> None:
        if reason not in self.failures:
            self.failures.append(reason)

    def block(self, reason: str) -> None:
        if reason not in self.blockers:
            self.blockers.append(reason)

    def result(self) -> dict[str, Any]:
        status = "FAIL" if self.failures else "BLOCKED" if self.blockers else "PASS"
        return {
            "schemaVersion": 1,
            "evaluator": "observability",
            "environment": self.environment,
            "status": status,
            "failureReasons": self.failures,
            "blockedReasons": self.blockers,
            "metrics": self.metrics,
        }


def parse_timestamp(value: Any) -> dt.datetime:
    if not isinstance(value, str):
        raise EvidenceError("timestamp must be a string")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise EvidenceError("timestamp is invalid") from error
    if parsed.tzinfo is None:
        raise EvidenceError("timestamp timezone is missing")
    return parsed.astimezone(dt.timezone.utc)


def nonnegative_int(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise EvidenceError("nonnegative integer required")
    return value


def finite_number(value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvidenceError("finite number required")
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise EvidenceError("nonnegative finite number required")
    return number


def load_snapshot(path: Path) -> dict[str, Any]:
    try:
        if path.stat().st_size > MAX_INPUT_BYTES:
            raise EvidenceError("snapshot too large")
        value = json.loads(path.read_text(encoding="utf-8"))
    except EvidenceError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise EvidenceError("snapshot unreadable") from error
    if not isinstance(value, dict):
        raise EvidenceError("snapshot must be an object")
    return value


def triplet(
    evaluation: Evaluation, snapshots: list[dict[str, Any]], collector: str, source: str = ""
) -> dict[str, dict[str, Any]] | None:
    selected = [
        item
        for item in snapshots
        if item.get("collector") == collector
        and (collector != "host-resource" or str(item.get("source", "default")) == source)
    ]
    phases: dict[str, dict[str, Any]] = {}
    for item in selected:
        phase = item.get("phase")
        if phase not in PHASES or phase in phases:
            evaluation.block(f"{collector.upper().replace('-', '_')}_PHASE_CONTRACT_INVALID")
            return None
        phases[phase] = item
    if set(phases) != set(PHASES):
        evaluation.block(f"{collector.upper().replace('-', '_')}_PHASE_EVIDENCE_MISSING")
        return None
    try:
        observed = [parse_timestamp(phases[phase].get("observedAt")) for phase in PHASES]
    except EvidenceError:
        evaluation.block(f"{collector.upper().replace('-', '_')}_TIME_INVALID")
        return None
    if not observed[0] < observed[1] < observed[2]:
        evaluation.block(f"{collector.upper().replace('-', '_')}_TIME_ORDER_INVALID")
        return None
    if (observed[2] - observed[0]).total_seconds() < evaluation.profile_seconds:
        evaluation.block(f"{collector.upper().replace('-', '_')}_PROFILE_WINDOW_INCOMPLETE")
        return None
    if any(
        (value - evaluation.now).total_seconds() > MAX_FUTURE_SKEW_SECONDS
        for value in observed
    ):
        evaluation.block(f"{collector.upper().replace('-', '_')}_TIME_IN_FUTURE")
        return None
    if (evaluation.now - observed[2]).total_seconds() > evaluation.max_evidence_age_seconds:
        evaluation.block(f"{collector.upper().replace('-', '_')}_END_EVIDENCE_STALE")
        return None
    return phases


def rows_by_key(rows: Any, fields: tuple[str, ...]) -> dict[tuple[Any, ...], dict[str, Any]]:
    if not isinstance(rows, list):
        raise EvidenceError("rows must be an array")
    result = {}
    for row in rows:
        if not isinstance(row, dict):
            raise EvidenceError("row must be an object")
        key = tuple(row.get(field) for field in fields)
        if any(not isinstance(value, (str, int)) or isinstance(value, bool) for value in key):
            raise EvidenceError("row identity invalid")
        if key in result:
            raise EvidenceError("duplicate row identity")
        result[key] = row
    return result


def evaluate_kafka(
    evaluation: Evaluation,
    snapshots: list[dict[str, Any]],
    max_end_lag: int,
    expected_pairs: set[tuple[str, str]],
) -> None:
    phases = triplet(evaluation, snapshots, "kafka")
    if phases is None:
        return
    try:
        groups = {
            phase: rows_by_key(phases[phase].get("raw", {}).get("groups"), ("group", "topic"))
            for phase in PHASES
        }
        partitions = {
            phase: rows_by_key(
                phases[phase].get("raw", {}).get("partitions"),
                ("group", "topic", "partition"),
            )
            for phase in PHASES
        }
        if len({frozenset(value) for value in groups.values()}) != 1 or len(
            {frozenset(value) for value in partitions.values()}
        ) != 1:
            raise EvidenceError("Kafka identities changed")
        observed_pairs = {(key[1], key[0]) for key in groups["start"]}
        if not expected_pairs or observed_pairs != expected_pairs:
            evaluation.block("KAFKA_REQUIRED_PAIRS_MISMATCH")
        offset_history: dict[tuple[Any, ...], list[tuple[int, int, int]]] = {
            key: [] for key in partitions["start"]
        }
        for phase in PHASES:
            calculated: dict[tuple[Any, ...], list[int]] = {}
            truncated: dict[tuple[Any, ...], int] = {}
            for key, row in partitions[phase].items():
                group_key = (key[0], key[1])
                low = nonnegative_int(row.get("lowOffset"))
                high = nonnegative_int(row.get("highOffset"))
                committed = row.get("committedOffset")
                if isinstance(committed, bool) or not isinstance(committed, int) or committed < -1:
                    raise EvidenceError("Kafka committed offset invalid")
                if high < low or committed > high:
                    evaluation.block("KAFKA_OFFSET_CONTRACT_INVALID")
                    continue
                is_truncated = committed != -1 and committed < low
                is_uninitialized = committed == -1
                if row.get("uninitialized") is not is_uninitialized:
                    raise EvidenceError("Kafka uninitialized flag invalid")
                if row.get("truncated") is not is_truncated:
                    raise EvidenceError("Kafka truncation flag invalid")
                if is_uninitialized:
                    evaluation.block("KAFKA_UNINITIALIZED_PARTITION")
                if is_truncated:
                    evaluation.fail("KAFKA_LOG_TRUNCATION")
                effective = low if committed == -1 or is_truncated else committed
                if nonnegative_int(row.get("effectiveCommittedOffset")) != effective:
                    raise EvidenceError("Kafka effective committed offset invalid")
                lag = high - effective
                if nonnegative_int(row.get("lag")) != lag:
                    raise EvidenceError("Kafka lag does not match offsets")
                calculated.setdefault(group_key, []).append(lag)
                truncated[group_key] = truncated.get(group_key, 0) + int(is_truncated)
                offset_history[key].append((low, high, committed))
            if set(calculated) != set(groups[phase]):
                raise EvidenceError("Kafka group and partition identities differ")
            for key, row in groups[phase].items():
                lags = calculated.get(key)
                if (
                    not lags
                    or nonnegative_int(row.get("totalLag")) != sum(lags)
                    or nonnegative_int(row.get("maxLag")) != max(lags)
                    or nonnegative_int(row.get("partitions")) != len(lags)
                    or nonnegative_int(row.get("truncatedPartitions")) != truncated.get(key, 0)
                    or nonnegative_int(row.get("uninitializedPartitions"))
                    != sum(
                        int(partition.get("committedOffset") == -1)
                        for partition_key, partition in partitions[phase].items()
                        if (partition_key[0], partition_key[1]) == key
                    )
                ):
                    raise EvidenceError("Kafka group summary does not match partitions")
        for history in offset_history.values():
            if len(history) != len(PHASES):
                raise EvidenceError("Kafka partition phase missing")
            for previous, current in zip(history, history[1:]):
                if any(current[index] < previous[index] for index in range(3)):
                    evaluation.block("KAFKA_OFFSET_ROLLBACK")
        metrics = []
        for key in sorted(groups["start"]):
            lag = {
                phase: nonnegative_int(groups[phase][key].get("totalLag")) for phase in PHASES
            }
            metrics.append(
                {"group": key[0], "topic": key[1], **{f"{phase}Lag": lag[phase] for phase in PHASES}}
            )
            if lag["end"] > 0 and lag["end"] >= lag["start"]:
                evaluation.fail("KAFKA_LAG_NOT_DRAINING")
            if lag["end"] > max_end_lag:
                evaluation.fail("KAFKA_END_LAG_EXCEEDED")
        evaluation.metrics["kafka"] = metrics
    except EvidenceError:
        evaluation.block("KAFKA_EVIDENCE_INVALID")


def redis_nodes(
    snapshot: dict[str, Any],
) -> tuple[dict[tuple[Any, ...], dict[str, Any]], set[str]]:
    sources = snapshot.get("raw", {}).get("sources")
    if not isinstance(sources, list):
        raise EvidenceError("Redis sources missing")
    rows = []
    labels: set[str] = set()
    for source in sources:
        if not isinstance(source, dict) or not isinstance(source.get("label"), str):
            raise EvidenceError("Redis source invalid")
        if source["label"] in labels:
            raise EvidenceError("Redis source duplicated")
        labels.add(source["label"])
        nodes = source.get("nodes")
        if not isinstance(nodes, list) or not nodes:
            raise EvidenceError("Redis nodes missing")
        for node in nodes:
            if not isinstance(node, dict):
                raise EvidenceError("Redis node invalid")
            rows.append({"source": source["label"], **node})
    return rows_by_key(rows, ("source", "label")), labels


def evaluate_redis(
    evaluation: Evaluation,
    snapshots: list[dict[str, Any]],
    max_ping_latency_ms: float,
    expected_sources: set[str],
    expected_nodes: set[tuple[str, str]],
) -> None:
    phases = triplet(evaluation, snapshots, "redis")
    if phases is None:
        return
    try:
        parsed = {phase: redis_nodes(phases[phase]) for phase in PHASES}
        nodes = {phase: parsed[phase][0] for phase in PHASES}
        sources = {phase: parsed[phase][1] for phase in PHASES}
        if len({frozenset(value) for value in nodes.values()}) != 1:
            raise EvidenceError("Redis identities changed")
        if len({frozenset(value) for value in sources.values()}) != 1:
            raise EvidenceError("Redis source identities changed")
        observed_nodes = set(nodes["start"])
        observed_sources = sources["start"]
        if (
            not observed_nodes
            or not expected_sources
            or observed_sources != expected_sources
            or observed_nodes != expected_nodes
        ):
            evaluation.block("REDIS_EXPECTED_SET_MISMATCH")
        metrics = []
        for key in sorted(nodes["start"]):
            infos = []
            for phase in PHASES:
                node = nodes[phase][key]
                info = node.get("info")
                if not isinstance(info, dict):
                    raise EvidenceError("Redis INFO missing")
                ping_latency_ms = finite_number(node.get("pingLatencyMs"))
                if ping_latency_ms > max_ping_latency_ms:
                    evaluation.fail("REDIS_PING_LATENCY_EXCEEDED")
                blocked = nonnegative_int(info.get("blocked_clients"))
                evicted = nonnegative_int(info.get("evicted_keys"))
                if blocked > 0:
                    evaluation.fail("REDIS_BLOCKED_CLIENTS")
                infos.append(evicted)
            if infos != sorted(infos):
                evaluation.block("REDIS_COUNTER_RESET")
            elif infos[-1] > infos[0]:
                evaluation.fail("REDIS_EVICTIONS_INCREASED")
            metrics.append(
                {
                    "source": key[0],
                    "node": key[1],
                    "startEvictedKeys": infos[0],
                    "peakEvictedKeys": infos[1],
                    "endEvictedKeys": infos[2],
                    "startPingLatencyMs": finite_number(nodes["start"][key].get("pingLatencyMs")),
                    "peakPingLatencyMs": finite_number(nodes["peak"][key].get("pingLatencyMs")),
                    "endPingLatencyMs": finite_number(nodes["end"][key].get("pingLatencyMs")),
                }
            )
        evaluation.metrics["redis"] = metrics
    except EvidenceError:
        evaluation.block("REDIS_EVIDENCE_INVALID")


def evaluate_host(
    evaluation: Evaluation,
    snapshots: list[dict[str, Any]],
    max_cpu_percent: float,
    max_memory_percent: float,
    expected_sources: set[str],
    expected_containers: set[tuple[str, str]],
    expected_processes: set[tuple[str, str]],
) -> None:
    configured_mapping_sources = {
        source for source, _ in expected_containers | expected_processes
    }
    if not configured_mapping_sources.issubset(expected_sources):
        evaluation.block("HOST_RESOURCE_EXPECTED_SET_MISMATCH")
        return
    sources = sorted(
        {
            str(item.get("source", "default"))
            for item in snapshots
            if item.get("collector") == "host-resource"
        }
    )
    if not sources or not expected_sources or set(sources) != expected_sources:
        evaluation.block("HOST_RESOURCE_EXPECTED_SET_MISMATCH")
        return
    metrics = []
    for source in sources:
        phases = triplet(evaluation, snapshots, "host-resource", source)
        if phases is None:
            continue
        try:
            containers = {
                phase: rows_by_key(phases[phase].get("raw", {}).get("containers"), ("name",))
                for phase in PHASES
            }
            processes = {
                phase: rows_by_key(phases[phase].get("raw", {}).get("processes", []), ("name",))
                for phase in PHASES
            }
            if (
                len({frozenset(value) for value in containers.values()}) != 1
                or len({frozenset(value) for value in processes.values()}) != 1
            ):
                raise EvidenceError("container identities changed")
            expected_container_names = {
                name for expected_source, name in expected_containers if expected_source == source
            }
            expected_process_names = {
                name for expected_source, name in expected_processes if expected_source == source
            }
            if (
                {key[0] for key in containers["start"]} != expected_container_names
                or {key[0] for key in processes["start"]} != expected_process_names
            ):
                evaluation.block("HOST_RESOURCE_EXPECTED_SET_MISMATCH")
            for phase in PHASES:
                host = phases[phase].get("raw", {}).get("host")
                if not isinstance(host, dict):
                    raise EvidenceError("host evidence missing")
                cpu = finite_number(host.get("cpu", {}).get("busyPercent"))
                memory = finite_number(host.get("memory", {}).get("usedPercent"))
                if cpu > max_cpu_percent:
                    evaluation.fail("HOST_CPU_THRESHOLD_EXCEEDED")
                if memory > max_memory_percent:
                    evaluation.fail("HOST_MEMORY_THRESHOLD_EXCEEDED")
            for key in sorted(containers["start"]):
                rows = [containers[phase][key] for phase in PHASES]
                restarts = [nonnegative_int(row.get("restartCount")) for row in rows]
                started_at = [row.get("startedAt") for row in rows]
                if (
                    any(not isinstance(row.get("oomKilled"), bool) for row in rows)
                    or any(not isinstance(row.get("status"), str) for row in rows)
                    or any(not isinstance(value, str) or not value for value in started_at)
                ):
                    raise EvidenceError("container lifecycle invalid")
                if any(row.get("oomKilled") is True for row in rows):
                    evaluation.fail("CONTAINER_OOM_KILLED")
                if any(row.get("status") != "running" for row in rows):
                    evaluation.fail("CONTAINER_NOT_RUNNING")
                if restarts != sorted(restarts):
                    evaluation.block("CONTAINER_RESTART_COUNTER_RESET")
                elif restarts[-1] > restarts[0] or len(set(started_at)) != 1:
                    evaluation.fail("CONTAINER_RESTARTED")
            for key in sorted(processes["start"]):
                rows = [processes[phase][key] for phase in PHASES]
                restarts = [nonnegative_int(row.get("restartCount")) for row in rows]
                started_at = [nonnegative_int(row.get("startedAt")) for row in rows]
                pids = [nonnegative_int(row.get("pid")) for row in rows]
                if any(row.get("status") != "online" for row in rows):
                    evaluation.fail("PM2_PROCESS_NOT_ONLINE")
                if restarts != sorted(restarts):
                    evaluation.block("PM2_RESTART_COUNTER_RESET")
                elif (
                    restarts[-1] > restarts[0]
                    or len(set(started_at)) != 1
                    or len(set(pids)) != 1
                ):
                    evaluation.fail("PM2_PROCESS_RESTARTED")
            metrics.append(
                {
                    "source": source,
                    "host": {
                        phase: {
                            "cpuPercent": finite_number(
                                phases[phase]["raw"]["host"]["cpu"]["busyPercent"]
                            ),
                            "memoryPercent": finite_number(
                                phases[phase]["raw"]["host"]["memory"]["usedPercent"]
                            ),
                        }
                        for phase in PHASES
                    },
                    "containers": [
                        {
                            "name": key[0],
                            **{
                                phase: {
                                    "cpuPercent": finite_number(
                                        containers[phase][key].get("cpuPercent")
                                    ),
                                    "memoryPercent": finite_number(
                                        containers[phase][key].get("memoryPercent")
                                    ),
                                    "memoryBytes": nonnegative_int(
                                        containers[phase][key].get("memoryBytes")
                                    ),
                                }
                                for phase in PHASES
                            },
                        }
                        for key in sorted(containers["start"])
                    ],
                    "processes": [
                        {
                            "name": key[0],
                            **{
                                phase: {
                                    "cpuPercent": finite_number(
                                        processes[phase][key].get("cpuPercent")
                                    ),
                                    "memoryPercent": finite_number(
                                        processes[phase][key].get("memoryPercent")
                                    ),
                                    "memoryBytes": nonnegative_int(
                                        processes[phase][key].get("memoryBytes")
                                    ),
                                }
                                for phase in PHASES
                            },
                        }
                        for key in sorted(processes["start"])
                    ],
                }
            )
        except (EvidenceError, AttributeError):
            evaluation.block("HOST_RESOURCE_EVIDENCE_INVALID")
    evaluation.metrics["hosts"] = metrics


def minute_metrics(raw_minutes: Any) -> tuple[int, int, int]:
    if not isinstance(raw_minutes, list):
        raise EvidenceError("minute evidence missing")
    minutes = [nonnegative_int(value) for value in raw_minutes]
    if len(minutes) != len(set(minutes)) or minutes != sorted(minutes):
        raise EvidenceError("minute evidence invalid")
    if any(value % 60_000 != 0 for value in minutes):
        raise EvidenceError("minute boundary invalid")
    gaps = [(right - left) // 1000 for left, right in zip(minutes, minutes[1:])]
    coverage = 0 if not minutes else (minutes[-1] - minutes[0]) // 1000 + 60
    return len(minutes), coverage, max(gaps, default=0)


def evaluate_web(
    evaluation: Evaluation,
    snapshots: list[dict[str, Any]],
    minimum_window_seconds: int,
    maximum_gap_seconds: int,
) -> None:
    phases = triplet(evaluation, snapshots, "web-traffic")
    if phases is None:
        return
    try:
        start_at = parse_timestamp(phases["start"].get("observedAt"))
        end_at = parse_timestamp(phases["end"].get("observedAt"))
        required_window = max(minimum_window_seconds, evaluation.profile_seconds)
        if (end_at - start_at).total_seconds() < required_window:
            evaluation.block("WEB_TRAFFIC_WINDOW_INCOMPLETE")
        sources = {
            phase: rows_by_key(phases[phase].get("raw", {}).get("sources"), ("label",))
            for phase in PHASES
        }
        if len({frozenset(value) for value in sources.values()}) != 1:
            raise EvidenceError("Web source identities changed")
        if not sources["end"]:
            raise EvidenceError("Web sources missing")
        metrics = []
        for key in sorted(sources["end"]):
            source_rows = [sources[phase][key] for phase in PHASES]
            if any(row.get("captureMode") != "capture-directory" for row in source_rows):
                evaluation.block("WEB_SUMMARY_WATERMARK_UNAVAILABLE")
                continue
            if any(row.get("summaryLineageMode") != "current-run-only" for row in source_rows):
                evaluation.block("WEB_SUMMARY_LINEAGE_INVALID")
                continue
            targets = [nonnegative_int(row.get("summaryTargetBeforeMs")) for row in source_rows]
            if any(value % 60_000 != 0 for value in targets) or targets != sorted(targets):
                raise EvidenceError("Web summary targets invalid")
            end_observed_ms = int(end_at.timestamp() * 1000)
            expected_end_target = ((end_observed_ms + 59_999) // 60_000) * 60_000
            if targets[-1] != expected_end_target:
                raise EvidenceError("Web end summary target does not match observation")
            workers = {
                phase: rows_by_key(sources[phase][key].get("workers"), ("workerId",))
                for phase in PHASES
            }
            if not workers["end"]:
                raise EvidenceError("Web worker evidence missing")
            if len({frozenset(value) for value in workers.values()}) != 1:
                evaluation.block("WEB_WORKER_SET_CHANGED")
                continue
            for phase in PHASES:
                for worker in workers[phase].values():
                    for field in (
                        "collectorDropped",
                        "collectorSinkFailures",
                        "writerWriteFailures",
                        "writerSerializeFailures",
                        "writerFilesDropped",
                        "aggregatorOverflowed",
                        "aggregatorLateRecords",
                        "redundancyPendingDropped",
                    ):
                        if nonnegative_int(worker.get(field)) > 0:
                            evaluation.fail("WEB_COLLECTOR_DEGRADED")
            worker_metrics = []
            for worker_key in sorted(workers["end"]):
                rows = [workers[phase][worker_key] for phase in PHASES]
                run_ids = [row.get("runId") for row in rows]
                if any(not isinstance(value, str) or not value for value in run_ids):
                    raise EvidenceError("Web worker run id missing")
                if len(set(run_ids)) != 1:
                    evaluation.block("WEB_WORKER_LINEAGE_CHANGED")
                    continue
                watermarks = [
                    nonnegative_int(row.get("summaryCommittedBeforeMs")) for row in rows
                ]
                updated_at = [nonnegative_int(row.get("updatedAt")) for row in rows]
                if any(value % 60_000 != 0 for value in watermarks):
                    raise EvidenceError("Web worker watermark invalid")
                if any(
                    watermark > observed // 60_000 * 60_000
                    for watermark, observed in zip(watermarks, updated_at)
                ):
                    raise EvidenceError("Web worker watermark is ahead of its snapshot")
                if watermarks != sorted(watermarks):
                    evaluation.block("WEB_SUMMARY_WATERMARK_REGRESSED")
                if watermarks[-1] < targets[-1]:
                    evaluation.block("WEB_SUMMARY_WATERMARK_INCOMPLETE")
                worker_metrics.append(
                    {
                        "workerId": worker_key[0],
                        "runId": run_ids[0],
                        "summaryCommittedBeforeMs": watermarks[-1],
                    }
                )
            end = source_rows[-1]
            timeline = end.get("timelineEvidence")
            if not isinstance(timeline, dict):
                raise EvidenceError("Web timeline missing")
            count, coverage, max_gap = minute_metrics(timeline.get("minutes"))
            worker_evidence = end.get("workerMinuteEvidence")
            if not isinstance(worker_evidence, dict) or set(worker_evidence) != {
                value[0] for value in workers["end"]
            }:
                raise EvidenceError("Web worker minutes invalid")
            for evidence in worker_evidence.values():
                if not isinstance(evidence, dict):
                    raise EvidenceError("Web worker minutes invalid")
                minute_metrics(evidence.get("minutes"))
            metrics.append(
                {
                    "source": key[0],
                    "summaryTargetBeforeMs": targets[-1],
                    "minuteCount": count,
                    "coverageSeconds": coverage,
                    "maxGapSeconds": max_gap,
                    "workers": worker_metrics,
                }
            )
        evaluation.metrics["webTraffic"] = metrics
    except EvidenceError:
        evaluation.block("WEB_TRAFFIC_EVIDENCE_INVALID")


def android_counters(node: dict[str, Any]) -> dict[str, int]:
    counters = {}
    for section in ("proxy", "reconciliationGap"):
        value = node.get(section)
        if not isinstance(value, dict):
            raise EvidenceError("Android counter section missing")
        for direction in ("up", "down", "total"):
            counters[f"{section}.{direction}"] = nonnegative_int(value.get(direction))
    for section in ("categories", "scopes"):
        values = node.get(section)
        if not isinstance(values, dict):
            raise EvidenceError("Android counter map missing")
        for name, value in values.items():
            if not isinstance(name, str) or not isinstance(value, dict):
                raise EvidenceError("Android counter map invalid")
            for direction in ("up", "down", "total"):
                counters[f"{section}.{name}.{direction}"] = nonnegative_int(value.get(direction))
    return counters


def evaluate_android(
    evaluation: Evaluation, snapshots: list[dict[str, Any]], minimum_window_seconds: int
) -> None:
    phases = triplet(evaluation, snapshots, "android-traffic")
    if phases is None:
        return
    try:
        nodes = {
            phase: rows_by_key(phases[phase].get("raw", {}).get("nodes"), ("label",))
            for phase in PHASES
        }
        if len({frozenset(value) for value in nodes.values()}) != 1:
            raise EvidenceError("Android node identities changed")
        if not nodes["start"]:
            raise EvidenceError("Android nodes missing")
        start_time = parse_timestamp(phases["start"].get("observedAt"))
        end_time = parse_timestamp(phases["end"].get("observedAt"))
        if (end_time - start_time).total_seconds() < minimum_window_seconds:
            evaluation.block("ANDROID_TRAFFIC_WINDOW_INCOMPLETE")
        metrics = []
        for key in sorted(nodes["start"]):
            rows = [nodes[phase][key] for phase in PHASES]
            run_ids = [row.get("runId") for row in rows]
            if (
                any(not isinstance(value, str) or not value for value in run_ids)
                or len(set(run_ids)) != 1
            ):
                evaluation.block("ANDROID_RUN_ID_CHANGED")
                continue
            for row in rows:
                if (
                    row.get("continuous") is not True
                    or row.get("eventDetailDisabled") is not False
                    or row.get("persistenceDisabled") is not False
                    or nonnegative_int(row.get("droppedEvents")) > 0
                    or nonnegative_int(row.get("classificationErrors")) > 0
                ):
                    evaluation.block("ANDROID_COLLECTOR_UNHEALTHY")
            lineages = [row.get("cumulativeStartedAt") for row in rows]
            if any(not isinstance(value, str) for value in lineages) or len(set(lineages)) != 1:
                evaluation.block("ANDROID_LINEAGE_CHANGED")
                continue
            lineage_at = parse_timestamp(lineages[0])
            if (start_time - lineage_at).total_seconds() < minimum_window_seconds:
                evaluation.block("ANDROID_LINEAGE_TOO_YOUNG")
            counters = [android_counters(row) for row in rows]
            for previous, current in zip(counters, counters[1:]):
                for name, value in previous.items():
                    if name not in current or current[name] < value:
                        evaluation.block("ANDROID_COUNTER_NOT_MONOTONIC")
            metrics.append(
                {
                    "node": key[0],
                    "lineage": lineages[0],
                    "startBytes": counters[0]["proxy.total"],
                    "endBytes": counters[-1]["proxy.total"],
                }
            )
        evaluation.metrics["androidTraffic"] = metrics
    except EvidenceError:
        evaluation.block("ANDROID_TRAFFIC_EVIDENCE_INVALID")


def expected_labels(values: list[str]) -> set[str]:
    if any(SAFE_LABEL.fullmatch(value) is None for value in values):
        raise EvidenceError("expected label invalid")
    result = set(values)
    if len(result) != len(values):
        raise EvidenceError("expected labels contain duplicates")
    return result


def expected_mappings(values: list[str]) -> set[tuple[str, str]]:
    result: set[tuple[str, str]] = set()
    for raw in values:
        left, separator, right = raw.partition("=")
        if (
            not separator
            or SAFE_LABEL.fullmatch(left) is None
            or SAFE_LABEL.fullmatch(right) is None
            or (left, right) in result
        ):
            raise EvidenceError("expected mapping invalid")
        result.add((left, right))
    return result


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", default="test1")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--candidate-manifest-sha256", required=True)
    parser.add_argument("--profile-seconds", required=True, type=int)
    parser.add_argument("--max-evidence-age-seconds", type=int, default=300)
    parser.add_argument("--test-mode", action="store_true")
    parser.add_argument("--input", action="append", required=True)
    parser.add_argument("--require-collector", action="append", choices=COLLECTORS, default=[])
    parser.add_argument("--max-host-cpu-percent", type=float, default=95)
    parser.add_argument("--max-host-memory-percent", type=float, default=90)
    parser.add_argument("--max-kafka-end-lag", type=int, default=0)
    parser.add_argument("--max-redis-ping-latency-ms", type=float, default=100)
    parser.add_argument("--minimum-traffic-window-seconds", type=int, default=0)
    parser.add_argument("--maximum-traffic-gap-seconds", type=int, default=60)
    parser.add_argument("--expected-kafka-pair", action="append", default=[])
    parser.add_argument("--expected-redis-source", action="append", default=[])
    parser.add_argument("--expected-redis-node", action="append", default=[])
    parser.add_argument("--expected-host-source", action="append", default=[])
    parser.add_argument("--expected-host-container", action="append", default=[])
    parser.add_argument("--expected-host-process", action="append", default=[])
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    evaluation = Evaluation(
        args.environment,
        args.run_id,
        args.candidate_manifest_sha256,
        args.profile_seconds,
        args.max_evidence_age_seconds,
    )
    try:
        expected_kafka_pairs = expected_mappings(args.expected_kafka_pair)
        expected_redis_sources = expected_labels(args.expected_redis_source)
        expected_redis_nodes = expected_mappings(args.expected_redis_node)
        expected_host_sources = expected_labels(args.expected_host_source)
        expected_host_containers = expected_mappings(args.expected_host_container)
        expected_host_processes = expected_mappings(args.expected_host_process)
    except EvidenceError:
        expected_kafka_pairs = set()
        expected_redis_sources = set()
        expected_redis_nodes = set()
        expected_host_sources = set()
        expected_host_containers = set()
        expected_host_processes = set()
        evaluation.block("OBSERVABILITY_EXPECTED_SET_CONFIGURATION_INVALID")
    if (
        SAFE_LABEL.fullmatch(args.run_id) is None
        or EVIDENCE_SHA256.fullmatch(args.candidate_manifest_sha256) is None
        or args.profile_seconds <= 0
        or args.max_evidence_age_seconds <= 0
        or not math.isfinite(args.max_host_cpu_percent)
        or not 0 < args.max_host_cpu_percent <= 100
        or not math.isfinite(args.max_host_memory_percent)
        or not 0 < args.max_host_memory_percent <= 100
        or args.max_kafka_end_lag < 0
        or not math.isfinite(args.max_redis_ping_latency_ms)
        or args.max_redis_ping_latency_ms <= 0
        or args.minimum_traffic_window_seconds < 0
        or args.maximum_traffic_gap_seconds <= 0
    ):
        evaluation.block("OBSERVABILITY_EVALUATOR_CONFIGURATION_INVALID")
    snapshots = []
    for raw_path in args.input:
        try:
            snapshot = load_snapshot(Path(raw_path))
        except EvidenceError:
            evaluation.block("OBSERVABILITY_EVIDENCE_UNREADABLE")
            continue
        if snapshot.get("environment") != args.environment:
            evaluation.block("OBSERVABILITY_ENVIRONMENT_MISMATCH")
        if snapshot.get("collector") not in COLLECTORS:
            evaluation.block("OBSERVABILITY_COLLECTOR_INVALID")
        if type(snapshot.get("schemaVersion")) is not int or snapshot.get("schemaVersion") != 1:
            evaluation.block("OBSERVABILITY_SCHEMA_VERSION_INVALID")
        if snapshot.get("runId") != args.run_id:
            evaluation.block("OBSERVABILITY_RUN_ID_MISMATCH")
        candidate = snapshot.get("candidateManifestSha256")
        if (
            not isinstance(candidate, str)
            or candidate.lower() != args.candidate_manifest_sha256.lower()
        ):
            evaluation.block("OBSERVABILITY_CANDIDATE_MISMATCH")
        provenance = snapshot.get("provenance")
        if provenance not in ("live", "fixture"):
            evaluation.block("OBSERVABILITY_PROVENANCE_INVALID")
        elif provenance == "fixture" and not args.test_mode:
            evaluation.block("OBSERVABILITY_FIXTURE_REJECTED")
        if snapshot.get("status") == "BLOCKED":
            evaluation.block("OBSERVABILITY_COLLECTION_BLOCKED")
        elif snapshot.get("status") != "COLLECTED":
            evaluation.block("OBSERVABILITY_COLLECTION_STATUS_INVALID")
        health = snapshot.get("health")
        if not isinstance(health, dict) or health.get("ok") is not True:
            evaluation.block("OBSERVABILITY_COLLECTION_HEALTH_INVALID")
        snapshots.append(snapshot)
    present = {item.get("collector") for item in snapshots}
    for required in args.require_collector:
        if required not in present:
            evaluation.block(f"{required.upper().replace('-', '_')}_EVIDENCE_MISSING")
    if not snapshots:
        evaluation.block("OBSERVABILITY_EVIDENCE_MISSING")
    if "kafka" in present:
        evaluate_kafka(
            evaluation, snapshots, args.max_kafka_end_lag, expected_kafka_pairs
        )
    if "redis" in present:
        evaluate_redis(
            evaluation,
            snapshots,
            args.max_redis_ping_latency_ms,
            expected_redis_sources,
            expected_redis_nodes,
        )
    if "host-resource" in present:
        evaluate_host(
            evaluation,
            snapshots,
            args.max_host_cpu_percent,
            args.max_host_memory_percent,
            expected_host_sources,
            expected_host_containers,
            expected_host_processes,
        )
    if "web-traffic" in present:
        evaluate_web(
            evaluation,
            snapshots,
            args.minimum_traffic_window_seconds,
            args.maximum_traffic_gap_seconds,
        )
    if "android-traffic" in present:
        evaluate_android(evaluation, snapshots, args.minimum_traffic_window_seconds)
    result = evaluation.result()
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    return 0 if result["status"] == "PASS" else 2 if result["status"] == "FAIL" else 3


if __name__ == "__main__":
    sys.exit(main())
