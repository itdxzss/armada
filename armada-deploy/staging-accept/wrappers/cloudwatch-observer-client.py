#!/usr/bin/env python3
"""Capture the exact fixed test1 CloudWatch alarm set for a soak verify stage."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path
from typing import Any, Mapping


EXIT_FAIL = 30
EXIT_BLOCKED = 40
REGION = "ap-south-1"
RUN_ROOT = Path("/var/lib/staging-accept/runs")
RUN_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
STAGE_PHASES = {
    "verify-start": "start",
    "verify-peak": "peak",
    "verify-end": "end",
}
ALLOWED_PROFILES = {
    "test1-soak-1h",
    "test1-soak-6h",
    "test1-soak-24h",
}
TEST1_INSTANCES = {
    "i-06cf0d5fb86263860": "backend-runner",
    "i-03580d2585e074fec": "web-protocol",
    "i-06cb773a74046490e": "android-node1",
    "i-09aeea3efcb15f725": "android-node2",
    "i-015fe1e6c542d7e06": "android-node3",
}
ALARM_SIGNALS = {
    ("AWS/EC2", "CPUUtilization"): "cpu",
    ("AWS/EC2", "StatusCheckFailed"): "status-check",
    ("Armada/Test1", "mem_used_percent"): "memory",
}
ALARM_NAMES = (
    "Armada-test1-backend-runner-CPUHigh",
    "Armada-test1-backend-runner-MemoryHigh",
    "Armada-test1-backend-runner-StatusCheckFailed",
    "Armada-test1-web-protocol-CPUHigh",
    "Armada-test1-web-protocol-MemoryHigh",
    "Armada-test1-web-protocol-StatusCheckFailed",
    "Armada-test1-android-node1-CPUHigh",
    "Armada-test1-android-node1-MemoryHigh",
    "Armada-test1-android-node1-StatusCheckFailed",
    "Armada-test1-android-node2-CPUHigh",
    "Armada-test1-android-node2-MemoryHigh",
    "Armada-test1-android-node2-StatusCheckFailed",
    "Armada-test1-android-node3-CPUHigh",
    "Armada-test1-android-node3-MemoryHigh",
    "Armada-test1-android-node3-StatusCheckFailed",
)


class ObserverError(Exception):
    """A sanitized CloudWatch observer failure."""


def create_client():
    import boto3
    from botocore.config import Config

    session = boto3.Session(region_name=REGION)
    return session.client(
        "cloudwatch",
        config=Config(
            retries={"total_max_attempts": 2, "mode": "standard"},
            connect_timeout=5,
            read_timeout=10,
        ),
    )


def load_context(
    environment: Mapping[str, str], run_root: Path
) -> tuple[Path, str, str]:
    run_id = environment.get("STAGING_ACCEPT_RUN_ID", "")
    phase = STAGE_PHASES.get(environment.get("STAGING_ACCEPT_STAGE_ID", ""))
    raw_run_dir = environment.get("STAGING_ACCEPT_RUN_DIR", "")
    if RUN_ID.fullmatch(run_id) is None or phase is None or not raw_run_dir:
        raise ObserverError("Runner context invalid")
    run_dir = Path(raw_run_dir)
    try:
        root_metadata = run_root.lstat()
        run_metadata = run_dir.lstat()
        resolved_root = run_root.resolve(strict=True)
        resolved_run = run_dir.resolve(strict=True)
    except OSError as error:
        raise ObserverError("Runner path invalid") from error
    if (
        not run_root.is_absolute()
        or not run_dir.is_absolute()
        or stat.S_ISLNK(root_metadata.st_mode)
        or not stat.S_ISDIR(root_metadata.st_mode)
        or stat.S_ISLNK(run_metadata.st_mode)
        or not stat.S_ISDIR(run_metadata.st_mode)
        or resolved_root != run_root
        or resolved_run != run_dir
        or resolved_run.parent != resolved_root
        or resolved_run.name != run_id
        or stat.S_IMODE(run_metadata.st_mode) != 0o700
        or run_metadata.st_uid != os.geteuid()
    ):
        raise ObserverError("Runner path invalid")
    return resolved_run, run_id, phase


def candidate_hash(run_dir: Path) -> str:
    path = run_dir / "candidate-manifest.json"
    try:
        before = path.lstat()
        content = path.read_bytes()
        payload = json.loads(content)
        after = path.stat()
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ObserverError("candidate manifest invalid") from error
    builds = payload.get("builds") if isinstance(payload, dict) else None
    if (
        stat.S_ISLNK(before.st_mode)
        or not stat.S_ISREG(before.st_mode)
        or before.st_uid != os.geteuid()
        or stat.S_IMODE(before.st_mode) != 0o600
        or before.st_size > 1024 * 1024
        or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        or not isinstance(payload, dict)
        or set(payload) != {"schemaVersion", "profile", "environment", "safety", "builds"}
        or payload.get("schemaVersion") != 1
        or payload.get("profile") not in ALLOWED_PROFILES
        or payload.get("environment") != "test1"
        or payload.get("safety") != "read-only"
        or not isinstance(builds, dict)
        or set(builds) != {"backend", "frontend", "webProtocol", "androidProtocol"}
        or any(not isinstance(value, str) or FULL_SHA.fullmatch(value) is None for value in builds.values())
    ):
        raise ObserverError("candidate manifest invalid")
    return "sha256:" + hashlib.sha256(content).hexdigest()


def alarm_rows(cloudwatch) -> list[dict[str, str]]:
    selected: dict[tuple[str, str], dict[str, str]] = {}
    response = cloudwatch.describe_alarms(
        AlarmNames=list(ALARM_NAMES), AlarmTypes=["MetricAlarm"]
    )
    alarms = response.get("MetricAlarms") if isinstance(response, dict) else None
    if not isinstance(alarms, list):
        raise ObserverError("CloudWatch response invalid")
    for alarm in alarms:
        if not isinstance(alarm, dict) or alarm.get("AlarmName") not in ALARM_NAMES:
            raise ObserverError("CloudWatch response invalid")
        dimensions = alarm.get("Dimensions")
        if not isinstance(dimensions, list):
            raise ObserverError("CloudWatch response invalid")
        instance_ids = [
            value.get("Value")
            for value in dimensions
            if isinstance(value, dict) and value.get("Name") == "InstanceId"
        ]
        if len(instance_ids) != 1 or instance_ids[0] not in TEST1_INSTANCES:
            raise ObserverError("CloudWatch response invalid")
        signal = ALARM_SIGNALS.get((alarm.get("Namespace"), alarm.get("MetricName")))
        if signal is None:
            raise ObserverError("CloudWatch response invalid")
        state = alarm.get("StateValue")
        if state not in ("OK", "ALARM", "INSUFFICIENT_DATA"):
            raise ObserverError("CloudWatch response invalid")
        key = (instance_ids[0], signal)
        if key in selected:
            raise ObserverError("CloudWatch alarm set invalid")
        selected[key] = {
            "instance": TEST1_INSTANCES[instance_ids[0]],
            "signal": signal,
            "state": state,
        }
    expected = {
        (instance_id, signal)
        for instance_id in TEST1_INSTANCES
        for signal in ALARM_SIGNALS.values()
    }
    if set(selected) != expected:
        raise ObserverError("CloudWatch alarm set invalid")
    return [selected[key] for key in sorted(selected)]


def write_atomic(path: Path, payload: dict[str, Any]) -> None:
    directory = path.parent
    temporary: Path | None = None
    try:
        directory.mkdir(mode=0o700, exist_ok=True)
        metadata = directory.lstat()
        if (
            directory.resolve(strict=True) != directory
            or stat.S_ISLNK(metadata.st_mode)
            or not stat.S_ISDIR(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o700
            or path.is_symlink()
        ):
            raise ObserverError("evidence path invalid")
        content = (
            json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode("utf-8")
        with tempfile.NamedTemporaryFile(
            mode="wb", dir=directory, prefix=f".{path.name}.", delete=False
        ) as handle:
            temporary = Path(handle.name)
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
        temporary = None
    except ObserverError:
        raise
    except OSError as error:
        raise ObserverError("evidence write failed") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def execute(
    environment: Mapping[str, str],
    cloudwatch=None,
    run_root: Path = RUN_ROOT,
) -> int:
    run_dir, run_id, phase = load_context(environment, run_root)
    manifest_hash = candidate_hash(run_dir)
    rows = alarm_rows(create_client() if cloudwatch is None else cloudwatch)
    payload = {
        "schemaVersion": 1,
        "collector": "cloudwatch-alarms",
        "environment": "test1",
        "phase": phase,
        "runId": run_id,
        "candidateManifestSha256": manifest_hash,
        "provenance": "live",
        "observedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "status": "COLLECTED",
        "health": {"ok": True, "checks": [], "blockedReasons": []},
        "region": REGION,
        "expectedAlarmCount": 15,
        "alarms": rows,
    }
    write_atomic(run_dir / "observability" / f"cloudwatch-{phase}.json", payload)
    states = {row["state"] for row in rows}
    if "ALARM" in states:
        return EXIT_FAIL
    if states != {"OK"}:
        return EXIT_BLOCKED
    return 0


def main() -> int:
    if len(sys.argv) != 1:
        print("cloudwatch-observer-client: observation blocked", file=sys.stderr)
        return EXIT_BLOCKED
    try:
        return execute(os.environ)
    except Exception:
        print("cloudwatch-observer-client: observation blocked", file=sys.stderr)
        return EXIT_BLOCKED


if __name__ == "__main__":
    sys.exit(main())
