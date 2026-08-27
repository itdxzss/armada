#!/usr/bin/env python3
"""Run the fixed, read-only test1 deep checks from the staging Runner host."""

from __future__ import annotations

import json
import re
import stat
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


HTTP_TIMEOUT_SECONDS = 5
MAX_HTTP_BODY_BYTES = 1024 * 1024
MAX_SNAPSHOT_BYTES = 128 * 1024
MAX_SNAPSHOT_AGE_SECONDS = 90
EXPECTED_ONLINE_NODES = 3
SNAPSHOT_GENERATION = re.compile(r"^[0-9a-f]{32}$")
REQUIRED_CONTAINERS = ("armada-backend", "armada-nginx")
BACKEND_CODES = {0, 40001, 40101, 40104}


class DeepCheckError(RuntimeError):
    """A fixed, non-sensitive failure reason."""


@dataclass(frozen=True)
class HttpResponse:
    status: int
    content_type: str
    body: bytes


@dataclass(frozen=True)
class Config:
    frontend_url: str
    backend_url: str
    web_ready_url: str
    android_health_url: str
    android_nodes_url: str
    inspect_file: Path
    timeout_seconds: int
    max_snapshot_age_seconds: int
    test_mode: bool = False


PRODUCTION_CONFIG = Config(
    frontend_url="http://127.0.0.1/",
    backend_url="http://127.0.0.1/api/account-groups",
    web_ready_url="http://172.31.3.208:8080/readyz",
    android_health_url="http://172.31.13.65:9100/healthz",
    android_nodes_url="http://172.31.13.65:9100/admin/nodes",
    inspect_file=Path("/run/staging-accept/docker-inspect.jsonl"),
    timeout_seconds=HTTP_TIMEOUT_SECONDS,
    max_snapshot_age_seconds=MAX_SNAPSHOT_AGE_SECONDS,
)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
HttpGet = Callable[[str, int], HttpResponse]


def _read_http_response(response) -> HttpResponse:
    body = response.read(MAX_HTTP_BODY_BYTES + 1)
    if len(body) > MAX_HTTP_BODY_BYTES or b"\x00" in body:
        raise ValueError("invalid response body")
    content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
    return HttpResponse(int(response.status), content_type, body)


def _http_get(url: str, timeout_seconds: int) -> HttpResponse:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json,text/html;q=0.9",
            "User-Agent": "staging-accept-deep-check/1",
        },
        method="GET",
    )
    try:
        with _OPENER.open(request, timeout=timeout_seconds) as response:
            return _read_http_response(response)
    except urllib.error.HTTPError as error:
        with error:
            return _read_http_response(error)


def _fetch(label: str, url: str, timeout_seconds: int, http_get: HttpGet) -> HttpResponse:
    try:
        response = http_get(url, timeout_seconds)
    except ValueError as error:
        raise DeepCheckError(f"{label}_RESPONSE_INVALID") from error
    except (OSError, TimeoutError, urllib.error.URLError) as error:
        raise DeepCheckError(f"{label}_UNREACHABLE") from error
    if (
        not isinstance(response, HttpResponse)
        or isinstance(response.status, bool)
        or not isinstance(response.status, int)
        or not isinstance(response.content_type, str)
        or not isinstance(response.body, bytes)
        or not response.body
        or len(response.body) > MAX_HTTP_BODY_BYTES
        or b"\x00" in response.body
    ):
        raise DeepCheckError(f"{label}_RESPONSE_INVALID")
    return response


