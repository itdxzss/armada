#!/usr/bin/env python3
"""Deterministic, read-only orchestration entrypoint for fixed test1 soak plans."""

from __future__ import annotations

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
from typing import Any, Sequence


EXIT_FAIL = 30
EXIT_BLOCKED = 40
MAX_JSON_BYTES = 16 * 1024 * 1024
RUN_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
MANIFEST_SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
SAFE_REASON = re.compile(r"^[A-Z][A-Z0-9_]{0,79}$")

PROFILE_SECONDS = {
    "test1-soak-1h": 60 * 60,
    "test1-soak-6h": 6 * 60 * 60,
    "test1-soak-24h": 24 * 60 * 60,
}
OBSERVATION_PHASES = ("start", "peak", "end")
WEB_ACTIONS = {
    "kafka": ("kafka", ""),
    "redis": ("redis", ""),
    "host": ("host-resource", "web"),
    "web-traffic": ("web-traffic", ""),
}
KAFKA_PAIRS = (
    ("armada-protocol-master-commands", "armada.protocol.account.commands.v1"),
    ("protocol-web-normal-group-commands", "protocol.web.normal-group.commands.v1"),
    ("armada-api-account-state-events", "protocol.account.state.events.v1"),
    ("armada-api-account-group-sync-events", "protocol.account.group-sync.events.v1"),
    ("armada-api-group-events-staging", "armada.protocol.group.events.v1"),
    ("armada-api-normal-group-results", "protocol.normal-group.events.v1"),
)
REDIS_SOURCES = ("default", "registry", "keys", "rate-limit", "runtime")
REDIS_CLUSTER_NODE = "master-1"
BACKEND_CONTAINERS = (
    "armada-backend",
    "armada-nginx",
    "zhuan-native-probe-mysql",
    "zhuan-coordinator",
)
WEB_PROCESSES = (
    "armada-protocol-master",
    "armada-protocol-worker-1",
    "armada-protocol-worker-2",
    "armada-protocol-worker-3",
    "armada-protocol-worker-4",
    "protocol-runtime-collector",
    "protocol-traffic-dashboard",
)
ANDROID_TARGETS = (
    ("node01", "http://172.31.13.55:8001/ws/v1/traffic/snapshot", "01"),
    ("node02", "http://172.31.10.86:8001/ws/v1/traffic/snapshot", "02"),
    ("node03", "http://172.31.5.45:8001/ws/v1/traffic/snapshot", "03"),
)
TEST1_INSTANCES = {
    "i-06cf0d5fb86263860": "backend-runner",
    "i-03580d2585e074fec": "web-protocol",
    "i-06cb773a74046490e": "android-node1",
    "i-09aeea3efcb15f725": "android-node2",
    "i-015fe1e6c542d7e06": "android-node3",
}
ALARM_SIGNALS = {
    ("AWS/EC2", "CPUUtilization"): "cpu",
    ("AWS/EC2", "StatusCheckFailed"): "status-check",
    ("Armada/Test1", "mem_used_percent"): "memory",
}


def soak_stages(duration_seconds: int) -> tuple[tuple[str, int], ...]:
    half = duration_seconds // 2
    return (
        ("candidate-bind", 30),
        ("verify-start", 300),
        ("observe-start", 900),
        ("soak-to-peak", half + 60),
        ("verify-peak", 300),
        ("observe-peak", 900),
        ("soak-to-end", duration_seconds - half + 60),
        ("verify-end", 300),
        ("observe-end", 900),
        ("evaluate-soak", 300),
    )


class StageResult(Exception):
    """A sanitized, already-classified stage result."""

    def __init__(self, outcome: str, *reason_codes: str):
        super().__init__(outcome)
        self.outcome = outcome
        self.reason_codes = tuple(dict.fromkeys(reason_codes or ("HARNESS_FAILURE",)))

    @property
    def exit_code(self) -> int:
        return EXIT_FAIL if self.outcome == "FAIL" else EXIT_BLOCKED


