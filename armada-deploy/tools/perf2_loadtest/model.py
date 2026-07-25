from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path, PurePosixPath
from typing import Dict, Optional


@dataclass(frozen=True)
class SSHProfile:
    host: str = field(repr=False)
    user: str
    key_path: Path = field(repr=False)
    remote_dir: PurePosixPath
    compose_file: str


@dataclass(frozen=True)
class Perf2Profile:
    env_id: str
    armada: SSHProfile
    zhuan: SSHProfile
    public_url: str = field(repr=False)
    topic: str
    group_id: str
    expected_partitions: int

    def log_safe(self) -> Dict[str, object]:
        return {
            "env": self.env_id,
            "topic": self.topic,
            "group": self.group_id,
            "partitions": self.expected_partitions,
        }


@dataclass(frozen=True)
class RunOptions:
    env: str
    tenant: str
    execute: bool
    expected_count: Optional[int]
    resume_concurrency: int
    baseline_seconds: int
    zero_window_seconds: int
    timeout_seconds: int
    min_free_gib: int


@dataclass(frozen=True)
class TaskSnapshot:
    id: int
    task_name: str
    status: int
    selected_account_count: int
    target_group_count: int
    target_pair_count: int
    send_interval_seconds: int
    task_start_at: Optional[int]
    task_end_at: Optional[int]


@dataclass(frozen=True)
class ResumeOutcome:
    task_id: int
    started_at: datetime
    finished_at: datetime
    result: str
    http_status: Optional[int]


@dataclass(frozen=True)
class ReconciledTask:
    task_id: int
    final_status: Optional[int]
    classification: str