def _json_object(label: str, response: HttpResponse) -> dict:
    if response.content_type != "application/json" and not response.content_type.endswith("+json"):
        raise DeepCheckError(f"{label}_RESPONSE_INVALID")
    try:
        payload = json.loads(response.body.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise DeepCheckError(f"{label}_RESPONSE_INVALID") from error
    if not isinstance(payload, dict):
        raise DeepCheckError(f"{label}_RESPONSE_INVALID")
    return payload


def _check_snapshot(config: Config) -> None:
    path = config.inspect_file
    try:
        before = path.lstat()
    except OSError as error:
        raise DeepCheckError("DOCKER_SNAPSHOT_UNAVAILABLE") from error
    age_seconds = time.time() - before.st_mtime
    if age_seconds < -5 or age_seconds > config.max_snapshot_age_seconds:
        raise DeepCheckError("DOCKER_SNAPSHOT_STALE")
    if (
        not path.is_absolute()
        or stat.S_ISLNK(before.st_mode)
        or not stat.S_ISREG(before.st_mode)
        or before.st_size <= 0
        or before.st_size > MAX_SNAPSHOT_BYTES
    ):
        raise DeepCheckError("DOCKER_SNAPSHOT_INVALID")
    try:
        content = path.read_bytes()
        after = path.stat()
        rows = [json.loads(line) for line in content.decode("utf-8").splitlines() if line]
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise DeepCheckError("DOCKER_SNAPSHOT_INVALID") from error
    before_identity = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    after_identity = (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
    if before_identity != after_identity or not rows or any(not isinstance(row, dict) for row in rows):
        raise DeepCheckError("DOCKER_SNAPSHOT_INVALID")

    generations = [row.get("snapshotGeneration") for row in rows]
    if (
        any(
            not isinstance(generation, str)
            or SNAPSHOT_GENERATION.fullmatch(generation) is None
            for generation in generations
        )
        or len(set(generations)) != 1
    ):
        raise DeepCheckError("DOCKER_SNAPSHOT_INVALID")
    by_name = {}
    for row in rows:
        raw_name = row.get("name")
        name = raw_name.removeprefix("/") if isinstance(raw_name, str) else ""
        if not name or name in by_name:
            raise DeepCheckError("DOCKER_SNAPSHOT_INVALID")
        by_name[name] = row
    if any(name not in by_name for name in REQUIRED_CONTAINERS):
        raise DeepCheckError("DOCKER_SNAPSHOT_INVALID")
    for name in REQUIRED_CONTAINERS:
        row = by_name[name]
        restart_count = row.get("restartCount")
        if (
            row.get("status") != "running"
            or isinstance(restart_count, bool)
            or not isinstance(restart_count, int)
            or restart_count < 0
            or row.get("oomKilled") is not False
        ):
            raise DeepCheckError("CONTAINER_UNHEALTHY")


def _check_frontend(response: HttpResponse) -> None:
    if response.status != 200 or response.content_type != "text/html":
        raise DeepCheckError("FRONTEND_UNHEALTHY")
    try:
        body = response.body.decode("utf-8").lower()
    except UnicodeError as error:
        raise DeepCheckError("FRONTEND_RESPONSE_INVALID") from error
    if "<html" not in body or not re.search(r"<div\s+[^>]*id=[\"']app[\"']", body):
        raise DeepCheckError("FRONTEND_RESPONSE_INVALID")


def _check_backend(response: HttpResponse) -> None:
    if response.status not in (200, 400, 401, 403):
        raise DeepCheckError("BACKEND_UNHEALTHY")
    payload = _json_object("BACKEND", response)
    code = payload.get("code")
    message = payload.get("message", payload.get("msg"))
    if (
        isinstance(code, bool)
        or not isinstance(code, int)
        or code not in BACKEND_CODES
        or not isinstance(message, str)
        or not message
    ):
        raise DeepCheckError("BACKEND_RESPONSE_INVALID")


def _check_web(response: HttpResponse) -> None:
    payload = _json_object("WEB", response)
    if response.status != 200 or payload.get("ok") is not True:
        raise DeepCheckError("WEB_UNHEALTHY")


def _check_android_health(response: HttpResponse) -> None:
    payload = _json_object("ANDROID_HEALTH", response)
    if (
        response.status != 200
        or payload.get("success") is not True
        or payload.get("error") not in (None, "")
    ):
        raise DeepCheckError("ANDROID_HEALTH_UNHEALTHY")


def _check_android_nodes(response: HttpResponse) -> None:
    payload = _json_object("ANDROID_NODES", response)
    nodes = payload.get("data")
    if (
        response.status != 200
        or payload.get("success") is not True
        or payload.get("error") not in (None, "")
        or not isinstance(nodes, list)
        or any(not isinstance(node, dict) for node in nodes)
    ):
        raise DeepCheckError("ANDROID_NODES_UNHEALTHY")
    online = [node for node in nodes if node.get("status") == "online"]
    online_ids = [node.get("id") for node in online]
    if (
        len(online) != EXPECTED_ONLINE_NODES
        or any(not isinstance(node_id, str) or not node_id for node_id in online_ids)
        or len(set(online_ids)) != EXPECTED_ONLINE_NODES
    ):
        raise DeepCheckError("ANDROID_NODES_UNHEALTHY")


def run_checks(config: Config = PRODUCTION_CONFIG, *, http_get: HttpGet | None = None) -> None:
    getter = _http_get if http_get is None else http_get
    if not config.test_mode and (config != PRODUCTION_CONFIG or getter is not _http_get):
        raise DeepCheckError("CONFIG_INVALID")
    _check_snapshot(config)
    _check_frontend(_fetch("FRONTEND", config.frontend_url, config.timeout_seconds, getter))
    _check_backend(_fetch("BACKEND", config.backend_url, config.timeout_seconds, getter))
    _check_web(_fetch("WEB", config.web_ready_url, config.timeout_seconds, getter))
    _check_android_health(
        _fetch("ANDROID_HEALTH", config.android_health_url, config.timeout_seconds, getter)
    )
    _check_android_nodes(
        _fetch("ANDROID_NODES", config.android_nodes_url, config.timeout_seconds, getter)
    )


def main(argv: list[str] | None = None) -> int:
    arguments = sys.argv[1:] if argv is None else argv
    if arguments:
        print("runner-deep-check: invalid invocation", file=sys.stderr)
        return 2
    try:
        run_checks()
    except DeepCheckError as error:
        print(f"runner-deep-check: FAIL reason={error}", file=sys.stderr)
        return 1
    except Exception:
        print("runner-deep-check: FAIL reason=INTERNAL_ERROR", file=sys.stderr)
        return 1
    print("runner-deep-check: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
