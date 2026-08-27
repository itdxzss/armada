#!/usr/bin/env python3
"""Deterministic, read-only orchestration entrypoint for the test1 quick plan."""

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
DEFAULT_OBSERVER_TIMEOUT_SECONDS = 120
# Keep the orchestration timeout outside every nested Web traffic deadline:
# capture watermark wait < remote dispatcher < SSH client < quick wrapper.
WEB_CAPTURE_WATERMARK_WAIT_SECONDS = 125
WEB_OBSERVER_DISPATCH_TIMEOUT_SECONDS = 240
WEB_OBSERVER_TRANSPORT_TIMEOUT_SECONDS = 300
WEB_TRAFFIC_OBSERVER_TIMEOUT_SECONDS = 330
RUN_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
MANIFEST_SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")
SAFE_REASON = re.compile(r"^[A-Z][A-Z0-9_]{0,79}$")

QUICK_STAGES = (
    ("candidate-bind", 30),
    ("deep-check", 300),
    ("runtime-versions", 120),
    ("ui-smoke", 300),
    ("observe-start", 660),
    ("quick-midpoint", 45),
    ("observe-peak", 660),
    ("quick-endpoint", 45),
    ("observe-end", 900),
    ("evaluate-quick", 120),
)
OBSERVATION_PHASES = ("start", "peak", "end")
WEB_WINDOW_BOUNDARY_SECONDS = 60
WEB_ACTIONS = {
    "kafka": ("kafka", ""),
    "redis": ("redis", ""),
    "host": ("host-resource", "web"),
    "web-traffic": ("web-traffic", ""),
}
WEB_TRAFFIC_SUMMARY_SEMANTICS = {
    "rawTotals": "diagnostic-minute-envelope",
    "watermarks": "watermark-health-only",
    "runAttribution": "not-attributed",
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


class StageResult(Exception):
    """A safe, already-classified stage result."""

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
    entrypoint: Path = Path("/usr/local/libexec/staging-accept/test1-quick")
    deep_check_client: Path = Path("/usr/local/libexec/staging-accept/deep-check-client")
    runtime_observer_client: Path = Path(
        "/usr/local/libexec/staging-accept/runtime-observer-client"
    )
    preflight_script: Path = Path("/usr/local/libexec/staging-accept/scripts/preflight.sh")
    ui_wrapper: Path = Path("/usr/local/libexec/staging-accept/ui-smoke")
    ui_credentials: Path = Path("/etc/staging-accept/ui-smoke.env")
    web_observer_client: Path = Path(
        "/usr/local/libexec/staging-accept/web-observer-client"
    )
    backend_observer_client: Path = Path(
        "/usr/local/libexec/staging-accept/backend-observer-client"
    )
    python: Path = Path("/usr/bin/python3")
    evaluator_script: Path = Path(
        "/usr/local/libexec/staging-accept/scripts/observability/evaluate.py"
    )
    wait_seconds: int = 30
    profile_seconds: int = 60


class Controller:
    def __init__(self, config: Config):
        self.config = config
        self.run_id = ""
        self.stage_id = ""
        self.run_dir = Path("/")
        self.plan: dict[str, Any] = {}
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
        except StageResult as result:
            return self._finish_process(result)
        except (OSError, ValueError, json.JSONDecodeError, subprocess.SubprocessError):
            return self._finish_process(StageResult("BLOCKED", "HARNESS_FAILURE"))

        try:
            self._dispatch()
        except StageResult as result:
            self._write_stage_result(result.outcome, result.reason_codes)
            reasons = ",".join(result.reason_codes)
            print(
                f"RESULT {result.outcome} stage={self.stage_id} reasons={reasons}",
                file=sys.stderr,
            )
            if self.stage_id not in ("candidate-bind", "evaluate-quick"):
                print(f"CONTROL CONTINUE stage={self.stage_id} logicalOutcome={result.outcome}")
                return 0
            return result.exit_code
        except (OSError, ValueError, json.JSONDecodeError, subprocess.SubprocessError):
            result = StageResult("BLOCKED", "HARNESS_FAILURE")
            self._write_stage_result(result.outcome, result.reason_codes)
            if self.stage_id not in ("candidate-bind", "evaluate-quick"):
                print(
                    f"RESULT BLOCKED stage={self.stage_id} reasons=HARNESS_FAILURE",
                    file=sys.stderr,
                )
                print(f"CONTROL CONTINUE stage={self.stage_id} logicalOutcome=BLOCKED")
                return 0
            return self._finish_process(result)
        self._write_stage_result("PASS", ())
        print(f"RESULT PASS stage={self.stage_id}")
        return 0

    def _finish_process(self, result: StageResult) -> int:
        self._write_stage_result(result.outcome, result.reason_codes)
        reasons = ",".join(result.reason_codes)
        print(
            f"RESULT {result.outcome} stage={self.stage_id or 'context'} reasons={reasons}",
            file=sys.stderr,
        )
        return result.exit_code

    def _load_context(self) -> None:
        self.run_id = os.environ.get("STAGING_ACCEPT_RUN_ID", "")
        self.stage_id = os.environ.get("STAGING_ACCEPT_STAGE_ID", "")
        raw_run_dir = os.environ.get("STAGING_ACCEPT_RUN_DIR", "")
        if not RUN_ID.fullmatch(self.run_id):
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        if self.stage_id not in {stage for stage, _ in QUICK_STAGES}:
            raise StageResult("BLOCKED", "STAGE_CONTEXT_INVALID")
        if not raw_run_dir or not Path(raw_run_dir).is_absolute():
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        root = self.config.run_root
        try:
            resolved_root = root.resolve(strict=True)
            resolved_run = Path(raw_run_dir).resolve(strict=True)
        except OSError as error:
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID") from error
        if resolved_root != root or resolved_run != Path(raw_run_dir):
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        if resolved_run.parent != resolved_root or resolved_run.name != self.run_id:
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        if not resolved_run.is_dir() or resolved_run.is_symlink():
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        self.run_dir = resolved_run

    def _load_plan(self) -> None:
        value = self._read_json(self.run_dir / "plan.json", 1024 * 1024)
        expected_keys = {"schemaVersion", "profile", "environment", "safety", "builds", "stages"}
        if not isinstance(value, dict) or set(value) != expected_keys:
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        if (
            value["schemaVersion"] != 1
            or value["profile"] != "test1-quick"
            or value["environment"] != "test1"
            or value["safety"] != "read-only"
        ):
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        builds = value.get("builds")
        build_keys = {"backend", "frontend", "webProtocol", "androidProtocol"}
        if not isinstance(builds, dict) or set(builds) != build_keys:
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        if any(not isinstance(builds[name], str) or not FULL_SHA.fullmatch(builds[name]) for name in build_keys):
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        expected_stages = [
            {
                "id": stage_id,
                "command": [str(self.config.entrypoint)],
                "timeoutSeconds": timeout,
            }
            for stage_id, timeout in QUICK_STAGES
        ]
        if value.get("stages") != expected_stages:
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        self.plan = value
        self.candidate = {
            "schemaVersion": 1,
            "profile": "test1-quick",
            "environment": "test1",
            "safety": "read-only",
            "builds": builds,
        }

    def _candidate_bytes(self) -> bytes:
        return (
            json.dumps(
                self.candidate,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode("utf-8")

    def _bind_candidate(self) -> None:
        path = self.run_dir / "candidate-manifest.json"
        expected = self._candidate_bytes()
        if path.exists() or path.is_symlink():
            actual = self._read_regular_bytes(path, 1024 * 1024)
            if actual != expected:
                raise StageResult("BLOCKED", "CANDIDATE_BINDING_MISMATCH")
        else:
            self._write_atomic_bytes(path, expected)
        self.candidate_hash = "sha256:" + hashlib.sha256(expected).hexdigest()

    def _load_bound_candidate(self) -> None:
        path = self.run_dir / "candidate-manifest.json"
        expected = self._candidate_bytes()
        actual = self._read_regular_bytes(path, 1024 * 1024)
        if actual != expected:
            raise StageResult("BLOCKED", "CANDIDATE_BINDING_MISMATCH")
        self.candidate_hash = "sha256:" + hashlib.sha256(actual).hexdigest()
        if not MANIFEST_SHA256.fullmatch(self.candidate_hash):
            raise StageResult("BLOCKED", "CANDIDATE_BINDING_MISMATCH")

    def _dispatch(self) -> None:
        if self.stage_id == "candidate-bind":
            return
        if self.stage_id == "deep-check":
            self._deep_check()
            return
        if self.stage_id == "runtime-versions":
            self._runtime_versions()
            return
        if self.stage_id == "ui-smoke":
            self._ui_smoke()
            return
        if self.stage_id in ("quick-midpoint", "quick-endpoint"):
            time.sleep(self.config.wait_seconds)
            return
        if self.stage_id.startswith("observe-"):
            self._observe(self.stage_id.removeprefix("observe-"))
            return
        if self.stage_id == "evaluate-quick":
            self._evaluate()
            return
        raise StageResult("BLOCKED", "STAGE_CONTEXT_INVALID")

    def _deep_check(self) -> None:
        status = self._run_fixed(self.config.deep_check_client, ())
        self._classify_status(status, "DEEP_CHECK_BLOCKED", "DEEP_CHECK_FAILED")

    def _runtime_versions(self) -> None:
        status = self._run_fixed(self.config.runtime_observer_client, ())
        self._classify_status(status, "RUNTIME_OBSERVER_BLOCKED", "RUNTIME_OBSERVER_FAILED")
        runtime_manifest = self.run_dir / "runtime-manifest.json"
        self._read_json(runtime_manifest, 65536)
        builds = self.candidate["builds"]
        command = (
            "versions",
            "--env",
            "test1",
            "--manifest",
            str(runtime_manifest),
            "--backend-sha",
            builds["backend"],
            "--frontend-sha",
            builds["frontend"],
            "--web-protocol-sha",
            builds["webProtocol"],
            "--android-protocol-sha",
            builds["androidProtocol"],
            "--max-age-seconds",
            "300",
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

    def _ui_smoke(self) -> None:
        if not self._is_regular(self.config.ui_credentials):
            raise StageResult("BLOCKED", "UI_CREDENTIAL_ALIAS_UNAVAILABLE")
        status = self._run_fixed(self.config.ui_wrapper, ())
        if status != 0:
            raise StageResult("FAIL", "UI_SMOKE_FAILED")

    def _observe(self, phase: str) -> None:
        if phase not in OBSERVATION_PHASES:
            raise StageResult("BLOCKED", "STAGE_CONTEXT_INVALID")
        observability = self._ensure_directory(self.run_dir / "observability")
        window_seconds = {
            "start": 0,
            "peak": self.config.wait_seconds,
            # The runner observes whole minute buckets. Include one boundary
            # minute so normal collector/SSH overhead cannot exclude the start.
            "end": self.config.profile_seconds + WEB_WINDOW_BOUNDARY_SECONDS,
        }[phase]
        blockers: list[str] = []
        failures: list[str] = []

        for action, (collector, source) in WEB_ACTIONS.items():
            timeout_seconds = (
                WEB_TRAFFIC_OBSERVER_TIMEOUT_SECONDS
                if action == "web-traffic" and phase == "end"
                else DEFAULT_OBSERVER_TIMEOUT_SECONDS
            )
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
                blockers,
                failures,
                "WEB_OBSERVER",
                timeout_seconds,
            )
            path = observability / f"{action}-{phase}.json"
            self._check_snapshot(path, collector, phase, source, status, blockers, failures)

        backend_status = self._run_observer(
            self.config.backend_observer_client,
            (),
            blockers,
            failures,
            "BACKEND_OBSERVER",
            DEFAULT_OBSERVER_TIMEOUT_SECONDS,
        )
        self._check_snapshot(
            observability / f"host-backend-{phase}.json",
            "host-resource",
            phase,
            "backend",
            backend_status,
            blockers,
            failures,
        )

        if failures:
            raise StageResult("FAIL", *failures)
        if blockers:
            raise StageResult("BLOCKED", *blockers)

    def _evaluate(self) -> None:
        observability = self._ensure_directory(self.run_dir / "observability")
        inputs: list[Path] = []
        for phase in OBSERVATION_PHASES:
            inputs.extend(observability / f"{action}-{phase}.json" for action in WEB_ACTIONS)
            inputs.append(observability / f"host-backend-{phase}.json")
        command: list[str] = [
            str(self.config.evaluator_script),
            "--environment",
            "test1",
            "--run-id",
            self.run_id,
            "--candidate-manifest-sha256",
            self.candidate_hash,
            "--profile-seconds",
            str(self.config.profile_seconds),
            "--max-evidence-age-seconds",
            "600",
            "--max-kafka-end-lag",
            "0",
            "--minimum-traffic-window-seconds",
            str(self.config.profile_seconds),
            "--maximum-traffic-gap-seconds",
            "60",
        ]
        for path in inputs:
            command.extend(("--input", str(path)))
        for collector in ("kafka", "redis", "host-resource", "web-traffic"):
            command.extend(("--require-collector", collector))
        for group, topic in KAFKA_PAIRS:
            command.extend(("--expected-kafka-pair", f"{topic}={group}"))
        for source in REDIS_SOURCES:
            command.extend(("--expected-redis-source", source))
            command.extend(
                ("--expected-redis-node", f"{source}={REDIS_CLUSTER_NODE}")
            )
        for source in ("backend", "web"):
            command.extend(("--expected-host-source", source))
        for container in BACKEND_CONTAINERS:
            command.extend(("--expected-host-container", f"backend={container}"))
        for process in WEB_PROCESSES:
            command.extend(("--expected-host-process", f"web={process}"))

        output = observability / "evaluation.json"
        blockers: list[str] = []
        failures: list[str] = []
        status = self._capture_json(
            self.config.python,
            tuple(command),
            output,
            blockers,
            failures,
            "OBSERVABILITY_EVALUATOR",
        )
        evaluation_outcome = "BLOCKED"
        evaluation_reasons = ("OBSERVABILITY_EVALUATION_INVALID",)
        try:
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
            evaluation_outcome = result["status"]
            evaluation_reasons = {
                "PASS": (),
                "FAIL": ("OBSERVABILITY_THRESHOLDS_FAILED",),
                "BLOCKED": ("OBSERVABILITY_EVIDENCE_BLOCKED",),
            }[evaluation_outcome]
        except StageResult:
            pass

        stage_results: list[dict[str, Any]] = []
        for stage_id, _ in QUICK_STAGES:
            if stage_id == "evaluate-quick":
                continue
            stage_results.append(self._required_stage_result(stage_id))
        stage_results.append(
            {
                "stageId": "observability-evaluator",
                "outcome": evaluation_outcome,
                "reasonCodes": list(evaluation_reasons),
            }
        )
        failures = [row for row in stage_results if row["outcome"] == "FAIL"]
        blockers = [row for row in stage_results if row["outcome"] == "BLOCKED"]
        outcome = "FAIL" if failures else "BLOCKED" if blockers else "PASS"
        selected = failures if failures else blockers
        reason_codes = tuple(
            dict.fromkeys(
                reason
                for row in selected
                for reason in row.get("reasonCodes", ["STAGE_RESULT_INVALID"])
            )
        )
        summary = {
            "schemaVersion": 1,
            "runId": self.run_id,
            "candidateManifestSha256": self.candidate_hash,
            "profile": "test1-quick",
            "environment": "test1",
            "outcome": outcome,
            "reasonCodes": list(reason_codes),
            "stages": stage_results,
            "webTrafficSemantics": WEB_TRAFFIC_SUMMARY_SEMANTICS,
        }
        self._write_atomic_bytes(
            self.run_dir / "quick-summary.json",
            (json.dumps(summary, sort_keys=True, separators=(",", ":")) + "\n").encode(),
        )
        if outcome != "PASS":
            raise StageResult(outcome, *(reason_codes or ("STAGE_RESULT_INVALID",)))

    def _required_stage_result(self, stage_id: str) -> dict[str, Any]:
        try:
            result = self._read_json(self.run_dir / "results" / f"{stage_id}.json", 65536)
            if (
                not isinstance(result, dict)
                or result.get("schemaVersion") != 1
                or result.get("runId") != self.run_id
                or result.get("stageId") != stage_id
                or result.get("candidateManifestSha256") != self.candidate_hash
                or result.get("outcome") not in ("PASS", "FAIL", "BLOCKED")
                or not isinstance(result.get("reasonCodes"), list)
                or len(result["reasonCodes"]) > 16
                or any(
                    not isinstance(reason, str) or not SAFE_REASON.fullmatch(reason)
                    for reason in result["reasonCodes"]
                )
                or (result.get("outcome") == "PASS" and result["reasonCodes"])
                or (result.get("outcome") != "PASS" and not result["reasonCodes"])
            ):
                raise StageResult("BLOCKED", "STAGE_RESULT_INVALID")
            return {
                "stageId": stage_id,
                "outcome": result["outcome"],
                "reasonCodes": result["reasonCodes"],
            }
        except StageResult:
            return {
                "stageId": stage_id,
                "outcome": "BLOCKED",
                "reasonCodes": ["STAGE_RESULT_INVALID"],
            }

    def _run_observer(
        self,
        executable: Path,
        arguments: Sequence[str],
        blockers: list[str],
        failures: list[str],
        prefix: str,
        timeout_seconds: int,
    ) -> int | None:
        if not self._is_executable(executable):
            blockers.append(f"{prefix}_CLIENT_UNAVAILABLE")
            return None
        try:
            status = subprocess.run(
                [str(executable), *arguments],
                check=False,
                stdin=subprocess.DEVNULL,
                timeout=timeout_seconds,
            ).returncode
        except (OSError, subprocess.SubprocessError):
            blockers.append(f"{prefix}_CLIENT_UNAVAILABLE")
            return None
        if status in (2, 40):
            blockers.append(f"{prefix}_COLLECTION_BLOCKED")
        elif status != 0:
            failures.append(f"{prefix}_COLLECTION_FAILED")
        return status

    def _capture_json(
        self,
        executable: Path,
        arguments: Sequence[str],
        output: Path,
        blockers: list[str],
        failures: list[str],
        prefix: str,
    ) -> int | None:
        if not self._is_executable(executable):
            blockers.append(f"{prefix}_UNAVAILABLE")
            return None
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
                    timeout=120,
                ).returncode
                handle.flush()
                os.fsync(handle.fileno())
            if temporary.stat().st_size > MAX_JSON_BYTES:
                raise ValueError("output too large")
            json.loads(temporary.read_text(encoding="utf-8"))
            os.chmod(temporary, 0o600)
            os.replace(temporary, output)
            temporary = None
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError, subprocess.SubprocessError):
            blockers.append(f"{prefix}_OUTPUT_INVALID")
            return None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        if status in (2, 3, 40):
            blockers.append(f"{prefix}_BLOCKED")
        elif status != 0:
            failures.append(f"{prefix}_FAILED")
        return status

    def _check_snapshot(
        self,
        path: Path,
        collector: str,
        phase: str,
        source: str,
        command_status: int | None,
        blockers: list[str],
        failures: list[str],
    ) -> None:
        if command_status is None:
            return
        try:
            snapshot = self._read_json(path, MAX_JSON_BYTES)
        except StageResult:
            blockers.append(f"{collector.upper().replace('-', '_')}_EVIDENCE_INVALID")
            return
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
            blockers.append(f"{collector.upper().replace('-', '_')}_EVIDENCE_INVALID")
        elif snapshot["status"] == "BLOCKED":
            blockers.append(f"{collector.upper().replace('-', '_')}_COLLECTION_BLOCKED")
        elif command_status != 0:
            failures.append(f"{collector.upper().replace('-', '_')}_STATUS_INCONSISTENT")

    def _run_fixed(self, executable: Path, arguments: Sequence[str]) -> int:
        if not self._is_executable(executable):
            return EXIT_BLOCKED
        try:
            return subprocess.run(
                [str(executable), *arguments],
                check=False,
                stdin=subprocess.DEVNULL,
                timeout=300,
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
            mode = path.lstat().st_mode
            return stat.S_ISREG(mode) and not path.is_symlink()
        except OSError:
            return False

    @classmethod
    def _is_executable(cls, path: Path) -> bool:
        if not path.is_absolute():
            return False
        try:
            resolved = path.resolve(strict=True)
            mode = resolved.stat().st_mode
        except OSError:
            return False
        return stat.S_ISREG(mode) and os.access(resolved, os.X_OK)

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
        print("test1-quick: arguments are not accepted", file=sys.stderr)
        return EXIT_BLOCKED
    return Controller(Config()).run()


if __name__ == "__main__":
    sys.exit(main())
