import io
import json
import unittest
from pathlib import Path

from group_task_simulator.cli import run
from group_task_simulator.load import LoadConfig, run_load
from group_task_simulator.model import (
    Action,
    ExecutionStatus,
    GroupStatus,
    InviteStatus,
    ParentLifecycle,
    ParentOutcome,
    ProtocolCommand,
)
from group_task_simulator.scenario import replay_scenario
from group_task_simulator.simulator import (
    CommandConflict,
    OperationConflict,
    SimulatorSafetyError,
    StatefulProtocolSimulator,
    WorldStateError,
)


SCENARIO_PATH = (
    Path(__file__).resolve().parents[1]
    / "group_task_simulator"
    / "scenarios"
    / "pl-s01c-task-199.json"
)


class StatefulProtocolSimulatorTest(unittest.TestCase):
    def test_rejects_every_endpoint_that_could_reach_a_real_protocol(self) -> None:
        for endpoint in (
            "",
            "http://localhost",
            "https://web.whatsapp.com",
            "ws://localhost",
            "sim+http://local",
        ):
            with self.subTest(endpoint=endpoint), self.assertRaises(SimulatorSafetyError):
                StatefulProtocolSimulator(endpoint)

    def test_same_operation_replays_result_without_second_mutation(self) -> None:
        simulator = self._simulator()
        first = simulator.dispatch(
            ProtocolCommand(
                operation_id="op-add-target",
                command_id="cmd-add-target-1",
                attempt_no=1,
                action=Action.BATCH_ADD_MEMBER,
                group_id="group-1",
                actor_id="puller-1",
                subject_id="target-1",
            )
        )
        replay = simulator.dispatch(
            ProtocolCommand(
                operation_id="op-add-target",
                command_id="cmd-add-target-2",
                attempt_no=2,
                action=Action.BATCH_ADD_MEMBER,
                group_id="group-1",
                actor_id="puller-1",
                subject_id="target-1",
            )
        )

        self.assertTrue(first.applied)
        self.assertFalse(first.replayed)
        self.assertFalse(replay.applied)
        self.assertTrue(replay.replayed)
        self.assertIn("target-1", simulator.group("group-1").members)
        ledger = simulator.operation("op-add-target")
        self.assertEqual(2, ledger.dispatch_count)
        self.assertEqual(2, ledger.accept_count)
        self.assertEqual(1, ledger.mutation_count)
        self.assertEqual(2, ledger.result_count)
        self.assertEqual(0, simulator.duplicate_mutation_count)

    def test_same_operation_cannot_change_its_business_target(self) -> None:
        simulator = self._simulator()
        simulator.dispatch(
            ProtocolCommand(
                operation_id="op-add-target",
                command_id="cmd-add-target-1",
                attempt_no=1,
                action=Action.BATCH_ADD_MEMBER,
                group_id="group-1",
                actor_id="puller-1",
                subject_id="target-1",
            )
        )

        with self.assertRaises(OperationConflict):
            simulator.dispatch(
                ProtocolCommand(
                    operation_id="op-add-target",
                    command_id="cmd-wrong-target",
                    attempt_no=2,
                    action=Action.BATCH_ADD_MEMBER,
                    group_id="group-1",
                    actor_id="puller-1",
                    subject_id="other-target",
                )
            )

        self.assertNotIn("other-target", simulator.group("group-1").members)
        self.assertEqual(1, simulator.operation("op-add-target").mutation_count)

    def test_different_operations_for_same_business_effect_are_reported_as_duplicate(self) -> None:
        simulator = self._simulator()
        for operation_id in ("op-add-target-1", "op-add-target-2"):
            simulator.dispatch(
                ProtocolCommand(
                    operation_id=operation_id,
                    command_id=f"cmd-{operation_id}",
                    attempt_no=1,
                    action=Action.BATCH_ADD_MEMBER,
                    group_id="group-1",
                    actor_id="puller-1",
                    subject_id="target-1",
                )
            )

        self.assertEqual(2, simulator.total_mutation_count)
        self.assertEqual(1, simulator.duplicate_mutation_count)

    def test_command_id_cannot_belong_to_two_operations(self) -> None:
        simulator = self._simulator()
        simulator.dispatch(
            ProtocolCommand(
                operation_id="op-contact-1",
                command_id="cmd-shared",
                attempt_no=1,
                action=Action.SAVE_CONTACT,
                group_id="group-1",
                actor_id="puller-1",
                subject_id="target-1",
            )
        )

        with self.assertRaises(CommandConflict):
            simulator.dispatch(
                ProtocolCommand(
                    operation_id="op-contact-2",
                    command_id="cmd-shared",
                    attempt_no=1,
                    action=Action.SAVE_CONTACT,
                    group_id="group-1",
                    actor_id="target-1",
                    subject_id="puller-1",
                )
            )

        self.assertEqual(1, simulator.total_mutation_count)

    def test_execution_cannot_complete_before_all_targets_succeed(self) -> None:
        simulator = self._simulator()

        with self.assertRaises(WorldStateError):
            simulator.complete_execution("execution-1")

        self.assertEqual(ExecutionStatus.RUNNING, simulator.execution("execution-1").status)

    def test_normal_execution_completes_with_successful_parent_outcome(self) -> None:
        simulator = self._simulator()
        simulator.dispatch(
            ProtocolCommand(
                operation_id="op-add-target",
                command_id="cmd-add-target",
                attempt_no=1,
                action=Action.BATCH_ADD_MEMBER,
                group_id="group-1",
                actor_id="puller-1",
                subject_id="target-1",
            )
        )
        simulator.complete_execution("execution-1")

        execution = simulator.execution("execution-1")
        parent = simulator.task("task-1")
        self.assertEqual(ExecutionStatus.SUCCEEDED, execution.status)
        self.assertEqual(1, execution.target_protocol_successes)
        self.assertEqual(ParentLifecycle.COMPLETED, parent.lifecycle)
        self.assertEqual(ParentOutcome.SUCCESS, parent.outcome)
        self.assertEqual((1, 1, 0), (parent.processed, parent.success, parent.abnormal))

    def test_mixed_web_android_parent_waits_for_both_executions(self) -> None:
        simulator = StatefulProtocolSimulator("sim://local")
        for backend in ("WEB", "ANDROID"):
            suffix = backend.lower()
            group_id = f"group-{suffix}"
            target_id = f"target-{suffix}"
            simulator.add_group(
                group_id=group_id,
                current_invite=f"invite-{suffix}",
                members={f"puller-{suffix}"},
                admins={f"puller-{suffix}"},
                member_add_enabled=True,
                approval_required=False,
            )
            simulator.start_execution(
                task_id="task-mixed",
                execution_id=f"execution-{suffix}",
                backend=backend,
                group_id=group_id,
                target_ids={target_id},
            )
            simulator.dispatch(
                ProtocolCommand(
                    operation_id=f"op-add-{suffix}",
                    command_id=f"cmd-add-{suffix}",
                    attempt_no=1,
                    action=Action.BATCH_ADD_MEMBER,
                    group_id=group_id,
                    actor_id=f"puller-{suffix}",
                    subject_id=target_id,
                )
            )

        simulator.complete_execution("execution-web")
        running_parent = simulator.task("task-mixed")
        self.assertEqual(ParentLifecycle.RUNNING, running_parent.lifecycle)
        self.assertEqual(ParentOutcome.PENDING, running_parent.outcome)
        self.assertEqual((1, 1, 0), (
            running_parent.processed,
            running_parent.success,
            running_parent.abnormal,
        ))

        simulator.complete_execution("execution-android")
        completed_parent = simulator.task("task-mixed")
        self.assertEqual(ParentLifecycle.COMPLETED, completed_parent.lifecycle)
        self.assertEqual(ParentOutcome.SUCCESS, completed_parent.outcome)
        self.assertEqual((2, 2, 0), (
            completed_parent.processed,
            completed_parent.success,
            completed_parent.abnormal,
        ))

    @staticmethod
    def _simulator() -> StatefulProtocolSimulator:
        simulator = StatefulProtocolSimulator("sim://local")
        simulator.add_group(
            group_id="group-1",
            current_invite="invite-v1",
            members={"promoter-1", "puller-1"},
            admins={"promoter-1"},
            member_add_enabled=True,
            approval_required=False,
        )
        simulator.start_execution(
            task_id="task-1",
            execution_id="execution-1",
            backend="ANDROID",
            group_id="group-1",
            target_ids={"target-1"},
        )
        return simulator


