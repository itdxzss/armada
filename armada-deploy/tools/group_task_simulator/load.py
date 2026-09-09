from __future__ import annotations

import math
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from typing import Dict, List, Sequence, Tuple

from .model import Action, ExecutionStatus, ParentOutcome, ProtocolCommand
from .simulator import StatefulProtocolSimulator


COMMANDS_PER_EXECUTION = 8
SUPPORTED_BACKENDS = ("ANDROID", "WEB", "MIXED")


@dataclass(frozen=True)
class LoadConfig:
    executions: int
    concurrency: int
    command_rate: float
    backend: str
    replay_every: int = 0

    def __post_init__(self) -> None:
        if not 1 <= self.executions <= 100_000:
            raise ValueError("executions must be between 1 and 100000")
        if not 1 <= self.concurrency <= 1_024:
            raise ValueError("concurrency must be between 1 and 1024")
        if not 0 <= self.command_rate <= 100_000:
            raise ValueError("command_rate must be between 0 and 100000")
        if self.backend not in SUPPORTED_BACKENDS:
            raise ValueError("backend must be ANDROID, WEB, or MIXED")
        if not 0 <= self.replay_every <= COMMANDS_PER_EXECUTION:
            raise ValueError("replay_every must be between 0 and 8")


@dataclass(frozen=True)
class _ExecutionResult:
    backend: str
    command_count: int
    unique_command_count: int
    total_mutations: int
    duplicate_mutations: int
    commands_after_terminal: int
    replayed_commands: int


@dataclass(frozen=True)
class LoadReport:
    config: LoadConfig
    completed_executions: int
    failed_executions: int
    expected_business_mutations: int
    total_mutations: int
    command_count: int
    unique_command_count: int
    replayed_commands: int
    duplicate_mutations: int
    commands_after_terminal: int
    backend_counts: Dict[str, int]
    terminal_completeness: float
    max_in_flight: int
    wall_seconds: float
    actual_command_rate: float
    latency_ms: Dict[str, float]
    schedule_lag_ms: Dict[str, float]
    failures: Tuple[str, ...]

    @property
    def passed(self) -> bool:
        return (
            self.failed_executions == 0
            and self.completed_executions == self.config.executions
            and self.total_mutations == self.expected_business_mutations
            and self.command_count == self.unique_command_count
            and self.duplicate_mutations == 0
            and self.commands_after_terminal == 0
        )

    def as_dict(self) -> Dict[str, object]:
        return {
            "passed": self.passed,
            "scheduledExecutions": self.config.executions,
            "completedExecutions": self.completed_executions,
            "failedExecutions": self.failed_executions,
            "configuredConcurrency": self.config.concurrency,
            "targetCommandRate": self.config.command_rate,
            "actualCommandRate": self.actual_command_rate,
            "expectedBusinessMutations": self.expected_business_mutations,
            "totalMutations": self.total_mutations,
            "commandCount": self.command_count,
            "uniqueCommandCount": self.unique_command_count,
            "replayedCommands": self.replayed_commands,
            "duplicateMutations": self.duplicate_mutations,
            "commandsAfterTerminal": self.commands_after_terminal,
            "backendCounts": self.backend_counts,
            "terminalCompleteness": self.terminal_completeness,
            "maxInFlight": self.max_in_flight,
            "wallSeconds": self.wall_seconds,
            "latencyMs": self.latency_ms,
            "scheduleLagMs": self.schedule_lag_ms,
            "failures": list(self.failures),
            "scope": "LOCAL_SIMULATOR_ONLY",
        }


def run_load(config: LoadConfig) -> LoadReport:
    started_at = time.perf_counter()
    planned_commands = COMMANDS_PER_EXECUTION + (
        COMMANDS_PER_EXECUTION // config.replay_every
        if config.replay_every
        else 0
    )
    interval = (
        planned_commands / config.command_rate
        if config.command_rate > 0
        else 0.0
    )
    gauge_lock = threading.Lock()
    burst_width = min(config.concurrency, config.executions)
    burst_barrier = (
        threading.Barrier(burst_width)
        if config.command_rate == 0 and burst_width > 1
        else None
    )
    in_flight = 0
    max_in_flight = 0
    latencies: List[float] = []
    schedule_lags: List[float] = []
    results: List[_ExecutionResult] = []
    failures: List[str] = []

    def execute(index: int, due_at: float) -> _ExecutionResult:
        nonlocal in_flight, max_in_flight
        actual_start = time.perf_counter()
        with gauge_lock:
            in_flight += 1
            max_in_flight = max(max_in_flight, in_flight)
        try:
            if burst_barrier is not None and index < burst_width:
                burst_barrier.wait(timeout=5)
            return _run_execution(index, _backend(config.backend, index), config.replay_every)
        finally:
            finished_at = time.perf_counter()
            with gauge_lock:
                in_flight -= 1
                latencies.append((finished_at - actual_start) * 1000)
                schedule_lags.append(max(0.0, actual_start - due_at) * 1000)

    with ThreadPoolExecutor(max_workers=config.concurrency) as executor:
        futures = []
        for index in range(config.executions):
            due_at = started_at + (index + 1) * interval
            delay = due_at - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            futures.append(executor.submit(execute, index, due_at))
        for future in as_completed(futures):
            try:
                results.append(future.result())
            except Exception as error:  # load reports must retain every worker failure
                failures.append(f"{type(error).__name__}: {error}")

    wall_seconds = max(time.perf_counter() - started_at, 0.000_001)
    completed = len(results)
    backend_counts = {
        backend: sum(result.backend == backend for result in results)
        for backend in ("WEB", "ANDROID")
    }
    command_count = sum(result.command_count for result in results)
    return LoadReport(
        config=config,
        completed_executions=completed,
        failed_executions=len(failures),
        expected_business_mutations=config.executions * COMMANDS_PER_EXECUTION,
        total_mutations=sum(result.total_mutations for result in results),
        command_count=command_count,
        unique_command_count=sum(result.unique_command_count for result in results),
        replayed_commands=sum(result.replayed_commands for result in results),
        duplicate_mutations=sum(result.duplicate_mutations for result in results),
        commands_after_terminal=sum(result.commands_after_terminal for result in results),
        backend_counts=backend_counts,
        terminal_completeness=completed / config.executions,
        max_in_flight=max_in_flight,
        wall_seconds=wall_seconds,
        actual_command_rate=command_count / wall_seconds,
        latency_ms=_distribution(latencies),
        schedule_lag_ms=_distribution(schedule_lags),
        failures=tuple(failures),
    )


