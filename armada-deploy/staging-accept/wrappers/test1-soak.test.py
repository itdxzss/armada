#!/usr/bin/env python3
"""Contract tests for the deterministic fixed test1 soak wrapper."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parent
WRAPPER = ROOT / "test1-soak.py"
PLANS = ROOT.parent / "plans"
RUN_ID = "20260826T090000Z-a1b2c3d4"


def load_module():
    spec = importlib.util.spec_from_file_location("test1_soak", WRAPPER)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load soak wrapper")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class SoakWrapperTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.run_root = self.root / "runs"
        self.run_dir = self.run_root / RUN_ID
        self.run_dir.mkdir(parents=True, mode=0o700)
        self.entrypoint = self.executable("test1-soak", "#!/bin/sh\nexit 0\n")
        self.config = self.module.Config(
            run_root=self.run_root,
            entrypoint=self.entrypoint,
            deep_check_client=self.entrypoint,
            runtime_observer_client=self.entrypoint,
            preflight_script=self.entrypoint,
            web_observer_client=self.entrypoint,
            backend_observer_client=self.entrypoint,
            cloudwatch_observer_client=self.entrypoint,
            collector_script=self.root / "collector.py",
            evaluator_script=self.root / "evaluate.py",
            python=Path(sys.executable).resolve(),
            wait_seconds_override=0,
            command_timeout_seconds=5,
        )
        self.write_plan("test1-soak-1h")

    def tearDown(self):
        self.temporary.cleanup()

    def executable(self, name: str, content: str) -> Path:
        path = self.root / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)
        return path

    def write_plan(self, profile: str):
        duration = self.module.PROFILE_SECONDS[profile]
        source = json.loads((PLANS / f"{profile}.json").read_text())
        for stage in source["stages"]:
            stage["command"] = [str(self.entrypoint)]
        (self.run_dir / "plan.json").write_text(
            json.dumps(source, indent=2) + "\n", encoding="utf-8"
        )
        self.assertEqual(
            list(self.module.soak_stages(duration)),
            [(row["id"], row["timeoutSeconds"]) for row in source["stages"]],
        )

    def controller(self, stage: str):
        environment = {
            "STAGING_ACCEPT_RUN_ID": RUN_ID,
            "STAGING_ACCEPT_STAGE_ID": stage,
            "STAGING_ACCEPT_RUN_DIR": str(self.run_dir),
        }
        with mock.patch.dict(os.environ, environment, clear=True):
            value = self.module.Controller(self.config)
            value._load_context()
            value._load_plan()
        return value

    def bind(self):
        controller = self.controller("candidate-bind")
        controller._bind_candidate()
        return controller

    def test_three_plans_are_fixed_read_only_and_have_exact_stage_order(self):
        for profile, duration in self.module.PROFILE_SECONDS.items():
            with self.subTest(profile=profile):
                plan = json.loads((PLANS / f"{profile}.json").read_text())
                self.assertEqual("test1", plan["environment"])
                self.assertEqual("read-only", plan["safety"])
                self.assertEqual(
                    list(self.module.soak_stages(duration)),
                    [(row["id"], row["timeoutSeconds"]) for row in plan["stages"]],
                )
                self.assertTrue(
                    all(
                        row["command"]
                        == ["/usr/local/libexec/staging-accept/test1-soak"]
                        for row in plan["stages"]
                    )
                )

    def test_plan_contract_rejects_profile_duration_or_command_drift(self):
        for mutation in ("profile", "timeout", "command"):
            with self.subTest(mutation=mutation):
                self.write_plan("test1-soak-1h")
                path = self.run_dir / "plan.json"
                plan = json.loads(path.read_text())
                if mutation == "profile":
                    plan["profile"] = "test1-soak-arbitrary"
                elif mutation == "timeout":
                    plan["stages"][3]["timeoutSeconds"] += 1
                else:
                    plan["stages"][0]["command"] = ["/usr/bin/env"]
                path.write_text(json.dumps(plan) + "\n")
                with self.assertRaises(self.module.StageResult):
                    self.controller("candidate-bind")

    def test_wait_stages_use_profile_halves_and_allow_zero_test_override(self):
        self.bind()
        controller = self.controller("soak-to-peak")
        controller._load_bound_candidate()
        with mock.patch.object(self.module.time, "sleep") as sleep:
            controller._dispatch()
        sleep.assert_called_once_with(0)

        controller.stage_id = "soak-to-end"
        with mock.patch.object(self.module.time, "sleep") as sleep:
            controller._dispatch()
        sleep.assert_called_once_with(0)

    def test_cloudwatch_requires_exact_fifteen_ok_aliases(self):
        controller = self.bind()
        controller.stage_id = "verify-start"
        controller._load_bound_candidate()
        alarms = [
            {"instance": instance, "signal": signal, "state": "OK"}
            for instance in self.module.TEST1_INSTANCES.values()
            for signal in self.module.ALARM_SIGNALS.values()
        ]
        evidence = {
            "schemaVersion": 1,
            "collector": "cloudwatch-alarms",
            "environment": "test1",
            "phase": "start",
            "runId": RUN_ID,
            "candidateManifestSha256": controller.candidate_hash,
            "provenance": "live",
            "status": "COLLECTED",
            "health": {"ok": True, "checks": [], "blockedReasons": []},
            "region": "ap-south-1",
            "expectedAlarmCount": 15,
            "alarms": alarms,
        }
        observability = self.run_dir / "observability"
        observability.mkdir(mode=0o700)
        path = observability / "cloudwatch-start.json"
        path.write_text(json.dumps(evidence) + "\n")
        path.chmod(0o600)
        with mock.patch.object(controller, "_run_observer", return_value=0):
            controller._cloudwatch("start")

        evidence["alarms"].pop()
        path.write_text(json.dumps(evidence) + "\n")
        with mock.patch.object(controller, "_run_observer", return_value=0), self.assertRaises(
            (ValueError, self.module.StageResult)
        ):
            controller._cloudwatch("start")

    def test_android_collector_arguments_are_fixed_and_contain_three_node_mappings(self):
        calls = self.root / "android-calls.json"
        collector = self.executable(
            "collector.py",
            """#!/usr/bin/env python3
