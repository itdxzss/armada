from __future__ import annotations

import csv
import json
import math
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

from .model import (
    KafkaMetrics,
    MergedSample,
    MonitorSample,
    ReconciledTask,
    ResourceMetrics,
    ResumeOutcome,
    TaskSnapshot,
)


class ReportError(ValueError):
    """A stable monitor/report validation error."""


CSV_HEADER = (
    "at",
    "kafkaLatestOffset",
    "kafkaCommittedOffset",
    "kafkaLag",
    "producedPerSecond",
    "consumedPerSecond",
    "armadaHostCpuPercent",
    "armadaHostMemoryUsedBytes",
    "armadaHostMemoryPercent",
    "armadaContainerCpuPercent",
    "armadaContainerMemoryBytes",
    "armadaContainerMemoryPercent",
    "zhuanHostCpuPercent",
    "zhuanHostMemoryUsedBytes",
    "zhuanHostMemoryPercent",
    "zhuanContainerCpuPercent",
    "zhuanContainerMemoryBytes",
    "zhuanContainerMemoryPercent",
)

_UTC_RE = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$")
_ERROR_CLASS_RE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
_MAX_LINE_BYTES = 1024 * 1024


def parse_monitor_line(line: bytes, expected_node: str) -> MonitorSample:
    if expected_node not in ("armada", "zhuan") or not line or len(line) > _MAX_LINE_BYTES:
        raise ReportError("monitor_line")
    try:
        payload = json.loads(line.decode("utf-8"), parse_constant=_reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError, ReportError) as error:
        raise ReportError("monitor_json") from error
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1 or payload.get("node") != expected_node:
        raise ReportError("monitor_contract")
    at = _parse_utc(payload.get("at"))
    resource = _parse_resource(payload.get("resource"))
    raw_kafka = payload.get("kafka")
    if expected_node == "zhuan":
        if not isinstance(raw_kafka, dict):
            raise ReportError("kafka_contract")
        kafka = _parse_kafka(raw_kafka)
    else:
        if raw_kafka is not None:
            raise ReportError("kafka_contract")
        kafka = None
    return MonitorSample(
        at=at,
        second=at.replace(microsecond=0),
        node=expected_node,
        kafka=kafka,
        resource=resource,
    )


class SampleAligner:
    def __init__(self) -> None:
        self._pending: Dict[datetime, Dict[str, MonitorSample]] = {}
        self._seen = set()

    def add(self, sample: MonitorSample) -> Optional[MergedSample]:
        key = (sample.second, sample.node)
        if key in self._seen:
            raise ReportError("duplicate_sample")
        self._seen.add(key)
        bucket = self._pending.setdefault(sample.second, {})
        bucket[sample.node] = sample
        if set(bucket) != {"armada", "zhuan"}:
            return None
        del self._pending[sample.second]
        return merge_samples(bucket["armada"], bucket["zhuan"])


def merge_samples(first: MonitorSample, second: MonitorSample) -> MergedSample:
    by_node = {first.node: first, second.node: second}
    if set(by_node) != {"armada", "zhuan"} or first.second != second.second:
        raise ReportError("sample_alignment")
    armada = by_node["armada"]
    zhuan = by_node["zhuan"]
    if (
        zhuan.kafka is None
        or not zhuan.kafka.valid
        or not armada.resource.valid
        or not zhuan.resource.valid
    ):
        raise ReportError("invalid_sample")
    return MergedSample(
        at=first.second,
        kafka=zhuan.kafka,
        armada_resource=armada.resource,
        zhuan_resource=zhuan.resource,
    )


class ZeroWindow:
    def __init__(self, required_seconds: int) -> None:
        if isinstance(required_seconds, bool) or not isinstance(required_seconds, int) or required_seconds <= 0:
            raise ReportError("zero_window")
        self.required_seconds = required_seconds
        self._count = 0
        self._last_second: Optional[datetime] = None

    def observe(self, sample: MergedSample, resumes_complete: bool) -> bool:
        if not resumes_complete or sample.kafka.lag != 0 or sample.kafka.produced_per_second != 0:
            self._reset()
            return False
        if self._last_second is None or sample.at - self._last_second == timedelta(seconds=1):
            self._count += 1
        else:
            self._count = 1
        self._last_second = sample.at
        return self._count >= self.required_seconds

    def _reset(self) -> None:
        self._count = 0
        self._last_second = None


def nearest_rank_p95(values: Sequence[float]) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[math.ceil(0.95 * len(ordered)) - 1]


