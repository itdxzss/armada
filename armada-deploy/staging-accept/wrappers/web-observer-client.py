#!/usr/bin/env python3
"""Invoke the fixed test1 Web observer and atomically save its evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from web_observer_contract import (
    ContractError,
    ObservationCommand,
    parse_single_json,
    validate_explicit_values,
    validate_payload,
)


MAX_MANIFEST_BYTES = 1024 * 1024
SSH_TIMEOUT_SECONDS = 300


class ClientError(Exception):
    """A sanitized client-side failure."""


@dataclass(frozen=True)
class ClientConfig:
    ssh_binary: Path
    identity_file: Path
    known_hosts_file: Path
    target: str


PRODUCTION_CONFIG = ClientConfig(
    ssh_binary=Path("/usr/bin/ssh"),
    identity_file=Path("/etc/staging-accept/web-observer_ed25519"),
    known_hosts_file=Path("/etc/staging-accept/web-observer_known_hosts"),
    target="ec2-user@65.2.122.109",
)


def regular_file(path: Path, label: str, max_bytes: int | None = None) -> os.stat_result:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise ClientError(f"{label} unavailable") from error
    if not path.is_absolute() or stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise ClientError(f"{label} unavailable")
    if max_bytes is not None and metadata.st_size > max_bytes:
        raise ClientError(f"{label} too large")
    return metadata


def validate_ssh_files(config: ClientConfig) -> None:
    if not config.ssh_binary.is_absolute() or not os.access(config.ssh_binary, os.X_OK):
        raise ClientError("SSH executable unavailable")
    identity = regular_file(config.identity_file, "SSH identity")
    if stat.S_IMODE(identity.st_mode) != 0o600 or identity.st_uid != os.geteuid():
        raise ClientError("SSH identity permissions unsafe")
    known_hosts = regular_file(config.known_hosts_file, "SSH known-hosts")
    if stat.S_IMODE(known_hosts.st_mode) & 0o022:
        raise ClientError("SSH known-hosts permissions unsafe")


def candidate_identity(run_directory: Path, run_id: str) -> str:
    try:
        resolved = run_directory.resolve(strict=True)
    except OSError as error:
        raise ClientError("Runner directory unavailable") from error
    if not run_directory.is_absolute() or resolved != run_directory or run_directory.name != run_id:
        raise ClientError("Runner directory invalid")
    manifest = run_directory / "candidate-manifest.json"
    before = regular_file(manifest, "candidate manifest", MAX_MANIFEST_BYTES)
    try:
        content = manifest.read_bytes()
        payload = json.loads(content)
        after = manifest.stat()
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ClientError("candidate manifest invalid") from error
    identity_before = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    identity_after = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    if identity_before != identity_after:
        raise ClientError("candidate manifest changed during hashing")
    if not isinstance(payload, dict) or payload.get("environment") != "test1":
        raise ClientError("candidate manifest invalid")
    return f"sha256:{hashlib.sha256(content).hexdigest()}"


def ssh_argv(config: ClientConfig, command: ObservationCommand) -> list[str]:
    return [
        str(config.ssh_binary),
        "-T",
        "-i",
        str(config.identity_file),
        "-o",
        "BatchMode=yes",
        "-o",
        "IdentitiesOnly=yes",
        "-o",
        "StrictHostKeyChecking=yes",
        "-o",
        f"UserKnownHostsFile={config.known_hosts_file}",
        "-o",
        "ConnectTimeout=8",
        config.target,
        *command.remote_argv(),
    ]


def write_atomic(path: Path, content: bytes) -> None:
    directory = path.parent
    try:
        directory.mkdir(mode=0o700, exist_ok=True)
        if directory.resolve(strict=True) != directory or path.exists() and path.is_symlink():
            raise ClientError("evidence path invalid")
        temporary: Path | None = None
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
        if "temporary" in locals() and temporary is not None and temporary.exists():
            temporary.unlink()


def execute(
    action: str,
    phase: str,
    window_seconds: int,
    environment: Mapping[str, str],
    config: ClientConfig = PRODUCTION_CONFIG,
) -> int:
    run_id = environment.get("STAGING_ACCEPT_RUN_ID", "")
    run_directory_raw = environment.get("STAGING_ACCEPT_RUN_DIR", "")
    if not run_directory_raw:
        raise ClientError("Runner context unavailable")
    run_directory = Path(run_directory_raw)
    manifest_hash = candidate_identity(run_directory, run_id)
    try:
        command = validate_explicit_values(
            action, phase, window_seconds, run_id, manifest_hash
        )
    except ContractError as error:
        raise ClientError("observation arguments invalid") from error
    validate_ssh_files(config)
    try:
        completed = subprocess.run(
            ssh_argv(config, command),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=SSH_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ClientError("Web observer transport failed") from error
    if completed.returncode not in (0, 2):
        raise ClientError("Web observer transport failed")
    try:
        payload = parse_single_json(completed.stdout)
        validate_payload(payload, command)
    except ContractError as error:
        raise ClientError("Web observer response invalid") from error
    expected_code = 0 if payload["status"] == "COLLECTED" else 2
    if completed.returncode != expected_code:
        raise ClientError("Web observer response invalid")
    evidence = run_directory / "observability" / f"{action}-{phase}.json"
    write_atomic(
        evidence,
        (json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
    )
    return expected_code


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action", required=True)
    parser.add_argument("--phase", required=True)
    parser.add_argument("--window-seconds", required=True, type=int)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    try:
        args = parse_args(argv)
        return execute(args.action, args.phase, args.window_seconds, os.environ)
    except (ClientError, SystemExit):
        print("web-observer-client: observation blocked", file=sys.stderr)
        return 40


if __name__ == "__main__":
    sys.exit(main())
