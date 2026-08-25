import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
COLLECTOR = ROOT / "collect.py"
FIXTURES = ROOT / "fixtures"
RUN_ID = "test-run"
CANDIDATE = "sha256:" + "a" * 64


class CollectorFixtureTest(unittest.TestCase):
    def run_collector(self, *args: str, expected_code: int = 0, input_text: str | None = None) -> dict:
        adapter, *adapter_args = args
        completed = subprocess.run(
            [
                sys.executable,
                str(COLLECTOR),
                adapter,
                "--run-id",
                RUN_ID,
                "--candidate-manifest-sha256",
                CANDIDATE,
                *adapter_args,
            ],
            check=False,
            capture_output=True,
            text=True,
            input=input_text,
            timeout=10,
        )
        self.assertEqual(expected_code, completed.returncode, completed.stderr)
        lines = completed.stdout.splitlines()
        self.assertEqual(1, len(lines), completed.stdout)
        result = json.loads(lines[0])
        self.assertEqual(RUN_ID, result["runId"])
        self.assertEqual(CANDIDATE, result["candidateManifestSha256"])
        return result

    def test_host_snapshot_keeps_raw_counters_and_omits_network(self):
        result = self.run_collector(
            "host",
            "--phase",
            "start",
            "--proc-stat-before",
            str(FIXTURES / "proc-stat.before"),
            "--proc-stat-after",
            str(FIXTURES / "proc-stat.after"),
            "--meminfo",
            str(FIXTURES / "meminfo"),
            "--sample-ms",
            "1000",
            "--container",
            "armada-backend",
            "--docker-stats-file",
            str(FIXTURES / "docker-stats.jsonl"),
            "--docker-inspect-file",
            str(FIXTURES / "docker-inspect.jsonl"),
        )

        self.assertEqual("COLLECTED", result["status"])
        self.assertEqual("fixture", result["provenance"])
        self.assertAlmostEqual(41.667, result["raw"]["host"]["cpu"]["busyPercent"])
        self.assertEqual(50.0, result["raw"]["host"]["memory"]["usedPercent"])
        self.assertEqual(536870912, result["raw"]["containers"][0]["memoryBytes"])
        self.assertEqual(2, result["raw"]["containers"][0]["restartCount"])
        self.assertFalse(result["raw"]["containers"][0]["oomKilled"])
        self.assertNotIn("network", result["raw"]["containers"][0])

    def test_host_snapshot_keeps_only_allowlisted_pm2_resource_fields(self):
        result = self.run_collector(
            "host",
            "--phase",
            "start",
            "--proc-stat-before",
            str(FIXTURES / "proc-stat.before"),
            "--proc-stat-after",
            str(FIXTURES / "proc-stat.after"),
            "--meminfo",
            str(FIXTURES / "meminfo"),
            "--process",
            "protocol-master",
            "--pm2-jlist-file",
            str(FIXTURES / "pm2-jlist.json"),
        )

        self.assertEqual("COLLECTED", result["status"])
        process = result["raw"]["processes"][0]
        self.assertEqual("protocol-master", process["name"])
        self.assertEqual(1234, process["pid"])
        self.assertEqual(268435456, process["memoryBytes"])
        self.assertEqual(
            {
                "name",
                "pid",
                "cpuPercent",
                "memoryBytes",
                "memoryPercent",
                "restartCount",
                "status",
                "startedAt",
            },
            set(process),
        )
        self.assertNotIn("SECRET_VALUE", json.dumps(result))

    def test_web_dashboard_api_blocks_when_worker_contribution_is_unprovable(self):
        result = self.run_collector(
            "web-traffic",
            "--phase",
            "end",
            "--json-file",
            f"web={FIXTURES / 'web-overview.json'}",
            "--now-ms",
            "1800000000000",
            "--minimum-window-seconds",
            "180",
            expected_code=2,
        )

        self.assertEqual("BLOCKED", result["status"])
        self.assertFalse(result["semantics"]["cloudBilling"])
        source = result["raw"]["sources"][0]
        self.assertEqual(6000, source["reconciliation"]["proxyWire"])
        self.assertEqual(180, source["coverageSeconds"])
        self.assertEqual(5, len(source["workers"]))
        self.assertIn("WEB_WORKER_WINDOW_COMPLETENESS_UNPROVABLE", result["health"]["blockedReasons"])

    def test_web_capture_directory_preserves_each_worker_window(self):
        result = self.run_collector(
            "web-traffic",
            "--phase",
            "end",
            "--capture-directory",
            f"web={FIXTURES / 'web-capture'}",
            "--expected-workers",
            "2",
            "--now-ms",
            "1800000000000",
            "--minimum-window-seconds",
            "180",
        )

        self.assertEqual("COLLECTED", result["status"])
        self.assertEqual("fixture", result["provenance"])
        source = result["raw"]["sources"][0]
        self.assertEqual("capture-directory", source["captureMode"])
        self.assertEqual({"master": 180, "worker-1": 180}, source["workerCoverageSeconds"])
        self.assertEqual(9000, source["reconciliation"]["proxyWire"])
        self.assertEqual({"master", "worker-1"}, set(source["workerReconciliation"]))
        self.assertEqual(60, source["timelineEvidence"]["maxGapSeconds"])
        self.assertEqual(3, source["workerMinuteEvidence"]["master"]["minuteCount"])

    def test_web_two_points_cannot_fake_a_long_window(self):
        payload = json.loads((FIXTURES / "web-overview.json").read_text(encoding="utf-8"))
        payload["timeline"] = [payload["timeline"][0], payload["timeline"][-1]]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "web.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--json-file",
                f"web={path}",
                "--now-ms",
                "1800000000000",
                "--minimum-window-seconds",
                "180",
                expected_code=2,
            )

        self.assertEqual("BLOCKED", result["status"])
        self.assertIn("WEB_TRAFFIC_WINDOW_INCOMPLETE", result["health"]["blockedReasons"])

    def test_web_health_requires_unique_workers_and_complete_zero_counters(self):
        payload = json.loads((FIXTURES / "web-overview.json").read_text(encoding="utf-8"))
        payload["health"][1]["workerId"] = "master"
        payload["health"][0]["snapshot"]["aggregator"].pop("overflowed")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "web.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--json-file",
                f"web={path}",
                "--now-ms",
                "1800000000000",
                expected_code=2,
            )

        self.assertEqual("BLOCKED", result["status"])
        self.assertIn("WEB_WORKER_HEALTH_INVALID", result["health"]["blockedReasons"])

    def test_web_health_blocks_aggregator_overflow(self):
        payload = json.loads((FIXTURES / "web-overview.json").read_text(encoding="utf-8"))
        payload["health"][0]["snapshot"]["aggregator"]["overflowed"] = 1
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "web.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--json-file",
                f"web={path}",
                "--now-ms",
                "1800000000000",
                expected_code=2,
            )

        self.assertIn("WEB_COLLECTOR_DEGRADED", result["health"]["blockedReasons"])

    def test_web_health_blocks_dropped_files_and_redundancy_entries(self):
        payload = json.loads((FIXTURES / "web-overview.json").read_text(encoding="utf-8"))
        payload["health"][0]["snapshot"]["writer"]["filesDropped"] = 1
        payload["health"][1]["snapshot"]["redundancy"]["pendingDropped"] = 1
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "web.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--json-file",
                f"web={path}",
                "--now-ms",
                "1800000000000",
                expected_code=2,
            )

        self.assertIn("WEB_COLLECTOR_DEGRADED", result["health"]["blockedReasons"])

    def test_web_rank_output_rejects_private_or_unknown_fields(self):
        payload = json.loads((FIXTURES / "web-overview.json").read_text(encoding="utf-8"))
        payload["byScope"] = [
            {
                "key": "user@example.com",
                "up": 1,
                "down": 0,
                "total": 1,
                "count": 1,
                "share": 1,
                "authorization": "must-not-appear",
            }
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "web.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--json-file",
                f"web={path}",
                "--now-ms",
                "1800000000000",
                expected_code=2,
            )

        self.assertEqual("BLOCKED", result["status"])
        self.assertNotIn("must-not-appear", json.dumps(result))

    def test_web_output_does_not_pass_through_private_capture_metadata(self):
        payload = json.loads((FIXTURES / "web-overview.json").read_text(encoding="utf-8"))
        payload["_workerCoverage"] = {"secret": {"authorization": "must-not-appear"}}
        payload["_workerMinuteEvidence"] = {"secret": ["must-not-appear"]}
        payload["_workerReconciliation"] = {"secret": {"marker": "must-not-appear"}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "web.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--json-file",
                f"web={path}",
                "--now-ms",
                "1800000000000",
            )

        serialized = json.dumps(result)
        self.assertEqual("COLLECTED", result["status"])
        self.assertEqual({}, result["raw"]["sources"][0]["workerCoverageSeconds"])
        self.assertNotIn("must-not-appear", serialized)

    def test_web_rank_key_rejects_embedded_secret_markers(self):
        payload = json.loads((FIXTURES / "web-overview.json").read_text(encoding="utf-8"))
        payload["byScope"][0]["key"] = "accountTokenMustNotAppear"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "web.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--json-file",
                f"web={path}",
                "--now-ms",
                "1800000000000",
                expected_code=2,
            )

        self.assertEqual("BLOCKED", result["status"])
        self.assertNotIn("accountTokenMustNotAppear", json.dumps(result))

    def test_web_snapshot_blocks_when_soak_window_is_not_covered(self):
        result = self.run_collector(
            "web-traffic",
            "--phase",
            "end",
            "--json-file",
            f"web={FIXTURES / 'web-overview.json'}",
            "--now-ms",
            "1800000000000",
            "--minimum-window-seconds",
            "21600",
            expected_code=2,
        )

        self.assertEqual("BLOCKED", result["status"])
        self.assertIn("WEB_TRAFFIC_WINDOW_INCOMPLETE", result["health"]["blockedReasons"])

    def test_android_nodes_remain_separate_and_have_an_explicit_aggregate(self):
        result = self.run_collector(
            "android-traffic",
            "--phase",
            "end",
            "--now",
            "2027-01-15T08:00:00Z",
            "--minimum-retention-seconds",
            "86400",
            "--json-file",
            f"node1={FIXTURES / 'android-node1.json'}",
            "--json-file",
            f"node2={FIXTURES / 'android-node2.json'}",
            "--json-file",
            f"node3={FIXTURES / 'android-node3.json'}",
        )

        self.assertEqual("COLLECTED", result["status"])
        self.assertEqual("fixture", result["provenance"])
        self.assertEqual(["node1", "node2", "node3"], [node["label"] for node in result["raw"]["nodes"]])
        self.assertEqual({"up": 4500, "down": 6700, "total": 11200}, result["raw"]["aggregateProxy"])
        self.assertFalse(result["semantics"]["cloudBilling"])

    def test_android_lineage_must_be_old_enough_for_requested_window(self):
        payload = json.loads((FIXTURES / "android-node1.json").read_text(encoding="utf-8"))
        payload["health"]["cumulativeStartedAt"] = "2027-01-15T07:59:50Z"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "node.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "android-traffic",
                "--phase",
                "end",
                "--now",
                "2027-01-15T08:00:00Z",
                "--expected-targets",
                "1",
                "--minimum-retention-seconds",
                "60",
                "--json-file",
                f"node1={path}",
                expected_code=2,
            )

        self.assertIn("ANDROID_LINEAGE_TOO_YOUNG", result["health"]["blockedReasons"])

    def test_android_event_detail_degradation_is_blocked(self):
        payload = json.loads((FIXTURES / "android-node1.json").read_text(encoding="utf-8"))
        payload["health"]["eventDetailDisabled"] = True
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "node.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            result = self.run_collector(
                "android-traffic",
                "--phase",
                "end",
                "--now",
                "2027-01-15T08:00:00Z",
                "--expected-targets",
                "1",
                "--json-file",
                f"node1={path}",
                expected_code=2,
            )

        self.assertIn("ANDROID_COLLECTOR_UNHEALTHY", result["health"]["blockedReasons"])

    def test_android_snapshot_blocks_if_a_fleet_node_is_missing(self):
        result = self.run_collector(
            "android-traffic",
            "--phase",
            "start",
            "--now",
            "2027-01-15T08:00:00Z",
            "--json-file",
            f"node1={FIXTURES / 'android-node1.json'}",
            expected_code=2,
        )

        self.assertEqual("BLOCKED", result["status"])
        self.assertIn("ANDROID_TARGET_COUNT_MISMATCH", result["health"]["blockedReasons"])

    def test_zero_expected_workers_or_targets_is_invalid_configuration(self):
        web = self.run_collector(
            "web-traffic",
            "--phase",
            "start",
            "--expected-workers",
            "0",
            "--json-file",
            f"web={FIXTURES / 'web-overview.json'}",
            expected_code=2,
        )
        android = self.run_collector(
            "android-traffic",
            "--phase",
            "start",
            "--expected-targets",
            "0",
            "--json-file",
            f"node1={FIXTURES / 'android-node1.json'}",
            expected_code=2,
        )

        self.assertIn("INVALID_ARGUMENT", web["health"]["blockedReasons"])
        self.assertIn("INVALID_ARGUMENT", android["health"]["blockedReasons"])


if __name__ == "__main__":
    unittest.main()
