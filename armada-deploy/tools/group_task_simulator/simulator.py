from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, Iterable, Set, Tuple

from .model import (
    Action,
    DispatchResult,
    ExecutionStatus,
    ExecutionView,
    GroupStatus,
    GroupView,
    InviteStatus,
    OperationLedgerView,
    ParentLifecycle,
    ParentOutcome,
    ProtocolCommand,
    RiskEvent,
    RiskSignal,
    TaskView,
)


class SimulatorSafetyError(ValueError):
    pass


class WorldStateError(ValueError):
    pass


class OperationConflict(ValueError):
    pass


class CommandConflict(ValueError):
    pass


class ExecutionTerminatedError(ValueError):
    pass


@dataclass
class _GroupState:
    group_id: str
    current_invite: str
    members: Set[str]
    admins: Set[str]
    member_add_enabled: bool
    approval_required: bool
    status: GroupStatus = GroupStatus.ACTIVE
    invite_status: InviteStatus = InviteStatus.ACTIVE


@dataclass
class _OperationState:
    operation_id: str
    action: Action
    group_id: str
    actor_id: str
    subject_id: str
    dispatch_count: int = 1
    accept_count: int = 1
    mutation_count: int = 1
    result_count: int = 1
    command_ids: Set[str] = field(default_factory=set)

    @property
    def fingerprint(self) -> Tuple[Action, str, str, str]:
        return self.action, self.group_id, self.actor_id, self.subject_id


@dataclass
class _ExecutionState:
    task_id: str
    execution_id: str
    backend: str
    group_id: str
    target_ids: Set[str]
    successful_target_ids: Set[str] = field(default_factory=set)
    status: ExecutionStatus = ExecutionStatus.RUNNING
    failure_reason: str = ""


@dataclass
class _TaskState:
    task_id: str
    execution_ids: Set[str] = field(default_factory=set)


