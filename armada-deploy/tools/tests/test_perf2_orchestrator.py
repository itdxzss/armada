import json
import os
import queue
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

from perf2_loadtest.model import (
    BuiltMonitor,
    MonitorEvent,
    NodePreflight,
    PreflightEvidence,
    ReconciledTask,
    ResumeOutcome,
    RunOptions,
    TaskSnapshot,
)
from perf2_loadtest.orchestrator import Orchestrator, RunState


UTC = timezone.utc


def task(task_id):
    return TaskSnapshot(task_id, "task-%d" % task_id, 5, 2, 3, 4, 600, 1000, 2000)


def monitor_event(
    node,
    second,
    *,
    lag=0,
    produced=0,
    consumed=0,
    valid=True,
    resource_valid=None,
    kafka_valid=None,
    received_monotonic=None,
):
    resource_valid = valid if resource_valid is None else resource_valid
    kafka_valid = valid if kafka_valid is None else kafka_valid
    value = {
        "schemaVersion": 1,
        "at": "2026-07-25T02:00:%02dZ" % second,
        "node": node,
        "resource": {
            "hostCpuPercent": 10,
            "hostMemoryUsedBytes": 100,
            "hostMemoryPercent": 20,
            "containerCpuPercent": 30,
            "containerMemoryBytes": 200,
            "containerMemoryPercent": 40,
            "valid": resource_valid,
            "errorClass": None if resource_valid else "docker_stats",
        },
    }
    if node == "zhuan":
        value["kafka"] = {
            "latestOffset": 100 + second * 10,
            "committedOffset": 100 + second * 10 - lag,
            "lag": lag,
            "producedPerSecond": produced,
            "consumedPerSecond": consumed,
            "valid": kafka_valid,
            "errorClass": None if kafka_valid else "metadata",
        }
    received = float(second) if received_monotonic is None else received_monotonic
    return MonitorEvent(
        node=node,
        kind="sample",
        line=json.dumps(value).encode("utf-8") + b"\n",
        received_monotonic=received,
    )


class FakeRemoteManager:
    def __init__(self, events, calls, fail_preflight=False, fail_upload=False):
        self.events = queue.Queue()
        for event in events:
            self.events.put(event)
        self.calls = calls
        self.fail_preflight = fail_preflight
        self.fail_upload = fail_upload

    def build(self, repo):
        self.calls.append("build")
        return BuiltMonitor(Path(repo) / "perf-monitor", "a" * 64)

    def preflight(self):
        self.calls.append("preflight")
        if self.fail_preflight:
            raise RuntimeError("secret remote detail")
        node = NodePreflight("x86_64", True, 8 * 1024**3, True)
        return PreflightEvidence(node, node)

    def upload_and_check(self, built, run_id):
        self.calls.append("upload_check")
        if self.fail_upload:
            raise RuntimeError("live kafka identity mismatch")

    def start(self):
        self.calls.append("start")
        return SimpleNamespace(events=self.events)

    def close(self):
        self.calls.append("close")


class FakeTaskAPI:
    def __init__(
        self,
        inventories,
        calls,
        resume_finished_second=1.5,
        resume_finished_monotonic=1.5,
    ):
        self.inventories = list(inventories)
        self.calls = calls
        self.resume_calls = 0
        self.resume_finished_second = resume_finished_second
        self.resume_finished_monotonic = resume_finished_monotonic

    def list_paused(self):
        self.calls.append("list_paused")
        return self.inventories.pop(0)

    def resume_snapshot_once(self, snapshot, concurrency):
        self.calls.append("resume")
        self.resume_calls += 1
        now = datetime(2026, 7, 25, 2, 0, tzinfo=UTC) + timedelta(
            seconds=self.resume_finished_second
        )
        return tuple(
            ResumeOutcome(
                value.id,
                now,
                now,
                "success",
                200,
                finished_monotonic=self.resume_finished_monotonic,
            )
            for value in snapshot
        )

    def reconcile(self, snapshot, concurrency):
        self.calls.append("reconcile")
        return tuple(ReconciledTask(value.id, 2, "sending") for value in snapshot)


class OrchestratorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.zhuan_repo = self.root / "zhuan"
        self.zhuan_repo.mkdir()
        (self.zhuan_repo / "perf-monitor").write_bytes(b"fixture")
        self.results = self.root / "results"
        self.run_id = "20260725T020000Z-deadbeef"
        self.run_counter = 0

    def tearDown(self):
        self.temp.cleanup()

    def test_dry_run_collects_baseline_and_never_posts(self) -> None:
        calls = []
        snapshot = (task(1), task(2))
        api = FakeTaskAPI((snapshot, snapshot), calls)
        remote = FakeRemoteManager(self._events((0, 0, 0), (1, 0, 0)), calls)
        orchestrator = self._orchestrator(api, remote, execute=False)

        exit_code = orchestrator.run()

        self.assertEqual(0, exit_code)
        self.assertEqual(0, api.resume_calls)
        self.assertEqual(RunState.REPORTED, orchestrator.state)
        self.assertEqual(
            ["build", "preflight", "upload_check", "start", "list_paused", "list_paused", "close"],
            calls,
        )
        summary = self._read("summary.json")
        self.assertEqual("dry-run", summary["mode"])
        self.assertEqual(2, summary["snapshotTaskCount"])
        self.assertFalse(summary["incomplete"])

    def test_baseline_warmup_gap_restarts_full_candidate_window(self) -> None:
        calls = []
        snapshot = (task(1),)
        api = FakeTaskAPI((snapshot, snapshot), calls)
        remote = FakeRemoteManager(
            self._events((0, 0, 0), (2, 0, 0), (3, 0, 0), (4, 0, 0)),
            calls,
        )
        orchestrator = self._orchestrator(
            api,
            remote,
            execute=False,
            baseline_seconds=3,
        )

        self.assertEqual(0, orchestrator.run())

        with (orchestrator.run_dir / "samples.csv").open(encoding="utf-8") as samples:
            rows = samples.read().splitlines()
        self.assertEqual(4, len(rows))
        self.assertIn("02:00:02Z", rows[1])
        self.assertIn("02:00:04Z", rows[3])

    def test_execute_runs_guarded_resume_then_waits_for_zero_window(self) -> None:
        calls = []
        snapshot = (task(1), task(2))
        api = FakeTaskAPI((snapshot, snapshot), calls)
        remote = FakeRemoteManager(
            self._events(
                (0, 0, 0),
                (1, 0, 0),
                (2, 5, 10),
                (3, 2, 0),
                (4, 0, 0),
                (5, 0, 0),
            ),
            calls,
        )
        orchestrator = self._orchestrator(api, remote, execute=True, expected_count=2)

        exit_code = orchestrator.run()

        self.assertEqual(0, exit_code)
        self.assertEqual(1, api.resume_calls)
        self.assertLess(calls.index("list_paused", calls.index("list_paused") + 1), calls.index("resume"))
        self.assertLess(calls.index("resume"), calls.index("reconcile"))
        summary = self._read("summary.json")
        self.assertEqual("execute", summary["mode"])
        self.assertTrue(summary["allSnapshotTasksResumed"])
        self.assertEqual(5, summary["maxLag"])
        self.assertFalse(summary["incomplete"])
        self.assertEqual(0o700, os.stat(orchestrator.run_dir).st_mode & 0o777)
        for name in (
            "task-snapshot.json", "samples.csv", "resume-results.json", "summary.json", "run.log"
        ):
            self.assertTrue((orchestrator.run_dir / name).is_file(), name)
            self.assertEqual(0o600, os.stat(orchestrator.run_dir / name).st_mode & 0o777)

    def test_pre_resume_backlog_cannot_satisfy_post_resume_zero_window(self) -> None:
        calls = []
        snapshot = (task(1),)
        api = FakeTaskAPI(
            (snapshot, snapshot), calls, resume_finished_monotonic=4.5
        )
        remote = FakeRemoteManager(
            self._events(
                (0, 0, 0),
                (1, 0, 0),
                (2, 0, 0),
                (3, 10, 5),
                (4, 0, 0),
                (5, 0, 0),
                (6, 0, 0),
            ),
            calls,
        )
        orchestrator = self._orchestrator(api, remote, execute=True, expected_count=1)

        self.assertEqual(0, orchestrator.run())

        summary = self._read("summary.json")
        self.assertEqual(10, summary["maxLag"])
        self.assertEqual("observed_backlog_drained", summary["capacityConclusion"])

    def test_remote_clock_skew_cannot_shorten_post_resume_zero_window(self) -> None:
        calls = []
        snapshot = (task(1),)
        api = FakeTaskAPI(
            (snapshot, snapshot), calls, resume_finished_monotonic=4.5
        )
        events = self._events((0, 0, 0), (1, 0, 0))
        for second, received, lag, produced in (
            (5, 3.0, 0, 0),
            (6, 4.0, 0, 0),
            (7, 5.0, 7, 3),
            (8, 6.0, 0, 0),
            (9, 7.0, 0, 0),
        ):
            events.extend(
                (
                    monitor_event(
                        "zhuan", second, lag=lag, produced=produced,
                        consumed=produced, received_monotonic=received,
                    ),
                    monitor_event(
                        "armada", second, received_monotonic=received,
                    ),
                )
            )
        remote = FakeRemoteManager(events, calls)
        orchestrator = self._orchestrator(api, remote, execute=True, expected_count=1)

        self.assertEqual(0, orchestrator.run())
        self.assertEqual(7, self._read("summary.json")["maxLag"])

    def test_transient_invalid_post_resume_samples_continue_observation(self) -> None:
        for name, invalid_events, expected_field in (
            (
                "resource",
                (
                    monitor_event("zhuan", 2),
                    monitor_event("armada", 2, resource_valid=False),
                ),
                "invalidResourceSamples",
            ),
            (
                "kafka",
                (
                    monitor_event("zhuan", 2, kafka_valid=False),
                    monitor_event("armada", 2),
                ),
                "invalidKafkaSamples",
            ),
        ):
            with self.subTest(name=name):
                calls = []
                snapshot = (task(1),)
                events = self._events((0, 0, 0), (1, 0, 0))
                events.extend(invalid_events)
                events.extend(self._events((3, 0, 0), (4, 0, 0)))
                api = FakeTaskAPI((snapshot, snapshot), calls)
                remote = FakeRemoteManager(events, calls)
                orchestrator = self._orchestrator(api, remote, execute=True, expected_count=1)

                self.assertEqual(1, orchestrator.run())

                summary = json.loads(
                    (orchestrator.run_dir / "summary.json").read_text(encoding="utf-8")
                )
                self.assertEqual(1, summary[expected_field])
                self.assertIsNone(summary["failureClass"])
                self.assertIn("reconcile", calls)

    def test_changed_or_mismatched_snapshot_cannot_reach_resume(self) -> None:
        cases = (
            ((task(1),), (task(1), task(2)), 2),
            ((task(1),), (task(1),), 2),
        )
        for initial, frozen, expected in cases:
            with self.subTest(initial=initial, frozen=frozen):
                calls = []
                api = FakeTaskAPI((initial, frozen), calls)
                remote = FakeRemoteManager(self._events((0, 0, 0), (1, 0, 0)), calls)
                orchestrator = self._orchestrator(api, remote, execute=True, expected_count=expected)
                self.assertEqual(1, orchestrator.run())
                self.assertEqual(0, api.resume_calls)
                self.assertNotIn("resume", calls)

    def test_invalid_baseline_or_preflight_cannot_reach_resume(self) -> None:
        for name, remote in (
            ("preflight", FakeRemoteManager([], [], fail_preflight=True)),
            (
                "invalid sample",
                FakeRemoteManager(
                    [monitor_event("armada", 0, valid=False), monitor_event("zhuan", 0, valid=False)], []
                ),
            ),
            (
                "final lag",
                FakeRemoteManager(self._events((0, 0, 0), (1, 2, 0)), []),
            ),
        ):
            with self.subTest(name=name):
                calls = remote.calls
                api = FakeTaskAPI(((task(1),), (task(1),)), calls)
                orchestrator = self._orchestrator(api, remote, execute=True, expected_count=1)
                self.assertEqual(1, orchestrator.run())
                self.assertEqual(0, api.resume_calls)

    def test_live_kafka_identity_probe_failure_cannot_reach_resume(self) -> None:
        calls = []
        snapshot = (task(1),)
        api = FakeTaskAPI((snapshot, snapshot), calls)
        remote = FakeRemoteManager([], calls, fail_upload=True)
        orchestrator = self._orchestrator(api, remote, execute=True, expected_count=1)

        self.assertEqual(1, orchestrator.run())
        self.assertEqual(0, api.resume_calls)
        self.assertNotIn("resume", calls)

    def test_stream_failure_after_resume_reconciles_and_marks_incomplete(self) -> None:
        calls = []
        snapshot = (task(1),)
        events = self._events((0, 0, 0), (1, 0, 0))
        events.append(MonitorEvent("zhuan", "failure", error_class="monitor_failed"))
        api = FakeTaskAPI((snapshot, snapshot), calls)
        remote = FakeRemoteManager(events, calls)
        orchestrator = self._orchestrator(api, remote, execute=True, expected_count=1)

        self.assertEqual(1, orchestrator.run())

        self.assertIn("resume", calls)
        self.assertIn("reconcile", calls)
        self.assertTrue(self._read("summary.json")["incomplete"])

    def test_resume_boundary_failure_still_reconciles_uncertain_requests(self) -> None:
        calls = []
        snapshot = (task(1),)

        class UncertainTaskAPI(FakeTaskAPI):
            def resume_snapshot_once(self, snapshot, concurrency):
                self.calls.append("resume")
                self.resume_calls += 1
                raise RuntimeError("response lost after POST")

        api = UncertainTaskAPI((snapshot, snapshot), calls)
        remote = FakeRemoteManager(self._events((0, 0, 0), (1, 0, 0)), calls)
        orchestrator = self._orchestrator(api, remote, execute=True, expected_count=1)

        self.assertEqual(1, orchestrator.run())

        self.assertIn("resume", calls)
        self.assertIn("reconcile", calls)
        self.assertTrue((orchestrator.run_dir / "task-snapshot.json").is_file())

    def _orchestrator(
        self,
        api,
        remote,
        *,
        execute,
        expected_count=None,
        baseline_seconds=2,
    ):
        run_id = "20260725T020000Z-%08x" % (0xDEADBEEF + self.run_counter)
        self.run_counter += 1
        options = RunOptions(
            env="perf2",
            tenant="demo",
            execute=execute,
            expected_count=expected_count,
            resume_concurrency=4,
            baseline_seconds=baseline_seconds,
            zero_window_seconds=2,
            timeout_seconds=5,
            min_free_gib=5,
        )
        return Orchestrator(
            options=options,
            task_api=api,
            remote_manager=remote,
            results_root=self.results,
            zhuan_repo=self.zhuan_repo,
            run_id_factory=lambda: run_id,
        )

    @staticmethod
    def _events(*values):
        events = []
        for second, lag, produced in values:
            events.extend(
                (
                    monitor_event("zhuan", second, lag=lag, produced=produced, consumed=produced),
                    monitor_event("armada", second, lag=lag, produced=produced, consumed=produced),
                )
            )
        return events

    def _read(self, name):
        return json.loads((self.results / self.run_id / name).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
