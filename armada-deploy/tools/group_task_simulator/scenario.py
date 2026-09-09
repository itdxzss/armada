from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Mapping

from .model import Action, ProtocolCommand, RiskEvent, RiskSignal
from .simulator import StatefulProtocolSimulator


class ScenarioError(ValueError):
    pass


@dataclass(frozen=True)
class ScenarioReport:
    scenario_id: str
    actual: Dict[str, object]
    expected: Dict[str, object]
    mismatches: List[str]

    @property
    def passed(self) -> bool:
        return not self.mismatches


def replay_scenario(path: Path) -> ScenarioReport:
    payload = _load_payload(path)
    task = _mapping(payload, "task")
    world = _mapping(payload, "world")
    group_payload = _mapping(world, "group")
    simulator = StatefulProtocolSimulator(_text(payload, "endpoint"))
    simulator.add_group(
        group_id=_text(group_payload, "groupId"),
        current_invite=_text(group_payload, "currentInvite"),
        members=_string_list(group_payload, "members"),
        admins=_string_list(group_payload, "admins"),
        member_add_enabled=_boolean(group_payload, "memberAddEnabled"),
        approval_required=_boolean(group_payload, "approvalRequired"),
    )
    simulator.start_execution(
        task_id=_text(task, "taskId"),
        execution_id=_text(task, "executionId"),
        backend=_text(task, "backend"),
        group_id=_text(task, "groupId"),
        target_ids=_string_list(task, "targetIds"),
    )
    for step in _step_list(payload):
        _apply_step(simulator, task, step)

    actual = _build_summary(simulator, task)
    expected = dict(_mapping(payload, "expected"))
    mismatches = [
        f"{key}: expected={expected[key]!r}, actual={actual.get(key)!r}"
        for key in sorted(expected)
        if actual.get(key) != expected[key]
    ]
    return ScenarioReport(_text(payload, "scenarioId"), actual, expected, mismatches)


def _apply_step(
    simulator: StatefulProtocolSimulator,
    task: Mapping[str, object],
    step: Mapping[str, object],
) -> None:
    kind = _text(step, "kind")
    group_id = _text(task, "groupId")
    if kind == "command":
        simulator.dispatch(
            ProtocolCommand(
                operation_id=_text(step, "operationId"),
                command_id=_text(step, "commandId"),
                attempt_no=_integer(step, "attemptNo"),
                action=Action(_text(step, "action")),
                group_id=group_id,
                actor_id=_text(step, "actorId"),
                subject_id=_text(step, "subjectId"),
            )
        )
        return
    if kind == "risk":
        simulator.deliver_risk(
            RiskEvent(
                source_event_id=_text(step, "sourceEventId"),
                signal=RiskSignal(_text(step, "signal")),
                group_id=group_id,
                occurred_at=_text(step, "occurredAt"),
                delivered_at=_text(step, "deliveredAt"),
            )
        )
        return
    raise ScenarioError("unsupported scenario step")


def _build_summary(
    simulator: StatefulProtocolSimulator,
    task_payload: Mapping[str, object],
) -> Dict[str, object]:
    task_id = _text(task_payload, "taskId")
    execution_id = _text(task_payload, "executionId")
    group_id = _text(task_payload, "groupId")
    target_ids = _string_list(task_payload, "targetIds")
    if len(target_ids) != 1:
        raise ScenarioError("first simulator slice requires exactly one target")
    group = simulator.group(group_id)
    execution = simulator.execution(execution_id)
    parent = simulator.task(task_id)
    operations = [
        {
            "operationId": operation.operation_id,
            "action": operation.action.value,
            "commandIds": sorted(operation.command_ids),
            "dispatchCount": operation.dispatch_count,
            "acceptCount": operation.accept_count,
            "mutationCount": operation.mutation_count,
            "resultCount": operation.result_count,
            "result": operation.result,
        }
        for operation in simulator.operations()
    ]
    return {
        "taskId": task_id,
        "executionId": execution_id,
        "backend": execution.backend,
        "commandCount": simulator.command_count,
        "uniqueCommandCount": simulator.unique_command_count,
        "totalMutationCount": simulator.total_mutation_count,
        "duplicateMutations": simulator.duplicate_mutation_count,
        "riskEventDeliveries": simulator.risk_event_deliveries,
        "riskTransitions": simulator.risk_transitions,
        "newCommandsAfterTerminal": simulator.commands_after_terminal,
        "groupStatus": group.status.value,
        "inviteStatus": group.invite_status.value,
        "targetMember": target_ids[0] in group.members,
        "targetProtocolSuccess": execution.target_protocol_successes,
        "executionStatus": execution.status.value,
        "failureReason": execution.failure_reason,
        "parentLifecycle": parent.lifecycle.value,
        "parentOutcome": parent.outcome.value,
        "processed": parent.processed,
        "success": parent.success,
        "abnormal": parent.abnormal,
        "operations": operations,
    }


def _load_payload(path: Path) -> Mapping[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ScenarioError("scenario cannot be loaded") from error
    if not isinstance(payload, dict) or payload.get("schemaVersion") != 1:
        raise ScenarioError("unsupported scenario schema")
    return payload


def _mapping(payload: Mapping[str, object], key: str) -> Mapping[str, object]:
    value = payload.get(key)
    if not isinstance(value, dict):
        raise ScenarioError(f"{key} must be an object")
    return value


def _text(payload: Mapping[str, object], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ScenarioError(f"{key} must be non-empty text")
    return value


def _integer(payload: Mapping[str, object], key: str) -> int:
    value = payload.get(key)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ScenarioError(f"{key} must be an integer")
    return value


def _boolean(payload: Mapping[str, object], key: str) -> bool:
    value = payload.get(key)
    if not isinstance(value, bool):
        raise ScenarioError(f"{key} must be a boolean")
    return value


def _string_list(payload: Mapping[str, object], key: str) -> List[str]:
    value = payload.get(key)
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ScenarioError(f"{key} must be a string list")
    return value


def _step_list(payload: Mapping[str, object]) -> List[Mapping[str, object]]:
    value = payload.get("steps")
    if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
        raise ScenarioError("steps must be an object list")
    return value
