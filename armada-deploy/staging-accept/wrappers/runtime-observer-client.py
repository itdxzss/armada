#!/usr/bin/env python3
"""Copy the fixed test1 runtime manifest into the current Runner directory."""

from __future__ import annotations

import json
import os
import re
import stat
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


EXIT_BLOCKED = 40
MAX_MANIFEST_BYTES = 64 * 1024
RUN_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
SOURCE = Path("/var/lib/staging-accept/runtime-manifest-source.json")
RUN_ROOT = Path("/var/lib/staging-accept/runs")
TEST_MODE = "STAGING_ACCEPT_TEST_MODE"
TEST_SOURCE = "STAGING_ACCEPT_TEST_RUNTIME_MANIFEST_SOURCE"
TEST_RUN_ROOT = "STAGING_ACCEPT_TEST_RUN_ROOT"


class ClientError(Exception):
    """Sanitized runtime manifest copy failure."""


@dataclass(frozen=True)
class Config:
    source: Path
    run_root: Path
    source_owner: int
    test_mode: bool = False


def config_from_environment(environment: Mapping[str, str]) -> Config:
    test_mode = environment.get(TEST_MODE)
    overrides_present = TEST_SOURCE in environment or TEST_RUN_ROOT in environment
    if test_mode != "1":
        if test_mode is not None or overrides_present:
            raise ClientError("test override invalid")
        return Config(SOURCE, RUN_ROOT, 0)

    source = environment.get(TEST_SOURCE, "")
    run_root = environment.get(TEST_RUN_ROOT, "")
    if not source or not run_root:
        raise ClientError("test override invalid")
    return Config(Path(source), Path(run_root), os.geteuid(), True)


def read_bounded(fd: int, max_bytes: int) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while total <= max_bytes:
        chunk = os.read(fd, min(65536, max_bytes + 1 - total))
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)
        total += len(chunk)
    raise ClientError("manifest too large")


def source_bytes(config: Config) -> bytes:
    if not config.source.is_absolute():
        raise ClientError("source invalid")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(config.source, flags)
    except OSError as error:
        raise ClientError("source unavailable") from error
    try:
        before = os.fstat(fd)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_uid != config.source_owner
            or stat.S_IMODE(before.st_mode) & 0o022
            or before.st_size > MAX_MANIFEST_BYTES
        ):
            raise ClientError("source unsafe")
        content = read_bounded(fd, MAX_MANIFEST_BYTES)
        after = os.fstat(fd)
        identity_before = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        identity_after = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        if identity_before != identity_after:
            raise ClientError("source changed")
        return content
    except OSError as error:
        raise ClientError("source unreadable") from error
    finally:
        os.close(fd)


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ClientError("manifest JSON invalid")
        value[key] = item
    return value


def reject_json_constant(_: str) -> None:
    raise ClientError("manifest JSON invalid")


def validate_manifest(content: bytes) -> None:
    try:
        payload = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=reject_duplicate_keys,
            parse_constant=reject_json_constant,
        )
    except (UnicodeError, json.JSONDecodeError, ClientError) as error:
        raise ClientError("manifest JSON invalid") from error
    if (
        not isinstance(payload, dict)
        or set(payload) != {"schemaVersion", "environment", "generatedAt", "components"}
        or type(payload["schemaVersion"]) is not int
        or payload["schemaVersion"] != 1
        or payload["environment"] != "test1"
        or not isinstance(payload["generatedAt"], str)
        or not payload["generatedAt"]
        or not isinstance(payload["components"], dict)
    ):
        raise ClientError("manifest identity invalid")


def runner_directory(environment: Mapping[str, str], config: Config) -> Path:
    run_id = environment.get("STAGING_ACCEPT_RUN_ID", "")
    raw_run_directory = environment.get("STAGING_ACCEPT_RUN_DIR", "")
    if RUN_ID.fullmatch(run_id) is None or not raw_run_directory:
        raise ClientError("Runner context invalid")
    run_directory = Path(raw_run_directory)
    try:
        root_metadata = config.run_root.lstat()
        run_metadata = run_directory.lstat()
        resolved_root = config.run_root.resolve(strict=True)
        resolved_run = run_directory.resolve(strict=True)
    except OSError as error:
        raise ClientError("Runner path invalid") from error
    if (
        not config.run_root.is_absolute()
        or not run_directory.is_absolute()
        or stat.S_ISLNK(root_metadata.st_mode)
        or not stat.S_ISDIR(root_metadata.st_mode)
        or stat.S_ISLNK(run_metadata.st_mode)
        or not stat.S_ISDIR(run_metadata.st_mode)
        or resolved_root != config.run_root
        or resolved_run != run_directory
        or resolved_run.parent != resolved_root
        or resolved_run.name != run_id
    ):
        raise ClientError("Runner path invalid")
    return resolved_run


def existing_destination(path: Path, expected: bytes) -> bool:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except FileNotFoundError:
        return False
    except OSError as error:
        raise ClientError("destination invalid") from error
    try:
        before = os.fstat(fd)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.geteuid()
            or before.st_size > MAX_MANIFEST_BYTES
        ):
            raise ClientError("destination invalid")
        actual = read_bounded(fd, MAX_MANIFEST_BYTES)
        after = os.fstat(fd)
        identity_before = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
        identity_after = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
        if identity_before != identity_after or actual != expected:
            raise ClientError("destination differs")
        if stat.S_IMODE(after.st_mode) != 0o600:
            os.fchmod(fd, 0o600)
        return True
    except OSError as error:
        raise ClientError("destination invalid") from error
    finally:
        os.close(fd)


def install_atomic(path: Path, content: bytes) -> None:
    if existing_destination(path, content):
        return
    temporary: Path | None = None
    try:
        fd, temporary_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
        temporary = Path(temporary_name)
        with os.fdopen(fd, "wb") as handle:
            os.fchmod(handle.fileno(), 0o600)
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.link(temporary, path, follow_symlinks=False)
        except FileExistsError:
            if not existing_destination(path, content):
                raise ClientError("destination creation failed")
    except ClientError:
        raise
    except OSError as error:
        raise ClientError("destination creation failed") from error
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass


def execute(environment: Mapping[str, str]) -> int:
    config = config_from_environment(environment)
    content = source_bytes(config)
    validate_manifest(content)
    run_directory = runner_directory(environment, config)
    install_atomic(run_directory / "runtime-manifest.json", content)
    return 0


def main(
    argv: list[str] | None = None,
    environment: Mapping[str, str] | None = None,
) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    try:
        if arguments:
            raise ClientError("arguments unsupported")
        return execute(os.environ if environment is None else environment)
    except ClientError:
        print("runtime-observer-client: blocked", file=sys.stderr)
        return EXIT_BLOCKED


if __name__ == "__main__":
    sys.exit(main())
