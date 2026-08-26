#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("test1-group-classification-canary.py")
SPEC = importlib.util.spec_from_file_location("group_classification_canary", MODULE_PATH)
assert SPEC and SPEC.loader
canary = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = canary
SPEC.loader.exec_module(canary)


class CanaryTests(unittest.TestCase):
    def test_classification_requires_canonical_and_mutually_exclusive_legacy(self) -> None:
        valid = [
            {"groupClassification": "POST_CONTROL", "isHistorical": False, "isPostControl": True}
            for _ in range(3)
        ]
        self.assertTrue(canary.Controller._valid_classification(valid))
        for replacement in (
            [{**valid[0], "groupClassification": "UNCLASSIFIED"}, *valid[1:]],
            [{**valid[0], "isHistorical": True}, *valid[1:]],
            valid[:2],
        ):
            self.assertFalse(canary.Controller._valid_classification(replacement))

    def test_parse_env_is_strict_and_does_not_expand_values(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / "ui.env"
            path.write_text(
                "ENVIRONMENT=test1\n"
                "ARMADA_E2E_BASE_URL=http://127.0.0.1/\n"
                "ARMADA_E2E_USERNAME='canary-user'\n"
                "ARMADA_E2E_PASSWORD='$NOT_EXPANDED'\n",
                encoding="utf-8",
            )
            values = canary.parse_env(path)
            self.assertEqual(values["ARMADA_E2E_PASSWORD"], "$NOT_EXPANDED")

    def test_checksum_manifest_rejects_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            evidence = root / "summary.json"
            evidence.write_text("{}\n", encoding="utf-8")
            digest = canary.hashlib.sha256(evidence.read_bytes()).hexdigest()
            (root / "checksums.sha256").write_text(f"{digest}  summary.json\n", encoding="utf-8")
            canary.verify_checksum_manifest(root)
            evidence.write_text('{"changed":true}\n', encoding="utf-8")
            with self.assertRaisesRegex(canary.StageResult, "PREREQUISITE_CHECKSUM_INVALID"):
                canary.verify_checksum_manifest(root)

    def test_envelope_budget_is_exact(self) -> None:
        value = {
            "schemaVersion": 1,
            "reference": "group-classification-v1",
            "changeId": "2026-08-26-group-canonical-first-classification",
            "scopeHash": canary.EXPECTED_SCOPE_HASH,
            "environment": "test1",
            "prerequisiteRunId": "20260826T114941Z-1d66b842",
            "resourceAlias": "ag-c7c3edc6ec",
            "accountGroupId": 12,
            "expectedProtocolBackend": "ANDROID",
            "maxDistinctAccounts": 6,
            "groupCreateCount": 3,
            "memberAddsPerGroup": 1,
            "maxContactSaves": 6,
            "messageCount": 0,
            "leaveActionCount": 0,
            "existingGroupMutationCount": 0,
            "maxConcurrency": 1,
            "maxDurationSeconds": 1200,
            "cleanupPolicy": "RETAIN_NAMED_CANARY_GROUPS_NO_LEAVE_NO_DELETE",
        }
        controller = canary.Controller(canary.Config())
        controller.envelope = value
        controller._validate_envelope("group-classification-v1")
        controller.envelope = {**value, "messageCount": 1}
        with self.assertRaisesRegex(canary.StageResult, "SAFETY_BUDGET_MISMATCH"):
            controller._validate_envelope("group-classification-v1")
        controller.envelope = {**value, "expectedProtocolBackend": "WEB"}
        controller._validate_envelope("group-classification-v1")
        controller.envelope = {**value, "expectedProtocolBackend": "DESKTOP"}
        with self.assertRaisesRegex(canary.StageResult, "SAFETY_ENVELOPE_INVALID"):
            controller._validate_envelope("group-classification-v1")

    def test_resource_lease_is_exclusive_and_resumable_by_owner(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            controller = canary.Controller(canary.Config(lease_root=root))
            controller.run_id = "20260826T120000Z-00000001"
            controller.envelope = {"resourceAlias": "ag-c7c3edc6ec"}
            controller._acquire_lease()
            controller._acquire_lease()
            other = canary.Controller(canary.Config(lease_root=root))
            other.run_id = "20260826T120000Z-00000002"
            other.envelope = {"resourceAlias": "ag-c7c3edc6ec"}
            with self.assertRaisesRegex(canary.StageResult, "RESOURCE_LEASE_CONFLICT"):
                other._acquire_lease()

    def test_plan_stage_order_matches_committed_plan(self) -> None:
        plan = json.loads(
            (MODULE_PATH.parent.parent / "plans" / "test1-group-classification-canary.json").read_text(encoding="utf-8")
        )
        self.assertEqual([row["id"] for row in plan["stages"]], [row[0] for row in canary.STAGES])
        self.assertEqual(plan["safety"], "controlled-canary")
        self.assertEqual(plan["safetyEnvelopeRef"], "group-classification-v1")

    def test_task_creation_uses_fixed_budget_and_idempotency(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            controller = canary.Controller(canary.Config())
            controller.run_id = "20260826T120000Z-00000001"
            controller.run_dir = Path(raw)
            controller.envelope = {"accountGroupId": 12}
            calls: list[tuple] = []

            class FakeClient:
                def request(self, method, path, body=None, query=None, headers=None, authenticated=True):
                    calls.append((method, path, body, headers))
                    if method == "POST":
                        return {"id": 44}
                    return {
                        "task": {"status": "SUCCESS", "successCount": 3},
                        "items": [{"status": "CREATED"} for _ in range(3)],
                    }

            with mock.patch.object(controller, "_require_lease"), mock.patch.object(controller, "_client", return_value=FakeClient()):
                controller._execute_canary()
            post = calls[0]
            self.assertEqual(post[2]["groupCount"], 3)
            self.assertEqual(post[2]["memberCount"], 1)
            self.assertEqual(post[2]["creatorLeavePolicy"], "KEEP")
            self.assertEqual(post[3]["Idempotency-Key"], "gcf-canary-" + controller.run_id)
            self.assertFalse({"message", "messages", "content", "text"} & set(post[2]))


if __name__ == "__main__":
    unittest.main()
