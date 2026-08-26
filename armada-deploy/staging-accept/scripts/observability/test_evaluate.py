import copy
import datetime as dt
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
EVALUATOR = ROOT / "evaluate.py"
RUN_ID = "test-run"
CANDIDATE = "sha256:" + "a" * 64
PROFILE_SECONDS = 120
NOW = dt.datetime.now(dt.timezone.utc)
OBSERVED_TIMES = {
    "start": NOW - dt.timedelta(seconds=130),
    "peak": NOW - dt.timedelta(seconds=65),
    "end": NOW - dt.timedelta(seconds=1),
}
OBSERVED = {
    phase: value.isoformat().replace("+00:00", "Z") for phase, value in OBSERVED_TIMES.items()
}


def envelope(collector: str, phase: str, raw: dict) -> dict:
    return {
        "schemaVersion": 1,
        "collector": collector,
        "environment": "test1",
        "phase": phase,
        "runId": RUN_ID,
        "candidateManifestSha256": CANDIDATE,
        "provenance": "fixture",
        "observedAt": OBSERVED[phase],
        "status": "COLLECTED",
        "health": {"ok": True, "checks": [], "blockedReasons": []},
        "raw": raw,
    }


def kafka_snapshot(phase: str, high: int, committed: int) -> dict:
    lag = max(0, high - max(0, committed))
    truncated = committed != -1 and committed < 0
    return envelope(
        "kafka",
        phase,
        {
            "partitions": [
                {
                    "topic": "topic.v1",
                    "group": "group-a",
                    "partition": 0,
                    "lowOffset": 0,
                    "highOffset": high,
                    "committedOffset": committed,
                    "effectiveCommittedOffset": max(0, committed),
                    "lag": lag,
                    "uninitialized": committed == -1,
                    "truncated": truncated,
                }
            ],
            "groups": [
                {
                    "topic": "topic.v1",
                    "group": "group-a",
                    "partitions": 1,
                    "totalLag": lag,
                    "maxLag": lag,
                    "uninitializedPartitions": int(committed == -1),
                    "truncatedPartitions": int(truncated),
                }
            ],
        },
    )


def redis_snapshot(
    phase: str,
    blocked: int = 0,
    evicted: int = 0,
    rejected: int = 0,
    used_memory: int = 100,
) -> dict:
    return envelope(
        "redis",
        phase,
        {
            "sources": [
                {
                    "label": "web",
                    "mode": "standalone",
                    "nodes": [
                        {
                            "label": "primary",
                            "pingLatencyMs": 1.5,
                            "info": {
                                "blocked_clients": blocked,
                                "evicted_keys": evicted,
                                "rejected_connections": rejected,
                                "used_memory": used_memory,
                                "maxmemory": 1000,
                            },
                        }
                    ],
                }
            ]
        },
    )


def host_snapshot(phase: str, restart: int = 2, oom: bool = False) -> dict:
    result = envelope(
        "host-resource",
        phase,
        {
            "host": {
                "cpu": {"busyPercent": 20},
                "memory": {"usedPercent": 50},
            },
            "containers": [
                {
                    "name": "backend",
                    "cpuPercent": 30,
                    "memoryBytes": 512 * 1024 * 1024,
                    "memoryPercent": 25,
                    "restartCount": restart,
                    "oomKilled": oom,
                    "status": "running",
                    "startedAt": "2027-01-14T00:00:00Z",
                }
            ],
            "processes": [],
        },
    )
    result["source"] = "armada"
    return result


