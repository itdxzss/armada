import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import collect as collector
from web_capture import WebCaptureError, load_capture_directory


ROOT = Path(__file__).resolve().parent
COLLECTOR = ROOT / "collect.py"
FIXTURES = ROOT / "fixtures"
RUN_ID = "test-run"
CANDIDATE = "sha256:" + "a" * 64


def android_http_envelope(node_id: str = "01") -> dict:
    direct = json.loads((FIXTURES / "android-node1.json").read_text(encoding="utf-8"))
    return {
        "Code": 0,
        "Data": {
            "nodeId": node_id,
            "traffic": {
                **direct["snapshot"],
                "health": direct["health"],
                "privateToken": "must-not-appear",
            },
        },
        "Msg": "",
    }


def live_snapshot(now_ms: int, run_id: str = "worker-run", watermark: int | None = None) -> dict:
    return {
        "updatedAt": now_ms,
        "running": True,
        "runId": run_id,
        "summaryCommittedBeforeMs": now_ms if watermark is None else watermark,
        "collector": {"dropped": 0, "sinkFailures": 0},
        "writer": {"writeFailures": 0, "serializeFailures": 0, "filesDropped": 0},
        "aggregator": {"overflowed": 0, "lateRecords": 0},
        "redundancy": {"pendingDropped": 0},
    }


