#!/usr/bin/env python3
"""本机隔离 nginx TLS 验证；只生成测试证书和无效哨兵，不连接实际后端。"""
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parent
IMAGE = "nginx:1.28-alpine"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


class DeviceIngestGatewayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        template = ROOT / "device-ingest/nginx.conf.template"
        if not template.is_file():
            raise AssertionError("device-ingest nginx template not implemented")
        cls.temp = tempfile.TemporaryDirectory(prefix="armada-ingest-test-")
        cls.addClassCleanup(cls.temp.cleanup)
        fixture = Path(cls.temp.name)
        cls.marker = secrets.token_urlsafe(32)
        cls.failure = secrets.token_urlsafe(32)
        cls.gateway_error = secrets.token_urlsafe(32)
        cls.upload = json.dumps({"accountGroupId": 11, "phone": "999000000001", "payload": json.dumps({"jid": "999000000001",
                                "clientStaticPrivateKey": "test-only-invalid-" + cls.marker})}, separators=(",", ":"))
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-subj", "/CN=ingest.test", "-addext", "subjectAltName=DNS:ingest.test,IP:127.0.0.1",
            "-keyout", str(fixture / "privkey.pem"), "-out", str(fixture / "fullchain.pem"))
        # 哨兵只存在临时测试文件；该上游不会导入账号，也不接入数据库或协议。
        (fixture / "upstream.conf").write_text("""
server {
    listen 8080;
    access_log off;
    error_log /dev/null crit;
    default_type application/json;
    location = /api/device-imports {
        mirror /read-test-body;
        mirror_request_body on;
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header X-Test-Body $request_body;
    }
    location = /read-test-body { internal; return 204; }
    location = /api/device-imports/groups { proxy_pass http://127.0.0.1:8081; }
    location = /api/device-imports/logout-confirmed { proxy_pass http://127.0.0.1:8081; }
    location / { return 500; }
}
server {
    listen 8081;
    access_log off;
    error_log /dev/null crit;
    default_type application/json;
    location = /api/device-imports/logout-confirmed {
        if ($http_authorization != "") { return 500; }
        if ($http_cookie != "") { return 500; }
        if ($http_x_tenant_code != "") { return 500; }
        if ($http_x_ingest_token != "MARKER") { return 401; }
        return 200 '{"batchId":123,"onlinePhase":"QUEUED"}';
    }
    location = /api/device-imports/groups {
        if ($http_authorization != "") { return 500; }
        if ($http_cookie != "") { return 500; }
        if ($http_x_tenant_code != "") { return 500; }
        if ($http_x_ingest_token != "MARKER") { return 401; }
        return 200 '[{"id":11,"name":"mobile-group"}]';
    }
    location = /api/device-imports {
        if ($http_authorization != "") { return 500; }
        if ($http_cookie != "") { return 500; }
        if ($http_x_tenant_code != "") { return 500; }
        if ($http_x_ingest_token = "FAILURE") { return 409 '{"message":"upstream conflict"}'; }
        if ($http_x_ingest_token = "GATEWAY_ERROR") { return 502; }
        if ($http_x_ingest_token != "MARKER") { return 401 '{"message":"token rejected"}'; }
        if ($http_x_test_body != 'EXPECTED_UPLOAD') { return 422 '{"message":"upload body was changed"}'; }
        return 200 '{"batchId":123,"onlinePhase":"WAITING_LOGOUT"}';
    }
    location / { return 500; }
}
""".replace("FAILURE", cls.failure).replace("GATEWAY_ERROR", cls.gateway_error).replace("MARKER", cls.marker)
            .replace("EXPECTED_UPLOAD", cls.upload.replace("\\", "\\\\").replace("'", "\\'")))
        cls.container = "armada-ingest-test-" + uuid.uuid4().hex[:12]
        cls.addClassCleanup(lambda: subprocess.run(["docker", "rm", "-f", cls.container], capture_output=True))
        run("docker", "run", "--rm", "-d", "--name", cls.container,
            "--add-host", "backend:127.0.0.1", "-p", "127.0.0.1::443",
            "--tmpfs", "/var/cache/nginx", "-e", "INGEST_HOSTNAME=ingest.test",
            "-e", "NGINX_ENVSUBST_FILTER=^INGEST_HOSTNAME$",
            "-v", f"{template}:/etc/nginx/templates/default.conf.template:ro",
            "-v", f"{fixture}:/etc/nginx/tls:ro",
            "-v", f"{fixture / 'upstream.conf'}:/etc/nginx/conf.d/upstream-test.conf:ro", IMAGE)
        cls.port = int(run("docker", "port", cls.container, "443/tcp").rsplit(":", 1)[1])
        cls.context = ssl.create_default_context(cafile=str(fixture / "fullchain.pem"))
        for _ in range(50):
            try:
                if cls.request("POST")[0] == 200:
                    break
                time.sleep(0.1)
            except (OSError, http.client.HTTPException):
                time.sleep(0.1)
        else:
            raise AssertionError("local TLS gateway did not become ready")
        run("docker", "exec", cls.container, "nginx", "-t")

    @classmethod
    def request(cls, method, path="/api/device-imports", body=None, headers=None):
        conn = http.client.HTTPSConnection("127.0.0.1", cls.port, context=cls.context, timeout=4)
        values = {"Host": "ingest.test", "Content-Type": "application/json", "X-Ingest-Token": cls.marker}
        values.update(headers or {})
        try:
            default_body = cls.upload.encode() if method == "POST" else b""
            conn.request(method, path, body=default_body if body is None else body, headers=values)
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_success_is_exact_json_and_does_not_forward_admin_identity(self):
        status, headers, body = self.request("POST", headers={"Authorization": self.marker,
                                                               "Cookie": self.marker, "X-Tenant-Code": self.marker})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"batchId": 123, "onlinePhase": "WAITING_LOGOUT"})
        self.assertIn("no-store", headers.get("Cache-Control", ""))

    def test_logout_confirmation_is_an_exact_authenticated_post(self):
        path = "/api/device-imports/logout-confirmed"
        status, headers, body = self.request("POST", path, body=b'{"batchId":123}', headers={
            "Authorization": self.marker, "Cookie": self.marker, "X-Tenant-Code": self.marker})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"batchId": 123, "onlinePhase": "QUEUED"})
        self.assertIn("no-store", headers.get("Cache-Control", ""))
        self.assertEqual(self.request("POST", path, headers={"X-Ingest-Token": ""})[0], 401)
        self.assertEqual(self.request("POST", path + "?batchId=123")[0], 400)
        for alias in [path + "/", "/api//device-imports/logout-confirmed", "/api/device-imports/%6cogout-confirmed"]:
            self.assertEqual(self.request("POST", alias)[0], 404)
        for method in ["GET", "PUT", "DELETE", "HEAD"]:
            status, headers, _ = self.request(method, path)
            self.assertEqual(status, 405)
            self.assertEqual(headers.get("Allow"), "POST, OPTIONS")
        self.assertEqual(self.request("OPTIONS", path)[0], 204)

    def test_group_list_is_authenticated_get_with_only_expected_fields(self):
        path = "/api/device-imports/groups"
        status, headers, body = self.request("GET", path, headers={"Authorization": self.marker,
                                             "Cookie": self.marker, "X-Tenant-Code": self.marker})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), [{"id": 11, "name": "mobile-group"}])
        self.assertIn("no-store", headers.get("Cache-Control", ""))
        self.assertEqual(self.request("GET", path, headers={"X-Ingest-Token": ""})[0], 401)
        self.assertEqual(self.request("GET", path + "?tenantId=8")[0], 400)
        for method in ["POST", "PUT", "DELETE", "HEAD"]:
            status, headers, _ = self.request(method, path)
            self.assertEqual(status, 405)
            self.assertEqual(headers.get("Allow"), "GET, OPTIONS")
        status, headers, body = self.request("OPTIONS", path)
        self.assertEqual(status, 204)
        self.assertEqual(body, b"")
        self.assertEqual(headers.get("Allow"), "GET, OPTIONS")
        self.assertFalse(any(key.lower().startswith("access-control-") for key in headers))

    def test_upstream_receives_exact_original_phone_and_payload_bytes(self):
        self.assertEqual(self.request("POST")[0], 200)
        self.assertEqual(self.request("POST", body=b"{}")[0], 422)

    def test_other_paths_and_normalized_aliases_never_reach_backend(self):
        for path in ["/", "/api/account-imports", "/api/public/login", "/actuator/health", "/index.html",
                     "/api/device-imports/", "/api/device-imports/export", "/api/%64evice-imports",
                     "/api/a/../device-imports", "//api/device-imports", "/api/device-imports/groups/",
                     "/api/device-imports/%67roups", "/api/device-imports/a/../groups"]:
            with self.subTest(path=path):
                status, _, body = self.request("POST", path)
                self.assertEqual(status, 404)
                self.assertEqual(set(json.loads(body)), {"message"})

    def test_methods_options_and_no_cors(self):
        for method in ["GET", "PUT", "PATCH", "DELETE"]:
            status, _, body = self.request(method)
            self.assertEqual(status, 405)
            self.assertEqual(set(json.loads(body)), {"message"})
        status, headers, body = self.request("OPTIONS", headers={"Origin": "https://untrusted.invalid"})
        self.assertEqual(status, 204)
        self.assertEqual(body, b"")
        self.assertEqual(headers.get("Allow"), "POST, OPTIONS")
        self.assertFalse(any(key.lower().startswith("access-control-") for key in headers))
        self.assertEqual(self.request("OPTIONS", "/other")[0], 404)

    def test_errors_size_query_and_unknown_host_are_safe_json(self):
        for path, payload, extra, expected in [
            ("/api/device-imports", b"{}", {"X-Ingest-Token": self.failure}, 409),
            ("/api/device-imports", b"{}", {"X-Ingest-Token": self.gateway_error}, 502),
            ("/api/device-imports", b"{}", {"X-Ingest-Token": "invalid"}, 401),
            ("/api/device-imports", b"x" * (128 * 1024 + 1), {}, 413),
            ("/api/device-imports?token=" + self.marker, b"{}", {}, 400),
            ("/api/device-imports", b"{}", {"Host": "other.invalid"}, 404),
        ]:
            status, _, body = self.request("POST", path, payload, extra)
            self.assertEqual(status, expected)
            self.assertEqual(set(json.loads(body)), {"message"})
            self.assertFalse(self.marker.encode() in body)

    def test_only_tls_port_is_published_and_plain_http_cannot_import(self):
        bindings = json.loads(run("docker", "inspect", "--format", "{{json .HostConfig.PortBindings}}", self.container))
        self.assertEqual(set(bindings), {"443/tcp"})
        self.assertEqual(bindings["443/tcp"][0]["HostIp"], "127.0.0.1")
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=4)
        try:
            conn.request("POST", "/api/device-imports", body=b"{}", headers={"Host": "ingest.test"})
            response = conn.getresponse()
            self.assertEqual(response.status, 400)
            self.assertEqual(set(json.loads(response.read())), {"message"})
        finally:
            conn.close()

    def test_logs_and_temporary_body_directory_do_not_contain_sentinel(self):
        self.request("POST", body=self.marker.encode())
        self.request("POST", "/bad?token=" + self.marker)
        logs = run("docker", "logs", self.container)
        self.assertFalse(self.marker in logs)
        self.assertFalse(self.failure in logs)
        files = run("docker", "exec", self.container, "find", "/var/cache/nginx", "-type", "f")
        self.assertEqual(files, "")