def web_snapshot(
    phase: str,
    minutes: list[int],
    run_id: str = "web-worker-run",
    watermark: int | None = None,
) -> dict:
    observed_ms = int(OBSERVED_TIMES[phase].timestamp() * 1000)
    target = ((observed_ms + 59_999) // 60_000) * 60_000
    committed_before = target if watermark is None else watermark
    worker = {
        "workerId": "master",
        "runId": run_id,
        "summaryCommittedBeforeMs": committed_before,
        "updatedAt": max(committed_before, target),
        "collectorDropped": 0,
        "collectorSinkFailures": 0,
        "writerWriteFailures": 0,
        "writerSerializeFailures": 0,
        "writerFilesDropped": 0,
        "aggregatorOverflowed": 0,
        "aggregatorLateRecords": 0,
        "redundancyPendingDropped": 0,
    }
    evidence = {
        "minutes": minutes,
        "minuteCount": len(minutes),
        "coverageSeconds": 0 if not minutes else (minutes[-1] - minutes[0]) // 1000 + 60,
        "maxGapSeconds": 0,
        "continuous": True,
    }
    return envelope(
        "web-traffic",
        phase,
        {
            "sources": [
                {
                    "label": "web",
                    "captureMode": "capture-directory",
                    "summaryLineageMode": "current-run-only",
                    "summaryTargetBeforeMs": target,
                    "workers": [worker],
                    "timelineEvidence": evidence,
                    "workerMinuteEvidence": {"master": copy.deepcopy(evidence)},
                }
            ]
        },
    )


def android_snapshot(phase: str, total: int, lineage: str | None = None) -> dict:
    lineage = lineage or (NOW - dt.timedelta(days=1)).isoformat().replace("+00:00", "Z")
    return envelope(
        "android-traffic",
        phase,
        {
            "nodes": [
                {
                    "label": "node-01",
                    "nodeId": "01",
                    "runId": "android-run",
                    "cumulativeStartedAt": lineage,
                    "continuous": True,
                    "stopped": False,
                    "eventDetailDisabled": False,
                    "persistenceDisabled": False,
                    "droppedEvents": 0,
                    "classificationErrors": 0,
                    "proxy": {"up": total, "down": 0, "total": total},
                    "reconciliationGap": {"up": 0, "down": 0, "total": 0},
                    "categories": {"heartbeat": {"up": total, "down": 0, "total": total}},
                    "scopes": {"background": {"up": total, "down": 0, "total": total}},
                }
            ]
        },
    )


class EvaluateTest(unittest.TestCase):
    def run_evaluator(
        self,
        snapshots: list[dict],
        collector: str,
        expected_code: int,
        *extra: str,
        test_mode: bool = True,
    ) -> dict:
        expected = {
            "kafka": ["--expected-kafka-pair", "topic.v1=group-a"],
            "redis": [
                "--expected-redis-source",
                "web",
                "--expected-redis-node",
                "web=primary",
            ],
            "host-resource": [
                "--expected-host-source",
                "armada",
                "--expected-host-container",
                "armada=backend",
            ],
            "web-traffic": [],
            "android-traffic": [],
        }[collector]
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, snapshot in enumerate(snapshots):
                path = Path(directory) / f"snapshot-{index}.json"
                path.write_text(json.dumps(snapshot), encoding="utf-8")
                paths.extend(("--input", str(path)))
            completed = subprocess.run(
                [
                    sys.executable,
                    str(EVALUATOR),
                    *paths,
                    "--run-id",
                    RUN_ID,
                    "--candidate-manifest-sha256",
                    CANDIDATE,
                    "--profile-seconds",
                    str(PROFILE_SECONDS),
                    *(["--test-mode"] if test_mode else []),
                    "--require-collector",
                    collector,
                    *expected,
                    *extra,
                ],
                check=False,
                capture_output=True,
                text=True,
                timeout=10,
            )
        self.assertEqual(expected_code, completed.returncode, completed.stderr)
        return json.loads(completed.stdout)

    def test_kafka_only_passes_after_lag_is_drained(self):
        snapshots = [
            kafka_snapshot("start", 100, 90),
            kafka_snapshot("peak", 120, 100),
            kafka_snapshot("end", 125, 125),
        ]
        result = self.run_evaluator(snapshots, "kafka", 0)
        self.assertEqual("PASS", result["status"])
        snapshots[0]["status"] = "PASS"
        result = self.run_evaluator(snapshots, "kafka", 3)
        self.assertIn("OBSERVABILITY_COLLECTION_STATUS_INVALID", result["blockedReasons"])

        snapshots = [
            kafka_snapshot("start", 100, 90),
            kafka_snapshot("peak", 120, 100),
            kafka_snapshot("end", 125, 120),
        ]
        result = self.run_evaluator(snapshots, "kafka", 2)
        self.assertIn("KAFKA_END_LAG_EXCEEDED", result["failureReasons"])

    def test_kafka_non_draining_or_truncated_log_fails(self):
        snapshots = [
            kafka_snapshot("start", 100, 90),
            kafka_snapshot("peak", 120, 100),
            kafka_snapshot("end", 130, 115),
        ]
        result = self.run_evaluator(snapshots, "kafka", 2)
        self.assertIn("KAFKA_LAG_NOT_DRAINING", result["failureReasons"])

        snapshots[1]["raw"]["partitions"][0]["lowOffset"] = 101
        snapshots[1]["raw"]["partitions"][0]["committedOffset"] = 100
        snapshots[1]["raw"]["partitions"][0]["truncated"] = True
        result = self.run_evaluator(snapshots, "kafka", 2)
        self.assertIn("KAFKA_LOG_TRUNCATION", result["failureReasons"])

    def test_kafka_rejects_uninitialized_and_offset_rollback(self):
        uninitialized = [kafka_snapshot(phase, 0, -1) for phase in OBSERVED]
        result = self.run_evaluator(uninitialized, "kafka", 3)
        self.assertIn("KAFKA_UNINITIALIZED_PARTITION", result["blockedReasons"])

        rollback = [
            kafka_snapshot("start", 100, 100),
            kafka_snapshot("peak", 120, 120),
            kafka_snapshot("end", 0, 0),
        ]
        result = self.run_evaluator(rollback, "kafka", 3)
        self.assertIn("KAFKA_OFFSET_ROLLBACK", result["blockedReasons"])

    def test_kafka_rejects_a_required_pair_without_partitions(self):
        snapshots = [kafka_snapshot(phase, 0, 0) for phase in OBSERVED]
        for snapshot in snapshots:
            snapshot["raw"]["partitions"] = []
            snapshot["raw"]["groups"][0].update(
                {
                    "partitions": 0,
                    "totalLag": 0,
                    "maxLag": 0,
                    "uninitializedPartitions": 0,
                    "truncatedPartitions": 0,
                }
            )
        result = self.run_evaluator(snapshots, "kafka", 3)
        self.assertIn("KAFKA_EVIDENCE_INVALID", result["blockedReasons"])

    def test_redis_nonzero_blocked_clients_are_reported_without_failure(self):
        snapshots = [redis_snapshot(phase, blocked=12, evicted=3) for phase in OBSERVED]
        result = self.run_evaluator(snapshots, "redis", 0)
        self.assertEqual("PASS", result["status"])
        self.assertNotIn("REDIS_BLOCKED_CLIENTS", result["failureReasons"])
        self.assertEqual(
            {
                "startBlockedClients": 12,
                "peakBlockedClients": 12,
                "endBlockedClients": 12,
            },
            {
                key: result["metrics"]["redis"][0][key]
                for key in (
                    "startBlockedClients",
                    "peakBlockedClients",
                    "endBlockedClients",
                )
            },
        )

    def test_redis_evictions_increase_fails(self):
        snapshots = [
            redis_snapshot("start", evicted=3),
            redis_snapshot("peak", evicted=3),
            redis_snapshot("end", evicted=4),
        ]
        result = self.run_evaluator(snapshots, "redis", 2)
        self.assertIn("REDIS_EVICTIONS_INCREASED", result["failureReasons"])

    def test_redis_rejected_connections_increase_fails_and_memory_is_reported(self):
        snapshots = [
            redis_snapshot("start", rejected=2, used_memory=100),
            redis_snapshot("peak", rejected=2, used_memory=150),
            redis_snapshot("end", rejected=3, used_memory=125),
        ]
        result = self.run_evaluator(snapshots, "redis", 2)

        self.assertIn("REDIS_REJECTED_CONNECTIONS_INCREASED", result["failureReasons"])
        self.assertEqual(150, result["metrics"]["redis"][0]["peakUsedMemoryBytes"])

    def test_redis_ping_latency_threshold_fails(self):
        snapshots = [redis_snapshot(phase) for phase in OBSERVED]
        snapshots[1]["raw"]["sources"][0]["nodes"][0]["pingLatencyMs"] = 101
        result = self.run_evaluator(snapshots, "redis", 2)
        self.assertIn("REDIS_PING_LATENCY_EXCEEDED", result["failureReasons"])

    def test_redis_empty_sources_are_blocked(self):
        snapshots = [envelope("redis", phase, {"sources": []}) for phase in OBSERVED]
        result = self.run_evaluator(snapshots, "redis", 3)
        self.assertIn("REDIS_EXPECTED_SET_MISMATCH", result["blockedReasons"])

        snapshots = [redis_snapshot(phase) for phase in OBSERVED]
        for snapshot in snapshots:
            snapshot["raw"]["sources"].append({"label": "unexpected", "nodes": []})
        result = self.run_evaluator(snapshots, "redis", 3)
        self.assertIn("REDIS_EVIDENCE_INVALID", result["blockedReasons"])

    def test_host_restart_and_oom_fail(self):
        snapshots = [host_snapshot("start"), host_snapshot("peak", oom=True), host_snapshot("end", restart=3)]
        result = self.run_evaluator(snapshots, "host-resource", 2)
        self.assertIn("CONTAINER_OOM_KILLED", result["failureReasons"])
        self.assertIn("CONTAINER_RESTARTED", result["failureReasons"])

    def test_host_requires_the_exact_nonempty_expected_runtime_set(self):
        snapshots = [host_snapshot(phase) for phase in OBSERVED]
        for snapshot in snapshots:
            snapshot["raw"]["containers"] = []
        result = self.run_evaluator(snapshots, "host-resource", 3)
        self.assertIn("HOST_RESOURCE_EXPECTED_SET_MISMATCH", result["blockedReasons"])

        snapshots = [host_snapshot(phase) for phase in OBSERVED]
        result = self.run_evaluator(
            snapshots,
            "host-resource",
            3,
            "--expected-host-container",
            "unexpected=ghost",
        )
        self.assertIn("HOST_RESOURCE_EXPECTED_SET_MISMATCH", result["blockedReasons"])

    def test_zero_duration_or_fixture_production_evidence_is_blocked(self):
        snapshots = [host_snapshot(phase) for phase in OBSERVED]
        for snapshot in snapshots:
            snapshot["observedAt"] = OBSERVED["end"]
        result = self.run_evaluator(snapshots, "host-resource", 3)
        self.assertIn("HOST_RESOURCE_TIME_ORDER_INVALID", result["blockedReasons"])

        snapshots = [host_snapshot(phase) for phase in OBSERVED]
        result = self.run_evaluator(
            snapshots, "host-resource", 3, test_mode=False
        )
        self.assertIn("OBSERVABILITY_FIXTURE_REJECTED", result["blockedReasons"])

    def test_web_real_timeline_gaps_do_not_replace_watermark_completeness(self):
        minutes = [1_800_000_000_000, 1_800_003_600_000]
        snapshots = [web_snapshot(phase, minutes) for phase in OBSERVED]
        result = self.run_evaluator(
            snapshots,
            "web-traffic",
            0,
            "--minimum-traffic-window-seconds",
            "120",
        )
        self.assertEqual("PASS", result["status"])

    def test_web_idle_window_passes_on_current_lineage_watermarks(self):
        snapshots = [web_snapshot(phase, []) for phase in OBSERVED]

        result = self.run_evaluator(snapshots, "web-traffic", 0)

        self.assertEqual("PASS", result["status"])

    def test_web_worker_restart_between_phases_is_blocked(self):
        snapshots = [web_snapshot(phase, []) for phase in OBSERVED]
        snapshots[1]["raw"]["sources"][0]["workers"][0]["runId"] = "restarted-run"

        result = self.run_evaluator(snapshots, "web-traffic", 3)

        self.assertIn("WEB_WORKER_LINEAGE_CHANGED", result["blockedReasons"])

    def test_web_watermark_must_be_monotonic_and_cover_the_frozen_end_target(self):
        boundaries = {
            phase: (
                (int(OBSERVED_TIMES[phase].timestamp() * 1000) + 59_999)
                // 60_000
                * 60_000
            )
            for phase in OBSERVED
        }
        regressed = [
            web_snapshot("start", [], watermark=boundaries["start"]),
            web_snapshot("peak", [], watermark=boundaries["peak"]),
            web_snapshot("end", [], watermark=boundaries["start"]),
        ]
        result = self.run_evaluator(regressed, "web-traffic", 3)
        self.assertIn("WEB_SUMMARY_WATERMARK_REGRESSED", result["blockedReasons"])

        lagging = [web_snapshot(phase, []) for phase in OBSERVED]
        lagging[-1]["raw"]["sources"][0]["workers"][0]["summaryCommittedBeforeMs"] = (
            lagging[-1]["raw"]["sources"][0]["summaryTargetBeforeMs"] - 60_000
        )
        result = self.run_evaluator(lagging, "web-traffic", 3)
        self.assertIn("WEB_SUMMARY_WATERMARK_INCOMPLETE", result["blockedReasons"])

    def test_web_write_or_drop_degradation_fails_closed(self):
        snapshots = [web_snapshot(phase, []) for phase in OBSERVED]
        snapshots[1]["raw"]["sources"][0]["workers"][0]["writerWriteFailures"] = 1

        result = self.run_evaluator(snapshots, "web-traffic", 2)

        self.assertIn("WEB_COLLECTOR_DEGRADED", result["failureReasons"])

    def test_web_and_android_empty_evidence_sets_are_blocked(self):
        web = [envelope("web-traffic", phase, {"sources": []}) for phase in OBSERVED]
        result = self.run_evaluator(web, "web-traffic", 3)
        self.assertIn("WEB_TRAFFIC_EVIDENCE_INVALID", result["blockedReasons"])

        android = [envelope("android-traffic", phase, {"nodes": []}) for phase in OBSERVED]
        result = self.run_evaluator(android, "android-traffic", 3)
        self.assertIn("ANDROID_TRAFFIC_EVIDENCE_INVALID", result["blockedReasons"])

    def test_web_history_cannot_fake_a_longer_run(self):
        end_minute = int(OBSERVED_TIMES["end"].timestamp() * 1000) // 60_000 * 60_000
        minutes = [end_minute - index * 60_000 for index in reversed(range(1440))]
        snapshots = [web_snapshot(phase, minutes) for phase in OBSERVED]
        result = self.run_evaluator(
            snapshots,
            "web-traffic",
            3,
            "--profile-seconds",
            "86400",
            "--minimum-traffic-window-seconds",
            "86400",
        )
        self.assertIn("WEB_TRAFFIC_PROFILE_WINDOW_INCOMPLETE", result["blockedReasons"])

    def test_android_requires_same_lineage_and_monotonic_counters(self):
        snapshots = [
            android_snapshot("start", 10),
            android_snapshot("peak", 12, lineage="2027-01-13T00:00:00Z"),
            android_snapshot("end", 9),
        ]
        result = self.run_evaluator(
            snapshots,
            "android-traffic",
            3,
            "--minimum-traffic-window-seconds",
            "120",
        )
        self.assertIn("ANDROID_LINEAGE_CHANGED", result["blockedReasons"])

        snapshots = [
            android_snapshot("start", 10),
            android_snapshot("peak", 12),
            android_snapshot("end", 9),
        ]
        result = self.run_evaluator(
            snapshots,
            "android-traffic",
            3,
            "--minimum-traffic-window-seconds",
            "120",
        )
        self.assertIn("ANDROID_COUNTER_NOT_MONOTONIC", result["blockedReasons"])

    def test_android_requires_a_stable_runtime_and_healthy_collector(self):
        snapshots = [android_snapshot(phase, 10 + index) for index, phase in enumerate(OBSERVED)]
        snapshots[1]["raw"]["nodes"][0]["runId"] = "android-restarted"
        result = self.run_evaluator(snapshots, "android-traffic", 3)
        self.assertIn("ANDROID_RUN_ID_CHANGED", result["blockedReasons"])

        snapshots = [android_snapshot(phase, 10 + index) for index, phase in enumerate(OBSERVED)]
        snapshots[1]["raw"]["nodes"][0]["eventDetailDisabled"] = True
        result = self.run_evaluator(snapshots, "android-traffic", 3)
        self.assertIn("ANDROID_COLLECTOR_UNHEALTHY", result["blockedReasons"])

        snapshots = [android_snapshot(phase, 10 + index) for index, phase in enumerate(OBSERVED)]
        snapshots[1]["raw"]["nodes"][0]["stopped"] = True
        result = self.run_evaluator(snapshots, "android-traffic", 3)
        self.assertIn("ANDROID_COLLECTOR_UNHEALTHY", result["blockedReasons"])

    def test_android_node_identity_must_remain_stable(self):
        snapshots = [android_snapshot(phase, 10 + index) for index, phase in enumerate(OBSERVED)]
        snapshots[1]["raw"]["nodes"][0]["nodeId"] = "02"

        result = self.run_evaluator(snapshots, "android-traffic", 3)

        self.assertIn("ANDROID_NODE_ID_CHANGED", result["blockedReasons"])


if __name__ == "__main__":
    unittest.main()
