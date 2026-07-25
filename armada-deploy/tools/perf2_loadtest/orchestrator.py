from __future__ import annotations

import json
import os
import queue
import re
import secrets
import time
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
from enum import Enum, auto
from pathlib import Path
from typing import Callable, List, Mapping, Optional, Sequence, Tuple

from .model import (
    BuiltMonitor,
    MergedSample,
    MonitorEvent,
    Perf2Profile,
    PreflightEvidence,
    ReconciledTask,
    ResumeOutcome,
    RunOptions,
    TaskSnapshot,
)
from .report import (
    ReportError,
    SampleAligner,
    ZeroWindow,
    build_summary,
    parse_monitor_line,
    write_samples_csv,
)


class OrchestratorError(RuntimeError):
    """A stable orchestration failure without boundary details."""


class RunState(Enum):
    CREATED = auto()
    PREFLIGHTED = auto()
    MONITORING = auto()
    BASELINED = auto()
    SNAPSHOT_FROZEN = auto()
    RESUMING = auto()
    DRAINING = auto()
    RECONCILED = auto()
    REPORTED = auto()


_RUN_ID_RE = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")


class Orchestrator:
    def __init__(
        self,
        *,
        options: RunOptions,
        task_api,
        remote_manager,
        results_root: Path,
        zhuan_repo: Path,
        profile: Optional[Perf2Profile] = None,
        run_id_factory: Optional[Callable[[], str]] = None,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self.options = options
        self.task_api = task_api
        self.remote_manager = remote_manager
        self.results_root = results_root
        self.zhuan_repo = zhuan_repo
        self.profile = profile
        self._run_id_factory = run_id_factory or self._new_run_id
        self._monotonic = monotonic
        self.run_id = self._run_id_factory()
        if not _RUN_ID_RE.fullmatch(self.run_id):
            raise OrchestratorError("run_id")
        self.run_dir = self.results_root / self.run_id
        self.state = RunState.CREATED
        self.last_summary: Mapping[str, object] = {}
        self._logs: List[Mapping[str, object]] = []
        self._aligner = SampleAligner()
        self._invalid_kafka_samples = 0
        self._invalid_resource_samples = 0
        self._mutation_started = False

    def run(self) -> int:
        self._create_run_dir()
        samples: List[MergedSample] = []
        snapshot: Tuple[TaskSnapshot, ...] = ()
        outcomes: Tuple[ResumeOutcome, ...] = ()
        reconciled: Tuple[ReconciledTask, ...] = ()
        built: Optional[BuiltMonitor] = None
        preflight: Optional[PreflightEvidence] = None
        baseline_final_lag: Optional[int] = None
        streams = None
        failure_class: Optional[str] = None
        timed_out = False
        interrupted = False
        try:
            self._log("build_started")
            built = self.remote_manager.build(self.zhuan_repo)
            preflight = self.remote_manager.preflight()
            self._validate_preflight(preflight)
            self.state = RunState.PREFLIGHTED
            self.remote_manager.upload_and_check(built, self.run_id)
            streams = self.remote_manager.start()
            self.state = RunState.MONITORING

            initial_snapshot = tuple(self.task_api.list_paused())
            baseline = self._collect_baseline(streams.events)
            samples.extend(baseline)
            baseline_final_lag = baseline[-1].kafka.lag
            if baseline_final_lag != 0:
                raise OrchestratorError("baseline_lag")
            self.state = RunState.BASELINED

            snapshot = tuple(self.task_api.list_paused())
            self._write_task_snapshot(snapshot)
            self.state = RunState.SNAPSHOT_FROZEN
            if self.options.execute and tuple(task.id for task in initial_snapshot) != tuple(
                task.id for task in snapshot
            ):
                raise OrchestratorError("snapshot_changed")

            if self.options.execute:
                outcomes = self._resume_guarded(snapshot, preflight, baseline[-1])
                self.state = RunState.DRAINING
                post_samples = self._collect_until_idle(streams.events)
                samples.extend(post_samples)
                reconciled = tuple(
                    self.task_api.reconcile(snapshot, self.options.resume_concurrency)
                )
                self.state = RunState.RECONCILED
        except KeyboardInterrupt:
            interrupted = True
            failure_class = "interrupted"
            self._log(failure_class)
        except OrchestratorError as error:
            failure_class = str(error)
            timed_out = failure_class == "monitor_timeout"
            self._log(failure_class)
        except (ReportError, RuntimeError, OSError, ValueError, queue.Empty):
            failure_class = "orchestration_failed"
            self._log(failure_class)
        finally:
            if self._mutation_started and len(reconciled) != len(snapshot):
                try:
                    reconciled = tuple(
                        self.task_api.reconcile(snapshot, self.options.resume_concurrency)
                    )
                    self.state = RunState.RECONCILED
                except (RuntimeError, OSError, ValueError):
                    failure_class = failure_class or "reconcile_failed"
            try:
                self.remote_manager.close()
            except (RuntimeError, OSError, ValueError):
                failure_class = failure_class or "monitor_cleanup_failed"

        if not (self.run_dir / "task-snapshot.json").exists():
            self._write_task_snapshot(snapshot)

        summary = dict(
            build_summary(
                samples,
                snapshot,
                outcomes,
                reconciled,
                invalid_kafka_samples=self._invalid_kafka_samples,
                invalid_resource_samples=self._invalid_resource_samples,
                timed_out=timed_out,
                interrupted=interrupted,
                zero_window_seconds=self.options.zero_window_seconds,
                require_resumed=self.options.execute,
            )
        )
        summary.update(
            {
                "runId": self.run_id,
                "env": "perf2",
                "tenant": self.options.tenant,
                "mode": "execute" if self.options.execute else "dry-run",
                "baselineSeconds": self.options.baseline_seconds,
                "baselineFinalLag": baseline_final_lag,
                "monitorSha256": built.sha256 if built else None,
                "preflight": self._safe_preflight(preflight),
                "failureClass": failure_class,
            }
        )
        if failure_class:
            summary["incomplete"] = True
        if not self.options.execute:
            summary["allSnapshotTasksResumed"] = None
        self.last_summary = summary
        self._write_samples(samples)
        self._write_resume_results(outcomes, reconciled)
        self._atomic_json(self.run_dir / "summary.json", summary)
        self._write_log()
        self.state = RunState.REPORTED
        return 0 if not summary["incomplete"] else 1

    def _collect_baseline(self, events: queue.Queue) -> Tuple[MergedSample, ...]:
        deadline = self._monotonic() + max(10, self.options.baseline_seconds * 3)
        samples: List[MergedSample] = []
        while len(samples) < self.options.baseline_seconds:
            sample = self._next_merged(events, deadline, baseline=True)
            if samples and sample.at - samples[-1].at != timedelta(seconds=1):
                raise OrchestratorError("baseline_gap")
            samples.append(sample)
        return tuple(samples)

    def _collect_until_idle(self, events: queue.Queue) -> Tuple[MergedSample, ...]:
        deadline = self._monotonic() + self.options.timeout_seconds
        window = ZeroWindow(self.options.zero_window_seconds)
        samples: List[MergedSample] = []
        while True:
            sample = self._next_merged(events, deadline, baseline=False)
            samples.append(sample)
            if window.observe(sample, resumes_complete=True):
                return tuple(samples)

    def _next_merged(self, events: queue.Queue, deadline: float, *, baseline: bool) -> MergedSample:
        while True:
            remaining = deadline - self._monotonic()
            if remaining <= 0:
                raise OrchestratorError("monitor_timeout")
            try:
                event: MonitorEvent = events.get(timeout=min(1.0, remaining))
            except queue.Empty as error:
                if self._monotonic() >= deadline:
                    raise OrchestratorError("monitor_timeout") from error
                continue
            if event.kind != "sample" or event.line is None:
                raise OrchestratorError("monitor_stream")
            try:
                monitor_sample = parse_monitor_line(event.line, event.node)
            except ReportError as error:
                if event.node == "zhuan":
                    self._invalid_kafka_samples += 1
                self._invalid_resource_samples += 1
                raise OrchestratorError("invalid_monitor_sample") from error
            invalid = False
            if not monitor_sample.resource.valid:
                self._invalid_resource_samples += 1
                invalid = True
            if event.node == "zhuan" and (
                monitor_sample.kafka is None or not monitor_sample.kafka.valid
            ):
                self._invalid_kafka_samples += 1
                invalid = True
            if invalid:
                raise OrchestratorError("invalid_baseline_sample" if baseline else "invalid_monitor_sample")
            merged = self._aligner.add(monitor_sample)
            if merged is not None:
                return merged

    def _resume_guarded(
        self,
        snapshot: Sequence[TaskSnapshot],
        preflight: PreflightEvidence,
        baseline_final: MergedSample,
    ) -> Tuple[ResumeOutcome, ...]:
        if self.state is not RunState.SNAPSHOT_FROZEN:
            raise OrchestratorError("resume_state")
        if (
            not self.options.execute
            or self.options.expected_count is None
            or len(snapshot) != self.options.expected_count
            or baseline_final.kafka.lag != 0
        ):
            raise OrchestratorError("resume_guard")
        self._validate_preflight(preflight)
        self._mutation_started = True
        self.state = RunState.RESUMING
        return tuple(
            self.task_api.resume_snapshot_once(snapshot, self.options.resume_concurrency)
        )

    def _validate_preflight(self, evidence: PreflightEvidence) -> None:
        minimum = self.options.min_free_gib * 1024**3
        for node in (evidence.armada, evidence.zhuan):
            if (
                node.architecture != "x86_64"
                or not node.container_healthy
                or not node.docker_stats_available
                or node.free_bytes < minimum
            ):
                raise OrchestratorError("preflight_contract")

    def _create_run_dir(self) -> None:
        self.results_root.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.results_root, 0o700)
        self.run_dir.mkdir(mode=0o700, parents=False, exist_ok=False)

    def _write_task_snapshot(self, snapshot: Sequence[TaskSnapshot]) -> None:
        tasks = [
            {
                "id": task.id,
                "taskName": task.task_name,
                "status": task.status,
                "selectedAccountCount": task.selected_account_count,
                "targetGroupCount": task.target_group_count,
                "targetPairCount": task.target_pair_count,
                "sendIntervalSeconds": task.send_interval_seconds,
                "taskStartAt": task.task_start_at,
                "taskEndAt": task.task_end_at,
            }
            for task in snapshot
        ]
        self._atomic_json(self.run_dir / "task-snapshot.json", {"runId": self.run_id, "tasks": tasks})

    def _write_resume_results(
        self, outcomes: Sequence[ResumeOutcome], reconciled: Sequence[ReconciledTask]
    ) -> None:
        starts = [outcome.started_at for outcome in outcomes]
        spread = 0
        if starts:
            spread = int((max(starts) - min(starts)).total_seconds() * 1000)
        payload = {
            "outcomes": [
                {
                    "taskId": value.task_id,
                    "startedAt": self._format_time(value.started_at),
                    "finishedAt": self._format_time(value.finished_at),
                    "result": value.result,
                    "httpStatus": value.http_status,
                }
                for value in outcomes
            ],
            "reconciliation": [
                {
                    "taskId": value.task_id,
                    "finalStatus": value.final_status,
                    "classification": value.classification,
                }
                for value in reconciled
            ],
            "requestSpreadMilliseconds": spread,
        }
        self._atomic_json(self.run_dir / "resume-results.json", payload)

    def _write_samples(self, samples: Sequence[MergedSample]) -> None:
        path = self.run_dir / "samples.csv"
        temporary = path.with_name(path.name + ".tmp")
        descriptor = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(descriptor)
        write_samples_csv(temporary, samples)
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)

    def _write_log(self) -> None:
        path = self.run_dir / "run.log"
        temporary = path.with_name(path.name + ".tmp")
        descriptor = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            for entry in self._logs:
                output.write(json.dumps(entry, sort_keys=True, separators=(",", ":")) + "\n")
        os.replace(temporary, path)

    def _atomic_json(self, path: Path, value: Mapping[str, object]) -> None:
        temporary = path.with_name(path.name + ".tmp")
        descriptor = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            output.write("\n")
        os.replace(temporary, path)

    def _log(self, event: str) -> None:
        self._logs.append({"at": self._format_time(datetime.now(timezone.utc)), "event": event})

    @staticmethod
    def _safe_preflight(evidence: Optional[PreflightEvidence]):
        if evidence is None:
            return None
        return {
            "armada": asdict(evidence.armada),
            "zhuan": asdict(evidence.zhuan),
        }

    @staticmethod
    def _format_time(value: datetime) -> str:
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _new_run_id() -> str:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        return "%s-%s" % (timestamp, secrets.token_hex(4))
