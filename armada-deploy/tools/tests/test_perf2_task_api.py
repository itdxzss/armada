import json
import threading
import unittest
from dataclasses import asdict
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlparse

from perf2_loadtest.model import TaskSnapshot
from perf2_loadtest.task_api import APIError, HTTPResponse, TaskAPI


def api_response(data, code=0):
    return HTTPResponse(
        status=200,
        body=json.dumps({"code": code, "message": "do not persist this", "data": data}).encode("utf-8"),
    )


def task_row(task_id, status=5):
    return {
        "id": task_id,
        "taskName": "task-%d" % task_id,
        "status": status,
        "selectedAccountCount": 2,
        "targetGroupCount": 3,
        "targetPairCount": 4,
        "sendIntervalSeconds": 600,
        "taskStartAt": 1000,
        "taskEndAt": 2000,
        "marketingTemplateContent": "secret-content",
        "marketingTemplateBodyText": "secret-body",
        "marketingTemplatePromotionLink": "secret-link",
    }


class RecordingTransport:
    def __init__(self, handler):
        self.handler = handler
        self.calls = []
        self.lock = threading.Lock()

    def request(self, method, url, headers, timeout):
        with self.lock:
            self.calls.append((method, url, dict(headers), timeout))
        return self.handler(method, url, headers, timeout)


class TaskInventoryTest(unittest.TestCase):
    def test_lists_paused_tasks_and_keeps_only_safe_snapshot_fields(self) -> None:
        rows = [task_row(2), task_row(1)]
        transport = RecordingTransport(
            lambda *_: api_response(
                {"list": rows, "page": 1, "pageSize": 1000, "total": 2, "totalPages": 1}
            )
        )
        api = TaskAPI("http://armada.example", "demo", transport=transport)

        snapshot = api.list_paused()

        self.assertEqual((2, 1), tuple(task.id for task in snapshot))
        method, url, headers, timeout = transport.calls[0]
        self.assertEqual("GET", method)
        self.assertEqual(
            {"status": ["5"], "page": ["1"], "pageSize": ["1000"]},
            parse_qs(urlparse(url).query),
        )
        self.assertEqual("demo", headers["X-Tenant-Code"])
        self.assertEqual(10.0, timeout)
        serialized = json.dumps([asdict(task) for task in snapshot])
        for secret in ("secret-content", "secret-body", "secret-link", "marketingTemplate"):
            self.assertNotIn(secret, serialized)

    def test_rejects_truncated_or_invalid_inventory(self) -> None:
        invalid_payloads = (
            {"list": [task_row(1)], "page": 1, "pageSize": 1000, "total": 1001, "totalPages": 2},
            {"list": [task_row(1)], "page": 1, "pageSize": 1000, "total": 2, "totalPages": 1},
            {"list": [task_row(1), task_row(1)], "page": 1, "pageSize": 1000, "total": 2, "totalPages": 1},
            {"list": [task_row(-1)], "page": 1, "pageSize": 1000, "total": 1, "totalPages": 1},
            {"list": [{**task_row(1), "id": "1"}], "page": 1, "pageSize": 1000, "total": 1, "totalPages": 1},
        )
        for payload in invalid_payloads:
            with self.subTest(payload=payload):
                api = TaskAPI(
                    "http://armada.example",
                    "demo",
                    transport=RecordingTransport(lambda *_: api_response(payload)),
                )
                with self.assertRaises(APIError):
                    api.list_paused()

    def test_rejects_http_api_json_shape_and_oversized_failures(self) -> None:
        responses = (
            HTTPResponse(500, b"private backend response"),
            HTTPResponse(200, b"not-json"),
            api_response(None, code=70001),
            HTTPResponse(200, b"{" + b"x" * (4 * 1024 * 1024)),
        )
        for response in responses:
            with self.subTest(status=response.status):
                api = TaskAPI(
                    "http://armada.example",
                    "demo",
                    transport=RecordingTransport(lambda *_, value=response: value),
                )
                with self.assertRaises(APIError) as raised:
                    api.list_paused()
                self.assertNotIn("private backend response", str(raised.exception))


class ResumeAndReconcileTest(unittest.TestCase):
    def test_resumes_every_frozen_id_once_concurrently_and_preserves_order(self) -> None:
        barrier = threading.Barrier(3)

        def handler(method, url, _headers, _timeout):
            self.assertEqual("POST", method)
            barrier.wait(timeout=1)
            task_id = int(url.rstrip("/").split("/")[-2])
            return api_response(task_row(task_id, status=2))

        transport = RecordingTransport(handler)
        api = TaskAPI(
            "http://armada.example", "demo", transport=transport, now=lambda: datetime.now(timezone.utc)
        )
        snapshot = tuple(self._snapshot(task_id) for task_id in (3, 1, 2))

        outcomes = api.resume_snapshot_once(snapshot, concurrency=3)

        self.assertEqual((3, 1, 2), tuple(outcome.task_id for outcome in outcomes))
        self.assertTrue(all(outcome.result == "success" for outcome in outcomes))
        urls = [call[1] for call in transport.calls]
        self.assertEqual(3, len(urls))
        for task_id in (1, 2, 3):
            self.assertEqual(1, sum("/%d/resume" % task_id in url for url in urls))

    def test_transport_unknown_is_not_retried(self) -> None:
        def handler(_method, url, _headers, _timeout):
            if "/2/resume" in url:
                raise TimeoutError("response may have been lost")
            return api_response(task_row(1, status=2))

        transport = RecordingTransport(handler)
        api = TaskAPI("http://armada.example", "demo", transport=transport)
        snapshot = tuple(self._snapshot(task_id) for task_id in (1, 2))

        outcomes = api.resume_snapshot_once(snapshot, concurrency=2)

        self.assertEqual(("success", "transport_unknown"), tuple(item.result for item in outcomes))
        self.assertEqual(2, len(transport.calls))

    def test_reconciles_each_frozen_id(self) -> None:
        statuses = {1: 2, 2: 5, 3: 9}

        def handler(method, url, _headers, _timeout):
            self.assertEqual("GET", method)
            task_id = int(parse_qs(urlparse(url).query)["id"][0])
            if task_id == 4:
                rows = []
            else:
                rows = [task_row(task_id, status=statuses[task_id])]
            return api_response(
                {"list": rows, "page": 1, "pageSize": 1, "total": len(rows), "totalPages": len(rows)}
            )

        api = TaskAPI("http://armada.example", "demo", transport=RecordingTransport(handler))
        snapshot = tuple(self._snapshot(task_id) for task_id in (1, 2, 3, 4))

        reconciled = api.reconcile(snapshot, concurrency=4)

        self.assertEqual(
            ("sending", "paused", "other", "missing"),
            tuple(item.classification for item in reconciled),
        )
        self.assertEqual((2, 5, 9, None), tuple(item.final_status for item in reconciled))

    @staticmethod
    def _snapshot(task_id):
        return TaskSnapshot(
            id=task_id,
            task_name="task-%d" % task_id,
            status=5,
            selected_account_count=2,
            target_group_count=3,
            target_pair_count=4,
            send_interval_seconds=600,
            task_start_at=1000,
            task_end_at=2000,
        )


if __name__ == "__main__":
    unittest.main()
