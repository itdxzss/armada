#!/usr/bin/env python3
"""只读采集 test1 的主机与 Web/Android 协议流量快照。"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from web_capture import WebCaptureError, load_capture_directory


SCHEMA_VERSION = 1
PHASES = ("start", "peak", "end")
SAFE_LABEL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
EVIDENCE_SHA256 = re.compile(r"^sha256:[0-9a-fA-F]{64}$")
PRIVATE_DIMENSION = re.compile(
    r"(?:[A-Za-z][A-Za-z0-9+.-]*://|@|password|authorization|secret|token|\d{7,})",
    re.IGNORECASE,
)


class CollectionError(Exception):
    """不携带底层异常文本，防止连接串或凭据进入 Runner 日志。"""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def base_result(collector: str, args: argparse.Namespace) -> dict[str, Any]:
    result = {
        "schemaVersion": SCHEMA_VERSION,
        "collector": collector,
        "environment": args.environment,
        "phase": args.phase,
        "runId": args.run_id,
        "candidateManifestSha256": args.candidate_manifest_sha256.lower(),
        "provenance": evidence_provenance(args),
        "observedAt": utc_now(),
        "status": "COLLECTED",
        "health": {"ok": True, "checks": [], "blockedReasons": []},
        "semantics": {},
        "raw": {},
    }
    if getattr(args, "label", ""):
        result["source"] = args.label
    return result


def evidence_provenance(args: argparse.Namespace) -> str:
    adapter = getattr(args, "adapter", "")
    if adapter == "host" and any(
        getattr(args, name, None)
        for name in (
            "proc_stat_before",
            "proc_stat_after",
            "meminfo",
            "docker_stats_file",
            "docker_inspect_file",
            "pm2_jlist_file",
        )
    ):
        return "fixture"
    if adapter == "web-traffic" and (
        getattr(args, "json_file", []) or getattr(args, "now_ms", None) is not None
    ):
        return "fixture"
    if adapter == "android-traffic" and (
        getattr(args, "json_file", []) or getattr(args, "now", None) is not None
    ):
        return "fixture"
    return "live"


def add_check(result: dict[str, Any], name: str, ok: bool, reason: str = "") -> None:
    check: dict[str, Any] = {"name": name, "ok": ok}
    if not ok:
        check["reason"] = reason
        result["status"] = "BLOCKED"
        result["health"]["ok"] = False
        if reason and reason not in result["health"]["blockedReasons"]:
            result["health"]["blockedReasons"].append(reason)
    result["health"]["checks"].append(check)


def emit(result: dict[str, Any]) -> int:
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    return 0 if result["status"] == "COLLECTED" else 2


def parse_mapping(raw: str, value_name: str) -> tuple[str, str]:
    label, separator, value = raw.partition("=")
    if not separator or not SAFE_LABEL.fullmatch(label) or not value.strip():
        raise CollectionError(f"INVALID_{value_name.upper()}_MAPPING")
    return label, value.strip()


def unique_mappings(values: list[str], value_name: str) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for raw in values:
        label, value = parse_mapping(raw, value_name)
        if label in parsed:
            raise CollectionError(f"DUPLICATE_{value_name.upper()}_LABEL")
        parsed[label] = value
    return parsed


def read_json(path: str) -> Any:
    try:
        if path == "-":
            return json.load(sys.stdin)
        with Path(path).open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CollectionError("FIXTURE_UNAVAILABLE") from error


def read_text(path: str) -> str:
    try:
        return sys.stdin.read() if path == "-" else Path(path).read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise CollectionError("FIXTURE_UNAVAILABLE") from error


def fetch_json(url: str, timeout_seconds: float) -> Any:
    if not re.match(r"^https?://", url, re.IGNORECASE):
        raise CollectionError("INVALID_HTTP_ENDPOINT")
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "staging-accept-observability/1"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            if response.status != 200:
                raise CollectionError("HTTP_NON_200")
            body = response.read(16 * 1024 * 1024 + 1)
            if len(body) > 16 * 1024 * 1024:
                raise CollectionError("HTTP_RESPONSE_TOO_LARGE")
            return json.loads(body)
    except CollectionError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError, urllib.error.URLError) as error:
        raise CollectionError("HTTP_UNAVAILABLE") from error


def nonnegative_number(value: Any, code: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CollectionError(code)
    number = float(value)
    if not math.isfinite(number) or number < 0:
        raise CollectionError(code)
    return number


def integer_number(value: Any, code: str) -> int:
    number = nonnegative_number(value, code)
    if not number.is_integer():
        raise CollectionError(code)
    return int(number)


def parse_host(args: argparse.Namespace) -> dict[str, Any]:
    result = base_result("host-resource", args)
    result["semantics"] = {
        "cpu": "delta of Linux aggregate /proc/stat counters over sampleIntervalMs",
        "memory": "MemTotal minus MemAvailable",
        "containers": "Docker cgroup CPU/memory plus allowlisted lifecycle state; network fields are intentionally excluded",
        "phase": "caller-assigned start/peak/end observation point",
    }
    try:
        before_path = args.proc_stat_before or str(Path(args.proc_root) / "stat")
        after_path = args.proc_stat_after or str(Path(args.proc_root) / "stat")
        memory_path = args.meminfo or str(Path(args.proc_root) / "meminfo")
        before = parse_proc_stat(Path(before_path).read_text(encoding="utf-8"))
        if not args.proc_stat_after:
            time.sleep(args.sample_ms / 1000)
        after = parse_proc_stat(Path(after_path).read_text(encoding="utf-8"))
        memory = parse_meminfo(Path(memory_path).read_text(encoding="utf-8"))
        busy_percent = cpu_busy_percent(before, after)
        result["raw"]["host"] = {
            "cpu": {
                "before": before,
                "after": after,
                "busyPercent": busy_percent,
                "sampleIntervalMs": args.sample_ms,
            },
            "memory": memory,
        }
        add_check(result, "host-procfs", True)
    except (OSError, UnicodeError, CollectionError):
        add_check(result, "host-procfs", False, "HOST_PROCFS_UNOBSERVABLE")

    containers: list[dict[str, Any]] = []
    if args.container:
        try:
            raw_stats = docker_stats(args)
            containers = parse_docker_stats(raw_stats, set(args.container))
            lifecycle = parse_docker_inspect(docker_inspect(args), set(args.container))
            containers = [{**row, **lifecycle[row["name"]]} for row in containers if row["name"] in lifecycle]
            observed = {row["name"] for row in containers}
            missing = sorted(set(args.container) - observed)
            add_check(result, "docker-stats", not missing, "CONTAINER_RESOURCE_UNOBSERVABLE" if missing else "")
        except CollectionError:
            add_check(result, "docker-stats", False, "CONTAINER_RESOURCE_UNOBSERVABLE")
    else:
        add_check(result, "docker-stats", True)
    result["raw"]["containers"] = containers

    processes: list[dict[str, Any]] = []
    if args.process:
        try:
            host_memory = result["raw"].get("host", {}).get("memory", {})
            total_memory = integer_number(
                host_memory.get("totalBytes"), "PM2_PROCESS_RESOURCE_UNOBSERVABLE"
            )
            processes = parse_pm2_jlist(
                pm2_jlist(args), set(args.process), total_memory
            )
            observed = {row["name"] for row in processes}
            missing = sorted(set(args.process) - observed)
            add_check(
                result,
                "pm2-processes",
                not missing,
                "PM2_PROCESS_RESOURCE_UNOBSERVABLE" if missing else "",
            )
        except CollectionError:
            add_check(
                result,
                "pm2-processes",
                False,
                "PM2_PROCESS_RESOURCE_UNOBSERVABLE",
            )
    else:
        add_check(result, "pm2-processes", True)
    result["raw"]["processes"] = processes
    return result


def parse_proc_stat(raw: str) -> dict[str, int]:
    first = raw.splitlines()[0].split() if raw.splitlines() else []
    if len(first) < 9 or first[0] != "cpu":
        raise CollectionError("INVALID_PROC_STAT")
    names = ("user", "nice", "system", "idle", "iowait", "irq", "softirq", "steal")
    try:
        values = [int(value) for value in first[1:9]]
    except ValueError as error:
        raise CollectionError("INVALID_PROC_STAT") from error
    if any(value < 0 for value in values):
        raise CollectionError("INVALID_PROC_STAT")
    # test1 Web currently runs Python 3.9, where zip(strict=...) is unavailable.
    # Both sequences are fixed to eight entries above, so the plain zip is exact.
    return dict(zip(names, values))


def cpu_busy_percent(before: dict[str, int], after: dict[str, int]) -> float:
    before_total = sum(before.values())
    after_total = sum(after.values())
    total_delta = after_total - before_total
    idle_delta = (after["idle"] + after["iowait"]) - (before["idle"] + before["iowait"])
    if total_delta <= 0 or idle_delta < 0 or idle_delta > total_delta:
        raise CollectionError("INVALID_CPU_DELTA")
    return round(100 * (total_delta - idle_delta) / total_delta, 3)


def parse_meminfo(raw: str) -> dict[str, Any]:
    values: dict[str, int] = {}
    for line in raw.splitlines():
        fields = line.split()
        if len(fields) != 3 or fields[2] != "kB":
            continue
        name = fields[0].rstrip(":")
        if name not in ("MemTotal", "MemAvailable"):
            continue
        try:
            values[name] = int(fields[1]) * 1024
        except ValueError as error:
            raise CollectionError("INVALID_MEMINFO") from error
    total = values.get("MemTotal", 0)
    available = values.get("MemAvailable", -1)
    if total <= 0 or available < 0 or available > total:
        raise CollectionError("INVALID_MEMINFO")
    used = total - available
    return {
        "totalBytes": total,
        "availableBytes": available,
        "usedBytes": used,
        "usedPercent": round(100 * used / total, 3),
    }


def docker_stats(args: argparse.Namespace) -> str:
    if args.docker_stats_file:
        try:
            return read_text(args.docker_stats_file)
        except CollectionError as error:
            raise CollectionError("DOCKER_STATS_UNAVAILABLE") from error
    executable = args.docker_bin or shutil.which("docker")
    if not executable or not Path(executable).is_absolute():
        raise CollectionError("DOCKER_STATS_UNAVAILABLE")
    command = [executable, "stats", "--no-stream", "--format", "{{json .}}", *args.container]
    try:
        completed = subprocess.run(command, check=True, capture_output=True, text=True, timeout=15)
        return completed.stdout
    except (OSError, subprocess.SubprocessError) as error:
        raise CollectionError("DOCKER_STATS_UNAVAILABLE") from error


def parse_docker_stats(raw: str, expected: set[str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        try:
            source = json.loads(line)
            name = str(source.get("Name") or source.get("Container") or "")
            if name not in expected:
                continue
            cpu = parse_percent(source.get("CPUPerc"), bounded=False)
            memory_percent = parse_percent(source.get("MemPerc"), bounded=True)
            usage = str(source.get("MemUsage", "")).split("/")
            if len(usage) != 2:
                raise CollectionError("INVALID_DOCKER_STATS")
            rows.append(
                {
                    "name": name,
                    "cpuPercent": cpu,
                    "memoryBytes": parse_size(usage[0].strip()),
                    "memoryLimitBytes": parse_size(usage[1].strip()),
                    "memoryPercent": memory_percent,
                }
            )
        except (json.JSONDecodeError, TypeError, CollectionError) as error:
            raise CollectionError("INVALID_DOCKER_STATS") from error
    return sorted(rows, key=lambda row: row["name"])


def docker_inspect(args: argparse.Namespace) -> str:
    if args.docker_inspect_file:
        return read_text(args.docker_inspect_file)
    executable = args.docker_bin or shutil.which("docker")
    if not executable or not Path(executable).is_absolute():
        raise CollectionError("DOCKER_INSPECT_UNAVAILABLE")
    template = (
        '{"name":{{json .Name}},"restartCount":{{json .RestartCount}},'
        '"oomKilled":{{json .State.OOMKilled}},"status":{{json .State.Status}},'
        '"startedAt":{{json .State.StartedAt}}}'
    )
    rows = []
    for name in args.container:
        try:
            completed = subprocess.run(
                [executable, "inspect", "--format", template, name],
                check=True,
                capture_output=True,
                text=True,
                timeout=15,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise CollectionError("DOCKER_INSPECT_UNAVAILABLE") from error
        rows.append(completed.stdout.strip())
    return "\n".join(rows)


def parse_docker_inspect(raw: str, expected: set[str]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for line in raw.splitlines():
        if not line.strip():
            continue
        try:
            source = json.loads(line)
        except json.JSONDecodeError as error:
            raise CollectionError("INVALID_DOCKER_INSPECT") from error
        if not isinstance(source, dict):
            raise CollectionError("INVALID_DOCKER_INSPECT")
        name = str(source.get("name", "")).removeprefix("/")
        restart_count = source.get("restartCount")
        oom_killed = source.get("oomKilled")
        status = source.get("status")
        started_at = source.get("startedAt")
        if name not in expected:
            continue
        if (
            name in result
            or isinstance(restart_count, bool)
            or not isinstance(restart_count, int)
            or restart_count < 0
            or not isinstance(oom_killed, bool)
            or not isinstance(status, str)
            or not status
            or not isinstance(started_at, str)
            or not started_at
        ):
            raise CollectionError("INVALID_DOCKER_INSPECT")
        result[name] = {
            "restartCount": restart_count,
            "oomKilled": oom_killed,
            "status": status,
            "startedAt": started_at,
        }
    return result


def pm2_jlist(args: argparse.Namespace) -> str:
    if args.pm2_jlist_file:
        return read_text(args.pm2_jlist_file)
    executable = args.pm2_bin or shutil.which("pm2")
    if not executable or not Path(executable).is_absolute():
        raise CollectionError("PM2_JLIST_UNAVAILABLE")
    try:
        completed = subprocess.run(
            [executable, "jlist"],
            check=True,
            capture_output=True,
            text=True,
            timeout=15,
        )
        return completed.stdout
    except (OSError, subprocess.SubprocessError) as error:
        raise CollectionError("PM2_JLIST_UNAVAILABLE") from error


def parse_pm2_jlist(
    raw: str, expected: set[str], total_memory_bytes: int
) -> list[dict[str, Any]]:
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as error:
        raise CollectionError("INVALID_PM2_JLIST") from error
    if not isinstance(payload, list) or total_memory_bytes <= 0:
        raise CollectionError("INVALID_PM2_JLIST")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in payload:
        if not isinstance(item, dict):
            raise CollectionError("INVALID_PM2_JLIST")
        name = item.get("name")
        if name not in expected:
            continue
        monit = item.get("monit")
        environment = item.get("pm2_env")
        if name in seen or not isinstance(monit, dict) or not isinstance(environment, dict):
            raise CollectionError("INVALID_PM2_JLIST")
        pid = integer_number(item.get("pid"), "INVALID_PM2_JLIST")
        cpu = nonnegative_number(monit.get("cpu"), "INVALID_PM2_JLIST")
        memory = integer_number(monit.get("memory"), "INVALID_PM2_JLIST")
        restart_count = integer_number(
            environment.get("restart_time"), "INVALID_PM2_JLIST"
        )
        started_at = integer_number(environment.get("pm_uptime"), "INVALID_PM2_JLIST")
        status = environment.get("status")
        if pid <= 0 or not isinstance(status, str) or not status:
            raise CollectionError("INVALID_PM2_JLIST")
        result.append(
            {
                "name": name,
                "pid": pid,
                "cpuPercent": round(cpu, 3),
                "memoryBytes": memory,
                "memoryPercent": round(100 * memory / total_memory_bytes, 3),
                "restartCount": restart_count,
                "status": status,
                "startedAt": started_at,
            }
        )
        seen.add(name)
    return sorted(result, key=lambda row: row["name"])


def parse_percent(raw: Any, bounded: bool) -> float:
    if not isinstance(raw, str) or not raw.endswith("%"):
        raise CollectionError("INVALID_PERCENT")
    try:
        value = float(raw[:-1])
    except ValueError as error:
        raise CollectionError("INVALID_PERCENT") from error
    if not math.isfinite(value) or value < 0 or (bounded and value > 100):
        raise CollectionError("INVALID_PERCENT")
    return round(value, 3)


def parse_size(raw: str) -> int:
    match = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)(B|KiB|MiB|GiB|TiB)", raw)
    if not match:
        raise CollectionError("INVALID_SIZE")
    multipliers = {"B": 1, "KiB": 2**10, "MiB": 2**20, "GiB": 2**30, "TiB": 2**40}
    return round(float(match.group(1)) * multipliers[match.group(2)])


def load_sources(args: argparse.Namespace, mapping_name: str) -> tuple[dict[str, str], dict[str, str]]:
    return unique_mappings(args.target, f"{mapping_name}_target"), unique_mappings(
        args.json_file, f"{mapping_name}_fixture"
    )


def parse_web_traffic(args: argparse.Namespace) -> dict[str, Any]:
    result = base_result("web-traffic", args)
    result["semantics"] = {
        "proxyWireBytes": "application proxy-socket bytes from Web protocol traffic instrumentation",
        "classifiedBytes": "Noise frame bytes with application categories",
        "cloudBilling": False,
        "warning": "values are neither EC2 TCP payload counters nor AWS/cloud invoice bytes",
        "window": "current-worker-run statistics through the frozen end boundary; end-exclusive watermarks prove completeness",
    }
    try:
        targets, fixtures = load_sources(args, "web_traffic")
        directories = unique_mappings(args.capture_directory, "web_traffic_directory")
    except CollectionError as error:
        add_check(result, "web-inputs", False, error.code)
        return result
    if not targets and not fixtures and not directories:
        add_check(result, "web-inputs", False, "WEB_TRAFFIC_TARGETS_MISSING")
        return result

    sources: list[dict[str, Any]] = []
    frozen_now_ms = (
        args.now_ms
        if args.now_ms is not None
        else int(parse_timestamp(result["observedAt"]).timestamp() * 1000)
    )
    for label in sorted(set(targets) | set(fixtures) | set(directories)):
        try:
            if label in directories:
                try:
                    payload = load_capture_directory(
                        directories[label],
                        frozen_now_ms,
                        args.minimum_window_seconds,
                        phase=args.phase,
                        expected_workers=args.expected_workers,
                    )
                except WebCaptureError as error:
                    raise CollectionError(error.code) from error
                except ValueError as error:
                    raise CollectionError("WEB_CAPTURE_DIRECTORY_UNAVAILABLE") from error
            else:
                payload = read_json(fixtures[label]) if label in fixtures else fetch_json(targets[label], args.timeout_seconds)
            health_now_ms = (
                args.now_ms if args.now_ms is not None else int(time.time() * 1000)
            )
            source, reasons = normalize_web_payload(label, payload, args, health_now_ms)
            sources.append(source)
            add_check(result, f"web-traffic-{label}", not reasons, reasons[0] if reasons else "")
            for reason in reasons[1:]:
                add_check(result, f"web-traffic-{label}-{reason.lower()}", False, reason)
        except CollectionError as error:
            add_check(result, f"web-traffic-{label}", False, error.code)
    result["raw"]["sources"] = sources
    return result


def normalize_web_payload(
    label: str, payload: Any, args: argparse.Namespace, now_ms: int
) -> tuple[dict[str, Any], list[str]]:
    if not isinstance(payload, dict):
        raise CollectionError("WEB_TRAFFIC_INVALID")
    reconciliation = payload.get("reconciliation")
    timeline = payload.get("timeline")
    health = payload.get("health")
    if not isinstance(reconciliation, dict) or not isinstance(timeline, list) or not isinstance(health, list):
        raise CollectionError("WEB_TRAFFIC_INVALID")
    capture_mode = payload.get("_captureMode", "dashboard-api")
    if capture_mode not in ("dashboard-api", "capture-directory"):
        raise CollectionError("WEB_TRAFFIC_INVALID")
    normalized_reconciliation = normalize_web_reconciliation(reconciliation)

    points: list[dict[str, int]] = []
    seen_minutes: set[int] = set()
    for raw_point in timeline:
        if not isinstance(raw_point, dict):
            raise CollectionError("WEB_TRAFFIC_INVALID")
        point = {
            field: integer_number(raw_point.get(field), "WEB_TRAFFIC_INVALID")
            for field in ("minute", "up", "down", "total")
        }
        if point["up"] + point["down"] != point["total"]:
            raise CollectionError("WEB_TRAFFIC_INVALID")
        if point["minute"] in seen_minutes:
            raise CollectionError("WEB_TRAFFIC_INVALID")
        seen_minutes.add(point["minute"])
        points.append(point)
    points.sort(key=lambda point: point["minute"])
    timeline_evidence = minute_evidence(
        [point["minute"] for point in points], args.maximum_gap_seconds
    )
    coverage_seconds = timeline_evidence["coverageSeconds"]

    worker_health: list[dict[str, Any]] = []
    reasons: list[str] = []
    if len(health) != args.expected_workers:
        reasons.append("WEB_WORKER_COUNT_MISMATCH")
    seen_workers: set[str] = set()
    for item in health:
        if not isinstance(item, dict) or not isinstance(item.get("snapshot"), dict):
            reasons.append("WEB_WORKER_HEALTH_INVALID")
            continue
        snapshot = item["snapshot"]
        worker_id = item.get("workerId")
        if (
            not isinstance(worker_id, str)
            or SAFE_LABEL.fullmatch(worker_id) is None
            or worker_id in seen_workers
        ):
            reasons.append("WEB_WORKER_HEALTH_INVALID")
            continue
        seen_workers.add(worker_id)
        if snapshot.get("running") is not True:
            reasons.append("WEB_COLLECTOR_NOT_RUNNING")
        updated_at = integer_number(snapshot.get("updatedAt"), "WEB_WORKER_HEALTH_INVALID")
        lineage: dict[str, Any] = {}
        if capture_mode == "capture-directory":
            run_id = snapshot.get("runId")
            if (
                not isinstance(run_id, str)
                or not run_id
                or len(run_id) > 128
                or any(ord(character) < 32 for character in run_id)
            ):
                raise CollectionError("WEB_WORKER_LINEAGE_INVALID")
            watermark = integer_number(
                snapshot.get("summaryCommittedBeforeMs"), "WEB_WORKER_LINEAGE_INVALID"
            )
            if watermark % 60_000 != 0:
                raise CollectionError("WEB_WORKER_LINEAGE_INVALID")
            lineage = {
                "runId": run_id,
                "summaryCommittedBeforeMs": watermark,
            }
        age_ms = now_ms - updated_at
        collector = required_section(snapshot, "collector", ("dropped", "sinkFailures"))
        writer = required_section(
            snapshot, "writer", ("writeFailures", "serializeFailures", "filesDropped")
        )
        aggregator_fields = (
            ("overflowed", "lateRecords")
            if capture_mode == "capture-directory"
            else ("overflowed",)
        )
        aggregator = required_section(snapshot, "aggregator", aggregator_fields)
        redundancy = required_section(snapshot, "redundancy", ("pendingDropped",))
        dropped = collector["dropped"]
        sink_failures = collector["sinkFailures"]
        write_failures = writer["writeFailures"]
        serialize_failures = writer["serializeFailures"]
        files_dropped = writer["filesDropped"]
        overflowed = aggregator["overflowed"]
        late_records = aggregator.get("lateRecords", 0)
        pending_dropped = redundancy["pendingDropped"]
        if age_ms < -30_000 or age_ms > args.freshness_seconds * 1000:
            reasons.append("WEB_WORKER_STALE")
        if (
            dropped
            or sink_failures
            or write_failures
            or serialize_failures
            or files_dropped
            or overflowed
            or late_records
            or pending_dropped
        ):
            reasons.append("WEB_COLLECTOR_DEGRADED")
        worker_health.append(
            {
                "workerId": worker_id,
                **lineage,
                "updatedAt": updated_at,
                "ageMs": age_ms,
                "collectorDropped": dropped,
                "collectorSinkFailures": sink_failures,
                "writerWriteFailures": write_failures,
                "writerSerializeFailures": serialize_failures,
                "writerFilesDropped": files_dropped,
                "aggregatorOverflowed": overflowed,
                "aggregatorLateRecords": late_records,
                "redundancyPendingDropped": pending_dropped,
            }
        )
    if capture_mode != "capture-directory":
        if args.minimum_window_seconds and (
            coverage_seconds < args.minimum_window_seconds
            or timeline_evidence["minuteCount"] < math.ceil(args.minimum_window_seconds / 60)
        ):
            reasons.append("WEB_TRAFFIC_WINDOW_INCOMPLETE")
        if timeline_evidence["maxGapSeconds"] > args.maximum_gap_seconds:
            reasons.append("WEB_TRAFFIC_WINDOW_DISCONTINUITY")
    worker_coverage = normalize_worker_coverage(
        payload.get("_workerCoverage", {}), seen_workers, capture_mode
    )
    worker_minutes = payload.get("_workerMinuteEvidence", {})
    normalized_worker_evidence: dict[str, dict[str, Any]] = {}
    if capture_mode == "capture-directory":
        if (
            not isinstance(worker_coverage, dict)
            or not isinstance(worker_minutes, dict)
            or set(worker_coverage) != seen_workers
            or set(worker_minutes) != seen_workers
        ):
            raise CollectionError("WEB_WORKER_EVIDENCE_INVALID")
        for worker_id in sorted(seen_workers):
            raw_minutes = worker_minutes[worker_id]
            if not isinstance(raw_minutes, list):
                raise CollectionError("WEB_WORKER_EVIDENCE_INVALID")
            evidence = minute_evidence(raw_minutes, args.maximum_gap_seconds)
            if worker_coverage[worker_id] != evidence["coverageSeconds"]:
                raise CollectionError("WEB_WORKER_EVIDENCE_INVALID")
            normalized_worker_evidence[worker_id] = evidence
    elif args.minimum_window_seconds:
        if args.expected_workers > 1:
            reasons.append("WEB_WORKER_WINDOW_COMPLETENESS_UNPROVABLE")
        else:
            normalized_worker_evidence = {
                next(iter(seen_workers), "worker"): timeline_evidence
            }
    summary_lineage_mode = ""
    summary_target_before_ms = 0
    legacy_summary_rows_ignored = 0
    foreign_summary_rows_ignored = 0
    if capture_mode == "capture-directory":
        summary_lineage_mode = payload.get("_summaryLineageMode")
        if summary_lineage_mode != "current-run-only":
            raise CollectionError("WEB_SUMMARY_LINEAGE_INVALID")
        summary_target_before_ms = integer_number(
            payload.get("_summaryTargetBeforeMs"), "WEB_SUMMARY_WATERMARK_INVALID"
        )
        if summary_target_before_ms % 60_000 != 0:
            raise CollectionError("WEB_SUMMARY_WATERMARK_INVALID")
        legacy_summary_rows_ignored = integer_number(
            payload.get("_legacySummaryRowsIgnored"), "WEB_SUMMARY_LINEAGE_INVALID"
        )
        foreign_summary_rows_ignored = integer_number(
            payload.get("_foreignSummaryRowsIgnored"), "WEB_SUMMARY_LINEAGE_INVALID"
        )
    reasons = list(dict.fromkeys(reasons))
    return (
        {
            "label": label,
            "reconciliation": normalized_reconciliation,
            "timeline": points,
            "coverageSeconds": coverage_seconds,
            "timelineEvidence": timeline_evidence,
            "captureMode": capture_mode,
            "summaryLineageMode": summary_lineage_mode,
            "summaryTargetBeforeMs": summary_target_before_ms,
            "legacySummaryRowsIgnored": legacy_summary_rows_ignored,
            "foreignSummaryRowsIgnored": foreign_summary_rows_ignored,
            "workerCoverageSeconds": worker_coverage,
            "workerMinuteEvidence": normalized_worker_evidence,
            "workerReconciliation": normalize_worker_reconciliation(
                payload.get("_workerReconciliation", {}), seen_workers, capture_mode
            ),
            "workers": sorted(worker_health, key=lambda row: row["workerId"]),
            "byCategory": normalize_rank_rows(payload.get("byCategory", [])),
            "byScope": normalize_rank_rows(payload.get("byScope", [])),
        },
        reasons,
    )


def normalize_web_reconciliation(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CollectionError("WEB_TRAFFIC_INVALID")
    normalized = {
        field: integer_number(value.get(field), "WEB_TRAFFIC_INVALID")
        for field in (
            "proxyWire",
            "noiseFrame",
            "nodePlain",
            "transportOverhead",
            "protocolOverhead",
        )
    }
    attributed_share = nonnegative_number(value.get("attributedShare"), "WEB_TRAFFIC_INVALID")
    if attributed_share > 1.5:
        raise CollectionError("WEB_TRAFFIC_INVALID")
    normalized["attributedShare"] = attributed_share
    return normalized


def normalize_worker_reconciliation(
    value: Any, workers: set[str], capture_mode: str
) -> dict[str, dict[str, Any]]:
    if capture_mode != "capture-directory":
        return {}
    if not isinstance(value, dict) or set(value) != workers:
        raise CollectionError("WEB_TRAFFIC_INVALID")
    return {
        worker: normalize_web_reconciliation(value[worker]) for worker in sorted(workers)
    }


def normalize_worker_coverage(
    value: Any, workers: set[str], capture_mode: str
) -> dict[str, int]:
    if capture_mode != "capture-directory":
        return {}
    if not isinstance(value, dict) or set(value) != workers:
        raise CollectionError("WEB_TRAFFIC_INVALID")
    return {
        worker: integer_number(value[worker], "WEB_TRAFFIC_INVALID")
        for worker in sorted(workers)
    }


def normalize_rank_rows(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        raise CollectionError("WEB_TRAFFIC_INVALID")
    rows = []
    for item in value:
        if not isinstance(item, dict) or set(item) != {
            "key",
            "up",
            "down",
            "total",
            "count",
            "share",
        }:
            raise CollectionError("WEB_TRAFFIC_INVALID")
        key = item["key"]
        if (
            not isinstance(key, str)
            or not 0 < len(key) <= 128
            or any(ord(character) < 32 for character in key)
            or PRIVATE_DIMENSION.search(key) is not None
        ):
            raise CollectionError("WEB_TRAFFIC_PRIVATE_DIMENSION")
        up = integer_number(item["up"], "WEB_TRAFFIC_INVALID")
        down = integer_number(item["down"], "WEB_TRAFFIC_INVALID")
        total = integer_number(item["total"], "WEB_TRAFFIC_INVALID")
        count = integer_number(item["count"], "WEB_TRAFFIC_INVALID")
        share = nonnegative_number(item["share"], "WEB_TRAFFIC_INVALID")
        if up + down != total or share > 1:
            raise CollectionError("WEB_TRAFFIC_INVALID")
        rows.append(
            {"key": key, "up": up, "down": down, "total": total, "count": count, "share": share}
        )
    return rows


def required_section(
    snapshot: dict[str, Any], name: str, fields: tuple[str, ...]
) -> dict[str, int]:
    section = snapshot.get(name)
    if not isinstance(section, dict) or any(field not in section for field in fields):
        raise CollectionError("WEB_WORKER_HEALTH_INVALID")
    return {
        field: integer_number(section[field], "WEB_WORKER_HEALTH_INVALID")
        for field in fields
    }


def minute_evidence(minutes: list[Any], maximum_gap_seconds: int) -> dict[str, Any]:
    normalized = []
    for minute in minutes:
        value = integer_number(minute, "WEB_TRAFFIC_INVALID")
        if value % 60_000 != 0:
            raise CollectionError("WEB_TRAFFIC_INVALID")
        normalized.append(value)
    normalized.sort()
    if len(normalized) != len(set(normalized)):
        raise CollectionError("WEB_TRAFFIC_INVALID")
    gaps = [
        (right - left) // 1000
        for left, right in zip(normalized, normalized[1:])
    ]
    max_gap = max(gaps, default=0)
    coverage = 0 if not normalized else (normalized[-1] - normalized[0]) // 1000 + 60
    return {
        "minutes": normalized,
        "minuteCount": len(normalized),
        "coverageSeconds": coverage,
        "maxGapSeconds": max_gap,
        "continuous": max_gap <= maximum_gap_seconds,
    }


def parse_android_traffic(args: argparse.Namespace) -> dict[str, Any]:
    result = base_result("android-traffic", args)
    result["semantics"] = {
        "proxyBytes": "cumulative application proxy-socket bytes per Android node",
        "cloudBilling": False,
        "warning": "values are not EC2 TCP counters or AWS/cloud invoice bytes",
        "deltaRule": "subtract start from end only when cumulativeStartedAt is unchanged and counters are monotonic",
        "nodeIsolation": "each node remains separate; aggregate is an arithmetic convenience only",
    }
    try:
        targets, fixtures = load_sources(args, "android_traffic")
        expected_node_ids = unique_mappings(args.expected_node_id, "android_node_id")
        if (
            expected_node_ids
            and (
                len(expected_node_ids) != args.expected_targets
                or len(set(expected_node_ids.values())) != len(expected_node_ids)
                or any(android_dimension(value) != value for value in expected_node_ids.values())
            )
        ):
            raise CollectionError("INVALID_ANDROID_NODE_ID_MAPPING")
    except CollectionError as error:
        add_check(result, "android-inputs", False, error.code)
        return result
    labels = sorted(set(targets) | set(fixtures))
    if len(labels) != args.expected_targets:
        add_check(result, "android-target-count", False, "ANDROID_TARGET_COUNT_MISMATCH")
    else:
        add_check(result, "android-target-count", True)

    now = dt.datetime.now(dt.timezone.utc) if args.now is None else parse_timestamp(args.now)
    nodes: list[dict[str, Any]] = []
    aggregate = {"up": 0, "down": 0, "total": 0}
    for label in labels:
        try:
            payload = read_json(fixtures[label]) if label in fixtures else fetch_json(targets[label], args.timeout_seconds)
            node, reasons = normalize_android_payload(label, payload, args, now)
            nodes.append(node)
            aggregate["up"] += node["proxy"]["up"]
            aggregate["down"] += node["proxy"]["down"]
            aggregate["total"] += node["proxy"]["total"]
            add_check(result, f"android-traffic-{label}", not reasons, reasons[0] if reasons else "")
            for reason in reasons[1:]:
                add_check(result, f"android-traffic-{label}-{reason.lower()}", False, reason)
        except CollectionError as error:
            add_check(result, f"android-traffic-{label}", False, error.code)
    node_ids = [node["nodeId"] for node in nodes]
    add_check(
        result,
        "android-node-identities",
        len(node_ids) == args.expected_targets and len(set(node_ids)) == len(node_ids),
        "ANDROID_NODE_ID_SET_INVALID",
    )
    if expected_node_ids:
        observed_node_ids = {node["label"]: node["nodeId"] for node in nodes}
        add_check(
            result,
            "android-node-id-mapping",
            observed_node_ids == expected_node_ids,
            "ANDROID_NODE_ID_MISMATCH",
        )
    result["raw"] = {"nodes": nodes, "aggregateProxy": aggregate}
    return result


def parse_timestamp(raw: str) -> dt.datetime:
    try:
        parsed = dt.datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError as error:
        raise CollectionError("INVALID_TIMESTAMP") from error
    if parsed.tzinfo is None:
        raise CollectionError("INVALID_TIMESTAMP")
    return parsed.astimezone(dt.timezone.utc)


def directional(raw: Any, code: str) -> dict[str, int]:
    if not isinstance(raw, dict):
        raise CollectionError(code)
    up = integer_number(raw.get("up"), code)
    down = integer_number(raw.get("down"), code)
    return {"up": up, "down": down, "total": up + down}


def android_dimension(raw: Any) -> str:
    if (
        not isinstance(raw, str)
        or not 0 < len(raw) <= 128
        or any(ord(character) < 32 for character in raw)
        or PRIVATE_DIMENSION.search(raw) is not None
    ):
        raise CollectionError("ANDROID_TRAFFIC_PRIVATE_DIMENSION")
    return raw


def normalize_android_payload(
    label: str, payload: Any, args: argparse.Namespace, now: dt.datetime
) -> tuple[dict[str, Any], list[str]]:
    node_id = label
    if isinstance(payload, dict) and ("Code" in payload or "Data" in payload):
        data = payload.get("Data")
        if (
            type(payload.get("Code")) is not int
            or payload["Code"] != 0
            or not isinstance(data, dict)
            or not isinstance(data.get("nodeId"), str)
            or SAFE_LABEL.fullmatch(data["nodeId"]) is None
            or not isinstance(data.get("traffic"), dict)
            or not isinstance(data["traffic"].get("health"), dict)
        ):
            raise CollectionError("ANDROID_TRAFFIC_INVALID")
        node_id = android_dimension(data["nodeId"])
        snapshot = {name: value for name, value in data["traffic"].items() if name != "health"}
        health = data["traffic"]["health"]
    elif isinstance(payload, dict):
        snapshot = payload.get("snapshot")
        health = payload.get("health")
    else:
        snapshot = None
        health = None
    if not isinstance(snapshot, dict) or not isinstance(health, dict):
        raise CollectionError("ANDROID_TRAFFIC_INVALID")
    at = parse_timestamp(str(snapshot.get("at", "")))
    age_seconds = (now - at).total_seconds()
    run_id = snapshot.get("runId")
    schema_version = integer_number(snapshot.get("schemaVersion"), "ANDROID_TRAFFIC_INVALID")
    if schema_version != 1 or not isinstance(run_id, str) or not run_id:
        raise CollectionError("ANDROID_TRAFFIC_INVALID")
    proxy = directional(snapshot.get("proxy"), "ANDROID_TRAFFIC_INVALID")
    gap = directional(snapshot.get("reconciliationGap", {}), "ANDROID_TRAFFIC_INVALID")
    categories_raw = snapshot.get("categories")
    scopes_raw = snapshot.get("scopes", {})
    if not isinstance(categories_raw, dict) or not isinstance(scopes_raw, dict):
        raise CollectionError("ANDROID_TRAFFIC_INVALID")
    categories = {
        android_dimension(name): directional(value, "ANDROID_TRAFFIC_INVALID")
        for name, value in categories_raw.items()
    }
    scopes = {
        android_dimension(name): directional(value, "ANDROID_TRAFFIC_INVALID")
        for name, value in scopes_raw.items()
    }
    category_up = sum(value["up"] for value in categories.values())
    category_down = sum(value["down"] for value in categories.values())
    reasons: list[str] = []
    if category_up != proxy["up"] or category_down != proxy["down"]:
        reasons.append("ANDROID_RECONCILIATION_FAILED")
    if age_seconds < -30 or age_seconds > args.freshness_seconds:
        reasons.append("ANDROID_TRAFFIC_STALE")
    required_health_fields = (
        "cumulativeStartedAt",
        "continuous",
        "retentionSeconds",
        "stopped",
        "droppedEvents",
        "classificationErrors",
        "eventDetailDisabled",
        "persistenceDisabled",
    )
    if any(field not in health for field in required_health_fields):
        raise CollectionError("ANDROID_TRAFFIC_INVALID")
    for field in ("continuous", "stopped", "eventDetailDisabled", "persistenceDisabled"):
        if not isinstance(health[field], bool):
            raise CollectionError("ANDROID_TRAFFIC_INVALID")
    dropped = integer_number(health["droppedEvents"], "ANDROID_TRAFFIC_INVALID")
    classification_errors = integer_number(health["classificationErrors"], "ANDROID_TRAFFIC_INVALID")
    retention = integer_number(health["retentionSeconds"], "ANDROID_TRAFFIC_INVALID")
    if health["stopped"] or health["persistenceDisabled"] or health["eventDetailDisabled"]:
        reasons.append("ANDROID_COLLECTOR_UNHEALTHY")
    if not health["continuous"]:
        reasons.append("ANDROID_COLLECTOR_NOT_CONTINUOUS")
    if dropped or classification_errors:
        reasons.append("ANDROID_COLLECTOR_DEGRADED")
    if args.minimum_retention_seconds and retention < args.minimum_retention_seconds:
        reasons.append("ANDROID_RETENTION_INSUFFICIENT")
    cumulative_started_at = parse_timestamp(str(health["cumulativeStartedAt"]))
    lineage_age_seconds = (now - cumulative_started_at).total_seconds()
    if lineage_age_seconds < -30:
        reasons.append("ANDROID_CUMULATIVE_LINEAGE_INVALID")
    if args.minimum_retention_seconds and lineage_age_seconds < args.minimum_retention_seconds:
        reasons.append("ANDROID_LINEAGE_TOO_YOUNG")
    return (
        {
            "label": label,
            "nodeId": node_id,
            "schemaVersion": schema_version,
            "runId": run_id,
            "at": at.isoformat().replace("+00:00", "Z"),
            "ageSeconds": round(age_seconds, 3),
            "cumulativeStartedAt": cumulative_started_at.isoformat().replace("+00:00", "Z"),
            "lineageAgeSeconds": round(lineage_age_seconds, 3),
            "checkpointRestored": bool(health.get("checkpointRestored", False)),
            "continuous": health["continuous"],
            "retentionSeconds": retention,
            "stopped": health["stopped"],
            "proxy": proxy,
            "reconciliationGap": gap,
            "categories": categories,
            "scopes": scopes,
            "droppedEvents": dropped,
            "classificationErrors": classification_errors,
            "eventDetailDisabled": health["eventDetailDisabled"],
            "persistenceDisabled": health["persistenceDisabled"],
        },
        list(dict.fromkeys(reasons)),
    )


def add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--environment", default="test1")
    parser.add_argument("--phase", required=True, choices=PHASES)
    parser.add_argument("--label", default="default")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--candidate-manifest-sha256", required=True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="adapter", required=True)

    host = subparsers.add_parser("host")
    add_common(host)
    host.add_argument("--proc-root", default="/proc")
    host.add_argument("--proc-stat-before")
    host.add_argument("--proc-stat-after")
    host.add_argument("--meminfo")
    host.add_argument("--sample-ms", type=int, default=1000)
    host.add_argument("--container", action="append", default=[])
    host.add_argument("--docker-bin")
    host.add_argument("--docker-stats-file")
    host.add_argument("--docker-inspect-file")
    host.add_argument("--process", action="append", default=[])
    host.add_argument("--pm2-bin")
    host.add_argument("--pm2-jlist-file")
    host.set_defaults(handler=parse_host)

    web = subparsers.add_parser("web-traffic")
    add_common(web)
    web.add_argument("--target", action="append", default=[], help="label=http(s)://.../api/overview")
    web.add_argument("--json-file", action="append", default=[], help="fixture label=path")
    web.add_argument("--capture-directory", action="append", default=[], help="label=/absolute/traffic-capture")
    web.add_argument("--expected-workers", type=int, default=5)
    web.add_argument("--freshness-seconds", type=int, default=30)
    web.add_argument("--minimum-window-seconds", type=int, default=0)
    web.add_argument("--maximum-gap-seconds", type=int, default=60)
    web.add_argument("--timeout-seconds", type=float, default=8)
    web.add_argument("--now-ms", type=int)
    web.set_defaults(handler=parse_web_traffic)

    android = subparsers.add_parser("android-traffic")
    add_common(android)
    android.add_argument(
        "--target",
        action="append",
        default=[],
        help="label=http(s)://.../ws/v1/traffic/snapshot",
    )
    android.add_argument("--json-file", action="append", default=[], help="fixture label=path")
    android.add_argument("--expected-targets", type=int, default=3)
    android.add_argument(
        "--expected-node-id",
        action="append",
        default=[],
        help="fixed endpoint label=nodeId mapping",
    )
    android.add_argument("--freshness-seconds", type=int, default=30)
    android.add_argument("--minimum-retention-seconds", type=int, default=0)
    android.add_argument("--timeout-seconds", type=float, default=8)
    android.add_argument("--now", help="fixture clock as RFC3339")
    android.set_defaults(handler=parse_android_traffic)
    return parser


def validate_args(args: argparse.Namespace) -> None:
    if not SAFE_LABEL.fullmatch(args.environment):
        raise CollectionError("INVALID_ENVIRONMENT")
    if not SAFE_LABEL.fullmatch(args.label):
        raise CollectionError("INVALID_SOURCE_LABEL")
    if not SAFE_LABEL.fullmatch(args.run_id):
        raise CollectionError("INVALID_RUN_ID")
    if EVIDENCE_SHA256.fullmatch(args.candidate_manifest_sha256) is None:
        raise CollectionError("INVALID_CANDIDATE_MANIFEST_SHA256")
    for name in ("minimum_window_seconds", "minimum_retention_seconds"):
        if hasattr(args, name) and getattr(args, name) < 0:
            raise CollectionError("INVALID_ARGUMENT")
    for name in (
        "sample_ms",
        "expected_workers",
        "freshness_seconds",
        "maximum_gap_seconds",
        "expected_targets",
    ):
        if hasattr(args, name) and getattr(args, name) <= 0:
            raise CollectionError("INVALID_ARGUMENT")
    if hasattr(args, "timeout_seconds") and args.timeout_seconds <= 0:
        raise CollectionError("INVALID_ARGUMENT")
    if getattr(args, "container", None):
        if any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", name) for name in args.container):
            raise CollectionError("INVALID_CONTAINER")
    if getattr(args, "process", None):
        if any(
            not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", name)
            for name in args.process
        ):
            raise CollectionError("INVALID_PROCESS")


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        validate_args(args)
        return emit(args.handler(args))
    except CollectionError as error:
        result = base_result(str(args.adapter), args)
        add_check(result, "collector-setup", False, error.code)
        return emit(result)


if __name__ == "__main__":
    sys.exit(main())
