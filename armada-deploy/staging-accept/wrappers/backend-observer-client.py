#!/usr/bin/env python3
"""Capture one fixed test1 backend host observation for the current Runner stage."""

from __future__ import annotations

import grp
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


EXIT_BLOCKED = 40
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_SNAPSHOT_BYTES = 1024 * 1024
MAX_OUTPUT_BYTES = 16 * 1024 * 1024
MAX_SNAPSHOT_AGE_SECONDS = 90
COMMAND_TIMEOUT_SECONDS = 30
RUN_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
SNAPSHOT_GENERATION = re.compile(r"^[0-9a-f]{32}$")
STAGE_PHASES = {
    "observe-start": "start",
    "observe-peak": "peak",
    "observe-end": "end",
}
ALLOWED_CONTAINERS = (
    "armada-backend",
    "armada-nginx",
    "zhuan-native-probe-mysql",
)
RUN_ROOT = Path("/var/lib/staging-accept/runs")
PYTHON = Path("/usr/bin/python3")
COLLECTOR = Path(
    "/usr/local/libexec/staging-accept/scripts/observability/collect.py"
)
STATS_FILE = Path("/run/staging-accept/docker-stats.jsonl")
INSPECT_FILE = Path("/run/staging-accept/docker-inspect.jsonl")
PRIVATE_KEY = re.compile(
    r"(?:password|passwd|authorization|credential|privatekey|secret|token|cookie)",
    re.IGNORECASE,
)
PRIVATE_STRING = re.compile(
    r"(?:[A-Za-z][A-Za-z0-9+.-]*://|@|-----BEGIN[^\n]*PRIVATE KEY-----)"
)


class ClientError(Exception):
    """Sanitized backend observer failure."""


@dataclass(frozen=True)
class Config:
    run_root: Path
    python: Path
    collector: Path
    stats_file: Path
    inspect_file: Path
    snapshot_owner: tuple[int, int]


def production_config() -> Config:
    try:
        group_id = grp.getgrnam("staging-accept").gr_gid
    except KeyError as error:
        raise ClientError("observer group unavailable") from error
    return Config(RUN_ROOT, PYTHON, COLLECTOR, STATS_FILE, INSPECT_FILE, (0, group_id))


def regular_file(
    path: Path,
    *,
    max_bytes: int,
    mode: int | None = None,
    owner: tuple[int, int] | None = None,
) -> os.stat_result:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise ClientError("required file unavailable") from error
    if (
        not path.is_absolute()
        or stat.S_ISLNK(metadata.st_mode)
        or not stat.S_ISREG(metadata.st_mode)
        or metadata.st_size <= 0
        or metadata.st_size > max_bytes
        or mode is not None
        and stat.S_IMODE(metadata.st_mode) != mode
        or owner is not None
        and (metadata.st_uid, metadata.st_gid) != owner
    ):
        raise ClientError("required file invalid")
    return metadata


def load_context(environment: Mapping[str, str], config: Config) -> tuple[Path, str, str]:
    run_id = environment.get("STAGING_ACCEPT_RUN_ID", "")
    stage_id = environment.get("STAGING_ACCEPT_STAGE_ID", "")
    run_directory_raw = environment.get("STAGING_ACCEPT_RUN_DIR", "")
    phase = STAGE_PHASES.get(stage_id)
    if RUN_ID.fullmatch(run_id) is None or phase is None or not run_directory_raw:
        raise ClientError("Runner context invalid")
    run_directory = Path(run_directory_raw)
    try:
        resolved_root = config.run_root.resolve(strict=True)
        resolved_run = run_directory.resolve(strict=True)
        metadata = run_directory.lstat()
    except OSError as error:
        raise ClientError("Runner path invalid") from error
    if (
        not run_directory.is_absolute()
        or resolved_root != config.run_root
        or resolved_run != run_directory
        or resolved_run.parent != resolved_root
        or resolved_run.name != run_id
        or stat.S_ISLNK(metadata.st_mode)
        or not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) != 0o700
    ):
        raise ClientError("Runner path invalid")
    return run_directory, run_id, phase


def candidate_hash(run_directory: Path) -> str:
    manifest = run_directory / "candidate-manifest.json"
    before = regular_file(
        manifest,
        max_bytes=MAX_MANIFEST_BYTES,
        mode=0o600,
        owner=(os.geteuid(), os.getegid()),
    )
    try:
        content = manifest.read_bytes()
        payload = json.loads(content)
        after = manifest.stat()
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ClientError("candidate manifest invalid") from error
    identity_before = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    identity_after = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    build_keys = {"backend", "frontend", "webProtocol", "androidProtocol"}
    builds = payload.get("builds") if isinstance(payload, dict) else None
    if (
        identity_before != identity_after
        or not isinstance(payload, dict)
        or set(payload) != {
            "schemaVersion",
            "profile",
            "environment",
            "safety",
            "builds",
        }
        or payload.get("schemaVersion") != 1
        or payload.get("profile") != "test1-quick"
        or payload.get("environment") != "test1"
        or payload.get("safety") != "read-only"
        or not isinstance(builds, dict)
        or set(builds) != build_keys
        or any(not isinstance(value, str) or FULL_SHA.fullmatch(value) is None for value in builds.values())
    ):
        raise ClientError("candidate manifest invalid")
    return "sha256:" + hashlib.sha256(content).hexdigest()


