from __future__ import annotations

import argparse
import re
import shlex
from pathlib import Path, PurePosixPath
from typing import Dict, Mapping, Sequence
from urllib.parse import urlparse

from .model import Perf2Profile, RunOptions, SSHProfile


class ConfigError(ValueError):
    """A stable, non-secret configuration validation failure."""


ALLOWED_PROFILE_KEYS = frozenset(
    {
        "ENV_ID",
        "PROFILE_ARMADA_HOST",
        "PROFILE_ARMADA_USER",
        "PROFILE_ARMADA_KEY_REL",
        "PROFILE_ARMADA_REMOTE_DIR",
        "PROFILE_ARMADA_COMPOSE_FILE",
        "PROFILE_ARMADA_PUBLIC_URL",
        "PROFILE_ZHUAN_HOST",
        "PROFILE_ZHUAN_USER",
        "PROFILE_ZHUAN_KEY_REL",
        "PROFILE_ZHUAN_REMOTE_DIR",
        "PROFILE_ZHUAN_COMPOSE_FILE",
        "EXPECTED_KAFKA_TOPICS",
        "EXPECTED_KAFKA_GROUPS",
    }
)

_ASSIGNMENT_RE = re.compile(r"^([A-Z][A-Z0-9_]*)=(.*)$")
_TENANT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
_HOST_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
_USER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_FILE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
_MESSAGE_TOPIC = "armada.perf.protocol.android.message.commands.v1"
_MESSAGE_GROUP = "armada-perf-android-zhuan-message-v1"


class _SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise ConfigError("invalid_arguments")


def parse_args(argv: Sequence[str]) -> RunOptions:
    parser = _SafeArgumentParser(prog="perf2-marketing-load-test")
    parser.add_argument("--env", default="perf2", choices=("perf2",))
    parser.add_argument("--tenant", default="demo")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--expected-count", type=int)
    parser.add_argument("--resume-concurrency", type=int, default=10)
    parser.add_argument("--baseline-seconds", type=int, default=30)
    parser.add_argument("--zero-window-seconds", type=int, default=60)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--min-free-gib", type=int, default=5)
    try:
        values = parser.parse_args(list(argv))
    except (argparse.ArgumentError, SystemExit) as error:
        raise ConfigError("invalid_arguments") from error
    if not _TENANT_RE.fullmatch(values.tenant):
        raise ConfigError("invalid_arguments")
    if not 1 <= values.resume_concurrency <= 32:
        raise ConfigError("invalid_arguments")
    if values.baseline_seconds <= 0 or values.zero_window_seconds <= 0 or values.timeout_seconds <= 0:
        raise ConfigError("invalid_arguments")
    if values.min_free_gib <= 0:
        raise ConfigError("invalid_arguments")
    if values.execute:
        if values.expected_count is None or values.expected_count <= 0:
            raise ConfigError("expected_count_required")
    elif values.expected_count is not None:
        raise ConfigError("expected_count_requires_execute")
    return RunOptions(
        env=values.env,
        tenant=values.tenant,
        execute=values.execute,
        expected_count=values.expected_count,
        resume_concurrency=values.resume_concurrency,
        baseline_seconds=values.baseline_seconds,
        zero_window_seconds=values.zero_window_seconds,
        timeout_seconds=values.timeout_seconds,
        min_free_gib=values.min_free_gib,
    )


def parse_profile_assignments(path: Path) -> Mapping[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ConfigError("profile_read") from error
    values: Dict[str, str] = {}
    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        match = _ASSIGNMENT_RE.fullmatch(line)
        if match is None:
            raise ConfigError("profile_syntax")
        key, raw_value = match.groups()
        if key not in ALLOWED_PROFILE_KEYS:
            continue
        if key in values:
            raise ConfigError("profile_duplicate")
        if "$(" in raw_value or "`" in raw_value or "${" in raw_value:
            raise ConfigError("profile_syntax")
        try:
            words = shlex.split(raw_value, comments=True, posix=True)
        except ValueError as error:
            raise ConfigError("profile_syntax") from error
        if len(words) > 1:
            raise ConfigError("profile_syntax")
        values[key] = words[0] if words else ""
    return values


def load_perf2_profile(repo_root: Path, env: str) -> Perf2Profile:
    if env != "perf2":
        raise ConfigError("environment_contract")
    root = repo_root.resolve()
    values = parse_profile_assignments(root / "armada-deploy" / "envs" / "perf2.conf")
    if values.get("ENV_ID") != "perf2":
        raise ConfigError("environment_contract")
    armada = _ssh_profile(values, root, "ARMADA")
    zhuan = _ssh_profile(values, root, "ZHUAN")
    public_url = _required(values, "PROFILE_ARMADA_PUBLIC_URL")
    parsed_url = urlparse(public_url)
    if parsed_url.scheme not in ("http", "https") or not parsed_url.hostname or parsed_url.username or parsed_url.password:
        raise ConfigError("public_url_contract")
    topics = _parse_topic_contract(_required(values, "EXPECTED_KAFKA_TOPICS"))
    if topics.get(_MESSAGE_TOPIC) != 12:
        raise ConfigError("topic_contract")
    groups = {value.strip() for value in _required(values, "EXPECTED_KAFKA_GROUPS").split(",") if value.strip()}
    if _MESSAGE_GROUP not in groups:
        raise ConfigError("group_contract")
    return Perf2Profile(
        env_id="perf2",
        armada=armada,
        zhuan=zhuan,
        public_url=public_url.rstrip("/"),
        topic=_MESSAGE_TOPIC,
        group_id=_MESSAGE_GROUP,
        expected_partitions=12,
    )


def _ssh_profile(values: Mapping[str, str], root: Path, label: str) -> SSHProfile:
    prefix = "PROFILE_%s_" % label
    host = _required(values, prefix + "HOST")
    user = _required(values, prefix + "USER")
    if not _HOST_RE.fullmatch(host) or not _USER_RE.fullmatch(user):
        raise ConfigError("ssh_identity")
    relative_key = Path(_required(values, prefix + "KEY_REL"))
    if relative_key.is_absolute():
        raise ConfigError("ssh_key")
    try:
        key_path = (root / relative_key).resolve(strict=True)
        key_path.relative_to(root)
    except (OSError, ValueError) as error:
        raise ConfigError("ssh_key") from error
    if not key_path.is_file():
        raise ConfigError("ssh_key")
    remote_raw = _required(values, prefix + "REMOTE_DIR")
    remote_dir = PurePosixPath(remote_raw)
    if not remote_raw.startswith("/home/") or ".." in remote_dir.parts or "." in remote_dir.parts:
        raise ConfigError("remote_directory")
    compose_file = _required(values, prefix + "COMPOSE_FILE")
    if not _FILE_RE.fullmatch(compose_file):
        raise ConfigError("compose_file")
    return SSHProfile(
        host=host,
        user=user,
        key_path=key_path,
        remote_dir=remote_dir,
        compose_file=compose_file,
    )


def _required(values: Mapping[str, str], key: str) -> str:
    value = values.get(key, "").strip()
    if not value:
        raise ConfigError("profile_required")
    return value


def _parse_topic_contract(raw: str) -> Mapping[str, int]:
    topics: Dict[str, int] = {}
    for item in raw.split(","):
        name, separator, count = item.strip().partition("=")
        if not separator or not name or name in topics:
            raise ConfigError("topic_contract")
        try:
            parsed_count = int(count)
        except ValueError as error:
            raise ConfigError("topic_contract") from error
        if parsed_count <= 0:
            raise ConfigError("topic_contract")
        topics[name] = parsed_count
    return topics
