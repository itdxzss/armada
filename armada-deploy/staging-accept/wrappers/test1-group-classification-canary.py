#!/usr/bin/env python3
"""Fixed, budgeted test1 canary for canonical first group classification."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXIT_FAIL = 30
EXIT_BLOCKED = 40
RUN_ID = re.compile(r"^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")
SAFE_ID = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_SCOPE_HASH = "316839dc898e558b494ff6835abf15cd55d942e8a730c927ba76a6c25be61825"
EXPECTED_BUILDS = {
    "backend": "b637cf1e8c2124e678332300d0631db3295890e6",
    "frontend": "df3799c64870c8fb893ec7fac8c426634961ae50",
    "webProtocol": "1415022fa322221eba2d6cd85b0d2e66d26429ff",
    "androidProtocol": "9677fe69625432b004c9eb8e901c229f599ebf9a",
}
STAGES = (
    ("candidate-bind", 60),
    ("resource-preflight", 60),
    ("execute-canary", 1200),
    ("verify-canary", 420),
    ("evaluate-canary", 60),
    ("release-lease", 30),
)
TERMINAL_TASK = {"SUCCESS", "PARTIAL", "FAILED"}
SUCCESS_ITEMS = {"CREATED", "CREATED_PARTIAL"}


class StageResult(Exception):
    def __init__(self, outcome: str, reason: str):
        super().__init__(reason)
        self.outcome = outcome
        self.reason = reason

    @property
    def exit_code(self) -> int:
        return EXIT_FAIL if self.outcome == "FAIL" else EXIT_BLOCKED


@dataclass(frozen=True)
class Config:
    run_root: Path = Path("/var/lib/staging-accept/runs")
    envelope_root: Path = Path("/etc/staging-accept/safety-envelopes")
    lease_root: Path = Path("/var/lib/staging-accept/canary-leases")
    credentials_path: Path = Path("/etc/staging-accept/ui-smoke.env")
    prior_run_root: Path = Path("/var/lib/staging-accept/runs")
    poll_seconds: float = 5.0
    task_timeout_seconds: int = 900
    classification_timeout_seconds: int = 300


def atomic_json(path: Path, value: Any, mode: int = 0o600) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=True, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, mode)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def read_json(path: Path, maximum: int = 1024 * 1024) -> Any:
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_size > maximum:
        raise StageResult("BLOCKED", "EVIDENCE_FILE_INVALID")
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def parse_env(path: Path) -> dict[str, str]:
    expected = {
        "ENVIRONMENT",
        "ARMADA_E2E_BASE_URL",
        "ARMADA_E2E_USERNAME",
        "ARMADA_E2E_PASSWORD",
    }
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, raw_value = line.partition("=")
        if not separator or key not in expected or key in values:
            raise StageResult("BLOCKED", "CREDENTIAL_FILE_INVALID")
        value = raw_value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        values[key] = value
    if set(values) != expected or values["ENVIRONMENT"] != "test1":
        raise StageResult("BLOCKED", "CREDENTIAL_FILE_INVALID")
    if values["ARMADA_E2E_BASE_URL"] != "http://127.0.0.1/":
        raise StageResult("BLOCKED", "API_TARGET_INVALID")
    return values


def verify_checksum_manifest(directory: Path) -> None:
    manifest = directory / "checksums.sha256"
    lines = manifest.read_text(encoding="utf-8").splitlines()
    if not lines:
        raise StageResult("BLOCKED", "PREREQUISITE_CHECKSUM_INVALID")
    for line in lines:
        digest, separator, relative = line.partition("  ")
        if not separator or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise StageResult("BLOCKED", "PREREQUISITE_CHECKSUM_INVALID")
        candidate = (directory / relative).resolve()
        if candidate.parent != directory.resolve() and directory.resolve() not in candidate.parents:
            raise StageResult("BLOCKED", "PREREQUISITE_CHECKSUM_INVALID")
        if hashlib.sha256(candidate.read_bytes()).hexdigest() != digest:
            raise StageResult("BLOCKED", "PREREQUISITE_CHECKSUM_INVALID")


class ApiClient:
    def __init__(self, base_url: str, username: str, password: str):
        self.base_url = base_url
        self.username = username
        self.password = password
        self.token = ""

    def login(self) -> None:
        data = self.request(
            "POST",
            "api/public/auth/login",
            {"username": self.username, "password": self.password,
             "captchaId": "", "captchaCode": ""},
            authenticated=False,
        )
        token = data.get("token") if isinstance(data, dict) else None
        if not isinstance(token, str) or not token:
            raise StageResult("BLOCKED", "AUTH_RESPONSE_INVALID")
        self.token = token

    def request(
        self,
        method: str,
        path: str,
        body: Any | None = None,
        query: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
        authenticated: bool = True,
    ) -> Any:
        url = urllib.parse.urljoin(self.base_url, path)
        if query:
            url += "?" + urllib.parse.urlencode(query)
        payload = None if body is None else json.dumps(body).encode("utf-8")
        request_headers = {"Accept": "application/json"}
        if payload is not None:
            request_headers["Content-Type"] = "application/json"
        if authenticated:
            if not self.token:
                raise StageResult("BLOCKED", "AUTH_CONTEXT_INVALID")
            request_headers["Authorization"] = f"Bearer {self.token}"
        if headers:
            request_headers.update(headers)
        request = urllib.request.Request(url, data=payload, headers=request_headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                value = json.load(response)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            raise StageResult("BLOCKED", "API_UNAVAILABLE") from error
        if not isinstance(value, dict) or value.get("code") != 0:
            raise StageResult("FAIL", "API_BUSINESS_ERROR")
        return value.get("data")


class Controller:
    def __init__(self, config: Config):
        self.config = config
        self.run_id = ""
        self.stage_id = ""
        self.run_dir = Path("/")
        self.plan: dict[str, Any] = {}
        self.envelope: dict[str, Any] = {}

    def run(self) -> int:
        try:
            self._load_context()
            self._load_plan_and_envelope()
            getattr(self, "_" + self.stage_id.replace("-", "_"))()
        except StageResult as result:
            self._write_result(result.outcome, result.reason)
            print(f"RESULT {result.outcome} stage={self.stage_id or 'context'} reasons={result.reason}", file=sys.stderr)
            return result.exit_code
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
            self._write_result("BLOCKED", "HARNESS_FAILURE")
            print(f"RESULT BLOCKED stage={self.stage_id or 'context'} reasons=HARNESS_FAILURE", file=sys.stderr)
            return EXIT_BLOCKED
        self._write_result("PASS", "")
        print(f"RESULT PASS stage={self.stage_id}")
        return 0

    def _load_context(self) -> None:
        self.run_id = os.environ.get("STAGING_ACCEPT_RUN_ID", "")
        self.stage_id = os.environ.get("STAGING_ACCEPT_STAGE_ID", "")
        raw = os.environ.get("STAGING_ACCEPT_RUN_DIR", "")
        if not RUN_ID.fullmatch(self.run_id) or self.stage_id not in dict(STAGES) or not raw:
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        run_dir = Path(raw)
        root = self.config.run_root.resolve(strict=True)
        resolved = run_dir.resolve(strict=True)
        if resolved.parent != root or resolved.name != self.run_id or resolved.is_symlink():
            raise StageResult("BLOCKED", "RUN_CONTEXT_INVALID")
        self.run_dir = resolved

    def _load_plan_and_envelope(self) -> None:
        self.plan = read_json(self.run_dir / "plan.json")
        expected_keys = {"schemaVersion", "profile", "environment", "safety", "safetyEnvelopeRef", "builds", "stages"}
        expected_stages = [
            {"id": stage, "command": ["/usr/local/libexec/staging-accept/test1-group-classification-canary"], "timeoutSeconds": timeout}
            for stage, timeout in STAGES
        ]
        if (
            not isinstance(self.plan, dict)
            or set(self.plan) != expected_keys
            or self.plan.get("schemaVersion") != 1
            or self.plan.get("profile") != "test1-group-classification-canary"
            or self.plan.get("environment") != "test1"
            or self.plan.get("safety") != "controlled-canary"
            or self.plan.get("builds") != EXPECTED_BUILDS
            or self.plan.get("stages") != expected_stages
        ):
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        reference = self.plan.get("safetyEnvelopeRef")
        if not isinstance(reference, str) or not SAFE_ID.fullmatch(reference):
            raise StageResult("BLOCKED", "PLAN_CONTRACT_INVALID")
        path = self.config.envelope_root / f"{reference}.json"
        info = path.stat()
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) & 0o007:
            raise StageResult("BLOCKED", "SAFETY_ENVELOPE_PERMISSIONS_INVALID")
        self.envelope = read_json(path)
        self._validate_envelope(reference)

    def _validate_envelope(self, reference: str) -> None:
        required = {
            "schemaVersion", "reference", "changeId", "scopeHash", "environment",
            "prerequisiteRunId", "resourceAlias", "accountGroupId", "expectedProtocolBackend",
            "maxDistinctAccounts", "groupCreateCount", "memberAddsPerGroup", "maxContactSaves",
            "messageCount", "leaveActionCount", "existingGroupMutationCount", "maxConcurrency",
            "maxDurationSeconds", "cleanupPolicy",
        }
        value = self.envelope
        if not isinstance(value, dict) or set(value) != required:
            raise StageResult("BLOCKED", "SAFETY_ENVELOPE_INVALID")
        exact = {
            "schemaVersion": 1, "reference": reference,
            "changeId": "2026-08-26-group-canonical-first-classification",
            "scopeHash": EXPECTED_SCOPE_HASH, "environment": "test1",
            "maxDistinctAccounts": 6,
            "groupCreateCount": 3, "memberAddsPerGroup": 1, "maxContactSaves": 6,
            "messageCount": 0, "leaveActionCount": 0, "existingGroupMutationCount": 0,
            "maxConcurrency": 1, "maxDurationSeconds": 1200,
            "cleanupPolicy": "RETAIN_NAMED_CANARY_GROUPS_NO_LEAVE_NO_DELETE",
        }
        if any(value.get(key) != expected for key, expected in exact.items()):
            raise StageResult("BLOCKED", "SAFETY_BUDGET_MISMATCH")
        if value.get("expectedProtocolBackend") not in {"WEB", "ANDROID"}:
            raise StageResult("BLOCKED", "SAFETY_ENVELOPE_INVALID")
        if not RUN_ID.fullmatch(str(value.get("prerequisiteRunId", ""))):
            raise StageResult("BLOCKED", "SAFETY_ENVELOPE_INVALID")
        if not SAFE_ID.fullmatch(str(value.get("resourceAlias", ""))):
            raise StageResult("BLOCKED", "SAFETY_ENVELOPE_INVALID")
        account_group_id = value.get("accountGroupId")
        if not isinstance(account_group_id, int) or isinstance(account_group_id, bool) or account_group_id <= 0:
            raise StageResult("BLOCKED", "SAFETY_ENVELOPE_INVALID")

    def _client(self) -> ApiClient:
        values = parse_env(self.config.credentials_path)
        client = ApiClient(values["ARMADA_E2E_BASE_URL"], values["ARMADA_E2E_USERNAME"], values["ARMADA_E2E_PASSWORD"])
        client.login()
        return client

    def _candidate_bind(self) -> None:
        prior_id = self.envelope["prerequisiteRunId"]
        prior_dir = (self.config.prior_run_root / prior_id).resolve(strict=True)
        if prior_dir.parent != self.config.prior_run_root.resolve(strict=True):
            raise StageResult("BLOCKED", "PREREQUISITE_RUN_INVALID")
        verify_checksum_manifest(prior_dir)
        summary = read_json(prior_dir / "summary.json")
        if (
            not isinstance(summary, dict) or summary.get("runId") != prior_id
            or summary.get("environment") != "test1" or summary.get("status") != "PASS"
            or summary.get("builds") != EXPECTED_BUILDS
        ):
            raise StageResult("BLOCKED", "PREREQUISITE_RUN_INVALID")
        atomic_json(self.run_dir / "candidate-manifest.json", {
            "schemaVersion": 1, "prerequisiteRunId": prior_id, "status": "BOUND",
            "builds": EXPECTED_BUILDS, "scopeHash": EXPECTED_SCOPE_HASH,
        })

    def _lease_path(self) -> Path:
        return self.config.lease_root / "group-classification.json"

    def _acquire_lease(self) -> None:
        path = self._lease_path()
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        value = {"schemaVersion": 1, "runId": self.run_id, "resourceAlias": self.envelope["resourceAlias"], "state": "ACTIVE"}
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            current = read_json(path, 65536)
            if not isinstance(current, dict) or current.get("runId") != self.run_id:
                raise StageResult("BLOCKED", "RESOURCE_LEASE_CONFLICT")
            return
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())

    def _require_lease(self) -> None:
        value = read_json(self._lease_path(), 65536)
        if not isinstance(value, dict) or value.get("runId") != self.run_id or value.get("state") != "ACTIVE":
            raise StageResult("BLOCKED", "RESOURCE_LEASE_INVALID")

    def _resource_preflight(self) -> None:
        self._acquire_lease()
        client = self._client()
        current = client.request("GET", "api/auth/me")
        permissions = set((current.get("user") or {}).get("permissions") or []) if isinstance(current, dict) else set()
        required = {"tenant:normal_group:create", "tenant:normal_group:view", "tenant:account:view", "tenant:group_link:view"}
        if not required.issubset(permissions):
            raise StageResult("BLOCKED", "CANARY_PERMISSION_MISSING")
        page = client.request("GET", "api/accounts", query={
            "page": 1, "pageSize": 100, "accountGroupId": self.envelope["accountGroupId"],
            "accountState": 2, "loginState": 1, "marketingOccupancyType": "FREE", "callable": "true",
        })
        rows = page.get("list") if isinstance(page, dict) else None
        expected_backend = self.envelope["expectedProtocolBackend"]
        eligible = [row for row in rows or [] if isinstance(row, dict) and row.get("protocolBackend") == expected_backend and row.get("marketingOccupancyType") == "FREE"]
        if len(eligible) < self.envelope["maxDistinctAccounts"]:
            raise StageResult("BLOCKED", "CANARY_RESOURCES_INSUFFICIENT")
        atomic_json(self.run_dir / "resource-preflight.json", {
            "schemaVersion": 1, "resourceAlias": self.envelope["resourceAlias"],
            "eligibleCount": len(eligible), "requiredCount": self.envelope["maxDistinctAccounts"],
            "protocolBackend": expected_backend, "status": "READY",
        })

    def _task_state_path(self) -> Path:
        return self.run_dir / "canary-task-state.json"

    def _execute_canary(self) -> None:
        self._require_lease()
        client = self._client()
        prefix = "ARMADA-CANARY-GCF-" + self.run_id[-8:].upper()
        payload = {
            "adminAccountGroupId": self.envelope["accountGroupId"],
            "secondaryAdminAccountGroupId": None, "secondaryAdminCount": 0,
            "creatorLeavePolicy": "KEEP", "memberSource": "CONTROLLED_GROUP",
            "memberAccountGroupId": self.envelope["accountGroupId"], "memberCount": 1,
            "folderId": None, "groupNameTemplate": prefix + "-{no}", "groupCount": 3,
            "startNo": 1, "speed": "NORMAL", "successMigrationGroupId": None,
            "failedMigrationGroupId": None,
            "settings": {"sendMessagesAllowed": True, "editGroupSettingsAllowed": False,
                         "addMembersAllowed": True, "joinApprovalEnabled": False,
                         "ephemeralDurationSeconds": 0},
        }
        created = client.request("POST", "api/normal-group-creation-tasks", payload,
                                 headers={"Idempotency-Key": "gcf-canary-" + self.run_id})
        task_id = created.get("id") if isinstance(created, dict) else None
        if not isinstance(task_id, int) or isinstance(task_id, bool) or task_id <= 0:
            raise StageResult("BLOCKED", "CANARY_TASK_RESPONSE_INVALID")
        atomic_json(self._task_state_path(), {"schemaVersion": 1, "taskId": task_id, "groupPrefix": prefix})
        detail = self._poll_task(client, task_id)
        task = detail.get("task") if isinstance(detail, dict) else None
        items = detail.get("items") if isinstance(detail, dict) else None
        if not isinstance(task, dict) or task.get("status") != "SUCCESS" or task.get("successCount") != 3:
            raise StageResult("FAIL", "CANARY_GROUP_CREATION_FAILED")
        if not isinstance(items, list) or len(items) != 3 or any(not isinstance(item, dict) or item.get("status") not in SUCCESS_ITEMS for item in items):
            raise StageResult("FAIL", "CANARY_GROUP_CREATION_FAILED")
        atomic_json(self.run_dir / "execution-summary.json", {
            "schemaVersion": 1, "status": "SUCCESS", "groupsCreated": 3,
            "messagesSent": 0, "leaveActions": 0, "existingGroupsMutated": 0,
        })

    def _poll_task(self, client: ApiClient, task_id: int) -> dict[str, Any]:
        deadline = time.monotonic() + self.config.task_timeout_seconds
        while time.monotonic() < deadline:
            detail = client.request("GET", f"api/normal-group-creation-tasks/{task_id}")
            status = ((detail or {}).get("task") or {}).get("status") if isinstance(detail, dict) else None
            if status in TERMINAL_TASK:
                return detail
            time.sleep(self.config.poll_seconds)
        raise StageResult("BLOCKED", "CANARY_TASK_TIMEOUT")

    def _classification_snapshot(self, client: ApiClient, prefix: str) -> list[dict[str, Any]]:
        page = client.request("GET", "api/group-links", query={"page": 1, "pageSize": 20, "keyword": prefix})
        rows = page.get("list") if isinstance(page, dict) else None
        if not isinstance(rows, list):
            raise StageResult("BLOCKED", "GROUP_LIST_RESPONSE_INVALID")
        exact = [row for row in rows if isinstance(row, dict) and str(row.get("groupName") or row.get("waSubject") or "").startswith(prefix)]
        return exact

    @staticmethod
    def _valid_classification(rows: list[dict[str, Any]]) -> bool:
        return len(rows) == 3 and all(
            row.get("groupClassification") == "POST_CONTROL"
            and row.get("isHistorical") is False and row.get("isPostControl") is True
            for row in rows
        )

    def _verify_canary(self) -> None:
        self._require_lease()
        state = read_json(self._task_state_path(), 65536)
        prefix = state.get("groupPrefix") if isinstance(state, dict) else None
        if not isinstance(prefix, str) or not prefix.startswith("ARMADA-CANARY-GCF-"):
            raise StageResult("BLOCKED", "CANARY_TASK_STATE_INVALID")
        client = self._client()
        deadline = time.monotonic() + self.config.classification_timeout_seconds
        first: list[dict[str, Any]] = []
        while time.monotonic() < deadline:
            first = self._classification_snapshot(client, prefix)
            if self._valid_classification(first):
                break
            time.sleep(self.config.poll_seconds)
        if not self._valid_classification(first):
            raise StageResult("FAIL", "CANONICAL_CLASSIFICATION_MISMATCH")
        time.sleep(self.config.poll_seconds)
        second = self._classification_snapshot(client, prefix)
        if not self._valid_classification(second):
            raise StageResult("FAIL", "CANONICAL_CLASSIFICATION_DRIFT")
        aliases = sorted(
            "grp-" + hashlib.sha256(str(row.get("groupJid", "")).encode("utf-8")).hexdigest()[:12]
            for row in second
        )
        atomic_json(self.run_dir / "classification-verification.json", {
            "schemaVersion": 1, "status": "PASS", "groupAliases": aliases,
            "classification": "POST_CONTROL", "legacyHistorical": False,
            "legacyPostControl": True, "stableReads": 2,
        })

    def _evaluate_canary(self) -> None:
        execution = read_json(self.run_dir / "execution-summary.json", 65536)
        verification = read_json(self.run_dir / "classification-verification.json", 65536)
        if execution.get("status") != "SUCCESS" or verification.get("status") != "PASS":
            raise StageResult("FAIL", "CANARY_EVALUATION_FAILED")
        atomic_json(self.run_dir / "canary-summary.json", {
            "schemaVersion": 1, "status": "PASS", "resourceAlias": self.envelope["resourceAlias"],
            "accountsBudget": 6, "groupsCreated": 3, "classification": "POST_CONTROL",
            "protocolBackend": self.envelope["expectedProtocolBackend"],
            "messagesSent": 0, "leaveActions": 0, "existingGroupsMutated": 0,
            "cleanupPolicy": self.envelope["cleanupPolicy"],
        })

    def _release_lease(self) -> None:
        self._require_lease()
        summary = read_json(self.run_dir / "canary-summary.json", 65536)
        if summary.get("status") != "PASS":
            raise StageResult("BLOCKED", "CANARY_NOT_EVALUATED")
        self._lease_path().unlink()

    def _write_result(self, outcome: str, reason: str) -> None:
        if not self.run_dir.is_dir() or not self.stage_id:
            return
        atomic_json(self.run_dir / "results" / f"{self.stage_id}.json", {
            "schemaVersion": 1, "stageId": self.stage_id, "outcome": outcome,
            "reasonCodes": [] if not reason else [reason],
        })


def main() -> int:
    return Controller(Config()).run()


if __name__ == "__main__":
    raise SystemExit(main())
