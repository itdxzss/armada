import importlib.util
import json
import os
import sys
import tempfile
import time
import unittest
from dataclasses import replace
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MODULE_PATH = ROOT / "runner-deep-check.py"


def load_module():
    spec = importlib.util.spec_from_file_location("runner_deep_check", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load runner deep check")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class RunnerDeepCheckTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.snapshot = self.root / "docker-inspect.jsonl"
        self.write_snapshot()
        self.config = replace(
            self.module.PRODUCTION_CONFIG,
            test_mode=True,
            inspect_file=self.snapshot,
        )
        self.responses = {
            self.config.frontend_url: self.response(
                200, "text/html", b'<!doctype html><html><body><div id="app"></div></body></html>'
            ),
            self.config.backend_url: self.response(
                401,
                "application/json",
                {"code": 40104, "msg": "authentication required", "data": None},
            ),
            self.config.web_ready_url: self.response(
                200, "application/json", {"ok": True}
            ),
            self.config.android_health_url: self.response(
                200,
                "application/json",
                {"success": True, "data": None, "error": ""},
            ),
            self.config.android_nodes_url: self.response(
                200,
                "application/json",
                {
                    "success": True,
                    "data": [
                        {"id": "node-01", "status": "online"},
                        {"id": "node-02", "status": "online"},
                        {"id": "node-03", "status": "online"},
                    ],
                    "error": "",
                },
            ),
        }
        self.calls = []

    def tearDown(self):
        self.temporary.cleanup()

    def response(self, status, content_type, body):
        if isinstance(body, dict):
            body = json.dumps(body, separators=(",", ":")).encode("utf-8")
        return self.module.HttpResponse(status, content_type, body)

    def write_snapshot(self, **overrides):
        generation = "a" * 32
        rows = []
        for name in ("armada-backend", "armada-nginx", "zhuan-native-probe-mysql"):
            values = overrides.get(name, {})
            rows.append(
                {
                    "name": f"/{name}",
                    "restartCount": values.get("restartCount", 0),
                    "oomKilled": values.get("oomKilled", False),
                    "status": values.get("status", "running"),
                    "startedAt": "2026-08-26T00:00:00Z",
                    "snapshotGeneration": generation,
                }
            )
        self.snapshot.write_text(
            "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows),
            encoding="utf-8",
        )

    def get(self, url, timeout_seconds):
        self.calls.append((url, timeout_seconds))
        return self.responses[url]

    def test_pass_checks_only_the_fixed_test1_targets(self):
        self.write_snapshot(**{"armada-backend": {"restartCount": 2}})
        self.module.run_checks(self.config, http_get=self.get)

        self.assertEqual(
            [
                self.config.frontend_url,
                self.config.backend_url,
                self.config.web_ready_url,
                self.config.android_health_url,
                self.config.android_nodes_url,
            ],
            [url for url, _ in self.calls],
        )
        self.assertTrue(all(timeout == 5 for _, timeout in self.calls))
        self.assertEqual("http://127.0.0.1/", self.module.PRODUCTION_CONFIG.frontend_url)
        self.assertEqual(
            "http://127.0.0.1/api/account-groups",
            self.module.PRODUCTION_CONFIG.backend_url,
        )
        self.assertEqual(
            "http://172.31.3.208:8080/readyz",
            self.module.PRODUCTION_CONFIG.web_ready_url,
        )
        self.assertEqual(
            "http://172.31.13.65:9100/healthz",
            self.module.PRODUCTION_CONFIG.android_health_url,
        )
        self.assertEqual(
            "http://172.31.13.65:9100/admin/nodes",
            self.module.PRODUCTION_CONFIG.android_nodes_url,
        )

    def test_unreachable_and_invalid_http_fail_closed(self):
        def unreachable(url, timeout_seconds):
            raise TimeoutError("contains a private diagnostic")

        with self.assertRaisesRegex(self.module.DeepCheckError, "FRONTEND_UNREACHABLE"):
            self.module.run_checks(self.config, http_get=unreachable)

        self.responses[self.config.backend_url] = self.response(
            401, "application/json", b"not-json internal-detail"
        )
        with self.assertRaisesRegex(self.module.DeepCheckError, "BACKEND_RESPONSE_INVALID"):
            self.module.run_checks(self.config, http_get=self.get)

        locked_config = replace(self.config, test_mode=False)
        with self.assertRaisesRegex(self.module.DeepCheckError, "CONFIG_INVALID"):
            self.module.run_checks(locked_config, http_get=self.get)

    def test_android_requires_three_online_nodes(self):
        self.responses[self.config.android_nodes_url] = self.response(
            200,
            "application/json",
            {
                "success": True,
                "data": [
                    {"id": "node-01", "status": "online"},
                    {"id": "node-02", "status": "offline"},
                    {"id": "node-03", "status": "online"},
                ],
                "error": "",
            },
        )

        with self.assertRaisesRegex(self.module.DeepCheckError, "ANDROID_NODES_UNHEALTHY"):
            self.module.run_checks(self.config, http_get=self.get)

    def test_stale_snapshot_fails_closed_before_http(self):
        stale_at = time.time() - self.config.max_snapshot_age_seconds - 1
        os.utime(self.snapshot, (stale_at, stale_at))

        with self.assertRaisesRegex(self.module.DeepCheckError, "DOCKER_SNAPSHOT_STALE"):
            self.module.run_checks(self.config, http_get=self.get)

        self.assertEqual([], self.calls)

    def test_container_invalid_restart_oom_or_non_running_state_fails(self):
        cases = (
            ("armada-backend", {"restartCount": -1}),
            ("armada-backend", {"oomKilled": True}),
            ("armada-nginx", {"status": "exited"}),
        )
        for name, values in cases:
            with self.subTest(name=name, values=values):
                self.write_snapshot(**{name: values})
                with self.assertRaisesRegex(
                    self.module.DeepCheckError, "CONTAINER_UNHEALTHY"
                ):
                    self.module.run_checks(self.config, http_get=self.get)


if __name__ == "__main__":
    unittest.main()
