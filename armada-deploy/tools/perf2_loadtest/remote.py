from __future__ import annotations

import hashlib
import os
import queue
import re
import shutil
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass, replace
from pathlib import Path, PurePosixPath
from typing import Dict, Mapping, Optional, Protocol, Sequence

from .model import (
    BuiltMonitor,
    MonitorEvent,
    NodePreflight,
    Perf2Profile,
    PreflightEvidence,
    SSHProfile,
)
from .report import parse_monitor_line


class RemoteError(RuntimeError):
    """A stable remote-boundary failure without command output."""


class ProcessRunner(Protocol):
    def run(
        self,
        argv: Sequence[str],
        *,
        cwd: Optional[Path] = None,
        env: Optional[Mapping[str, str]] = None,
        timeout: Optional[float] = None,
        input: Optional[bytes] = None,
    ) -> subprocess.CompletedProcess:
        ...


class SubprocessRunner:
    def run(
        self,
        argv: Sequence[str],
        *,
        cwd: Optional[Path] = None,
        env: Optional[Mapping[str, str]] = None,
        timeout: Optional[float] = None,
        input: Optional[bytes] = None,
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            list(argv),
            cwd=str(cwd) if cwd else None,
            env=dict(env) if env else None,
            timeout=timeout,
            input=input,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )


@dataclass(frozen=True)
class MonitorStreams:
    events: queue.Queue


_RUN_ID_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
_REMOTE_BINARY = "perf-monitor"
_CONTAINERS = {"armada": "armada-backend", "zhuan": "whatsapp-android-zhuan"}
_PREFLIGHT_SCRIPT = b"""#!/usr/bin/env bash
# PRECHECK
set -euo pipefail
container="$1"
printf '%s\\n' "$(uname -m)"
sudo -n docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$container"
df -Pk / | awk 'NR == 2 {print $4}'
sudo -n docker stats --no-stream --format '{{json .}}' "$container" >/dev/null
printf '%s\\n' stats-ok
"""
_MKDIR_SCRIPT = b"""#!/usr/bin/env bash
set -euo pipefail
case "$1" in /home/*/.armada-perf-tools/*) ;; *) exit 64 ;; esac
umask 077
mkdir -p -- "$1"
"""
_CHMOD_SCRIPT = b"""#!/usr/bin/env bash
# CHMOD_MONITOR
set -euo pipefail
case "$1" in /home/*/.armada-perf-tools/*/perf-monitor) ;; *) exit 64 ;; esac
chmod 700 -- "$1"
"""
_CLEANUP_SCRIPT = b"""#!/usr/bin/env bash
set -euo pipefail
case "$1" in /home/*/.armada-perf-tools/[0-9]*Z-[0-9a-f]*) ;; *) exit 64 ;; esac
rm -rf -- "$1"
"""


