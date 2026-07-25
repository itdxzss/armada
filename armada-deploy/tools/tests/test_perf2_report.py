import csv
import io
import json
import math
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

from perf2_loadtest.model import (
    KafkaMetrics,
    MergedSample,
    MonitorSample,
    ReconciledTask,
    ResourceMetrics,
    ResumeOutcome,
    TaskSnapshot,
)
from perf2_loadtest.report import (
    ReportError,
    SampleAligner,
    ZeroWindow,
    build_summary,
    merge_samples,
    nearest_rank_p95,
    parse_monitor_line,
    write_samples_csv,
)


UTC = timezone.utc


class MonitorParsingTest(unittest.TestCase):
    def test_parses_and_aligns_one_sample_per_node_per_utc_second(self) -> None:
        at = "2026-07-25T02:00:00.123456789Z"
        zhuan = parse_monitor_line(self._line("zhuan", at, kafka=True), "zhuan")
        armada = parse_monitor_line(self._line("armada", at, kafka=False), "armada")
        self.assertEqual(datetime(2026, 7, 25, 2, 0, tzinfo=UTC), zhuan.second)
        self.assertEqual(12, zhuan.kafka.latest_offset)

        aligner = SampleAligner()
        self.assertIsNone(aligner.add(zhuan))
        merged = aligner.add(armada)
        self.assertIsInstance(merged, MergedSample)
        self.assertEqual(zhuan.kafka, merged.kafka)

        with self.assertRaises(ReportError):
            aligner.add(armada)

    def test_rejects_invalid_schema_time_node_and_numbers(self) -> None:
        base = json.loads(self._line("zhuan", "2026-07-25T02:00:00Z", kafka=True))
        mutations = (
            lambda value: value.update(schemaVersion=2),
            lambda value: value.update(at="2026-07-25T10:00:00+08:00"),
            lambda value: value.update(node="armada"),
            lambda value: value["kafka"].update(lag=-1),
            lambda value: value["kafka"].update(producedPerSecond=float("nan")),
            lambda value: value["resource"].update(hostCpuPercent=-1),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                value = json.loads(json.dumps(base))
                mutate(value)
                with self.assertRaises(ReportError):
                    parse_monitor_line(json.dumps(value).encode("utf-8"), "zhuan")

    def test_rejects_invalid_measurements_instead_of_turning_them_into_zero(self) -> None:
        value = json.loads(self._line("zhuan", "2026-07-25T02:00:00Z", kafka=True))
        value["kafka"] = {"valid": False, "errorClass": "metadata"}
        sample = parse_monitor_line(json.dumps(value).encode("utf-8"), "zhuan")
        self.assertFalse(sample.kafka.valid)
        merged = merge_samples(
            parse_monitor_line(self._line("armada", value["at"], kafka=False), "armada"),
            sample,
        )
        self.assertFalse(merged.kafka.valid)

    @staticmethod
    def _line(node, at, kafka):
        value = {
            "schemaVersion": 1,
            "at": at,
            "node": node,
            "resource": {
                "hostCpuPercent": 10.0,
                "hostMemoryUsedBytes": 100,
                "hostMemoryPercent": 20.0,
                "containerCpuPercent": 30.0,
                "containerMemoryBytes": 200,
                "containerMemoryPercent": 40.0,
                "valid": True,
            },
        }
        if kafka:
            value["kafka"] = {
                "latestOffset": 12,
                "committedOffset": 10,
                "lag": 2,
                "producedPerSecond": 3.0,
                "consumedPerSecond": 2.0,
                "valid": True,
            }
        return json.dumps(value, allow_nan=True).encode("utf-8")


class ReportMathTest(unittest.TestCase):
    def test_zero_window_requires_complete_consecutive_idle_seconds(self) -> None:
        window = ZeroWindow(required_seconds=3)
        samples = [self._sample(second, lag=0, produced=0) for second in (0, 1, 2)]
        self.assertFalse(window.observe(samples[0], resumes_complete=False))
        self.assertFalse(window.observe(samples[0], resumes_complete=True))
        self.assertFalse(window.observe(samples[1], resumes_complete=True))
        self.assertTrue(window.observe(samples[2], resumes_complete=True))

        window = ZeroWindow(required_seconds=2)
        self.assertFalse(window.observe(self._sample(0, lag=0, produced=0), True))
        self.assertFalse(window.observe(self._sample(2, lag=0, produced=0), True))
        self.assertFalse(window.observe(self._sample(3, lag=0, produced=1), True))

    def test_nearest_rank_p95(self) -> None:
        self.assertIsNone(nearest_rank_p95([]))
        self.assertEqual(1, nearest_rank_p95([1]))
        self.assertEqual(19, nearest_rank_p95(list(range(1, 21))))

    def test_builds_peak_lag_drain_and_resource_summary(self) -> None:
        samples = (
            self._sample(0, latest=100, committed=100, lag=0, produced=0, consumed=0),
            self._sample(1, latest=120, committed=105, lag=15, produced=20, consumed=5),
            self._sample(2, latest=140, committed=120, lag=20, produced=20, consumed=15),
            self._sample(3, latest=140, committed=130, lag=10, produced=0, consumed=10),
            self._sample(4, latest=140, committed=140, lag=0, produced=0, consumed=10),
            self._sample(5, latest=140, committed=140, lag=0, produced=0, consumed=0),
        )
        snapshot = (self._task(1), self._task(2))
        now = datetime(2026, 7, 25, 2, tzinfo=UTC)
        outcomes = tuple(ResumeOutcome(task.id, now, now, "success", 200) for task in snapshot)
        reconciled = tuple(ReconciledTask(task.id, 2, "sending") for task in snapshot)

        summary = build_summary(
            samples,
            snapshot,
            outcomes,
            reconciled,
            invalid_kafka_samples=0,
            invalid_resource_samples=0,
            timed_out=False,
            interrupted=False,
            zero_window_seconds=2,
        )

        self.assertEqual(40, summary["topicProducedMessages"])
        self.assertEqual(20, summary["observedPeakProducedPerSecond"])
        self.assertEqual(15, summary["observedPeakConsumedPerSecond"])
        self.assertEqual(20, summary["maxLag"])
        self.assertEqual("2026-07-25T02:00:02Z", summary["maxLagAt"])
        self.assertEqual(2, summary["lagDrainSeconds"])
        self.assertEqual(10, summary["drainPeakConsumedPerSecond"])
        self.assertEqual("observed_backlog_drained", summary["capacityConclusion"])
        self.assertTrue(summary["allSnapshotTasksResumed"])
        self.assertFalse(summary["incomplete"])
        self.assertEqual(15, summary["resources"]["armada"]["hostCpuPercent"]["max"])

    def test_no_positive_lag_is_only_an_observed_lower_bound(self) -> None:
        samples = (self._sample(0), self._sample(1))
        summary = build_summary(
            samples,
            (),
            (),
            (),
            invalid_kafka_samples=0,
            invalid_resource_samples=0,
            timed_out=False,
            interrupted=False,
        )
        self.assertEqual("observed_lower_bound", summary["capacityConclusion"])

    def test_empty_and_undrained_data_do_not_claim_observed_capacity(self) -> None:
        empty = build_summary(
            (), (), (), (), invalid_kafka_samples=0, invalid_resource_samples=0,
            timed_out=True, interrupted=False, require_resumed=False,
        )
        self.assertIsNone(empty["topicProducedMessages"])
        self.assertIsNone(empty["maxLag"])
        self.assertIsNone(empty["maxLagAt"])
        self.assertEqual("insufficient_data", empty["capacityConclusion"])

        undrained = build_summary(
            (self._sample(0, lag=5), self._sample(1, lag=5)),
            (), (), (), invalid_kafka_samples=0, invalid_resource_samples=0,
            timed_out=True, interrupted=False, require_resumed=False,
        )
        self.assertEqual(5, undrained["maxLag"])
        self.assertEqual("2026-07-25T02:00:00Z", undrained["maxLagAt"])
        self.assertIsNone(undrained["lagDrainSeconds"])
        self.assertEqual("observed_backlog_not_drained", undrained["capacityConclusion"])

    def test_invalid_or_unreconciled_run_is_incomplete(self) -> None:
        task = self._task(1)
        now = datetime.now(UTC)
        summary = build_summary(
            (self._sample(0),),
            (task,),
            (ResumeOutcome(1, now, now, "transport_unknown", None),),
            (ReconciledTask(1, 5, "paused"),),
            invalid_kafka_samples=1,
            invalid_resource_samples=1,
            timed_out=True,
            interrupted=False,
        )
        self.assertTrue(summary["incomplete"])
        self.assertFalse(summary["allSnapshotTasksResumed"])

    def test_writes_exact_safe_csv_header(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.csv"
            write_samples_csv(path, (self._sample(0),))
            rows = list(csv.reader(io.StringIO(path.read_text(encoding="utf-8"))))
            self.assertEqual(
                [
                    "at", "kafkaLatestOffset", "kafkaCommittedOffset", "kafkaLag",
                    "producedPerSecond", "consumedPerSecond", "armadaHostCpuPercent",
                    "armadaHostMemoryUsedBytes", "armadaHostMemoryPercent",
                    "armadaContainerCpuPercent", "armadaContainerMemoryBytes",
                    "armadaContainerMemoryPercent", "zhuanHostCpuPercent",
                    "zhuanHostMemoryUsedBytes", "zhuanHostMemoryPercent",
                    "zhuanContainerCpuPercent", "zhuanContainerMemoryBytes",
                    "zhuanContainerMemoryPercent",
                ],
                rows[0],
            )
            text = path.read_text(encoding="utf-8")
            for forbidden in ("task", "broker", "payload", "message"):
                self.assertNotIn(forbidden, text.lower())

    def test_invalid_resource_fields_are_blank_without_dropping_kafka(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "samples.csv"
            sample = self._sample(0, latest=10, committed=9, lag=1)
            sample = replace(
                sample,
                armada_resource=replace(
                    sample.armada_resource, valid=False, error_class="docker_stats"
                ),
            )
            write_samples_csv(path, (sample,))
            row = list(csv.reader(io.StringIO(path.read_text(encoding="utf-8"))))[1]
            self.assertEqual(["10", "9", "1"], row[1:4])
            self.assertEqual(["", "", "", "", "", ""], row[6:12])
            self.assertNotEqual(["", "", "", "", "", ""], row[12:18])

    @staticmethod
    def _sample(second, latest=0, committed=0, lag=0, produced=0, consumed=0):
        at = datetime(2026, 7, 25, 2, 0, second, tzinfo=UTC)
        kafka = KafkaMetrics(latest, committed, lag, produced, consumed, True, None)
        armada = ResourceMetrics(10 + second, 100, 20, 30, 200, 40, True, None)
        zhuan = ResourceMetrics(20 + second, 300, 30, 40, 400, 50, True, None)
        return MergedSample(at, kafka, armada, zhuan)

    @staticmethod
    def _task(task_id):
        return TaskSnapshot(task_id, "task-%d" % task_id, 5, 2, 3, 4, 600, 1000, 2000)


if __name__ == "__main__":
    unittest.main()
