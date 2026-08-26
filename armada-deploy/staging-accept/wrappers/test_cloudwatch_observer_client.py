import hashlib
import importlib.util
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MODULE_PATH = ROOT / "cloudwatch-observer-client.py"
RUN_ID = "20260826T080000Z-a1b2c3d4"


def load_module():
    spec = importlib.util.spec_from_file_location("cloudwatch_observer_client", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load CloudWatch observer client")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeCloudWatch:
    def __init__(self, rows):
        self.rows = rows
        self.calls = []

    def describe_alarms(self, **kwargs):
        self.calls.append(kwargs)
        return {"MetricAlarms": self.rows}


class CloudWatchObserverClientTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.run_root = self.root / "runs"
        self.run_dir = self.run_root / RUN_ID
        self.run_dir.mkdir(parents=True, mode=0o700)
        self.manifest_content = (
            json.dumps(
                {
                    "schemaVersion": 1,
                    "profile": "test1-soak-1h",
                    "environment": "test1",
                    "safety": "read-only",
                    "builds": {
                        "backend": "1" * 40,
                        "frontend": "2" * 40,
                        "webProtocol": "3" * 40,
                        "androidProtocol": "4" * 40,
                    },
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode()
        manifest = self.run_dir / "candidate-manifest.json"
        manifest.write_bytes(self.manifest_content)
        manifest.chmod(0o600)

    def tearDown(self):
        self.temporary.cleanup()

    def environment(self, stage="verify-start"):
        return {
            "STAGING_ACCEPT_RUN_ID": RUN_ID,
            "STAGING_ACCEPT_STAGE_ID": stage,
            "STAGING_ACCEPT_RUN_DIR": str(self.run_dir),
        }

    def alarms(self, changed=None, state="OK"):
        rows = []
        for instance_id, instance in self.module.TEST1_INSTANCES.items():
            for (namespace, metric), signal in self.module.ALARM_SIGNALS.items():
                row_state = state if changed == (instance_id, metric) else "OK"
                suffix = {
                    "cpu": "CPUHigh",
                    "memory": "MemoryHigh",
                    "status-check": "StatusCheckFailed",
                }[signal]
                rows.append(
                    {
                        "AlarmName": f"Armada-test1-{instance}-{suffix}",
                        "Namespace": namespace,
                        "MetricName": metric,
                        "StateValue": row_state,
                        "Dimensions": [{"Name": "InstanceId", "Value": instance_id}],
                    }
                )
        return rows

    def test_exact_fifteen_ok_alarms_are_paginated_and_saved_without_names(self):
        client = FakeCloudWatch(self.alarms())

        status = self.module.execute(self.environment(), client, self.run_root)

        self.assertEqual(0, status)
        self.assertEqual(
            [
                {
                    "AlarmNames": list(self.module.ALARM_NAMES),
                    "AlarmTypes": ["MetricAlarm"],
                }
            ],
            client.calls,
        )
        self.assertEqual(
            {
                "Armada-test1-backend-runner-CPUHigh",
                "Armada-test1-backend-runner-MemoryHigh",
                "Armada-test1-backend-runner-StatusCheckFailed",
                "Armada-test1-web-protocol-CPUHigh",
                "Armada-test1-web-protocol-MemoryHigh",
                "Armada-test1-web-protocol-StatusCheckFailed",
                "Armada-test1-android-node1-CPUHigh",
                "Armada-test1-android-node1-MemoryHigh",
                "Armada-test1-android-node1-StatusCheckFailed",
                "Armada-test1-android-node2-CPUHigh",
                "Armada-test1-android-node2-MemoryHigh",
                "Armada-test1-android-node2-StatusCheckFailed",
                "Armada-test1-android-node3-CPUHigh",
                "Armada-test1-android-node3-MemoryHigh",
                "Armada-test1-android-node3-StatusCheckFailed",
            },
            set(client.calls[0]["AlarmNames"]),
        )
        evidence = self.run_dir / "observability" / "cloudwatch-start.json"
        payload = json.loads(evidence.read_text())
        self.assertEqual(15, len(payload["alarms"]))
        self.assertEqual(
            "sha256:" + hashlib.sha256(self.manifest_content).hexdigest(),
            payload["candidateManifestSha256"],
        )
        self.assertNotIn("AlarmName", evidence.read_text())
        self.assertEqual(0o600, stat.S_IMODE(evidence.stat().st_mode))

    def test_alarm_and_insufficient_data_have_distinct_terminal_codes(self):
        first_instance = next(iter(self.module.TEST1_INSTANCES))
        alarm = FakeCloudWatch(
            self.alarms((first_instance, "CPUUtilization"), "ALARM")
        )
        self.assertEqual(
            self.module.EXIT_FAIL,
            self.module.execute(self.environment("verify-peak"), alarm, self.run_root),
        )
        insufficient = FakeCloudWatch(
            self.alarms((first_instance, "mem_used_percent"), "INSUFFICIENT_DATA")
        )
        self.assertEqual(
            self.module.EXIT_BLOCKED,
            self.module.execute(self.environment("verify-end"), insufficient, self.run_root),
        )

    def test_missing_or_duplicate_expected_alarm_is_blocked_without_evidence(self):
        rows = self.alarms()[:-1]
        with self.assertRaises(self.module.ObserverError):
            self.module.execute(self.environment(), FakeCloudWatch(rows), self.run_root)
        self.assertFalse((self.run_dir / "observability").exists())

        duplicate = self.alarms()
        duplicate.insert(0, dict(duplicate[0]))
        with self.assertRaises(self.module.ObserverError):
            self.module.execute(self.environment(), FakeCloudWatch(duplicate), self.run_root)

    def test_context_and_manifest_profiles_are_fail_closed(self):
        with self.assertRaises(self.module.ObserverError):
            self.module.execute(self.environment("observe-start"), FakeCloudWatch([]), self.run_root)
        manifest = self.run_dir / "candidate-manifest.json"
        payload = json.loads(self.manifest_content)
        payload["profile"] = "test1-quick"
        manifest.write_text(json.dumps(payload) + "\n")
        manifest.chmod(0o600)
        with self.assertRaises(self.module.ObserverError):
            self.module.execute(self.environment(), FakeCloudWatch([]), self.run_root)


if __name__ == "__main__":
    unittest.main()
