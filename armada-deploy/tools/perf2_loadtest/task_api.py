from __future__ import annotations

import json
import re
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable, Dict, List, Mapping, Optional, Protocol, Sequence, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

from .model import ReconciledTask, ResumeOutcome, TaskSnapshot


_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
_TENANT_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")


class APIError(RuntimeError):
    """A stable API boundary failure that never includes response content."""


@dataclass(frozen=True)
class HTTPResponse:
    status: int
    body: bytes


class HTTPTransport(Protocol):
    def request(
        self, method: str, url: str, headers: Mapping[str, str], timeout: float
    ) -> HTTPResponse:
        ...


class UrllibTransport:
    def request(
        self, method: str, url: str, headers: Mapping[str, str], timeout: float
    ) -> HTTPResponse:
        data = b"" if method == "POST" else None
        request = Request(url=url, data=data, headers=dict(headers), method=method)
        try:
            with urlopen(request, timeout=timeout) as response:
                body = response.read(_MAX_RESPONSE_BYTES + 1)
                return HTTPResponse(status=response.status, body=body)
        except HTTPError as error:
            return HTTPResponse(status=error.code, body=error.read(_MAX_RESPONSE_BYTES + 1))
        except (URLError, OSError) as error:
            raise APIError("transport_unknown") from error