class DeviceIngestComposeTest(unittest.TestCase):
    def test_preflight_rejects_public_or_wildcard_admin_binding_without_echoing_values(self):
        spec = importlib.util.spec_from_file_location("ingest_preflight", ROOT / "device-ingest/preflight.py")
        preflight = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(preflight)
        with tempfile.TemporaryDirectory(prefix="armada-ingest-preflight-") as directory:
            for name in ["fullchain.pem", "privkey.pem"]:
                (Path(directory) / name).write_text("test-only-not-a-real-certificate")
            marker = secrets.token_urlsafe(32)
            env = {"ARMADA_DEVICE_INGEST_CLIENTS_JSON": marker, "INGEST_HOSTNAME": "ingest.test",
                   "INGEST_TLS_DIR": directory, "ARMADA_ADMIN_BIND_IP": "127.0.0.1"}
            self.assertEqual(preflight.validate(env), [])
            for address in ["0.0.0.0", "::", "203.0.113.9", "invalid"]:
                env["ARMADA_ADMIN_BIND_IP"] = address
                errors = preflight.validate(env)
                self.assertIn("ADMIN_BIND_MUST_BE_PRIVATE_IPV4_OR_LOOPBACK", errors)
                self.assertFalse(marker in str(errors))

    def test_overlay_preserves_private_admin_binding_and_required_secret(self):
        overlay = ROOT / "docker-compose.device-ingest.yml"
        self.assertTrue(overlay.is_file())
        with tempfile.TemporaryDirectory(prefix="armada-ingest-compose-") as temp:
            # 所有插值都用合成值；禁止加载仓库 .env 或输出 compose 展开的环境变量。
            template = (ROOT / "docker-compose.rds.yml").read_text() + overlay.read_text()
            variables = set(re.findall(r"\$\{([A-Z0-9_]+)", template))
            env = dict(os.environ)
            for variable in variables:
                env[variable] = "test-only"
            env.update({"ARMADA_HTTP_PORT": "18080", "ARMADA_ADMIN_BIND_IP": "127.0.0.1",
                        "ARMADA_PULL_TASK_AVATAR_HOST_DIR": str(Path(temp) / "avatars"),
                        "INGEST_HOSTNAME": "ingest.test", "INGEST_TLS_DIR": temp,
                        "ARMADA_DEVICE_INGEST_CLIENTS_JSON": "[]"})
            blank = Path(temp) / "empty.env"
            blank.write_text("")
            arguments = ["docker", "compose", "--env-file", str(blank), "-f", str(ROOT / "docker-compose.rds.yml"),
                         "-f", str(overlay), "config", "--format", "json"]
            model = json.loads(run(*arguments, env=env))
            ports = model["services"]["nginx"]["ports"]
            self.assertEqual(len(ports), 1)
            self.assertEqual(ports[0]["host_ip"], "127.0.0.1")
            self.assertNotIn("ports", model["services"]["backend"])
            self.assertEqual(model["services"]["device-ingest-nginx"]["ports"][0]["target"], 443)
            tls_mounts = [mount for mount in model["services"]["device-ingest-nginx"]["volumes"]
                          if mount["target"].startswith("/etc/nginx/tls")]
            self.assertEqual(len(tls_mounts), 1)
            self.assertEqual(tls_mounts[0]["target"], "/etc/nginx/tls")
            self.assertTrue(tls_mounts[0]["read_only"])
            # 日常 --be/--fe 发布只显式加载基础 Compose，也必须保留令牌和私网绑定。
            base_arguments = arguments[:6] + arguments[8:]
            base_model = json.loads(run(*base_arguments, env=env))
            self.assertEqual(base_model["services"]["nginx"]["ports"][0]["host_ip"], "127.0.0.1")
            self.assertEqual(base_model["services"]["backend"]["environment"].get(
                "ARMADA_DEVICE_INGEST_CLIENTS_JSON"), "[]")
            self.assertEqual(base_model["services"]["backend"]["environment"].get(
                "MYBATIS_PLUS_CONFIGURATION_LOG_IMPL"), "org.apache.ibatis.logging.nologging.NoLoggingImpl")
            del env["ARMADA_DEVICE_INGEST_CLIENTS_JSON"]
            result = subprocess.run(arguments, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