class Task199ReplayTest(unittest.TestCase):
    def test_replays_target_success_then_five_group_ban_events(self) -> None:
        report = replay_scenario(SCENARIO_PATH)

        self.assertTrue(report.passed, report.mismatches)
        actual = report.actual
        self.assertEqual("199", actual["taskId"])
        self.assertEqual("449", actual["executionId"])
        self.assertEqual(8, actual["commandCount"])
        self.assertEqual(8, actual["uniqueCommandCount"])
        self.assertEqual(8, actual["totalMutationCount"])
        self.assertEqual(0, actual["duplicateMutations"])
        self.assertEqual(5, actual["riskEventDeliveries"])
        self.assertEqual(1, actual["riskTransitions"])
        self.assertEqual(0, actual["newCommandsAfterTerminal"])
        self.assertEqual(GroupStatus.BANNED.value, actual["groupStatus"])
        self.assertEqual(InviteStatus.QUARANTINED.value, actual["inviteStatus"])
        self.assertTrue(actual["targetMember"])
        self.assertEqual(1, actual["targetProtocolSuccess"])
        self.assertEqual(ExecutionStatus.FAILED.value, actual["executionStatus"])
        self.assertEqual("GROUP_BANNED", actual["failureReason"])
        self.assertEqual(ParentLifecycle.COMPLETED.value, actual["parentLifecycle"])
        self.assertEqual(ParentOutcome.FAILED.value, actual["parentOutcome"])
        self.assertEqual((1, 0, 1), (actual["processed"], actual["success"], actual["abnormal"]))
        self.assertEqual(8, len(actual["operations"]))
        for operation in actual["operations"]:
            with self.subTest(operation=operation["operationId"]):
                self.assertEqual(1, operation["dispatchCount"])
                self.assertEqual(1, operation["acceptCount"])
                self.assertEqual(1, operation["mutationCount"])
                self.assertEqual(1, operation["resultCount"])
                self.assertEqual("SUCCESS", operation["result"])
                self.assertEqual(1, len(operation["commandIds"]))

    def test_cli_returns_machine_readable_passing_report(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()

        exit_code = run(["replay", "--scenario", str(SCENARIO_PATH)], stdout, stderr)

        self.assertEqual(0, exit_code)
        self.assertEqual("", stderr.getvalue())
        payload = json.loads(stdout.getvalue())
        self.assertTrue(payload["passed"])
        self.assertEqual("PL-S01C-TASK-199", payload["scenarioId"])
        self.assertEqual([], payload["mismatches"])


class SimulatorLoadTest(unittest.TestCase):
    def test_mixed_backend_load_keeps_retries_idempotent(self) -> None:
        report = run_load(
            LoadConfig(
                executions=12,
                concurrency=4,
                command_rate=0,
                backend="MIXED",
                replay_every=2,
            )
        )

        self.assertTrue(report.passed, report.failures)
        self.assertEqual(12, report.completed_executions)
        self.assertEqual(0, report.failed_executions)
        self.assertEqual(96, report.expected_business_mutations)
        self.assertEqual(96, report.total_mutations)
        self.assertEqual(48, report.replayed_commands)
        self.assertEqual(144, report.command_count)
        self.assertEqual(144, report.unique_command_count)
        self.assertEqual(0, report.duplicate_mutations)
        self.assertEqual(0, report.commands_after_terminal)
        self.assertEqual(6, report.backend_counts["WEB"])
        self.assertEqual(6, report.backend_counts["ANDROID"])
        self.assertEqual(1.0, report.terminal_completeness)
        self.assertEqual(4, report.max_in_flight)
        self.assertGreaterEqual(report.latency_ms["p99"], 0)
        self.assertGreaterEqual(report.schedule_lag_ms["p99"], 0)

    def test_load_config_rejects_unsafe_or_ambiguous_values(self) -> None:
        invalid = (
            {"executions": 0},
            {"concurrency": 0},
            {"command_rate": -1},
            {"backend": "AUTO"},
            {"replay_every": -1},
        )
        defaults = {
            "executions": 1,
            "concurrency": 1,
            "command_rate": 0,
            "backend": "ANDROID",
            "replay_every": 0,
        }
        for override in invalid:
            with self.subTest(override=override), self.assertRaises(ValueError):
                LoadConfig(**(defaults | override))

    def test_load_cli_returns_machine_readable_report(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()

        exit_code = run(
            [
                "load",
                "--executions",
                "4",
                "--concurrency",
                "2",
                "--command-rate",
                "0",
                "--backend",
                "MIXED",
                "--replay-every",
                "2",
            ],
            stdout,
            stderr,
        )

        self.assertEqual(0, exit_code)
        self.assertEqual("", stderr.getvalue())
        payload = json.loads(stdout.getvalue())
        self.assertTrue(payload["passed"])
        self.assertEqual(4, payload["completedExecutions"])
        self.assertEqual(0, payload["duplicateMutations"])
        self.assertEqual(16, payload["replayedCommands"])


if __name__ == "__main__":
    unittest.main()