def load_snapshot(path: Path, owner: tuple[int, int]) -> tuple[bytes, str]:
    metadata = regular_file(path, max_bytes=MAX_SNAPSHOT_BYTES, mode=0o640, owner=owner)
    age_seconds = time.time() - metadata.st_mtime
    if age_seconds < -5 or age_seconds > MAX_SNAPSHOT_AGE_SECONDS:
        raise ClientError("backend snapshot stale")
    try:
        content = path.read_bytes()
        after = path.stat()
        rows = [json.loads(line) for line in content.decode("utf-8").splitlines() if line]
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ClientError("backend snapshot invalid") from error
    identity_before = (metadata.st_dev, metadata.st_ino, metadata.st_size, metadata.st_mtime_ns)
    identity_after = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    generations = {
        row.get("snapshotGeneration")
        for row in rows
        if isinstance(row, dict)
    }
    if (
        identity_before != identity_after
        or len(rows) != len(ALLOWED_CONTAINERS)
        or len(generations) != 1
    ):
        raise ClientError("backend snapshot invalid")
    generation = next(iter(generations))
    if not isinstance(generation, str) or SNAPSHOT_GENERATION.fullmatch(generation) is None:
        raise ClientError("backend snapshot invalid")
    return content, generation


def collector_argv(
    config: Config,
    run_id: str,
    phase: str,
    manifest_hash: str,
    stats_file: Path,
    inspect_file: Path,
) -> list[str]:
    command = [
        str(config.python),
        str(config.collector),
        "host",
        "--environment",
        "test1",
        "--phase",
        phase,
        "--label",
        "backend",
        "--run-id",
        run_id,
        "--candidate-manifest-sha256",
        manifest_hash,
        "--docker-stats-file",
        str(stats_file),
        "--docker-inspect-file",
        str(inspect_file),
    ]
    for name in ALLOWED_CONTAINERS:
        command.extend(("--container", name))
    return command


def reject_private(value: Any) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or PRIVATE_KEY.search(key):
                raise ClientError("collector output invalid")
            reject_private(item)
    elif isinstance(value, list):
        for item in value:
            reject_private(item)
    elif isinstance(value, str) and PRIVATE_STRING.search(value):
        raise ClientError("collector output invalid")


def parse_payload(raw: bytes, run_id: str, phase: str, manifest_hash: str) -> dict[str, Any]:
    if not raw or len(raw) > MAX_OUTPUT_BYTES or b"\x00" in raw:
        raise ClientError("collector output invalid")
    try:
        lines = raw.decode("utf-8").splitlines()
        payload = json.loads(lines[0]) if len(lines) == 1 and lines[0] else None
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ClientError("collector output invalid") from error
    expected = {
        "schemaVersion": 1,
        "collector": "host-resource",
        "environment": "test1",
        "phase": phase,
        "runId": run_id,
        "candidateManifestSha256": manifest_hash,
        "provenance": "fixture",
        "source": "backend",
    }
    if not isinstance(payload, dict) or any(payload.get(key) != value for key, value in expected.items()):
        raise ClientError("collector identity invalid")
    status_value = payload.get("status")
    health = payload.get("health")
    if (
        status_value not in ("COLLECTED", "BLOCKED")
        or not isinstance(health, dict)
        or health.get("ok") is not (status_value == "COLLECTED")
    ):
        raise ClientError("collector status invalid")
    reject_private(payload)
    payload["provenance"] = "live"
    return payload


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
            raise ClientError("evidence path invalid")
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
    except ClientError:
        raise
    except OSError as error:
        raise ClientError("evidence write failed") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def execute(environment: Mapping[str, str], config: Config) -> int:
    run_directory, run_id, phase = load_context(environment, config)
    manifest_hash = candidate_hash(run_directory)
    if not config.python.is_absolute() or not os.access(config.python, os.X_OK):
        raise ClientError("Python unavailable")
    regular_file(config.collector, max_bytes=MAX_OUTPUT_BYTES)
    stats_content, stats_generation = load_snapshot(config.stats_file, config.snapshot_owner)
    inspect_content, inspect_generation = load_snapshot(
        config.inspect_file, config.snapshot_owner
    )
    if stats_generation != inspect_generation:
        raise ClientError("backend snapshot generation mismatch")
    temporary_paths: list[Path] = []
    try:
        for label, content in (("stats", stats_content), ("inspect", inspect_content)):
            with tempfile.NamedTemporaryFile(
                mode="wb",
                dir=run_directory,
                prefix=f".backend-{label}-snapshot.",
                delete=False,
            ) as handle:
                temporary_paths.append(Path(handle.name))
                handle.write(content)
                handle.flush()
                os.fchmod(handle.fileno(), 0o600)
                os.fsync(handle.fileno())
        completed = subprocess.run(
            collector_argv(
                config,
                run_id,
                phase,
                manifest_hash,
                temporary_paths[0],
                temporary_paths[1],
            ),
            cwd="/",
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=COMMAND_TIMEOUT_SECONDS,
            env={
                "LANG": "C.UTF-8",
                "LC_ALL": "C.UTF-8",
                "PATH": "/usr/bin:/bin",
                "PYTHONNOUSERSITE": "1",
            },
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ClientError("collector execution failed") from error
    finally:
        for path in temporary_paths:
            path.unlink(missing_ok=True)
    if completed.returncode not in (0, 2):
        raise ClientError("collector execution failed")
    payload = parse_payload(completed.stdout, run_id, phase, manifest_hash)
    expected_status = 0 if payload["status"] == "COLLECTED" else 2
    if completed.returncode != expected_status:
        raise ClientError("collector status invalid")
    write_atomic(
        run_directory / "observability" / f"host-backend-{phase}.json",
        payload,
    )
    return expected_status


def main() -> int:
    if len(sys.argv) != 1:
        print("backend-observer-client: observation blocked", file=sys.stderr)
        return EXIT_BLOCKED
    try:
        return execute(os.environ, production_config())
    except ClientError:
        print("backend-observer-client: observation blocked", file=sys.stderr)
        return EXIT_BLOCKED


if __name__ == "__main__":
    sys.exit(main())