@dataclass(frozen=True)
class Config:
    run_root: Path = Path("/var/lib/staging-accept/runs")
    entrypoint: Path = Path("/usr/local/libexec/staging-accept/test1-soak")
    deep_check_client: Path = Path("/usr/local/libexec/staging-accept/deep-check-client")
    runtime_observer_client: Path = Path(
        "/usr/local/libexec/staging-accept/runtime-observer-client"
    )
    preflight_script: Path = Path("/usr/local/libexec/staging-accept/scripts/preflight.sh")
    web_observer_client: Path = Path(
        "/usr/local/libexec/staging-accept/web-observer-client"
    )
    backend_observer_client: Path = Path(
        "/usr/local/libexec/staging-accept/backend-observer-client"
    )
    collector_script: Path = Path(
        "/usr/local/libexec/staging-accept/scripts/observability/collect.py"
    )
    evaluator_script: Path = Path(
        "/usr/local/libexec/staging-accept/scripts/observability/evaluate.py"
    )
    python: Path = Path("/usr/bin/python3")
    cloudwatch_observer_client: Path = Path(
        "/usr/local/libexec/staging-accept/cloudwatch-observer-client"
    )
    wait_seconds_override: int | None = None
    command_timeout_seconds: int = 360


class Controller:
    def __init__(self, config: Config):
        self.config = config
        self.run_id = ""
        self.stage_id = ""
        self.run_dir = Path("/")
        self.plan: dict[str, Any] = {}
        self.profile = ""
        self.duration_seconds = 0
        self.candidate: dict[str, Any] = {}
        self.candidate_hash = ""

    def run(self) -> int:
        try:
            self._load_context()
            self._load_plan()
            if self.stage_id == "candidate-bind":
                self._bind_candidate()
            else:
                self._load_bound_candidate()
            self._dispatch()
        except StageResult as result:
            self._write_stage_result(result.outcome, result.reason_codes)
            print(
                f"RESULT {result.outcome} stage={self.stage_id or 'context'} "
                f"reasons={','.join(result.reason_codes)}",
                file=sys.stderr,
            )
            return result.exit_code
        except (OSError, ValueError, json.JSONDecodeError, subprocess.SubprocessError):
            result = StageResult("BLOCKED", "HARNESS_FAILURE")
            self._write_stage_result(result.outcome, result.reason_codes)
            print(
                f"RESULT BLOCKED stage={self.stage_id or 'context'} reasons=HARNESS_FAILURE",
                file=sys.stderr,
            )
            return result.exit_code
        self._write_stage_result("PASS", ())
        print(f"RESULT PASS stage={self.stage_id}")
        return 0

    def _load_context(self) -> None:
        self.run_id = os.environ.get("STAGING_ACCEPT_RUN_ID", "")
        self.stage_id = os.environ.get("STAGING_ACCEPT_STAGE_ID", "")
        raw_run_dir = os.environ.get("STAGING_ACCEPT_RUN_DIR", "")
        if not RUN_ID.fullmatch(self.run_id) or not raw_run_dir:
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        run_dir = Path(raw_run_dir)
        if not run_dir.is_absolute():
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        try:
            resolved_root = self.config.run_root.resolve(strict=True)
            resolved_run = run_dir.resolve(strict=True)
        except OSError as error:
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID") from error
        if (
            resolved_root != self.config.run_root
            or resolved_run != run_dir
            or resolved_run.parent != resolved_root
            or resolved_run.name != self.run_id
            or not resolved_run.is_dir()
            or resolved_run.is_symlink()
        ):
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        self.run_dir = resolved_run

    def _load_plan(self) -> None:
        value = self._read_json(self.run_dir / "plan.json", 1024 * 1024)
        expected_keys = {"schemaVersion", "profile", "environment", "safety", "builds", "stages"}
        if not isinstance(value, dict) or set(value) != expected_keys:
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        profile = value.get("profile")
        if (
            value.get("schemaVersion") != 1
            or profile not in PROFILE_SECONDS
            or value.get("environment") != "test1"
            or value.get("safety") != "read-only"
        ):
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        builds = value.get("builds")
        build_keys = {"backend", "frontend", "webProtocol", "androidProtocol"}
        if (
            not isinstance(builds, dict)
            or set(builds) != build_keys
            or any(
                not isinstance(builds[name], str) or not FULL_SHA.fullmatch(builds[name])
                for name in build_keys
            )
        ):
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        duration = PROFILE_SECONDS[profile]
        expected_stages = [
            {
                "id": stage_id,
                "command": [str(self.config.entrypoint)],
                "timeoutSeconds": timeout,
            }
            for stage_id, timeout in soak_stages(duration)
        ]
        if value.get("stages") != expected_stages or self.stage_id not in {
            stage_id for stage_id, _ in soak_stages(duration)
        }:
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        self.plan = value
        self.profile = profile
        self.duration_seconds = duration
        self.candidate = {
            "schemaVersion": 1,
            "profile": profile,
            "environment": "test1",
            "safety": "read-only",
            "builds": builds,
        }

    def _candidate_bytes(self) -> bytes:
        return (
            json.dumps(self.candidate, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode("utf-8")

    def _bind_candidate(self) -> None:
        path = self.run_dir / "candidate-manifest.json"
        expected = self._candidate_bytes()
        if path.exists() or path.is_symlink():
            if self._read_regular_bytes(path, 1024 * 1024) != expected:
                raise StageResult("BLOCKED", "CANDIDATE_BINDING_MISMATCH")
        else:
            self._write_atomic_bytes(path, expected)
        self.candidate_hash = "sha256:" + hashlib.sha256(expected).hexdigest()

    def _load_bound_candidate(self) -> None:
        expected = self._candidate_bytes()
        actual = self._read_regular_bytes(
            self.run_dir / "candidate-manifest.json", 1024 * 1024
        )
        if actual != expected:
            raise StageResult("BLOCKED", "CANDIDATE_BINDING_MISMATCH")
        self.candidate_hash = "sha256:" + hashlib.sha256(actual).hexdigest()
        if not MANIFEST_SHA256.fullmatch(self.candidate_hash):
            raise StageResult("BLOCKED", "CANDIDATE_BINDING_MISMATCH")

    def _dispatch(self) -> None:
        if self.stage_id == "candidate-bind":
            return
        if self.stage_id.startswith("verify-"):
            self._verify(self.stage_id.removeprefix("verify-"))
            return
        if self.stage_id.startswith("observe-"):
            self._observe(self.stage_id.removeprefix("observe-"))
            return
        if self.stage_id == "soak-to-peak":
            self._wait(self.duration_seconds // 2)
            return
        if self.stage_id == "soak-to-end":
            self._wait(self.duration_seconds - self.duration_seconds // 2)
            return
        if self.stage_id == "evaluate-soak":
            self._evaluate()
            return
        raise StageResult("BLOCKED", "STAGE_CONTEXT_INVALID")

    def _wait(self, seconds: int) -> None:
        actual = seconds if self.config.wait_seconds_override is None else self.config.wait_seconds_override
        if actual < 0:
            raise StageResult("BLOCKED", "WAIT_CONFIGURATION_INVALID")
        time.sleep(actual)

    def _verify(self, phase: str) -> None:
        if phase not in OBSERVATION_PHASES:
            raise StageResult("BLOCKED", "STAGE_CONTEXT_INVALID")
        status = self._run_fixed(self.config.deep_check_client, ())
        self._classify_status(status, "DEEP_CHECK_BLOCKED", "DEEP_CHECK_FAILED")
        status = self._run_fixed(self.config.runtime_observer_client, ())
        self._classify_status(status, "RUNTIME_OBSERVER_BLOCKED", "RUNTIME_OBSERVER_FAILED")
        max_age = {
            "start": 600,
            "peak": self.duration_seconds // 2 + 600,
            "end": self.duration_seconds + 600,
        }[phase]
        builds = self.candidate["builds"]
        command = (
            "versions",
            "--env",
            "test1",
            "--manifest",
            str(self.run_dir / "runtime-manifest.json"),
            "--backend-sha",
            builds["backend"],
            "--frontend-sha",
            builds["frontend"],
            "--web-protocol-sha",
            builds["webProtocol"],
            "--android-protocol-sha",
            builds["androidProtocol"],
            "--max-age-seconds",
            str(max_age),
            "--android-role",
            "coordinator",
            "--android-role",
            "node-01",
            "--android-role",
            "node-02",
            "--android-role",
            "node-03",
        )
        status = self._run_fixed(self.config.preflight_script, command)
        if status == 41:
            raise StageResult("FAIL", "RUNTIME_VERSION_MISMATCH")
        self._classify_status(status, "RUNTIME_EVIDENCE_BLOCKED", "PREFLIGHT_FAILED")
        self._cloudwatch(phase)

    def _cloudwatch(self, phase: str) -> None:
        status = self._run_observer(
            self.config.cloudwatch_observer_client, (), "CLOUDWATCH_OBSERVER"
        )
        path = self.run_dir / "observability" / f"cloudwatch-{phase}.json"
        evidence = self._read_json(path, MAX_JSON_BYTES)
        alarms = self._test1_alarm_rows(evidence)
        if (
            evidence.get("schemaVersion") != 1
            or evidence.get("collector") != "cloudwatch-alarms"
            or evidence.get("environment") != "test1"
            or evidence.get("phase") != phase
            or evidence.get("runId") != self.run_id
            or evidence.get("candidateManifestSha256") != self.candidate_hash
            or evidence.get("provenance") != "live"
            or evidence.get("status") != "COLLECTED"
            or evidence.get("region") != "ap-south-1"
            or evidence.get("expectedAlarmCount") != 15
            or {row["state"] for row in alarms} != {"OK"}
            or status != 0
        ):
            raise StageResult("BLOCKED", "CLOUDWATCH_EVIDENCE_INVALID")

    @staticmethod
    def _test1_alarm_rows(payload: Any) -> list[dict[str, str]]:
        if not isinstance(payload, dict) or not isinstance(payload.get("alarms"), list):
            raise ValueError("alarm payload invalid")
        selected: dict[tuple[str, str], dict[str, str]] = {}
        aliases = set(TEST1_INSTANCES.values())
        signals = set(ALARM_SIGNALS.values())
        for alarm in payload["alarms"]:
            if not isinstance(alarm, dict):
                raise ValueError("alarm row invalid")
            if set(alarm) != {"instance", "signal", "state"}:
                raise ValueError("alarm row shape invalid")
            instance = alarm.get("instance")
            signal = alarm.get("signal")
            state = alarm.get("state")
            if instance not in aliases or signal not in signals:
                raise ValueError("alarm identity invalid")
            if state not in ("OK", "ALARM", "INSUFFICIENT_DATA"):
                raise ValueError("alarm state invalid")
            key = (instance, signal)
            if key in selected:
                raise ValueError("alarm identity duplicated")
            selected[key] = alarm
        expected = {
            (instance, signal)
            for instance in TEST1_INSTANCES.values()
            for signal in ALARM_SIGNALS.values()
        }
        if set(selected) != expected:
            raise ValueError("alarm set incomplete")
        return [selected[key] for key in sorted(selected)]

    def _observe(self, phase: str) -> None:
        if phase not in OBSERVATION_PHASES:
            raise StageResult("BLOCKED", "STAGE_CONTEXT_INVALID")
        observability = self._ensure_directory(self.run_dir / "observability")
        window_seconds = {
            "start": 0,
            "peak": self.duration_seconds // 2,
            "end": min(self.duration_seconds + 60, 86_400),
        }[phase]
        for action, (collector, source) in WEB_ACTIONS.items():
            status = self._run_observer(
                self.config.web_observer_client,
                (
                    "--action",
                    action,
                    "--phase",
                    phase,
                    "--window-seconds",
                    str(window_seconds),
                ),
                "WEB_OBSERVER",
            )
            self._check_snapshot(
                observability / f"{action}-{phase}.json",
                collector,
                phase,
                source,
                status,
            )
        status = self._run_observer(
            self.config.backend_observer_client, (), "BACKEND_OBSERVER"
        )
        self._check_snapshot(
            observability / f"host-backend-{phase}.json",
            "host-resource",
            phase,
            "backend",
            status,
        )
        self._collect_android(phase, observability)

    def _collect_android(self, phase: str, observability: Path) -> None:
        if not self._is_regular(self.config.collector_script):
            raise StageResult("BLOCKED", "ANDROID_COLLECTOR_UNAVAILABLE")
        command: list[str] = [
            str(self.config.collector_script),
            "android-traffic",
            "--environment",
            "test1",
            "--phase",
            phase,
            "--label",
            "android",
            "--run-id",
            self.run_id,
            "--candidate-manifest-sha256",
            self.candidate_hash,
            "--expected-targets",
            "3",
            "--freshness-seconds",
            "90",
            "--minimum-retention-seconds",
            str(self.duration_seconds),
            "--timeout-seconds",
            "8",
        ]
        for label, target, node_id in ANDROID_TARGETS:
            command.extend(("--target", f"{label}={target}"))
            command.extend(("--expected-node-id", f"{label}={node_id}"))
        output = observability / f"android-traffic-{phase}.json"
        status = self._capture_json(self.config.python, tuple(command), output)
        self._check_snapshot(output, "android-traffic", phase, "", status)

    def _evaluate(self) -> None:
        observability = self._ensure_directory(self.run_dir / "observability")
        inputs: list[Path] = []
        for phase in OBSERVATION_PHASES:
            inputs.extend(observability / f"{action}-{phase}.json" for action in WEB_ACTIONS)
            inputs.append(observability / f"host-backend-{phase}.json")
            inputs.append(observability / f"android-traffic-{phase}.json")
        command: list[str] = [
            str(self.config.evaluator_script),
            "--environment",
            "test1",
            "--run-id",
            self.run_id,
            "--candidate-manifest-sha256",
            self.candidate_hash,
            "--profile-seconds",
            str(self.duration_seconds),
            "--max-evidence-age-seconds",
            "900",
            "--max-kafka-end-lag",
            "0",
            "--minimum-traffic-window-seconds",
            str(self.duration_seconds),
            "--maximum-traffic-gap-seconds",
            "60",
        ]
        for path in inputs:
            command.extend(("--input", str(path)))
        for collector in ("kafka", "redis", "host-resource", "web-traffic", "android-traffic"):
            command.extend(("--require-collector", collector))
        for group, topic in KAFKA_PAIRS:
            command.extend(("--expected-kafka-pair", f"{topic}={group}"))
        for source in REDIS_SOURCES:
            command.extend(("--expected-redis-source", source))
            command.extend(("--expected-redis-node", f"{source}={REDIS_CLUSTER_NODE}"))
        for source in ("backend", "web"):
            command.extend(("--expected-host-source", source))
        for container in BACKEND_CONTAINERS:
            command.extend(("--expected-host-container", f"backend={container}"))
        for process in WEB_PROCESSES:
            command.extend(("--expected-host-process", f"web={process}"))
        output = observability / "evaluation.json"
        status = self._capture_json(
            self.config.python, tuple(command), output, allow_evaluation_status=True
        )
        result = self._read_json(output, MAX_JSON_BYTES)
        expected_status = {0: "PASS", 2: "FAIL", 3: "BLOCKED"}.get(status)
        if (
            not isinstance(result, dict)
            or result.get("schemaVersion") != 1
            or result.get("evaluator") != "observability"
            or result.get("environment") != "test1"
            or result.get("status") != expected_status
        ):
            raise StageResult("BLOCKED", "OBSERVABILITY_EVALUATION_INVALID")
        observability_outcome = result["status"]
        observability_reasons = {
            "PASS": (),
            "FAIL": ("OBSERVABILITY_THRESHOLDS_FAILED",),
            "BLOCKED": ("OBSERVABILITY_EVIDENCE_BLOCKED",),
        }[observability_outcome]
        prior_stage_results = [
            self._required_stage_result(stage_id)
            for stage_id, _ in soak_stages(self.duration_seconds)
            if stage_id != "evaluate-soak"
        ]
        stage_results = [*prior_stage_results, {
            "stageId": "observability-evaluator",
            "outcome": observability_outcome,
            "reasonCodes": list(observability_reasons),
        }]
        if any(stage["outcome"] != "PASS" for stage in prior_stage_results):
            evaluation_outcome = "BLOCKED"
            evaluation_reasons = tuple(
                dict.fromkeys((*observability_reasons, "STAGE_RESULT_INVALID"))
            )
        else:
            evaluation_outcome = observability_outcome
            evaluation_reasons = observability_reasons
        summary = {
            "schemaVersion": 1,
            "runId": self.run_id,
            "candidateManifestSha256": self.candidate_hash,
            "profile": self.profile,
            "environment": "test1",
            "outcome": evaluation_outcome,
            "reasonCodes": list(evaluation_reasons),
            "stages": stage_results,
            "trafficSemantics": {
                "web": "application proxy-socket bytes",
                "android": "application proxy-socket bytes",
                "cloudBilling": False,
            },
            "runnerPersistence": {
                "state": "staging-acceptd SQLite heartbeat and explicit resume",
                "evidence": "independent run directory with terminal checksums and report",
            },
        }
        self._write_atomic_bytes(
            self.run_dir / "soak-summary.json",
            (json.dumps(summary, sort_keys=True, separators=(",", ":")) + "\n").encode(),
        )
        if evaluation_outcome != "PASS":
            raise StageResult(evaluation_outcome, *evaluation_reasons)

    def _required_stage_result(self, stage_id: str) -> dict[str, Any]:
        try:
            result = self._read_json(self.run_dir / "results" / f"{stage_id}.json", 65536)
            if (
                not isinstance(result, dict)
                or result.get("schemaVersion") != 1
                or result.get("runId") != self.run_id
                or result.get("stageId") != stage_id
                or result.get("candidateManifestSha256") != self.candidate_hash
                or result.get("outcome") != "PASS"
                or result.get("reasonCodes") != []
            ):
                raise StageResult("BLOCKED", "STAGE_RESULT_INVALID")
            return {"stageId": stage_id, "outcome": "PASS", "reasonCodes": []}
        except StageResult:
            return {
                "stageId": stage_id,
                "outcome": "BLOCKED",
                "reasonCodes": ["STAGE_RESULT_INVALID"],
            }

    def _run_observer(self, executable: Path, arguments: Sequence[str], prefix: str) -> int:
        if not self._is_executable(executable):
            raise StageResult("BLOCKED", f"{prefix}_CLIENT_UNAVAILABLE")
        try:
            status = subprocess.run(
                [str(executable), *arguments],
                check=False,
                stdin=subprocess.DEVNULL,
                timeout=self.config.command_timeout_seconds,
            ).returncode
        except (OSError, subprocess.SubprocessError) as error:
            raise StageResult("BLOCKED", f"{prefix}_CLIENT_UNAVAILABLE") from error
        if status in (2, 40):
            raise StageResult("BLOCKED", f"{prefix}_COLLECTION_BLOCKED")
        if status != 0:
            raise StageResult("FAIL", f"{prefix}_COLLECTION_FAILED")
        return status

    def _capture_json(
        self,
        executable: Path,
        arguments: Sequence[str],
        output: Path,
        *,
        allow_evaluation_status: bool = False,
    ) -> int:
        if not self._is_executable(executable):
            raise StageResult("BLOCKED", "OBSERVABILITY_EXECUTABLE_UNAVAILABLE")
        temporary: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="wb", dir=output.parent, prefix=f".{output.name}.", delete=False
            ) as handle:
                temporary = Path(handle.name)
                status = subprocess.run(
                    [str(executable), *arguments],
                    check=False,
                    stdin=subprocess.DEVNULL,
                    stdout=handle,
                    timeout=self.config.command_timeout_seconds,
                ).returncode
                handle.flush()
                os.fsync(handle.fileno())
            if temporary.stat().st_size > MAX_JSON_BYTES:
                raise ValueError("output too large")
            json.loads(temporary.read_text(encoding="utf-8"))
            os.chmod(temporary, 0o600)
            os.replace(temporary, output)
            temporary = None
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError, subprocess.SubprocessError) as error:
            raise StageResult("BLOCKED", "OBSERVABILITY_OUTPUT_INVALID") from error
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        if allow_evaluation_status and status in (0, 2, 3):
            return status
        if status in (2, 3, 40):
            raise StageResult("BLOCKED", "OBSERVABILITY_COLLECTION_BLOCKED")
        if status != 0:
            raise StageResult("FAIL", "OBSERVABILITY_COLLECTION_FAILED")
        return status

    def _check_snapshot(
        self,
        path: Path,
        collector: str,
        phase: str,
        source: str,
        command_status: int,
    ) -> None:
        snapshot = self._read_json(path, MAX_JSON_BYTES)
        valid = (
            isinstance(snapshot, dict)
            and snapshot.get("schemaVersion") == 1
            and snapshot.get("collector") == collector
            and snapshot.get("environment") == "test1"
            and snapshot.get("phase") == phase
            and snapshot.get("runId") == self.run_id
            and snapshot.get("candidateManifestSha256") == self.candidate_hash
            and snapshot.get("provenance") == "live"
            and snapshot.get("status") in ("COLLECTED", "BLOCKED")
            and isinstance(snapshot.get("health"), dict)
        )
        if source:
            valid = valid and snapshot.get("source") == source
        if not valid:
            raise StageResult("BLOCKED", f"{collector.upper().replace('-', '_')}_EVIDENCE_INVALID")
        if snapshot["status"] == "BLOCKED":
            raise StageResult("BLOCKED", f"{collector.upper().replace('-', '_')}_COLLECTION_BLOCKED")
        if command_status != 0:
            raise StageResult("FAIL", f"{collector.upper().replace('-', '_')}_STATUS_INCONSISTENT")

    def _run_fixed(self, executable: Path, arguments: Sequence[str]) -> int:
        if not self._is_executable(executable):
            return EXIT_BLOCKED
        try:
            return subprocess.run(
                [str(executable), *arguments],
                check=False,
                stdin=subprocess.DEVNULL,
                timeout=self.config.command_timeout_seconds,
            ).returncode
        except (OSError, subprocess.SubprocessError):
            return EXIT_BLOCKED

    @staticmethod
    def _classify_status(status: int, blocked: str, failed: str) -> None:
        if status == 0:
            return
        if status in (2, 40):
            raise StageResult("BLOCKED", blocked)
        raise StageResult("FAIL", failed)

    def _write_stage_result(self, outcome: str, reason_codes: Sequence[str]) -> None:
        if not self.run_id or self.run_dir == Path("/") or not self.run_dir.is_dir():
            return
        try:
            if any(not SAFE_REASON.fullmatch(reason) for reason in reason_codes):
                return
            results = self._ensure_directory(self.run_dir / "results")
            payload = {
                "schemaVersion": 1,
                "runId": self.run_id,
                "stageId": self.stage_id,
                "candidateManifestSha256": self.candidate_hash or None,
                "outcome": outcome,
                "reasonCodes": list(reason_codes),
            }
            self._write_atomic_bytes(
                results / f"{self.stage_id}.json",
                (json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n").encode(),
            )
        except (OSError, StageResult):
            return

    @staticmethod
    def _is_regular(path: Path) -> bool:
        try:
            return path.is_absolute() and stat.S_ISREG(path.lstat().st_mode) and not path.is_symlink()
        except OSError:
            return False

    @staticmethod
    def _is_executable(path: Path) -> bool:
        if not path.is_absolute():
            return False
        try:
            resolved = path.resolve(strict=True)
            return stat.S_ISREG(resolved.stat().st_mode) and os.access(resolved, os.X_OK)
        except OSError:
            return False

    @classmethod
    def _read_regular_bytes(cls, path: Path, max_bytes: int) -> bytes:
        if not cls._is_regular(path):
            raise StageResult("BLOCKED", "EVIDENCE_FILE_UNAVAILABLE")
        try:
            if path.stat().st_size > max_bytes:
                raise StageResult("BLOCKED", "EVIDENCE_FILE_INVALID")
            return path.read_bytes()
        except OSError as error:
            raise StageResult("BLOCKED", "EVIDENCE_FILE_UNAVAILABLE") from error

    @classmethod
    def _read_json(cls, path: Path, max_bytes: int) -> Any:
        try:
            return json.loads(cls._read_regular_bytes(path, max_bytes).decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise StageResult("BLOCKED", "EVIDENCE_FILE_INVALID") from error

    @staticmethod
    def _ensure_directory(path: Path) -> Path:
        if not path.exists():
            path.mkdir(mode=0o700)
        try:
            resolved = path.resolve(strict=True)
        except OSError as error:
            raise StageResult("BLOCKED", "EVIDENCE_DIRECTORY_INVALID") from error
        if resolved != path or not path.is_dir() or path.is_symlink():
            raise StageResult("BLOCKED", "EVIDENCE_DIRECTORY_INVALID")
        return path

    @staticmethod
    def _write_atomic_bytes(path: Path, payload: bytes) -> None:
        temporary: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="wb", dir=path.parent, prefix=f".{path.name}.", delete=False
            ) as handle:
                temporary = Path(handle.name)
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(temporary, 0o600)
            os.replace(temporary, path)
            temporary = None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def main() -> int:
    if len(sys.argv) != 1:
        print("test1-soak: arguments are not accepted", file=sys.stderr)
        return EXIT_BLOCKED
    return Controller(Config()).run()


if __name__ == "__main__":
    sys.exit(main())