class TaskAPI:
    def __init__(
        self,
        base_url: str,
        tenant: str,
        *,
        transport: Optional[HTTPTransport] = None,
        timeout: float = 10.0,
        now: Optional[Callable[[], datetime]] = None,
    ) -> None:
        parsed = urlparse(base_url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise APIError("base_url")
        if not _TENANT_RE.fullmatch(tenant) or timeout <= 0:
            raise APIError("client_options")
        self._base_url = base_url.rstrip("/")
        self._tenant = tenant
        self._transport = transport or UrllibTransport()
        self._timeout = timeout
        self._now = now or (lambda: datetime.now(timezone.utc))

    def list_paused(self) -> Tuple[TaskSnapshot, ...]:
        url = self._url("/api/marketing-tasks", {"status": 5, "page": 1, "pageSize": 1000})
        payload = self._request_data("GET", url)
        if not isinstance(payload, dict):
            raise APIError("response_shape")
        rows = payload.get("list")
        total = _plain_int(payload.get("total"), "response_shape")
        total_pages = _plain_int(payload.get("totalPages"), "response_shape")
        if not isinstance(rows, list) or total < 0 or total_pages < 0:
            raise APIError("response_shape")
        if total > 1000 or total_pages > 1 or total != len(rows):
            raise APIError("inventory_truncated")
        snapshot = tuple(_paused_snapshot(row) for row in rows)
        self._validate_snapshot(snapshot)
        return snapshot

    def resume_snapshot_once(
        self, snapshot: Sequence[TaskSnapshot], concurrency: int
    ) -> Tuple[ResumeOutcome, ...]:
        frozen = tuple(snapshot)
        self._validate_snapshot(frozen)
        _validate_concurrency(concurrency)
        if not frozen:
            return ()

        def resume(task: TaskSnapshot) -> ResumeOutcome:
            started_at = self._utc_now()
            status: Optional[int] = None
            result = "transport_unknown"
            try:
                response = self._transport.request(
                    "POST",
                    self._url("/api/marketing-tasks/%d/resume" % task.id),
                    self._headers(),
                    self._timeout,
                )
                status = response.status
                if not 200 <= response.status < 300:
                    result = "http_error"
                else:
                    self._decode_success(response)
                    result = "success"
            except APIError as error:
                result = "transport_unknown" if str(error) == "transport_unknown" else "response_invalid"
            except (OSError, TimeoutError):
                result = "transport_unknown"
            return ResumeOutcome(
                task_id=task.id,
                started_at=started_at,
                finished_at=self._utc_now(),
                result=result,
                http_status=status,
            )

        with ThreadPoolExecutor(max_workers=min(concurrency, len(frozen))) as executor:
            return tuple(executor.map(resume, frozen))

    def reconcile(
        self, snapshot: Sequence[TaskSnapshot], concurrency: int
    ) -> Tuple[ReconciledTask, ...]:
        frozen = tuple(snapshot)
        self._validate_snapshot(frozen)
        _validate_concurrency(concurrency)
        if not frozen:
            return ()

        def read(task: TaskSnapshot) -> ReconciledTask:
            payload = self._request_data(
                "GET",
                self._url("/api/marketing-tasks", {"id": task.id, "page": 1, "pageSize": 1}),
            )
            if not isinstance(payload, dict) or not isinstance(payload.get("list"), list):
                raise APIError("response_shape")
            rows = payload["list"]
            total = _plain_int(payload.get("total"), "response_shape")
            if total == 0 and rows == []:
                return ReconciledTask(task_id=task.id, final_status=None, classification="missing")
            if total != 1 or len(rows) != 1 or not isinstance(rows[0], dict):
                raise APIError("response_shape")
            row_id = _positive_int(rows[0].get("id"), "response_shape")
            status = _plain_int(rows[0].get("status"), "response_shape")
            if row_id != task.id:
                raise APIError("response_shape")
            classification = "sending" if status == 2 else "paused" if status == 5 else "other"
            return ReconciledTask(task_id=task.id, final_status=status, classification=classification)

        with ThreadPoolExecutor(max_workers=min(concurrency, len(frozen))) as executor:
            return tuple(executor.map(read, frozen))

    def _request_data(self, method: str, url: str):
        try:
            response = self._transport.request(method, url, self._headers(), self._timeout)
        except APIError:
            raise
        except (OSError, TimeoutError) as error:
            raise APIError("transport_unknown") from error
        return self._decode_success(response)

    @staticmethod
    def _decode_success(response: HTTPResponse):
        if not 200 <= response.status < 300:
            raise APIError("http_status")
        if len(response.body) > _MAX_RESPONSE_BYTES:
            raise APIError("response_too_large")
        try:
            payload = json.loads(response.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise APIError("response_json") from error
        if not isinstance(payload, dict) or payload.get("code") != 0 or "data" not in payload:
            raise APIError("api_response")
        return payload["data"]

    def _headers(self) -> Mapping[str, str]:
        return {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Tenant-Code": self._tenant,
        }

    def _url(self, path: str, query: Optional[Mapping[str, object]] = None) -> str:
        url = self._base_url + path
        if query:
            url += "?" + urlencode(query)
        return url

    def _utc_now(self) -> datetime:
        value = self._now()
        if value.tzinfo is None:
            raise APIError("clock")
        return value.astimezone(timezone.utc)

    @staticmethod
    def _validate_snapshot(snapshot: Sequence[TaskSnapshot]) -> None:
        seen = set()
        for task in snapshot:
            if not isinstance(task, TaskSnapshot) or task.id <= 0 or task.id in seen or task.status != 5:
                raise APIError("snapshot_contract")
            seen.add(task.id)


def _paused_snapshot(row) -> TaskSnapshot:
    if not isinstance(row, dict):
        raise APIError("response_shape")
    task_id = _positive_int(row.get("id"), "response_shape")
    name = row.get("taskName")
    status = _plain_int(row.get("status"), "response_shape")
    if not isinstance(name, str) or not name.strip() or len(name) > 256 or status != 5:
        raise APIError("response_shape")
    return TaskSnapshot(
        id=task_id,
        task_name=name.strip(),
        status=status,
        selected_account_count=_non_negative_int(row.get("selectedAccountCount")),
        target_group_count=_non_negative_int(row.get("targetGroupCount")),
        target_pair_count=_non_negative_int(row.get("targetPairCount")),
        send_interval_seconds=_positive_int(row.get("sendIntervalSeconds"), "response_shape"),
        task_start_at=_optional_non_negative_int(row.get("taskStartAt")),
        task_end_at=_optional_non_negative_int(row.get("taskEndAt")),
    )


def _validate_concurrency(concurrency: int) -> None:
    if isinstance(concurrency, bool) or not isinstance(concurrency, int) or not 1 <= concurrency <= 32:
        raise APIError("concurrency")


def _plain_int(value, error_class: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise APIError(error_class)
    return value


def _positive_int(value, error_class: str) -> int:
    parsed = _plain_int(value, error_class)
    if parsed <= 0:
        raise APIError(error_class)
    return parsed


def _non_negative_int(value) -> int:
    parsed = _plain_int(value, "response_shape")
    if parsed < 0:
        raise APIError("response_shape")
    return parsed


def _optional_non_negative_int(value) -> Optional[int]:
    if value is None:
        return None
    return _non_negative_int(value)