def _run_execution(index: int, backend: str, replay_every: int) -> _ExecutionResult:
    suffix = f"{index:06d}"
    group_id = f"group-{suffix}"
    execution_id = f"execution-{suffix}"
    owner = f"owner-{suffix}"
    manager = f"manager-{suffix}"
    puller = f"puller-{suffix}"
    target = f"target-{suffix}"
    simulator = StatefulProtocolSimulator("sim://local")
    simulator.add_group(
        group_id=group_id,
        current_invite=f"invite-{suffix}",
        members={owner},
        admins={owner},
        member_add_enabled=False,
        approval_required=True,
    )
    simulator.start_execution(
        task_id=f"task-{suffix}",
        execution_id=execution_id,
        backend=backend,
        group_id=group_id,
        target_ids={target},
    )
    operations = _operations(owner, manager, puller, target)
    replayed = 0
    for position, (action, actor, subject) in enumerate(operations, start=1):
        operation_id = f"op-{suffix}-{position:02d}"
        simulator.dispatch(ProtocolCommand(
            operation_id=operation_id,
            command_id=f"cmd-{suffix}-{position:02d}-1",
            attempt_no=1,
            action=action,
            group_id=group_id,
            actor_id=actor,
            subject_id=subject,
        ))
        if replay_every and position % replay_every == 0:
            simulator.dispatch(ProtocolCommand(
                operation_id=operation_id,
                command_id=f"cmd-{suffix}-{position:02d}-2",
                attempt_no=2,
                action=action,
                group_id=group_id,
                actor_id=actor,
                subject_id=subject,
            ))
            replayed += 1
    simulator.complete_execution(execution_id)
    execution = simulator.execution(execution_id)
    parent = simulator.task(f"task-{suffix}")
    if execution.status is not ExecutionStatus.SUCCEEDED:
        raise AssertionError("execution did not reach SUCCEEDED")
    if parent.outcome is not ParentOutcome.SUCCESS:
        raise AssertionError("parent did not reach SUCCESS")
    return _ExecutionResult(
        backend=backend,
        command_count=simulator.command_count,
        unique_command_count=simulator.unique_command_count,
        total_mutations=simulator.total_mutation_count,
        duplicate_mutations=simulator.duplicate_mutation_count,
        commands_after_terminal=simulator.commands_after_terminal,
        replayed_commands=replayed,
    )


def _operations(
    owner: str,
    manager: str,
    puller: str,
    target: str,
) -> Sequence[Tuple[Action, str, str]]:
    return (
        (Action.SAVE_CONTACT, manager, puller),
        (Action.SAVE_CONTACT, puller, manager),
        (Action.JOIN_GROUP, owner, manager),
        (Action.PROMOTE_MEMBER, owner, manager),
        (Action.ENABLE_MEMBER_ADD, manager, "group-setting"),
        (Action.DISABLE_JOIN_APPROVAL, manager, "group-setting"),
        (Action.INVITE_MEMBER, manager, puller),
        (Action.BATCH_ADD_MEMBER, puller, target),
    )


def _backend(configured: str, index: int) -> str:
    if configured == "MIXED":
        return "WEB" if index % 2 == 0 else "ANDROID"
    return configured


def _distribution(values: Sequence[float]) -> Dict[str, float]:
    if not values:
        return {"p50": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0}
    ordered = sorted(values)
    return {
        "p50": _percentile(ordered, 0.50),
        "p95": _percentile(ordered, 0.95),
        "p99": _percentile(ordered, 0.99),
        "max": ordered[-1],
    }


def _percentile(ordered: Sequence[float], quantile: float) -> float:
    index = max(0, math.ceil(len(ordered) * quantile) - 1)
    return ordered[index]