def summary_row(minute: int, run_id: str = "worker-run", byte_count: int = 1) -> dict:
    return {
        "minute": minute,
        "scope": "system",
        "category": "heartbeat",
        "frameKind": "message",
        "direction": "up",
        "measure": "noise_frame",
        "channel": "ws",
        "bytes": byte_count,
        "count": 1,
        "runId": run_id,
    }


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

    def test_web_capture_waits_for_the_frozen_end_boundary(self):
        minute_boundary = 1_800_000_000_000
        now_ms = minute_boundary + 30_000
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "live-master.json").write_text(
                json.dumps(live_snapshot(now_ms, watermark=minute_boundary)),
                encoding="utf-8",
            )
            (root / "summary-master-20270115T07Z.jsonl").write_text(
                json.dumps(summary_row(minute_boundary - 60_000))
                + "\n"
                + json.dumps(summary_row(minute_boundary))
                + "\n",
                encoding="utf-8",
            )

            flushes = 0

            def advance_watermark(_seconds: float) -> None:
                nonlocal flushes
                flushes += 1
                watermark = (
                    minute_boundary if flushes == 1 else minute_boundary + 60_000
                )
                (root / "live-master.json").write_text(
                    json.dumps(
                        live_snapshot(
                            now_ms if flushes == 1 else minute_boundary + 61_000,
                            watermark=watermark,
                        )
                    ),
                    encoding="utf-8",
                )

            with mock.patch("web_capture.time.sleep", side_effect=advance_watermark):
                payload = load_capture_directory(
                    str(root),
                    now_ms,
                    60,
                    phase="end",
                    expected_workers=1,
                    wait_timeout_seconds=1,
                    poll_interval_seconds=0,
                )

        self.assertEqual(minute_boundary + 60_000, payload["_summaryTargetBeforeMs"])
        self.assertEqual(2, flushes)
        self.assertEqual(
            [minute_boundary - 60_000, minute_boundary],
            payload["_workerMinuteEvidence"]["master"],
        )

    def test_web_capture_idle_worker_proves_zero_without_making_timeline_rows(self):
        now_ms = 1_800_000_000_000
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "live-master.json").write_text(
                json.dumps(live_snapshot(now_ms)),
                encoding="utf-8",
            )

            payload = load_capture_directory(
                str(root), now_ms, 180, phase="end", expected_workers=1
            )

        self.assertEqual([], payload["timeline"])
        self.assertEqual(
            {
                "proxyWire": 0,
                "noiseFrame": 0,
                "nodePlain": 0,
                "transportOverhead": 0,
                "protocolOverhead": 0,
                "attributedShare": 0,
            },
            payload["_workerReconciliation"]["master"],
        )
        self.assertEqual(0, payload["_workerCoverage"]["master"])
        self.assertEqual([], payload["_workerMinuteEvidence"]["master"])
        self.assertEqual("worker-run", payload["health"][0]["snapshot"]["runId"])
        self.assertEqual(now_ms, payload["health"][0]["snapshot"]["summaryCommittedBeforeMs"])

    def test_web_capture_blocks_when_a_worker_watermark_times_out(self):
        minute_boundary = 1_800_000_000_000
        now_ms = minute_boundary + 30_000
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "live-master.json").write_text(
                json.dumps(live_snapshot(now_ms, watermark=minute_boundary)),
                encoding="utf-8",
            )

            with self.assertRaises(WebCaptureError) as raised:
                load_capture_directory(
                    str(root),
                    now_ms,
                    60,
                    phase="end",
                    expected_workers=1,
                    wait_timeout_seconds=0,
                )

        self.assertEqual("WEB_SUMMARY_WATERMARK_TIMEOUT", raised.exception.code)

    def test_web_capture_uses_post_wait_clock_for_worker_freshness(self):
        captured: dict[str, int] = {}

        def capture_payload(
            _directory: str,
            frozen_now_ms: int,
            _window_seconds: int,
            **_kwargs: object,
        ) -> dict:
            captured["frozen"] = frozen_now_ms
            target = ((frozen_now_ms + 59_999) // 60_000) * 60_000
            snapshot = live_snapshot(frozen_now_ms + 60_000, watermark=target)
            return {
                "reconciliation": {
                    "proxyWire": 0,
                    "noiseFrame": 0,
                    "nodePlain": 0,
                    "transportOverhead": 0,
                    "protocolOverhead": 0,
                    "attributedShare": 0,
                },
                "timeline": [],
                "byCategory": [],
                "byScope": [],
                "health": [{"workerId": "master", "snapshot": snapshot}],
                "_captureMode": "capture-directory",
                "_workerReconciliation": {"master": {
                    "proxyWire": 0,
                    "noiseFrame": 0,
                    "nodePlain": 0,
                    "transportOverhead": 0,
                    "protocolOverhead": 0,
                    "attributedShare": 0,
                }},
                "_workerCoverage": {"master": 0},
                "_workerMinuteEvidence": {"master": []},
                "_summaryLineageMode": "current-run-only",
                "_summaryTargetBeforeMs": target,
                "_legacySummaryRowsIgnored": 0,
                "_foreignSummaryRowsIgnored": 0,
            }

        def post_wait_time() -> float:
            return (captured["frozen"] + 61_000) / 1000

        args = SimpleNamespace(
            adapter="web-traffic",
            environment="test1",
            phase="end",
            run_id=RUN_ID,
            candidate_manifest_sha256=CANDIDATE,
            label="default",
            target=[],
            json_file=[],
            capture_directory=["web=/absolute/capture"],
            expected_workers=1,
            freshness_seconds=30,
            minimum_window_seconds=0,
            maximum_gap_seconds=60,
            timeout_seconds=8,
            now_ms=None,
        )
        with mock.patch.object(collector, "load_capture_directory", side_effect=capture_payload):
            with mock.patch.object(collector.time, "time", side_effect=post_wait_time):
                result = collector.parse_web_traffic(args)

        self.assertEqual("COLLECTED", result["status"])
        self.assertEqual(1_000, result["raw"]["sources"][0]["workers"][0]["ageMs"])

    def test_web_capture_blocks_when_a_worker_restarts_during_end_wait(self):
        now_ms = 1_800_000_000_000
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            live_path = root / "live-master.json"
            live_path.write_text(
                json.dumps(live_snapshot(now_ms, watermark=now_ms - 60_000)),
                encoding="utf-8",
            )

            def restart_worker(_seconds: float) -> None:
                live_path.write_text(
                    json.dumps(live_snapshot(now_ms, run_id="restarted-run")),
                    encoding="utf-8",
                )

            with mock.patch("web_capture.time.sleep", side_effect=restart_worker):
                with self.assertRaises(WebCaptureError) as raised:
                    load_capture_directory(
                        str(root),
                        now_ms,
                        60,
                        phase="end",
                        expected_workers=1,
                        wait_timeout_seconds=1,
                        poll_interval_seconds=0,
                    )

        self.assertEqual("WEB_WORKER_LINEAGE_CHANGED", raised.exception.code)

    def test_web_capture_ignores_legacy_and_previous_run_summary_rows(self):
        now_ms = 1_800_000_000_000
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "live-master.json").write_text(
                json.dumps(live_snapshot(now_ms)), encoding="utf-8"
            )
            legacy = summary_row(now_ms - 60_000)
            legacy.pop("runId")
            rows = [
                legacy,
                summary_row(now_ms - 60_000, run_id="previous-run", byte_count=10),
                summary_row(now_ms - 60_000, byte_count=2),
                summary_row(now_ms, byte_count=100),
            ]
            (root / "summary-master-20270115T07Z.jsonl").write_text(
                "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8"
            )

            payload = load_capture_directory(
                str(root), now_ms, 60, phase="end", expected_workers=1
            )

        self.assertEqual(2, payload["reconciliation"]["noiseFrame"])
        self.assertEqual(1, payload["_legacySummaryRowsIgnored"])
        self.assertEqual(1, payload["_foreignSummaryRowsIgnored"])

    def test_web_capture_degraded_writer_still_fails_closed(self):
        now_ms = 1_800_000_000_000
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            degraded = live_snapshot(now_ms)
            degraded["writer"]["writeFailures"] = 1
            (root / "live-master.json").write_text(json.dumps(degraded), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--capture-directory",
                f"web={root}",
                "--expected-workers",
                "1",
                "--now-ms",
                str(now_ms),
                "--minimum-window-seconds",
                "180",
                expected_code=2,
            )

        self.assertIn("WEB_COLLECTOR_DEGRADED", result["health"]["blockedReasons"])

    def test_web_capture_late_summary_record_fails_closed(self):
        now_ms = 1_800_000_000_000
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            degraded = live_snapshot(now_ms)
            degraded["aggregator"]["lateRecords"] = 1
            (root / "live-master.json").write_text(json.dumps(degraded), encoding="utf-8")
            result = self.run_collector(
                "web-traffic",
                "--phase",
                "end",
                "--capture-directory",
                f"web={root}",
                "--expected-workers",
                "1",
                "--now-ms",
                str(now_ms),
                expected_code=2,
            )

        self.assertIn("WEB_COLLECTOR_DEGRADED", result["health"]["blockedReasons"])

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

    def test_android_http_envelope_contract_table(self):
        direct = json.loads((FIXTURES / "android-node1.json").read_text(encoding="utf-8"))
        valid = android_http_envelope()
        cases = (
            ("valid", valid, 0, None),
            ("business-failure", {**valid, "Code": 1003}, 2, "ANDROID_TRAFFIC_INVALID"),
            ("boolean-code", {**valid, "Code": False}, 2, "ANDROID_TRAFFIC_INVALID"),
            (
                "missing-code",
                {
                    "Data": valid["Data"],
                    "snapshot": direct["snapshot"],
                    "health": direct["health"],
                },
                2,
                "ANDROID_TRAFFIC_INVALID",
            ),
            (
                "missing-node-id",
                {**valid, "Data": {**valid["Data"], "nodeId": ""}},
                2,
                "ANDROID_TRAFFIC_INVALID",
            ),
            (
                "missing-traffic",
                {**valid, "Data": {"nodeId": "01"}},
                2,
                "ANDROID_TRAFFIC_INVALID",
            ),
            (
                "missing-health",
                {
                    **valid,
                    "Data": {
                        **valid["Data"],
                        "traffic": direct["snapshot"],
                    },
                },
                2,
                "ANDROID_TRAFFIC_INVALID",
            ),
        )

        with tempfile.TemporaryDirectory() as directory:
            for name, payload, expected_code, reason in cases:
                with self.subTest(name=name):
                    path = Path(directory) / f"{name}.json"
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
                        expected_code=expected_code,
                    )
                    if reason is None:
                        self.assertEqual("COLLECTED", result["status"])
                        self.assertEqual("node1-run", result["raw"]["nodes"][0]["runId"])
                        self.assertFalse(result["raw"]["nodes"][0]["stopped"])
                        self.assertNotIn("must-not-appear", json.dumps(result))
                    else:
                        self.assertIn(reason, result["health"]["blockedReasons"])

    def test_android_private_dimensions_fail_without_leaking_values(self):
        cases = {
            "node-id": ("919000000001", "919000000001"),
            "category": ("01", "919000000001@s.whatsapp.net"),
            "scope": ("01", "proxyTokenMustNotAppear"),
        }
        with tempfile.TemporaryDirectory() as directory:
            for name, (node_id, private_value) in cases.items():
                with self.subTest(name=name):
                    payload = android_http_envelope(node_id)
                    if name == "category":
                        payload["Data"]["traffic"]["categories"] = {
                            private_value: {"up": 1000, "down": 2000}
                        }
                    if name == "scope":
                        payload["Data"]["traffic"]["scopes"] = {
                            private_value: {"up": 1000, "down": 2000}
                        }
                    path = Path(directory) / f"{name}.json"
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
                    serialized = json.dumps(result)
                    self.assertIn(
                        "ANDROID_TRAFFIC_PRIVATE_DIMENSION",
                        result["health"]["blockedReasons"],
                    )
                    self.assertNotIn(private_value, serialized)

    def test_android_duplicate_endpoint_node_ids_are_blocked(self):
        with tempfile.TemporaryDirectory() as directory:
            mappings = []
            for label in ("node1", "node2", "node3"):
                path = Path(directory) / f"{label}.json"
                path.write_text(json.dumps(android_http_envelope("01")), encoding="utf-8")
                mappings.extend(("--json-file", f"{label}={path}"))
            result = self.run_collector(
                "android-traffic",
                "--phase",
                "end",
                "--now",
                "2027-01-15T08:00:00Z",
                *mappings,
                expected_code=2,
            )

        self.assertIn("ANDROID_NODE_ID_SET_INVALID", result["health"]["blockedReasons"])

    def test_android_fixed_endpoint_node_mapping_rejects_swaps(self):
        with tempfile.TemporaryDirectory() as directory:
            mappings = []
            for label, node_id in (("node01", "02"), ("node02", "01"), ("node03", "03")):
                path = Path(directory) / f"{label}.json"
                path.write_text(json.dumps(android_http_envelope(node_id)), encoding="utf-8")
                mappings.extend(("--json-file", f"{label}={path}"))
            result = self.run_collector(
                "android-traffic",
                "--phase",
                "end",
                "--now",
                "2027-01-15T08:00:00Z",
                *mappings,
                "--expected-node-id",
                "node01=01",
                "--expected-node-id",
                "node02=02",
                "--expected-node-id",
                "node03=03",
                expected_code=2,
            )

        self.assertIn("ANDROID_NODE_ID_MISMATCH", result["health"]["blockedReasons"])

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