class RemoteMonitorManager:
    def __init__(
        self,
        profile: Perf2Profile,
        *,
        min_free_gib: int,
        runner: Optional[ProcessRunner] = None,
        popen_factory=None,
        queue_size: int = 4096,
        monotonic=time.monotonic,
    ) -> None:
        if profile.env_id != "perf2" or min_free_gib <= 0 or queue_size < 2:
            raise RemoteError("remote_options")
        self.profile = profile
        self.min_free_gib = min_free_gib
        self.runner = runner or SubprocessRunner()
        self.popen_factory = popen_factory or subprocess.Popen
        self.events: queue.Queue = queue.Queue(maxsize=queue_size)
        self._local_temp: Optional[Path] = None
        self._remote_dirs: Dict[str, PurePosixPath] = {}
        self._processes: Dict[str, object] = {}
        self._threads = []
        self._closing = threading.Event()
        self._overflow_published = False
        self._monotonic = monotonic

    def build(self, zhuan_repo: Path) -> BuiltMonitor:
        repo = zhuan_repo.resolve()
        if not (repo / "go.mod").is_file() or not (repo / "cmd/perf-monitor/main.go").is_file():
            raise RemoteError("monitor_source")
        status = self._run(
            ["git", "status", "--porcelain", "--", "cmd/perf-monitor", "internal/perfmonitor"],
            cwd=repo,
            timeout=10,
            error_class="monitor_source",
        )
        if status.stdout.strip():
            raise RemoteError("monitor_source_dirty")
        if self._local_temp is None:
            self._local_temp = Path(tempfile.mkdtemp(prefix="armada-perf-monitor-"))
        output = self._local_temp / _REMOTE_BINARY
        environment = os.environ.copy()
        environment.update({"CGO_ENABLED": "0", "GOOS": "linux", "GOARCH": "amd64"})
        self._run(
            ["go", "build", "-trimpath", "-o", str(output), "./cmd/perf-monitor"],
            cwd=repo,
            env=environment,
            timeout=300,
            error_class="monitor_build",
        )
        if not output.is_file():
            raise RemoteError("monitor_build")
        return BuiltMonitor(path=output, sha256=hashlib.sha256(output.read_bytes()).hexdigest())

    def preflight(self) -> PreflightEvidence:
        try:
            armada = self._preflight_node("armada", self.profile.armada)
            zhuan = self._preflight_node("zhuan", self.profile.zhuan)
        except RemoteError as error:
            if str(error) in ("architecture", "container_health", "free_disk", "docker_stats"):
                raise
            raise RemoteError("remote_preflight") from error
        return PreflightEvidence(armada=armada, zhuan=zhuan)

    def upload_and_check(self, built: BuiltMonitor, run_id: str) -> None:
        if not _RUN_ID_RE.fullmatch(run_id) or not built.path.is_file() or len(built.sha256) != 64:
            raise RemoteError("upload_contract")
        for node, ssh_profile in (("armada", self.profile.armada), ("zhuan", self.profile.zhuan)):
            remote_dir = PurePosixPath("/home") / ssh_profile.user / ".armada-perf-tools" / run_id
            self._remote_dirs[node] = remote_dir
            self._run(
                self._ssh_argv(ssh_profile) + ["bash", "-s", "--", str(remote_dir)],
                input=_MKDIR_SCRIPT,
                timeout=20,
                error_class="remote_mkdir",
            )
            target = "%s@%s:%s" % (ssh_profile.user, ssh_profile.host, remote_dir / _REMOTE_BINARY)
            self._run(
                self._scp_argv(ssh_profile) + [str(built.path), target],
                timeout=60,
                error_class="monitor_upload",
            )
            self._run(
                self._ssh_argv(ssh_profile)
                + ["bash", "-s", "--", str(remote_dir / _REMOTE_BINARY)],
                input=_CHMOD_SCRIPT,
                timeout=20,
                error_class="monitor_chmod",
            )
            check = self._run(
                self._monitor_argv(node, ssh_profile, check=True),
                timeout=20,
                error_class="monitor_check",
            )
            lines = check.stdout.splitlines()
            if len(lines) != 1:
                raise RemoteError("monitor_check")
            try:
                sample = parse_monitor_line(lines[0], node)
            except ValueError as error:
                raise RemoteError("monitor_check") from error
            if not sample.resource.valid or node == "zhuan" and (sample.kafka is None or not sample.kafka.valid):
                raise RemoteError("monitor_check")

    def start(self) -> MonitorStreams:
        if set(self._remote_dirs) != {"armada", "zhuan"} or self._processes:
            raise RemoteError("monitor_state")
        for node, ssh_profile in (("armada", self.profile.armada), ("zhuan", self.profile.zhuan)):
            try:
                process = self.popen_factory(
                    self._monitor_argv(node, ssh_profile, check=False),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    bufsize=0,
                )
            except OSError as error:
                raise RemoteError("monitor_start") from error
            self._processes[node] = process
            stdout_thread = threading.Thread(target=self._read_stdout, args=(node, process), daemon=True)
            stderr_thread = threading.Thread(target=self._drain_stderr, args=(process,), daemon=True)
            stdout_thread.start()
            stderr_thread.start()
            self._threads.extend((stdout_thread, stderr_thread))
        return MonitorStreams(events=self.events)

    def close(self) -> None:
        self._closing.set()
        cleanup_failed = False
        for process in self._processes.values():
            try:
                process.terminate()
            except OSError:
                pass
        for process in self._processes.values():
            try:
                process.wait(timeout=5)
            except (subprocess.TimeoutExpired, OSError):
                try:
                    process.kill()
                    process.wait(timeout=2)
                except (subprocess.TimeoutExpired, OSError):
                    cleanup_failed = True
        for node, remote_dir in tuple(self._remote_dirs.items()):
            ssh_profile = self.profile.armada if node == "armada" else self.profile.zhuan
            try:
                self._run(
                    self._ssh_argv(ssh_profile) + ["bash", "-s", "--", str(remote_dir)],
                    input=_CLEANUP_SCRIPT,
                    timeout=20,
                    error_class="remote_cleanup",
                )
            except RemoteError:
                cleanup_failed = True
        self._remote_dirs.clear()
        if self._local_temp is not None and self._local_temp.is_dir():
            shutil.rmtree(self._local_temp)
            self._local_temp = None
        if cleanup_failed:
            raise RemoteError("remote_cleanup")

    def _preflight_node(self, node: str, ssh_profile: SSHProfile) -> NodePreflight:
        result = self._run(
            self._ssh_argv(ssh_profile)
            + ["bash", "-s", "--", _CONTAINERS[node], str(self.min_free_gib * 1024 * 1024)],
            input=_PREFLIGHT_SCRIPT,
            timeout=20,
            error_class="remote_preflight",
        )
        lines = result.stdout.decode("utf-8", errors="strict").splitlines()
        if len(lines) != 4:
            raise RemoteError("remote_preflight")
        architecture = lines[0].strip()
        if architecture != "x86_64":
            raise RemoteError("architecture")
        state = lines[1].split()
        healthy = bool(state) and state[0] == "running" and (len(state) == 1 or state[1] == "healthy")
        if not healthy:
            raise RemoteError("container_health")
        try:
            free_bytes = int(lines[2]) * 1024
        except ValueError as error:
            raise RemoteError("remote_preflight") from error
        if free_bytes < self.min_free_gib * 1024**3:
            raise RemoteError("free_disk")
        stats_available = lines[3].strip() == "stats-ok"
        if not stats_available:
            raise RemoteError("docker_stats")
        return NodePreflight(architecture, healthy, free_bytes, stats_available)

    def _monitor_argv(self, node: str, ssh_profile: SSHProfile, *, check: bool) -> list:
        remote_dir = self._remote_dirs.get(node)
        if remote_dir is None:
            raise RemoteError("monitor_state")
        argv = self._ssh_argv(ssh_profile) + [
            str(remote_dir / _REMOTE_BINARY),
            "-node",
            node,
            "-container",
            _CONTAINERS[node],
            "-interval",
            "1s",
            "-sample-timeout",
            "5s",
        ]
        if node == "armada":
            argv.append("-no-kafka")
        else:
            argv.extend(
                [
                    "-config",
                    str(self.profile.zhuan.remote_dir / "deploy/configs/prod_configs.toml"),
                    "-expected-partitions",
                    str(self.profile.expected_partitions),
                    "-expected-topic",
                    self.profile.topic,
                    "-expected-group",
                    self.profile.group_id,
                ]
            )
        if check:
            argv.append("-check")
        return argv

    @staticmethod
    def _ssh_argv(profile: SSHProfile) -> list:
        return [
            "ssh",
            "-T",
            "-i",
            str(profile.key_path),
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=10",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "%s@%s" % (profile.user, profile.host),
        ]

    @staticmethod
    def _scp_argv(profile: SSHProfile) -> list:
        return [
            "scp",
            "-i",
            str(profile.key_path),
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=10",
            "-o",
            "StrictHostKeyChecking=accept-new",
        ]

    def _read_stdout(self, node: str, process) -> None:
        try:
            for line in process.stdout:
                if line:
                    self._publish(MonitorEvent(node=node, kind="sample", line=bytes(line)))
            return_code = process.wait()
            if not self._closing.is_set():
                error_class = "monitor_exit" if return_code == 0 else "monitor_failed"
                self._publish(MonitorEvent(node=node, kind="failure", error_class=error_class))
        except (OSError, ValueError):
            if not self._closing.is_set():
                self._publish(MonitorEvent(node=node, kind="failure", error_class="monitor_stream"))

    @staticmethod
    def _drain_stderr(process) -> None:
        try:
            for _line in process.stderr:
                pass
        except OSError:
            pass

    def _publish(self, event: MonitorEvent) -> None:
        if event.received_monotonic is None:
            event = replace(event, received_monotonic=self._monotonic())
        try:
            self.events.put_nowait(event)
        except queue.Full:
            if self._overflow_published:
                return
            self._overflow_published = True
            try:
                self.events.get_nowait()
                self.events.put_nowait(
                    MonitorEvent(
                        node=event.node,
                        kind="failure",
                        error_class="sample_overflow",
                        received_monotonic=self._monotonic(),
                    )
                )
            except queue.Empty:
                pass

    def _run(
        self,
        argv: Sequence[str],
        *,
        cwd: Optional[Path] = None,
        env: Optional[Mapping[str, str]] = None,
        timeout: Optional[float] = None,
        input: Optional[bytes] = None,
        error_class: str,
    ) -> subprocess.CompletedProcess:
        try:
            result = self.runner.run(argv, cwd=cwd, env=env, timeout=timeout, input=input)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise RemoteError(error_class) from error
        if result.returncode != 0:
            raise RemoteError(error_class)
        return result