import json
import sys
from pathlib import Path

args = sys.argv[1:]
Path(__file__).with_name('android-calls.json').write_text(json.dumps(args))
def value(name):
    return args[args.index(name) + 1]
nodes = []
for label, node_id in (('node01', '01'), ('node02', '02'), ('node03', '03')):
    nodes.append({'label': label, 'nodeId': node_id})
print(json.dumps({
    'schemaVersion': 1,
    'collector': 'android-traffic',
    'environment': 'test1',
    'phase': value('--phase'),
    'runId': value('--run-id'),
    'candidateManifestSha256': value('--candidate-manifest-sha256'),
    'provenance': 'live',
    'status': 'COLLECTED',
    'health': {'ok': True, 'checks': [], 'blockedReasons': []},
    'raw': {'nodes': nodes},
}))
""",
        )
        self.config = self.module.Config(
            **{
                **self.config.__dict__,
                "collector_script": collector,
            }
        )
        controller = self.bind()
        controller.stage_id = "observe-start"
        output = self.run_dir / "observability"
        output.mkdir(mode=0o700)

        controller._collect_android("start", output)

        arguments = json.loads(calls.read_text())
        self.assertEqual("android-traffic", arguments[0])
        self.assertEqual("3", arguments[arguments.index("--expected-targets") + 1])
        targets = [
            arguments[index + 1]
            for index, value in enumerate(arguments)
            if value == "--target"
        ]
        mappings = [
            arguments[index + 1]
            for index, value in enumerate(arguments)
            if value == "--expected-node-id"
        ]
        self.assertEqual(
            [f"{label}={url}" for label, url, _ in self.module.ANDROID_TARGETS],
            targets,
        )
        self.assertEqual(
            [f"{label}={node_id}" for label, _, node_id in self.module.ANDROID_TARGETS],
            mappings,
        )

    def test_evaluator_pass_cannot_hide_missing_stage_results(self):
        controller = self.bind()
        controller.stage_id = "evaluate-soak"
        controller._load_bound_candidate()

        def evaluator_pass(_executable, _arguments, output, **_kwargs):
            output.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "evaluator": "observability",
                        "environment": "test1",
                        "status": "PASS",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            return 0

        with mock.patch.object(controller, "_capture_json", side_effect=evaluator_pass):
            with self.assertRaises(self.module.StageResult) as raised:
                controller._evaluate()

        self.assertEqual("BLOCKED", raised.exception.outcome)
        self.assertIn("STAGE_RESULT_INVALID", raised.exception.reason_codes)
        summary = json.loads((self.run_dir / "soak-summary.json").read_text())
        self.assertEqual("BLOCKED", summary["outcome"])
        self.assertIn("STAGE_RESULT_INVALID", summary["reasonCodes"])

    def test_evaluator_failure_remains_failure_when_prior_stages_pass(self):
        controller = self.bind()
        controller.stage_id = "evaluate-soak"
        controller._load_bound_candidate()
        results = self.run_dir / "results"
        results.mkdir(mode=0o700)
        for stage_id, _ in self.module.soak_stages(controller.duration_seconds):
            if stage_id == "evaluate-soak":
                continue
            (results / f"{stage_id}.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "runId": RUN_ID,
                        "stageId": stage_id,
                        "candidateManifestSha256": controller.candidate_hash,
                        "outcome": "PASS",
                        "reasonCodes": [],
                    }
                )
                + "\n",
                encoding="utf-8",
            )

        def evaluator_fail(_executable, _arguments, output, **_kwargs):
            output.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "evaluator": "observability",
                        "environment": "test1",
                        "status": "FAIL",
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            return 2

        with mock.patch.object(controller, "_capture_json", side_effect=evaluator_fail):
            with self.assertRaises(self.module.StageResult) as raised:
                controller._evaluate()

        self.assertEqual("FAIL", raised.exception.outcome)
        self.assertNotIn("STAGE_RESULT_INVALID", raised.exception.reason_codes)
        summary = json.loads((self.run_dir / "soak-summary.json").read_text())
        self.assertEqual("FAIL", summary["outcome"])
        self.assertNotIn("STAGE_RESULT_INVALID", summary["reasonCodes"])

    def test_plans_and_wrapper_have_no_whatsapp_write_or_fault_injection_path(self):
        content = WRAPPER.read_text().lower()
        for plan in sorted(PLANS.glob("test1-soak-*.json")):
            content += plan.read_text().lower()
        for forbidden in (
            "single-group-probe",
            "add-existing-group-participants",
            "send-message",
            "canary",
            "fault-inject",
            ".pem",
            "password",
            "token",
        ):
            self.assertNotIn(forbidden, content)

    def test_production_entrypoint_rejects_arguments(self):
        completed = subprocess.run(
            [sys.executable, str(WRAPPER), "unexpected"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(40, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertEqual("test1-soak: arguments are not accepted\n", completed.stderr)


if __name__ == "__main__":
    unittest.main()
