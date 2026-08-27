#!/usr/bin/env python3
"""Build an atomic runtime manifest from explicit, already-observed artifact JSON."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from typing import Any


FULL_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
SHA256 = re.compile(r"^sha256:[0-9a-fA-F]{64}$")
SAFE_ROLE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
COMPONENTS = ("backend", "frontend", "webProtocol", "androidProtocol")
ARTIFACT_KINDS = {"artifact-sha256", "docker-image-id", "oci-image-digest", "runtime-revision"}
MAX_ARTIFACT_BYTES = 65536
MAX_FUTURE_SKEW_SECONDS = 30


class InputError(Exception):
    pass


def exact_object(value: Any, keys: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise InputError(f"{label} has an invalid shape")
    return value


def full_sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or FULL_SHA.fullmatch(value) is None:
        raise InputError(f"{label} must be a full Git commit")
    return value.lower()


def observed_at(value: Any, label: str, max_age_seconds: int, now: dt.datetime) -> str:
    if not isinstance(value, str):
        raise InputError(f"{label} must be an RFC3339 timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise InputError(f"{label} must be an RFC3339 timestamp") from error
    if parsed.tzinfo is None:
        raise InputError(f"{label} must include a timezone")
    age_seconds = (now - parsed.astimezone(dt.timezone.utc)).total_seconds()
    if age_seconds > max_age_seconds:
        raise InputError(f"{label} is stale")
    if age_seconds < -MAX_FUTURE_SKEW_SECONDS:
        raise InputError(f"{label} is too far in the future")
    return parsed.astimezone(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def validate_artifact(
    value: Any,
    label: str,
    role_required: bool,
    max_age_seconds: int,
    now: dt.datetime,
) -> None:
    fields = {"kind", "identity", "observedCommit", "observedAt"}
    if role_required:
        fields.add("role")
    artifact_value = exact_object(value, fields, label)
    observed = full_sha(artifact_value["observedCommit"], f"{label}.observedCommit")
    kind = artifact_value["kind"]
    identity = artifact_value["identity"]
    observed_at(artifact_value["observedAt"], f"{label}.observedAt", max_age_seconds, now)
    if not isinstance(kind, str) or kind not in ARTIFACT_KINDS:
        raise InputError(f"{label}.kind is unsupported")
    if kind == "runtime-revision":
        if full_sha(identity, f"{label}.identity") != observed:
            raise InputError(f"{label}.identity does not match observedCommit")
    elif not isinstance(identity, str) or SHA256.fullmatch(identity) is None:
        raise InputError(f"{label}.identity must be a SHA-256 identity")
    if role_required and (
        not isinstance(artifact_value["role"], str)
        or SAFE_ROLE.fullmatch(artifact_value["role"]) is None
    ):
        raise InputError(f"{label}.role is invalid")


def load_artifacts(
    path: Path, environment: str, max_age_seconds: int, now: dt.datetime
) -> dict[str, Any]:
    try:
        if not path.is_absolute() or path.stat().st_size > MAX_ARTIFACT_BYTES:
            raise InputError("artifact input must be a bounded absolute file")
        payload = json.loads(path.read_text(encoding="utf-8"))
    except InputError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise InputError("artifact input is not readable JSON") from error
    root = exact_object(payload, {"schemaVersion", "environment", "components"}, "artifact input")
    if (
        type(root["schemaVersion"]) is not int
        or root["schemaVersion"] != 1
        or not isinstance(root["environment"], str)
        or root["environment"] != environment
    ):
        raise InputError("artifact input environment or schema does not match")
    components = exact_object(root["components"], set(COMPONENTS), "artifact components")
    for name in COMPONENTS:
        expected_keys = {"artifacts"} if name == "androidProtocol" else {"artifact"}
        exact_object(components[name], expected_keys, f"artifact component {name}")
        if name == "androidProtocol":
            artifacts = components[name]["artifacts"]
            if not isinstance(artifacts, list) or not artifacts:
                raise InputError("Android artifacts must be a non-empty array")
            for index, artifact_value in enumerate(artifacts):
                validate_artifact(
                    artifact_value,
                    f"Android artifact {index}",
                    True,
                    max_age_seconds,
                    now,
                )
            roles = [artifact_value["role"] for artifact_value in artifacts]
            if len(roles) != len(set(roles)):
                raise InputError("Android artifact roles contain duplicates")
        else:
            validate_artifact(
                components[name]["artifact"],
                f"artifact component {name}",
                False,
                max_age_seconds,
                now,
            )
    return components


def write_atomic(path: Path, payload: dict[str, Any]) -> None:
    if not path.is_absolute() or not path.parent.is_dir():
        raise InputError("output must be an absolute path in an existing directory")
    temporary = None
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
        raise InputError("runtime manifest could not be written atomically") from error
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--artifacts", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--backend-sha", required=True)
    parser.add_argument("--frontend-sha", required=True)
    parser.add_argument("--web-protocol-sha", required=True)
    parser.add_argument("--android-protocol-sha", required=True)
    parser.add_argument("--max-age-seconds", required=True, type=int)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.max_age_seconds <= 0:
            raise InputError("max age must be a positive integer")
        now = dt.datetime.now(dt.timezone.utc)
        observed = load_artifacts(
            Path(args.artifacts), args.environment, args.max_age_seconds, now
        )
        requested = {
            "backend": full_sha(args.backend_sha, "backend SHA"),
            "frontend": full_sha(args.frontend_sha, "frontend SHA"),
            "webProtocol": full_sha(args.web_protocol_sha, "Web protocol SHA"),
            "androidProtocol": full_sha(args.android_protocol_sha, "Android protocol SHA"),
        }
        components = {}
        for name in COMPONENTS:
            components[name] = {"expectedCommit": requested[name], **observed[name]}
        manifest = {
            "schemaVersion": 1,
            "environment": args.environment,
            "generatedAt": now.isoformat().replace("+00:00", "Z"),
            "components": components,
        }
        write_atomic(Path(args.output), manifest)
        return 0
    except InputError as error:
        print(f"MANIFEST BLOCKED {error}", file=sys.stderr)
        return 40


if __name__ == "__main__":
    sys.exit(main())