def build_summary(
    samples: Sequence[MergedSample],
    snapshot: Sequence[TaskSnapshot],
    outcomes: Sequence[ResumeOutcome],
    reconciled: Sequence[ReconciledTask],
    *,
    invalid_kafka_samples: int,
    invalid_resource_samples: int,
    timed_out: bool,
    interrupted: bool,
    zero_window_seconds: int = 60,
    require_resumed: bool = True,
) -> Mapping[str, object]:
    if invalid_kafka_samples < 0 or invalid_resource_samples < 0:
        raise ReportError("invalid_count")
    ordered = sorted(samples, key=lambda sample: sample.at)
    _validate_merged_order(ordered)
    peak_produced = _maximum([sample.kafka.produced_per_second for sample in ordered])
    peak_consumed = _maximum([sample.kafka.consumed_per_second for sample in ordered])
    max_lag = int(_maximum([sample.kafka.lag for sample in ordered]) or 0)
    topic_delta = 0
    if len(ordered) >= 2:
        topic_delta = max(0, ordered[-1].kafka.latest_offset - ordered[0].kafka.latest_offset)
    drain_values = [
        sample.kafka.consumed_per_second
        for sample in ordered
        if sample.kafka.lag > 0 and sample.kafka.produced_per_second == 0
    ]
    drain_peak = _maximum(drain_values)
    drain_seconds = _lag_drain_seconds(ordered, max_lag, zero_window_seconds)
    snapshot_ids = {task.id for task in snapshot}
    sending_ids = {task.task_id for task in reconciled if task.classification == "sending"}
    all_resumed = snapshot_ids == sending_ids and len(reconciled) == len(snapshot)
    incomplete = bool(
        timed_out
        or interrupted
        or invalid_kafka_samples
        or invalid_resource_samples
        or require_resumed and not all_resumed
    )
    return {
        "snapshotTaskCount": len(snapshot),
        "selectedAccountCount": sum(task.selected_account_count for task in snapshot),
        "targetGroupCount": sum(task.target_group_count for task in snapshot),
        "targetPairCount": sum(task.target_pair_count for task in snapshot),
        "topicProducedMessages": topic_delta,
        "observedPeakProducedPerSecond": _round_optional(peak_produced),
        "observedPeakConsumedPerSecond": _round_optional(peak_consumed),
        "maxLag": max_lag,
        "lagDrainSeconds": drain_seconds,
        "drainPeakConsumedPerSecond": _round_optional(drain_peak),
        "capacityConclusion": "observed_lower_bound" if max_lag == 0 else "observed_backlog_drained",
        "resources": {
            "armada": _resource_summary([sample.armada_resource for sample in ordered]),
            "zhuan": _resource_summary([sample.zhuan_resource for sample in ordered]),
        },
        "invalidKafkaSamples": invalid_kafka_samples,
        "invalidResourceSamples": invalid_resource_samples,
        "resumeOutcomeCounts": _outcome_counts(outcomes),
        "allSnapshotTasksResumed": all_resumed,
        "timedOut": timed_out,
        "interrupted": interrupted,
        "incomplete": incomplete,
    }


def write_samples_csv(path: Path, samples: Sequence[MergedSample]) -> None:
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(CSV_HEADER)
        for sample in sorted(samples, key=lambda value: value.at):
            armada = sample.armada_resource
            zhuan = sample.zhuan_resource
            writer.writerow(
                (
                    _format_utc(sample.at),
                    sample.kafka.latest_offset,
                    sample.kafka.committed_offset,
                    sample.kafka.lag,
                    sample.kafka.produced_per_second,
                    sample.kafka.consumed_per_second,
                    armada.host_cpu_percent,
                    armada.host_memory_used_bytes,
                    armada.host_memory_percent,
                    armada.container_cpu_percent,
                    armada.container_memory_bytes,
                    armada.container_memory_percent,
                    zhuan.host_cpu_percent,
                    zhuan.host_memory_used_bytes,
                    zhuan.host_memory_percent,
                    zhuan.container_cpu_percent,
                    zhuan.container_memory_bytes,
                    zhuan.container_memory_percent,
                )
            )


def _parse_kafka(value: Mapping[str, object]) -> KafkaMetrics:
    valid = _boolean(value.get("valid"))
    error_class = _error_class(value.get("errorClass"))
    if not valid:
        return KafkaMetrics(0, 0, 0, 0, 0, False, error_class)
    return KafkaMetrics(
        latest_offset=_integer(value.get("latestOffset")),
        committed_offset=_integer(value.get("committedOffset")),
        lag=_integer(value.get("lag")),
        produced_per_second=_number(value.get("producedPerSecond")),
        consumed_per_second=_number(value.get("consumedPerSecond")),
        valid=True,
        error_class=None,
    )


