#!/usr/bin/env python3
"""Shared validation for the fixed test1 Web observer SSH bridge."""

from __future__ import annotations

import datetime as dt
import json
import re
from dataclasses import dataclass
from typing import Any


ACTIONS = ("kafka", "redis", "host", "web-traffic")
PHASES = ("start", "peak", "end")
MAX_WINDOW_SECONDS = 86_400
MAX_OUTPUT_BYTES = 16 * 1024 * 1024
RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
MANIFEST_HASH = re.compile(r"^sha256:[0-9a-f]{64}$")
COLLECTORS = {
    "kafka": "kafka",
    "redis": "redis",
    "host": "host-resource",
    "web-traffic": "web-traffic",
}
PRIVATE_KEY = re.compile(
    r"(?:password|passwd|authorization|credential|privatekey|secret|token|cookie)",
    re.IGNORECASE,
)
PRIVATE_STRING = re.compile(r"(?:[A-Za-z][A-Za-z0-9+.-]*://|@|-----BEGIN[^\n]*PRIVATE KEY-----)")


class ContractError(Exception):
    """A generic bridge contract error that never includes input or secret values."""


@dataclass(frozen=True)
class ObservationCommand:
    action: str
    phase: str
    window_seconds: int
    run_id: str
    manifest_hash: str

    @property
    def collector(self) -> str:
        return COLLECTORS[self.action]

    def remote_argv(self) -> list[str]:
        return [
            "observe",
            "--action",
            self.action,
            "--phase",
            self.phase,
            "--window-seconds",
            str(self.window_seconds),
            "--run-id",
            self.run_id,
            "--manifest-hash",
            self.manifest_hash,
        ]


def validate_explicit_values(
    action: str,
    phase: str,
    window_seconds: int,
    run_id: str,
    manifest_hash: str,
) -> ObservationCommand:
    if action not in ACTIONS or phase not in PHASES:
        raise ContractError("invalid observation selector")
    if (
        isinstance(window_seconds, bool)
        or not isinstance(window_seconds, int)
        or window_seconds < 0
        or window_seconds > MAX_WINDOW_SECONDS
    ):
        raise ContractError("invalid observation window")
    if RUN_ID.fullmatch(run_id) is None or MANIFEST_HASH.fullmatch(manifest_hash) is None:
        raise ContractError("invalid evidence identity")
    return ObservationCommand(action, phase, window_seconds, run_id, manifest_hash)


def parse_forced_command(raw: str) -> ObservationCommand:
    parts = raw.split(" ")
    if len(parts) != 11 or any(not part for part in parts):
        raise ContractError("invalid forced command")
    if [parts[index] for index in (0, 1, 3, 5, 7, 9)] != [
        "observe",
        "--action",
        "--phase",
        "--window-seconds",
        "--run-id",
        "--manifest-hash",
    ]:
        raise ContractError("invalid forced command")
    if re.fullmatch(r"0|[1-9][0-9]{0,5}", parts[6]) is None:
        raise ContractError("invalid observation window")
    return validate_explicit_values(
        parts[2], parts[4], int(parts[6]), parts[8], parts[10]
    )


def blocked_payload(command: ObservationCommand | None, reason: str) -> dict[str, Any]:
    collector = command.collector if command else "web-observer"
    phase = command.phase if command else "start"
    run_id = command.run_id if command else "invalid"
    manifest_hash = command.manifest_hash if command else f"sha256:{'0' * 64}"
    payload: dict[str, Any] = {
        "schemaVersion": 1,
        "collector": collector,
        "environment": "test1",
        "phase": phase,
        "runId": run_id,
        "candidateManifestSha256": manifest_hash,
        "provenance": "live",
        "observedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "status": "BLOCKED",
        "health": {
            "ok": False,
            "checks": [{"name": "web-observer-bridge", "ok": False, "reason": reason}],
            "blockedReasons": [reason],
        },
        "semantics": {"bridge": "fixed read-only test1 Web observer"},
        "raw": {},
    }
    if command and command.action == "host":
        payload["source"] = "web"
    return payload


def parse_single_json(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) > MAX_OUTPUT_BYTES or b"\x00" in raw:
        raise ContractError("invalid observer output")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ContractError("invalid observer output") from error
    lines = text.splitlines()
    if len(lines) != 1 or not lines[0]:
        raise ContractError("invalid observer output")
    try:
        payload = json.loads(lines[0])
    except json.JSONDecodeError as error:
        raise ContractError("invalid observer output") from error
    if not isinstance(payload, dict):
        raise ContractError("invalid observer output")
    return payload


def validate_payload(payload: dict[str, Any], command: ObservationCommand) -> None:
    expected = {
        "schemaVersion": 1,
        "collector": command.collector,
        "environment": "test1",
        "phase": command.phase,
        "runId": command.run_id,
        "candidateManifestSha256": command.manifest_hash,
        "provenance": "live",
    }
    if any(payload.get(name) != value for name, value in expected.items()):
        raise ContractError("observer identity mismatch")
    if command.action == "host" and payload.get("source") != "web":
        raise ContractError("observer source mismatch")
    status = payload.get("status")
    health = payload.get("health")
    if status not in ("COLLECTED", "BLOCKED") or not isinstance(health, dict):
        raise ContractError("invalid observer status")
    if health.get("ok") is not (status == "COLLECTED"):
        raise ContractError("invalid observer health")
    _reject_private_output(payload)


def _reject_private_output(value: Any) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or PRIVATE_KEY.search(key):
                raise ContractError("private observer output")
            _reject_private_output(item)
        return
    if isinstance(value, list):
        for item in value:
            _reject_private_output(item)
        return
    if isinstance(value, str) and PRIVATE_STRING.search(value):
        raise ContractError("private observer output")


def encode_payload(payload: dict[str, Any]) -> bytes:
    return (
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