class StatefulProtocolSimulator:
    SAFE_ENDPOINT = "sim://local"

    def __init__(self, endpoint: str) -> None:
        if endpoint != self.SAFE_ENDPOINT:
            raise SimulatorSafetyError("simulator endpoint must be the fail-closed local scheme")
        self._groups: Dict[str, _GroupState] = {}
        self._operations: Dict[str, _OperationState] = {}
        self._executions: Dict[str, _ExecutionState] = {}
        self._execution_by_group: Dict[str, str] = {}
        self._tasks: Dict[str, _TaskState] = {}
        self._contacts: Set[Tuple[str, str]] = set()
        self._business_mutations: Dict[Tuple[str, ...], int] = {}
        self._command_ids: Set[str] = set()
        self._command_operations: Dict[str, str] = {}
        self._command_count = 0
        self._risk_events = []
        self._risk_transitions = 0
        self._commands_after_terminal = 0

    def add_group(
        self,
        group_id: str,
        current_invite: str,
        members: Iterable[str],
        admins: Iterable[str],
        member_add_enabled: bool,
        approval_required: bool,
    ) -> None:
        if group_id in self._groups:
            raise WorldStateError("group already exists")
        member_set = set(members)
        admin_set = set(admins)
        if not admin_set.issubset(member_set):
            raise WorldStateError("every group admin must also be a member")
        self._groups[group_id] = _GroupState(
            group_id=group_id,
            current_invite=current_invite,
            members=member_set,
            admins=admin_set,
            member_add_enabled=member_add_enabled,
            approval_required=approval_required,
        )

    def start_execution(
        self,
        task_id: str,
        execution_id: str,
        backend: str,
        group_id: str,
        target_ids: Iterable[str],
    ) -> None:
        self._require_group(group_id)
        if execution_id in self._executions or group_id in self._execution_by_group:
            raise WorldStateError("execution or group is already registered")
        execution = _ExecutionState(
            task_id=task_id,
            execution_id=execution_id,
            backend=backend,
            group_id=group_id,
            target_ids=set(target_ids),
        )
        self._executions[execution_id] = execution
        self._execution_by_group[group_id] = execution_id
        task = self._tasks.setdefault(task_id, _TaskState(task_id=task_id))
        task.execution_ids.add(execution_id)

    def dispatch(self, command: ProtocolCommand) -> DispatchResult:
        group = self._require_group(command.group_id)
        execution = self._execution_for_group(command.group_id)
        if execution.status is not ExecutionStatus.RUNNING:
            self._commands_after_terminal += 1
            raise ExecutionTerminatedError("cannot dispatch after execution terminal state")
        if group.status is not GroupStatus.ACTIVE:
            raise WorldStateError("cannot mutate a non-active group")

        command_operation = self._command_operations.get(command.command_id)
        if command_operation is not None and command_operation != command.operation_id:
            raise CommandConflict("command id cannot belong to two operations")
        current = self._operations.get(command.operation_id)
        if current is not None:
            self._verify_same_operation(current, command)
            self._record_command(command)
            current.dispatch_count += 1
            current.accept_count += 1
            current.result_count += 1
            current.command_ids.add(command.command_id)
            return DispatchResult(command.operation_id, command.command_id, False, True)

        self._apply(group, execution, command)
        self._record_command(command)
        operation = _OperationState(
            operation_id=command.operation_id,
            action=command.action,
            group_id=command.group_id,
            actor_id=command.actor_id,
            subject_id=command.subject_id,
            command_ids={command.command_id},
        )
        self._operations[command.operation_id] = operation
        business_key = self._business_key(command)
        self._business_mutations[business_key] = self._business_mutations.get(business_key, 0) + 1
        return DispatchResult(command.operation_id, command.command_id, True, False)

    def deliver_risk(self, event: RiskEvent) -> None:
        group = self._require_group(event.group_id)
        self._risk_events.append(event)
        if event.signal not in (RiskSignal.CHAT_SUSPENDED, RiskSignal.GROUP_BANNED):
            raise WorldStateError("unsupported risk signal")
        if group.status is GroupStatus.BANNED:
            return
        group.status = GroupStatus.BANNED
        group.invite_status = InviteStatus.QUARANTINED
        self._risk_transitions += 1
        execution = self._execution_for_group(group.group_id)
        if execution.status is ExecutionStatus.RUNNING:
            execution.status = ExecutionStatus.FAILED
            execution.failure_reason = "GROUP_BANNED"

    def complete_execution(self, execution_id: str) -> None:
        execution = self._require_execution(execution_id)
        if execution.status is not ExecutionStatus.RUNNING:
            raise ExecutionTerminatedError("execution is already terminal")
        if not execution.target_ids.issubset(execution.successful_target_ids):
            raise WorldStateError("cannot complete before every target succeeds")
        execution.status = ExecutionStatus.SUCCEEDED

    def group(self, group_id: str) -> GroupView:
        group = self._require_group(group_id)
        return GroupView(
            group_id=group.group_id,
            status=group.status,
            current_invite=group.current_invite,
            invite_status=group.invite_status,
            members=frozenset(group.members),
            admins=frozenset(group.admins),
            member_add_enabled=group.member_add_enabled,
            approval_required=group.approval_required,
        )

    def operation(self, operation_id: str) -> OperationLedgerView:
        operation = self._operations.get(operation_id)
        if operation is None:
            raise WorldStateError("operation does not exist")
        return OperationLedgerView(
            operation_id=operation.operation_id,
            action=operation.action,
            group_id=operation.group_id,
            actor_id=operation.actor_id,
            subject_id=operation.subject_id,
            dispatch_count=operation.dispatch_count,
            accept_count=operation.accept_count,
            mutation_count=operation.mutation_count,
            result_count=operation.result_count,
            result="SUCCESS",
            command_ids=frozenset(operation.command_ids),
        )

    def operations(self) -> Tuple[OperationLedgerView, ...]:
        return tuple(self.operation(operation_id) for operation_id in sorted(self._operations))

    def execution(self, execution_id: str) -> ExecutionView:
        execution = self._require_execution(execution_id)
        return ExecutionView(
            task_id=execution.task_id,
            execution_id=execution.execution_id,
            backend=execution.backend,
            group_id=execution.group_id,
            status=execution.status,
            failure_reason=execution.failure_reason,
            target_protocol_successes=len(execution.successful_target_ids),
        )

    def task(self, task_id: str) -> TaskView:
        task = self._tasks.get(task_id)
        if task is None:
            raise WorldStateError("task does not exist")
        executions = [self._executions[value] for value in task.execution_ids]
        processed = sum(value.status is not ExecutionStatus.RUNNING for value in executions)
        success = sum(value.status is ExecutionStatus.SUCCEEDED for value in executions)
        abnormal = sum(value.status is ExecutionStatus.FAILED for value in executions)
        completed = processed == len(executions)
        lifecycle = ParentLifecycle.COMPLETED if completed else ParentLifecycle.RUNNING
        if not completed:
            outcome = ParentOutcome.PENDING
        elif abnormal:
            outcome = ParentOutcome.FAILED
        else:
            outcome = ParentOutcome.SUCCESS
        return TaskView(task_id, lifecycle, outcome, processed, success, abnormal)

    @property
    def command_count(self) -> int:
        return self._command_count

    @property
    def unique_command_count(self) -> int:
        return len(self._command_ids)

    @property
    def total_mutation_count(self) -> int:
        return sum(value.mutation_count for value in self._operations.values())

    @property
    def duplicate_mutation_count(self) -> int:
        return sum(max(0, count - 1) for count in self._business_mutations.values())

    @property
    def risk_event_deliveries(self) -> int:
        return len(self._risk_events)

    @property
    def risk_transitions(self) -> int:
        return self._risk_transitions

    @property
    def commands_after_terminal(self) -> int:
        return self._commands_after_terminal

    def _apply(
        self,
        group: _GroupState,
        execution: _ExecutionState,
        command: ProtocolCommand,
    ) -> None:
        if command.action is Action.SAVE_CONTACT:
            self._contacts.add((command.actor_id, command.subject_id))
        elif command.action in (
            Action.JOIN_GROUP,
            Action.INVITE_MEMBER,
            Action.BATCH_ADD_MEMBER,
        ):
            group.members.add(command.subject_id)
            if command.subject_id in execution.target_ids:
                execution.successful_target_ids.add(command.subject_id)
        elif command.action is Action.PROMOTE_MEMBER:
            if command.subject_id not in group.members:
                raise WorldStateError("cannot promote a non-member")
            group.admins.add(command.subject_id)
        elif command.action is Action.ENABLE_MEMBER_ADD:
            group.member_add_enabled = True
        elif command.action is Action.DISABLE_JOIN_APPROVAL:
            group.approval_required = False
        else:
            raise WorldStateError("unsupported action")

    def _record_command(self, command: ProtocolCommand) -> None:
        self._command_count += 1
        self._command_ids.add(command.command_id)
        self._command_operations[command.command_id] = command.operation_id

    @staticmethod
    def _verify_same_operation(current: _OperationState, command: ProtocolCommand) -> None:
        fingerprint = (
            command.action,
            command.group_id,
            command.actor_id,
            command.subject_id,
        )
        if current.fingerprint != fingerprint:
            raise OperationConflict("operation id cannot change its business target")

    @staticmethod
    def _business_key(command: ProtocolCommand) -> Tuple[str, ...]:
        if command.action is Action.SAVE_CONTACT:
            return "CONTACT", command.actor_id, command.subject_id
        if command.action in (
            Action.JOIN_GROUP,
            Action.INVITE_MEMBER,
            Action.BATCH_ADD_MEMBER,
        ):
            return "ADD_MEMBER", command.group_id, command.subject_id
        if command.action is Action.PROMOTE_MEMBER:
            return "PROMOTE_MEMBER", command.group_id, command.subject_id
        return command.action.value, command.group_id

    def _require_group(self, group_id: str) -> _GroupState:
        group = self._groups.get(group_id)
        if group is None:
            raise WorldStateError("group does not exist")
        return group

    def _execution_for_group(self, group_id: str) -> _ExecutionState:
        execution_id = self._execution_by_group.get(group_id)
        if execution_id is None:
            raise WorldStateError("group has no execution")
        return self._executions[execution_id]

    def _require_execution(self, execution_id: str) -> _ExecutionState:
        execution = self._executions.get(execution_id)
        if execution is None:
            raise WorldStateError("execution does not exist")
        return execution
