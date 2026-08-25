#!/usr/bin/env python3
"""Capture fixed test1 backend container resource and lifecycle snapshots."""

from __future__ import annotations

import grp
import json
import os
import stat
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Any


DOCKER_BIN = Path("/usr/bin/docker")
OUTPUT_DIR = Path("/run/staging-accept")
OUTPUT_GROUP = "staging-accept"
ALLOWED_CONTAINERS = (
    "armada-backend",
    "armada-nginx",
    "zhuan-native-probe-mysql",
)
STATS_FILE = "docker-stats.jsonl"
INSPECT_FILE = "docker-inspect.jsonl"
COMMAND_TIMEOUT_SECONDS = 10
INSPECT_TEMPLATE = (
    '{"name":{{json .Name}},"restartCount":{{json .RestartCount}},'
    '"oomKilled":{{json .State.OOMKilled}},"status":{{json .State.Status}},'
    '"startedAt":{{json .State.StartedAt}}}'
)
DOCKER_ENVIRONMENT = {
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "PATH": "/usr/bin:/bin",
}


class SnapshotError(RuntimeError):
    """Fail-closed snapshot error whose message contains no Docker output."""


def _run_docker(docker_bin: Path, arguments: list[str]) -> str:
    try:
        completed = subprocess.run(
            [str(docker_bin), *arguments],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="strict",
            timeout=COMMAND_TIMEOUT_SECONDS,
            env=DOCKER_ENVIRONMENT,
        )
    except (OSError, subprocess.SubprocessError, UnicodeError) as error:
        raise SnapshotError("docker command unavailable") from error
    if completed.returncode != 0:
        raise SnapshotError("docker command failed")
    return completed.stdout


def _parse_json_lines(raw: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        for line in raw.splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if not isinstance(row, dict):
                raise SnapshotError("docker output is not an object")
            rows.append(row)
    except json.JSONDecodeError as error:
        raise SnapshotError("docker output is not JSONL") from error
    return rows


def _normalize_stats(raw: str) -> list[dict[str, str]]:
    by_name: dict[str, dict[str, str]] = {}
    for source in _parse_json_lines(raw):
        name = source.get("Name") or source.get("Container")
        cpu = source.get("CPUPerc")
        memory_usage = source.get("MemUsage")
        memory_percent = source.get("MemPerc")
        if (
            not isinstance(name, str)
            or name not in ALLOWED_CONTAINERS
            or name in by_name
        ):
            raise SnapshotError("unexpected or duplicate container stats")
        resource_values = (cpu, memory_usage, memory_percent)
        if not all(isinstance(value, str) and value for value in resource_values):
            raise SnapshotError("incomplete container stats")
        by_name[name] = {
            "Name": name,
            "CPUPerc": cpu,
            "MemUsage": memory_usage,
            "MemPerc": memory_percent,
        }
    if set(by_name) != set(ALLOWED_CONTAINERS):
        raise SnapshotError("container stats allowlist is incomplete")
    return [by_name[name] for name in ALLOWED_CONTAINERS]


def _normalize_inspect(raw: str) -> list[dict[str, Any]]:
    by_name: dict[str, dict[str, Any]] = {}
    for source in _parse_json_lines(raw):
        raw_name = source.get("name")
        name = raw_name.removeprefix("/") if isinstance(raw_name, str) else ""
        restart_count = source.get("restartCount")
        oom_killed = source.get("oomKilled")
        status_value = source.get("status")
        started_at = source.get("startedAt")
        if name not in ALLOWED_CONTAINERS or name in by_name:
            raise SnapshotError("unexpected or duplicate container inspect row")
        if (
            isinstance(restart_count, bool)
            or not isinstance(restart_count, int)
            or restart_count < 0
        ):
            raise SnapshotError("invalid container restart count")
        if not isinstance(oom_killed, bool):
            raise SnapshotError("invalid container OOM state")
        if not isinstance(status_value, str) or not status_value:
            raise SnapshotError("invalid container lifecycle status")
        if not isinstance(started_at, str) or not started_at:
            raise SnapshotError("invalid container start time")
        by_name[name] = {
            "name": f"/{name}",
            "restartCount": restart_count,
            "oomKilled": oom_killed,
            "status": status_value,
            "startedAt": started_at,
        }
    if set(by_name) != set(ALLOWED_CONTAINERS):
        raise SnapshotError("container inspect allowlist is incomplete")
    return [by_name[name] for name in ALLOWED_CONTAINERS]


def _jsonl(rows: list[dict[str, Any]], generation: str) -> str:
    return "".join(
        json.dumps(
            {**row, "snapshotGeneration": generation},
            ensure_ascii=False,
            separators=(",", ":"),
        )
        + "\n"
        for row in rows
    )


def _prepare_output_directory(output_dir: Path, ownership: tuple[int, int] | None) -> None:
    try:
        output_dir.mkdir(mode=0o750, parents=True, exist_ok=True)
        metadata = output_dir.lstat()
    except OSError as error:
        raise SnapshotError("snapshot directory unavailable") from error
    if not stat.S_ISDIR(metadata.st_mode):
        raise SnapshotError("snapshot path is not a directory")
    try:
        if ownership is not None:
            os.chown(output_dir, *ownership)
        os.chmod(output_dir, 0o750)
    except OSError as error:
        raise SnapshotError("snapshot directory permissions unavailable") from error


def _atomic_write(path: Path, content: str, ownership: tuple[int, int] | None) -> None:
    descriptor = -1
    temporary_path = ""
    try:
        descriptor, temporary_path = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            descriptor = -1
            handle.write(content)
            handle.flush()
            os.fchmod(handle.fileno(), 0o640)
            if ownership is not None:
                os.fchown(handle.fileno(), *ownership)
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
        temporary_path = ""
    except OSError as error:
        raise SnapshotError("snapshot write failed") from error
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        if temporary_path:
            try:
                os.unlink(temporary_path)
            except FileNotFoundError:
                pass


def collect_and_write(
    *,
    docker_bin: Path,
    output_dir: Path,
    ownership: tuple[int, int] | None,
) -> None:
    stats_raw = _run_docker(
        docker_bin,
        ["stats", "--no-stream", "--format", "{{json .}}", *ALLOWED_CONTAINERS],
    )
    inspect_raw = _run_docker(
        docker_bin,
        ["inspect", "--format", INSPECT_TEMPLATE, *ALLOWED_CONTAINERS],
    )
    generation = uuid.uuid4().hex
    stats = _jsonl(_normalize_stats(stats_raw), generation)
    inspect_rows = _jsonl(_normalize_inspect(inspect_raw), generation)

    _prepare_output_directory(output_dir, ownership)
    _atomic_write(output_dir / STATS_FILE, stats, ownership)
    _atomic_write(output_dir / INSPECT_FILE, inspect_rows, ownership)


def main() -> int:
    if len(sys.argv) != 1:
        print("backend docker snapshot accepts no arguments", file=sys.stderr)
        return 2
    if os.geteuid() != 0:
        print("backend docker snapshot requires root", file=sys.stderr)
        return 2
    try:
        group_id = grp.getgrnam(OUTPUT_GROUP).gr_gid
        collect_and_write(
            docker_bin=DOCKER_BIN,
            output_dir=OUTPUT_DIR,
            ownership=(0, group_id),
        )
    except (KeyError, SnapshotError):
        print("backend docker snapshot failed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
