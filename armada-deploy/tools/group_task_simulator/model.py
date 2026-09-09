from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import FrozenSet


class Action(str, Enum):
    SAVE_CONTACT = "SAVE_CONTACT"
    JOIN_GROUP = "JOIN_GROUP"
    PROMOTE_MEMBER = "PROMOTE_MEMBER"
    ENABLE_MEMBER_ADD = "ENABLE_MEMBER_ADD"
    DISABLE_JOIN_APPROVAL = "DISABLE_JOIN_APPROVAL"
    INVITE_MEMBER = "INVITE_MEMBER"
    BATCH_ADD_MEMBER = "BATCH_ADD_MEMBER"


class GroupStatus(str, Enum):
    ACTIVE = "ACTIVE"
    BANNED = "BANNED"


class InviteStatus(str, Enum):
    ACTIVE = "ACTIVE"
    QUARANTINED = "QUARANTINED"


class ExecutionStatus(str, Enum):
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class ParentLifecycle(str, Enum):
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"


class ParentOutcome(str, Enum):
    PENDING = "PENDING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"


class RiskSignal(str, Enum):
    CHAT_SUSPENDED = "CHAT_SUSPENDED"
    GROUP_BANNED = "GROUP_BANNED"


@dataclass(frozen=True)
class ProtocolCommand:
    operation_id: str
    command_id: str
    attempt_no: int
    action: Action
    group_id: str
    actor_id: str
    subject_id: str

    def __post_init__(self) -> None:
        for value in (
            self.operation_id,
            self.command_id,
            self.group_id,
            self.actor_id,
            self.subject_id,
        ):
            if not value or not value.strip():
                raise ValueError("protocol command identifiers must be non-empty")
        if self.attempt_no < 1:
            raise ValueError("attempt_no must be positive")


@dataclass(frozen=True)
class RiskEvent:
    source_event_id: str
    signal: RiskSignal
    group_id: str
    occurred_at: str
    delivered_at: str


@dataclass(frozen=True)
class DispatchResult:
    operation_id: str
    command_id: str
    applied: bool
    replayed: bool


@dataclass(frozen=True)
class GroupView:
    group_id: str
    status: GroupStatus
    current_invite: str
    invite_status: InviteStatus
    members: FrozenSet[str]
    admins: FrozenSet[str]
    member_add_enabled: bool
    approval_required: bool


@dataclass(frozen=True)
class OperationLedgerView:
    operation_id: str
    action: Action
    group_id: str
    actor_id: str
    subject_id: str
    dispatch_count: int
    accept_count: int
    mutation_count: int
    result_count: int
    result: str
    command_ids: FrozenSet[str]


@dataclass(frozen=True)
class ExecutionView:
    task_id: str
    execution_id: str
    backend: str
    group_id: str
    status: ExecutionStatus
    failure_reason: str
    target_protocol_successes: int


@dataclass(frozen=True)
class TaskView:
    task_id: str
    lifecycle: ParentLifecycle
    outcome: ParentOutcome
    processed: int
    success: int
    abnormal: int
