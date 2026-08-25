#!/usr/bin/env python3
"""Observe explicit local runtime artifacts without reading Git or secret stores."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from typing import Any


FULL_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
SAFE_ROLE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
COMPONENTS = ("backend", "frontend", "webProtocol", "androidProtocol")
CHUNK_BYTES = 1024 * 1024


class ObservationError(Exception):
    pass


def full_sha(value: str, label: str) -> str:
    if FULL_SHA.fullmatch(value) is None:
        raise ObservationError(f"{label} must be a full Git commit")
    return value.lower()


def artifact_digest(path_value: str, label: str) -> str:
    path = Path(path_value)
    try:
        if not path.is_absolute() or not path.is_file():
            raise ObservationError(f"{label} runtime artifact is unavailable")
        before = path.stat()
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(CHUNK_BYTES), b""):
                digest.update(chunk)
        after = path.stat()
    except ObservationError:
        raise
    except OSError as error:
        raise ObservationError(f"{label} runtime artifact is unavailable") from error
    identity_before = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    identity_after = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    if identity_before != identity_after:
        raise ObservationError(f"{label} runtime artifact changed during observation")
    return f"sha256:{digest.hexdigest()}"


def android_mapping(raw: str) -> tuple[str, str]:
    role, separator, path = raw.partition("=")
    if not separator or SAFE_ROLE.fullmatch(role) is None or not path:
        raise ObservationError("Android artifact must use role=/absolute/path")
    return role, path


def write_atomic(path: Path, payload: dict[str, Any]) -> None:
    if not path.is_absolute() or not path.parent.is_dir():
        raise ObservationError("output must be an absolute path in an existing directory")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, prefix=f".{path.name}.", delete=False
        ) as handle:
            temporary = Path(handle.name)
            json.dump(payload, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except OSError as error:
        raise ObservationError("artifact observation could not be written atomically") from error
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def artifact(path: str, commit: str, observed_at: str, label: str) -> dict[str, str]:
    return {
        "kind": "artifact-sha256",
        "identity": artifact_digest(path, label),
        "observedCommit": full_sha(commit, f"{label} commit"),
        "observedAt": observed_at,
    }


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, choices=("test1", "perf2"))
    parser.add_argument("--output", required=True)
    parser.add_argument("--backend-artifact", required=True)
    parser.add_argument("--backend-commit", required=True)
    parser.add_argument("--frontend-artifact", required=True)
    parser.add_argument("--frontend-commit", required=True)
    parser.add_argument("--web-protocol-artifact", required=True)
    parser.add_argument("--web-protocol-commit", required=True)
    parser.add_argument("--android-artifact", action="append", required=True)
    parser.add_argument("--android-protocol-commit", required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        android = [android_mapping(value) for value in args.android_artifact]
        roles = [role for role, _ in android]
        if len(roles) != len(set(roles)):
            raise ObservationError("Android artifact roles contain duplicates")
        observed_at = dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")
        android_commit = full_sha(args.android_protocol_commit, "Android protocol commit")
        payload = {
            "schemaVersion": 1,
            "environment": args.environment,
            "components": {
                "backend": {
                    "artifact": artifact(
                        args.backend_artifact, args.backend_commit, observed_at, "backend"
                    )
                },
                "frontend": {
                    "artifact": artifact(
                        args.frontend_artifact, args.frontend_commit, observed_at, "frontend"
                    )
                },
                "webProtocol": {
                    "artifact": artifact(
                        args.web_protocol_artifact,
                        args.web_protocol_commit,
                        observed_at,
                        "Web protocol",
                    )
                },
                "androidProtocol": {
                    "artifacts": [
                        {
                            "role": role,
                            **artifact(path, android_commit, observed_at, f"Android {role}"),
                        }
                        for role, path in android
                    ]
                },
            },
        }
        write_atomic(Path(args.output), payload)
        return 0
    except ObservationError as error:
        print(f"ARTIFACT OBSERVATION BLOCKED {error}", file=sys.stderr)
        return 40


if __name__ == "__main__":
    sys.exit(main())