def _parse_resource(value) -> ResourceMetrics:
    if not isinstance(value, dict):
        raise ReportError("resource_contract")
    valid = _boolean(value.get("valid"))
    error_class = _error_class(value.get("errorClass"))
    if not valid:
        return ResourceMetrics(0, 0, 0, 0, 0, 0, False, error_class)
    return ResourceMetrics(
        host_cpu_percent=_number(value.get("hostCpuPercent")),
        host_memory_used_bytes=_integer(value.get("hostMemoryUsedBytes")),
        host_memory_percent=_bounded_percent(value.get("hostMemoryPercent")),
        container_cpu_percent=_number(value.get("containerCpuPercent")),
        container_memory_bytes=_integer(value.get("containerMemoryBytes")),
        container_memory_percent=_bounded_percent(value.get("containerMemoryPercent")),
        valid=True,
        error_class=None,
    )


def _parse_utc(value) -> datetime:
    if not isinstance(value, str):
        raise ReportError("timestamp")
    match = _UTC_RE.fullmatch(value)
    if match is None:
        raise ReportError("timestamp")
    fraction = (match.group(2) or "")[:6].ljust(6, "0")
    normalized = "%s.%s+00:00" % (match.group(1), fraction)
    try:
        return datetime.fromisoformat(normalized).astimezone(timezone.utc)
    except ValueError as error:
        raise ReportError("timestamp") from error


def _number(value) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ReportError("number")
    result = float(value)
    if result < 0 or not math.isfinite(result):
        raise ReportError("number")
    return result


def _integer(value) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ReportError("integer")
    return value


def _bounded_percent(value) -> float:
    result = _number(value)
    if result > 100:
        raise ReportError("percent")
    return result


def _boolean(value) -> bool:
    if not isinstance(value, bool):
        raise ReportError("boolean")
    return value


def _error_class(value) -> Optional[str]:
    if value is None:
        return None
    if not isinstance(value, str) or not _ERROR_CLASS_RE.fullmatch(value):
        raise ReportError("error_class")
    return value


def _reject_constant(_value: str):
    raise ReportError("number")


def _validate_merged_order(samples: Sequence[MergedSample]) -> None:
    previous: Optional[datetime] = None
    for sample in samples:
        if previous is not None and sample.at <= previous:
            raise ReportError("sample_order")
        previous = sample.at


def _maximum(values: Sequence[float]):
    return max(values) if values else None


def _round_optional(value):
    return None if value is None else round(float(value), 3)


def _lag_drain_seconds(samples: Sequence[MergedSample], max_lag: int, required: int) -> Optional[int]:
    if max_lag <= 0 or required <= 0:
        return None
    max_index = next(index for index, sample in enumerate(samples) if sample.kafka.lag == max_lag)
    count = 0
    start: Optional[datetime] = None
    previous: Optional[datetime] = None
    for sample in samples[max_index + 1 :]:
        idle = sample.kafka.lag == 0 and sample.kafka.produced_per_second == 0
        consecutive = previous is None or sample.at - previous == timedelta(seconds=1)
        if idle and consecutive:
            if count == 0:
                start = sample.at
            count += 1
            if count >= required and start is not None:
                return int((start - samples[max_index].at).total_seconds())
        elif idle:
            count = 1
            start = sample.at
        else:
            count = 0
            start = None
        previous = sample.at
    return None


def _resource_summary(resources: Sequence[ResourceMetrics]) -> Mapping[str, object]:
    fields = {
        "hostCpuPercent": [value.host_cpu_percent for value in resources],
        "hostMemoryUsedBytes": [value.host_memory_used_bytes for value in resources],
        "hostMemoryPercent": [value.host_memory_percent for value in resources],
        "containerCpuPercent": [value.container_cpu_percent for value in resources],
        "containerMemoryBytes": [value.container_memory_bytes for value in resources],
        "containerMemoryPercent": [value.container_memory_percent for value in resources],
    }
    return {
        name: {"max": _round_optional(_maximum(values)), "p95": _round_optional(nearest_rank_p95(values))}
        for name, values in fields.items()
    }


def _outcome_counts(outcomes: Sequence[ResumeOutcome]) -> Mapping[str, int]:
    counts: Dict[str, int] = {}
    for outcome in outcomes:
        counts[outcome.result] = counts.get(outcome.result, 0) + 1
    return counts


def _format_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
