#!/usr/bin/env python3
"""Dispatch one strictly allowlisted observation on the test1 Web host."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

from web_observer_contract import (
    ContractError,
    ObservationCommand,
    blocked_payload,
    encode_payload,
    parse_forced_command,
    parse_single_json,
    validate_payload,
)


KAFKA_PAIRS = (
    "armada.protocol.account.commands.v1=armada-protocol-master-commands",
    "protocol.web.normal-group.commands.v1=protocol-web-normal-group-commands",
    "protocol.account.state.events.v1=armada-api-account-state-events",
    "protocol.account.group-sync.events.v1=armada-api-account-group-sync-events",
    "armada.protocol.group.events.v1=armada-api-group-events-staging",
    "protocol.normal-group.events.v1=armada-api-normal-group-results",
)
PM2_PROCESSES = (
    "protocol-master",
    "protocol-worker-1",
    "protocol-worker-2",
    "protocol-worker-3",
    "protocol-worker-4",
    "protocol-traffic-dashboard",
)
REDIS_SOURCES = (
    "default=REDIS_URL",
    "registry=REGISTRY_REDIS_URL",
    "keys=KEYS_REDIS_URL",
    "rate-limit=RATELIMIT_REDIS_URL",
    "runtime=RUNTIME_REDIS_URL",
)
ALLOWED_FORCED_ERRORS = {
    "WEB_ENV_UNAVAILABLE",
    "WEB_OBSERVER_OUTPUT_INVALID",
}
COMMAND_TIMEOUT_SECONDS = 240


class DispatchError(Exception):
    """A sanitized local dispatch failure."""


def executable(name: str) -> str:
    resolved = shutil.which(name)
    if not resolved or not Path(resolved).is_absolute():
        raise DispatchError("required observer executable unavailable")
    return resolved


def require_layout(protocol_root: Path, observability_root: Path, capture_root: Path) -> None:
    required = (
        protocol_root / "package.json",
        observability_root / "kafka.mjs",
        observability_root / "redis.mjs",
        observability_root / "collect.py",
        observability_root / "web_capture.py",
    )
    if (
        not protocol_root.is_absolute()
        or not protocol_root.is_dir()
        or not observability_root.is_absolute()
        or not observability_root.is_dir()
        or not capture_root.is_absolute()
        or not capture_root.is_dir()
        or any(not path.is_file() for path in required)
    ):
        raise DispatchError("observer layout unavailable")


def collector_argv(
    command: ObservationCommand,
    protocol_root: Path,
    observability_root: Path,
    capture_root: Path,
) -> list[str]:
    identity = [
        "--environment",
        "test1",
        "--phase",
        command.phase,
        "--run-id",
        command.run_id,
        "--candidate-manifest-sha256",
        command.manifest_hash,
    ]
    if command.action == "kafka":
        argv = [executable("node"), str(observability_root / "kafka.mjs"), *identity]
        for pair in KAFKA_PAIRS:
            argv.extend(("--pair", pair))
        argv.extend(("--module", "kafkajs", "--step-timeout-ms", "15000"))
        return argv
    if command.action == "redis":
        argv = [
            executable("node"),
            str(observability_root / "redis.mjs"),
            *identity,
        ]
        for source in REDIS_SOURCES:
            argv.extend(("--source", source))
        argv.extend(("--module", "ioredis"))
        return argv
    if command.action == "host":
        argv = [
            executable("python3"),
            str(observability_root / "collect.py"),
            "host",
            *identity,
            "--label",
            "web",
            "--pm2-bin",
            executable("pm2"),
        ]
        for process_name in PM2_PROCESSES:
            argv.extend(("--process", process_name))
        return argv
    return [
        executable("python3"),
        str(observability_root / "collect.py"),
        "web-traffic",
        *identity,
        "--label",
        "web",
        "--capture-directory",
        f"web={capture_root}",
        "--expected-workers",
        "5",
        "--minimum-window-seconds",
        str(command.window_seconds),
        "--maximum-gap-seconds",
        "60",
        "--freshness-seconds",
        "30",
    ]


def dispatch(
    original_command: str,
    protocol_root: Path,
    observability_root: Path,
    capture_root: Path,
    forced_error: str = "",
) -> tuple[dict, int]:
    try:
        command = parse_forced_command(original_command)
    except ContractError:
        return blocked_payload(None, "WEB_OBSERVER_COMMAND_REJECTED"), 40
    if forced_error:
        if forced_error not in ALLOWED_FORCED_ERRORS:
            return blocked_payload(command, "WEB_OBSERVER_CONFIGURATION_INVALID"), 40
        return blocked_payload(command, forced_error), 2
    try:
        require_layout(protocol_root, observability_root, capture_root)
        completed = subprocess.run(
            collector_argv(command, protocol_root, observability_root, capture_root),
            cwd=protocol_root,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=COMMAND_TIMEOUT_SECONDS,
        )
        if completed.returncode not in (0, 2):
            raise DispatchError("observer execution failed")
        payload = parse_single_json(completed.stdout)
        validate_payload(payload, command)
        expected_code = 0 if payload["status"] == "COLLECTED" else 2
        if completed.returncode != expected_code:
            raise DispatchError("observer exit status mismatch")
        return payload, expected_code
    except (ContractError, DispatchError, OSError, subprocess.SubprocessError):
        return blocked_payload(command, "WEB_OBSERVER_EXECUTION_FAILED"), 2


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--original-command", required=True)
    parser.add_argument("--protocol-root", required=True)
    parser.add_argument("--observability-root", required=True)
    parser.add_argument("--capture-root", required=True)
    parser.add_argument("--forced-error", default="")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    try:
        args = parse_args(argv)
        payload, exit_code = dispatch(
            args.original_command,
            Path(args.protocol_root),
            Path(args.observability_root),
            Path(args.capture_root),
            args.forced_error,
        )
    except (SystemExit, Exception):
        payload, exit_code = blocked_payload(None, "WEB_OBSERVER_CONFIGURATION_INVALID"), 40
    sys.stdout.buffer.write(encode_payload(payload))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
