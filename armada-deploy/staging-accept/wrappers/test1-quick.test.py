#!/usr/bin/env python3
"""Fixture contract tests for the deterministic test1 quick wrapper."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock


WRAPPER_DIR = Path(__file__).resolve().parent
WRAPPER_PATH = WRAPPER_DIR / "test1-quick.py"
PLAN_PATH = WRAPPER_DIR.parent / "plans" / "test1-quick.json"
PREFLIGHT_PATH = WRAPPER_DIR.parent / "scripts" / "preflight.sh"
spec = importlib.util.spec_from_file_location("test1_quick", WRAPPER_PATH)
assert spec and spec.loader
quick = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = quick
spec.loader.exec_module(quick)


RUN_ID = "20260825T123456Z-abcdef12"
BUILDS = {
    "backend": "1" * 40,
    "frontend": "2" * 40,
    "webProtocol": "3" * 40,
    "androidProtocol": "4" * 40,
}


class QuickWrapperTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.run_root = self.root / "runs"
        self.run_root.mkdir()
        self.run_dir = self.run_root / RUN_ID
        self.run_dir.mkdir(mode=0o700)
        self.entrypoint = self.root / "test1-quick"
        self.entrypoint.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        self.entrypoint.chmod(0o755)
        self.deep_client = self._executable("deep-check", "#!/bin/sh\nexit 0\n")
        self.runtime_client = self._executable("runtime-observer", "#!/bin/sh\nexit 0\n")
        self.ui_wrapper = self._executable("ui-smoke", "#!/bin/sh\nexit 0\n")
        self.ui_credentials = self.root / "ui-smoke.env"
        self.ui_credentials.write_text("fixture alias only\n", encoding="utf-8")
        self.web_client = self.root / "web-observer"
        self.backend_client = self.root / "backend-observer"
        self.evaluator = self.root / "evaluate.py"
        self.call_log = self.root / "calls.ndjson"
        self.config = quick.Config(
            run_root=self.run_root,
            entrypoint=self.entrypoint,
            deep_check_client=self.deep_client,
            runtime_observer_client=self.runtime_client,
            preflight_script=PREFLIGHT_PATH,
            ui_wrapper=self.ui_wrapper,
            ui_credentials=self.ui_credentials,
            web_observer_client=self.web_client,
            backend_observer_client=self.backend_client,
            python=Path(sys.executable).resolve(),
            evaluator_script=self.evaluator,
            wait_seconds=0,
            profile_seconds=1,
        )
        self._write_plan()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _executable(self, name: str, body: str) -> Path:
        path = self.root / name
        path.write_text(body, encoding="utf-8")
        path.chmod(0o755)
        return path

    def _write_plan(self) -> None:
        plan = {
            "schemaVersion": 1,
            "profile": "test1-quick",
            "environment": "test1",
            "safety": "read-only",
            "builds": BUILDS,
            "stages": [
                {
                    "id": stage,
                    "command": [str(self.entrypoint)],
                    "timeoutSeconds": timeout,
                }
                for stage, timeout in quick.QUICK_STAGES
            ],
        }
        (self.run_dir / "plan.json").write_text(
            json.dumps(plan, indent=2) + "\n", encoding="utf-8"
        )

    def _run(self, stage: str) -> tuple[int, str]:
        environment = {
            "STAGING_ACCEPT_RUN_ID": RUN_ID,
            "STAGING_ACCEPT_STAGE_ID": stage,
            "STAGING_ACCEPT_RUN_DIR": str(self.run_dir),
            "TEST1_QUICK_CALL_LOG": str(self.call_log),
        }
        stdout = StringIO()
        stderr = StringIO()
        with mock.patch.dict(os.environ, environment, clear=False):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                status = quick.Controller(self.config).run()
        return status, stdout.getvalue() + stderr.getvalue()

    def _bind(self) -> str:
        status, output = self._run("candidate-bind")
        self.assertEqual(0, status, output)
        data = (self.run_dir / "candidate-manifest.json").read_bytes()
        return "sha256:" + hashlib.sha256(data).hexdigest()

    def _install_observer_fixtures(self) -> None:
        common = textwrap.dedent(
            """
            import hashlib, json, os
            from pathlib import Path

            run_id = os.environ['STAGING_ACCEPT_RUN_ID']
            stage = os.environ['STAGING_ACCEPT_STAGE_ID']
            phase = stage.removeprefix('observe-')
            run_dir = Path(os.environ['STAGING_ACCEPT_RUN_DIR'])
            candidate = 'sha256:' + hashlib.sha256((run_dir / 'candidate-manifest.json').read_bytes()).hexdigest()
            observability = run_dir / 'observability'

            def snapshot(collector, source=''):
                value = {
                    'schemaVersion': 1,
                    'collector': collector,
                    'environment': 'test1',
                    'phase': phase,
                    'runId': run_id,
                    'candidateManifestSha256': candidate,
                    'provenance': 'live',
                    'status': 'COLLECTED',
                    'health': {'ok': True, 'checks': [], 'blockedReasons': []},
                    'raw': {},
                }
                if source:
                    value['source'] = source
                return value

            def write(path, value):
                path.write_text(json.dumps(value, separators=(',', ':')) + '\\n', encoding='utf-8')
            """
        )
        web = "#!/usr/bin/env python3\n" + common + textwrap.dedent(
            """
            import sys
            arguments = sys.argv[1:]
            action = arguments[arguments.index('--action') + 1]
            collector = {'kafka': 'kafka', 'redis': 'redis', 'host': 'host-resource', 'web-traffic': 'web-traffic'}[action]
            write(observability / f'{action}-{phase}.json', snapshot(collector, 'web' if action == 'host' else ''))
            with Path(os.environ['TEST1_QUICK_CALL_LOG']).open('a', encoding='utf-8') as handle:
                handle.write(json.dumps({'client': 'web', 'arguments': arguments}) + '\\n')
            """
        )
        backend = "#!/usr/bin/env python3\n" + common + textwrap.dedent(
            """
            write(observability / f'host-backend-{phase}.json', snapshot('host-resource', 'backend'))
            with Path(os.environ['TEST1_QUICK_CALL_LOG']).open('a', encoding='utf-8') as handle:
                handle.write(json.dumps({'client': 'backend', 'arguments': []}) + '\\n')
            """
        )
        self._executable(self.web_client.name, web)
        self._executable(self.backend_client.name, backend)
        self.evaluator.write_text(
            textwrap.dedent(
                """
                import json, os, sys
                with open(os.environ['TEST1_QUICK_CALL_LOG'], 'a', encoding='utf-8') as handle:
                    handle.write(json.dumps({'client': 'evaluator', 'arguments': sys.argv[1:]}) + '\\n')
                print(json.dumps({'schemaVersion': 1, 'evaluator': 'observability', 'environment': 'test1', 'status': 'PASS', 'failureReasons': [], 'blockedReasons': [], 'metrics': {}}))
                """
            ),
            encoding="utf-8",
        )

    def _install_runtime_success_fixture(self) -> None:
        self.runtime_client.write_text(
            "#!/usr/bin/env python3\n"
            "import os\n"
            "from pathlib import Path\n"
            "Path(os.environ['STAGING_ACCEPT_RUN_DIR'], 'runtime-manifest.json').write_text('{}\\n')\n",
            encoding="utf-8",
        )
        self.runtime_client.chmod(0o755)
        preflight = self._executable("preflight", "#!/bin/sh\nexit 0\n")
        self.config = quick.Config(**{**self.config.__dict__, "preflight_script": preflight})

    def _run_complete_fixture(self, *, missing_deep: bool = False, failing_ui: bool = False) -> str:
        candidate_hash = self._bind()
        self._install_observer_fixtures()
        self._install_runtime_success_fixture()
        if missing_deep:
            self.config = quick.Config(
                **{**self.config.__dict__, "deep_check_client": self.root / "missing-deep"}
            )
        if failing_ui:
            self.ui_wrapper.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            self.ui_wrapper.chmod(0o755)
        for stage, _ in quick.QUICK_STAGES:
            if stage in ("candidate-bind", "evaluate-quick"):
                continue
            status, output = self._run(stage)
            self.assertEqual(0, status, f"stage={stage}\n{output}")
        return candidate_hash

    def _evaluator_arguments(self) -> list[str]:
        self._run_complete_fixture()
        status, output = self._run("evaluate-quick")
        self.assertEqual(0, status, output)
        calls = [json.loads(line) for line in self.call_log.read_text().splitlines()]
        return next(row for row in calls if row["client"] == "evaluator")["arguments"]

    def test_is_executable_accepts_absolute_regular_executable(self) -> None:
        executable = self._executable("direct-executable", "#!/bin/sh\nexit 0\n")
        self.assertTrue(quick.Controller._is_executable(executable))

    def test_is_executable_accepts_absolute_symlink_to_regular_executable(self) -> None:
        executable = self._executable("symlink-target", "#!/bin/sh\nexit 0\n")
        symlink = self.root / "symlink-executable"
        symlink.symlink_to(executable.name)
        self.assertTrue(quick.Controller._is_executable(symlink))

    def test_is_executable_rejects_relative_path(self) -> None:
        self.assertFalse(quick.Controller._is_executable(Path("relative-executable")))

    def test_is_executable_rejects_dangling_symlink(self) -> None:
        symlink = self.root / "dangling-executable"
        symlink.symlink_to("missing-target")
        self.assertFalse(quick.Controller._is_executable(symlink))

    def test_is_executable_rejects_directory(self) -> None:
        directory = self.root / "executable-directory"
        directory.mkdir(mode=0o755)
        self.assertFalse(quick.Controller._is_executable(directory))

    def test_is_executable_rejects_symlink_to_directory(self) -> None:
        directory = self.root / "target-directory"
        directory.mkdir(mode=0o755)
        symlink = self.root / "directory-symlink"
        symlink.symlink_to(directory.name)
        self.assertFalse(quick.Controller._is_executable(symlink))

    def test_is_executable_rejects_non_executable_regular_file(self) -> None:
        file = self.root / "non-executable"
        file.write_text("fixture\n", encoding="utf-8")
        file.chmod(0o644)
        self.assertFalse(quick.Controller._is_executable(file))

    def test_is_executable_rejects_symlink_to_non_executable_regular_file(self) -> None:
        file = self.root / "non-executable-target"
        file.write_text("fixture\n", encoding="utf-8")
        file.chmod(0o644)
        symlink = self.root / "non-executable-symlink"
        symlink.symlink_to(file.name)
        self.assertFalse(quick.Controller._is_executable(symlink))

    def test_evaluator_expected_kafka_pairs_are_topic_to_group(self) -> None:
        arguments = self._evaluator_arguments()
        pairs = [
            arguments[index + 1]
            for index, value in enumerate(arguments)
            if value == "--expected-kafka-pair"
        ]
        self.assertEqual(
            [f"{topic}={group}" for group, topic in quick.KAFKA_PAIRS],
            pairs,
        )

    def test_evaluator_expected_redis_nodes_are_master_1_for_all_sources(self) -> None:
        arguments = self._evaluator_arguments()
        nodes = [
            arguments[index + 1]
            for index, value in enumerate(arguments)
            if value == "--expected-redis-node"
        ]
        self.assertEqual(
            [f"{source}=master-1" for source in quick.REDIS_SOURCES],
            nodes,
        )

    def test_evaluator_expected_backend_containers_are_exact(self) -> None:
        arguments = self._evaluator_arguments()
        containers = [
            arguments[index + 1]
            for index, value in enumerate(arguments)
            if value == "--expected-host-container"
        ]
        self.assertEqual(
            [
                "backend=armada-backend",
                "backend=armada-nginx",
                "backend=zhuan-native-probe-mysql",
                "backend=zhuan-coordinator",
            ],
            containers,
        )

    def test_plan_is_fixed_read_only_and_contains_no_active_or_credential_path(self) -> None:
        plan = json.loads(PLAN_PATH.read_text(encoding="utf-8"))
        self.assertEqual("test1-quick", plan["profile"])
        self.assertEqual("read-only", plan["safety"])
        self.assertEqual(
            list(quick.QUICK_STAGES),
            [(row["id"], row["timeoutSeconds"]) for row in plan["stages"]],
        )
        self.assertTrue(all(row["command"] == ["/usr/local/libexec/staging-accept/test1-quick"] for row in plan["stages"]))
        serialized = json.dumps(plan).lower()
        for forbidden in ("canary", "soak", ".pem", "private key", "password", "token", "secret", "ssh"):
            self.assertNotIn(forbidden, serialized)

    def test_candidate_is_bound_to_plan_and_tampering_blocks_before_client(self) -> None:
        candidate_hash = self._bind()
        self.assertRegex(candidate_hash, quick.MANIFEST_SHA256)
        candidate_path = self.run_dir / "candidate-manifest.json"
        candidate_path.write_bytes(candidate_path.read_bytes() + b" ")
        marker = self.root / "deep-ran"
        self.deep_client.write_text(f"#!/bin/sh\ntouch '{marker}'\n", encoding="utf-8")
        self.deep_client.chmod(0o755)
        status, output = self._run("deep-check")
        self.assertEqual(quick.EXIT_BLOCKED, status, output)
        self.assertIn("CANDIDATE_BINDING_MISMATCH", output)
        self.assertFalse(marker.exists())
        result = json.loads((self.run_dir / "results" / "deep-check.json").read_text())
        self.assertEqual("BLOCKED", result["outcome"])

    def test_missing_fixed_adapter_is_explicit_blocked(self) -> None:
        self._bind()
        self.config = quick.Config(**{**self.config.__dict__, "deep_check_client": self.root / "missing"})
        status, output = self._run("deep-check")
        self.assertEqual(0, status, output)
        self.assertIn("DEEP_CHECK_BLOCKED", output)
        result = json.loads((self.run_dir / "results" / "deep-check.json").read_text())
        self.assertEqual("BLOCKED", result["outcome"])

    def test_fixture_observation_and_evaluation_use_only_fixed_adapter_arguments(self) -> None:
        candidate_hash = self._run_complete_fixture()
        status, output = self._run("evaluate-quick")
        self.assertEqual(0, status, output)

        calls = [json.loads(line) for line in self.call_log.read_text().splitlines()]
        web_calls = [row for row in calls if row["client"] == "web"]
        self.assertEqual(12, len(web_calls))
        for call in web_calls:
            arguments = call["arguments"]
            self.assertEqual(6, len(arguments))
            self.assertNotIn(RUN_ID, arguments)
            self.assertNotIn(candidate_hash, arguments)
            self.assertNotIn(str(self.run_dir), arguments)
        web_end = next(
            row["arguments"]
            for row in web_calls
            if row["arguments"][1] == "web-traffic"
            and row["arguments"][3] == "end"
        )
        self.assertEqual(
            str(self.config.profile_seconds + quick.WEB_WINDOW_BOUNDARY_SECONDS),
            web_end[5],
        )
        self.assertTrue(all(not row["arguments"] for row in calls if row["client"] == "backend"))

        evaluator = next(row for row in calls if row["client"] == "evaluator")["arguments"]
        self.assertEqual(15, evaluator.count("--input"))
        self.assertEqual(6, evaluator.count("--expected-kafka-pair"))
        self.assertEqual(7, evaluator.count("--expected-host-process"))
        self.assertEqual(5, evaluator.count("--expected-redis-source"))
        self.assertEqual(5, evaluator.count("--expected-redis-node"))
        self.assertIn("default=master-1", evaluator)
        self.assertIn("runtime=master-1", evaluator)
        self.assertIn("backend=armada-backend", evaluator)
        self.assertIn("backend=armada-nginx", evaluator)
        self.assertIn("backend=zhuan-native-probe-mysql", evaluator)
        self.assertIn("backend=zhuan-coordinator", evaluator)
        self.assertNotIn("--test-mode", evaluator)
        summary = json.loads((self.run_dir / "quick-summary.json").read_text())
        self.assertEqual("PASS", summary["outcome"])
        self.assertEqual(
            {
                "rawTotals": "diagnostic-minute-envelope",
                "watermarks": "watermark-health-only",
                "runAttribution": "not-attributed",
            },
            summary["webTrafficSemantics"],
        )
        rendered_summary = json.dumps(summary).lower()
        for prohibited in (
            "acceptance-run",
            "acceptance run bytes",
            "acceptancerunbytes",
        ):
            self.assertNotIn(prohibited, rendered_summary)

    def test_web_traffic_timeout_hierarchy_is_bounded_without_waiting(self) -> None:
        self.assertEqual(120, quick.DEFAULT_OBSERVER_TIMEOUT_SECONDS)
        self.assertLess(
            quick.WEB_CAPTURE_WATERMARK_WAIT_SECONDS,
            quick.WEB_OBSERVER_DISPATCH_TIMEOUT_SECONDS,
        )
        self.assertLess(
            quick.WEB_OBSERVER_DISPATCH_TIMEOUT_SECONDS,
            quick.WEB_OBSERVER_TRANSPORT_TIMEOUT_SECONDS,
        )
        self.assertLess(
            quick.WEB_OBSERVER_TRANSPORT_TIMEOUT_SECONDS,
            quick.WEB_TRAFFIC_OBSERVER_TIMEOUT_SECONDS,
        )
        plan_timeouts = dict(quick.QUICK_STAGES)
        self.assertEqual(900, plan_timeouts["observe-end"])

        controller = quick.Controller(self.config)
        controller.run_dir = self.run_dir
        observed: dict[str, list[tuple[str, int]]] = {}
        for phase in ("start", "peak", "end"):
            calls: list[tuple[str, int]] = []

            def record_timeout(
                executable, arguments, blockers, failures, prefix, timeout_seconds
            ):
                action = arguments[1] if arguments else "backend"
                calls.append((action, timeout_seconds))
                return None

            with mock.patch.object(
                controller, "_run_observer", side_effect=record_timeout
            ), mock.patch.object(controller, "_check_snapshot"):
                controller._observe(phase)
            observed[phase] = calls

        default_calls = [
            ("kafka", quick.DEFAULT_OBSERVER_TIMEOUT_SECONDS),
            ("redis", quick.DEFAULT_OBSERVER_TIMEOUT_SECONDS),
            ("host", quick.DEFAULT_OBSERVER_TIMEOUT_SECONDS),
            ("web-traffic", quick.DEFAULT_OBSERVER_TIMEOUT_SECONDS),
            ("backend", quick.DEFAULT_OBSERVER_TIMEOUT_SECONDS),
        ]
        self.assertEqual(default_calls, observed["start"])
        self.assertEqual(default_calls, observed["peak"])
        self.assertEqual(
            [
                *default_calls[:3],
                ("web-traffic", quick.WEB_TRAFFIC_OBSERVER_TIMEOUT_SECONDS),
                default_calls[4],
            ],
            observed["end"],
        )

        self._executable(self.web_client.name, "#!/bin/sh\nexit 0\n")
        controller = quick.Controller(self.config)
        blockers: list[str] = []
        failures: list[str] = []
        started = time.monotonic()
        with mock.patch.object(
            quick.subprocess,
            "run",
            side_effect=subprocess.TimeoutExpired(
                "fixture-web-traffic", quick.WEB_TRAFFIC_OBSERVER_TIMEOUT_SECONDS
            ),
        ) as run:
            status = controller._run_observer(
                self.web_client,
                ("--action", "web-traffic", "--phase", "end", "--window-seconds", "61"),
                blockers,
                failures,
                "WEB_OBSERVER",
                quick.WEB_TRAFFIC_OBSERVER_TIMEOUT_SECONDS,
            )
        self.assertLess(time.monotonic() - started, 1)
        self.assertIsNone(status)
        self.assertEqual(["WEB_OBSERVER_CLIENT_UNAVAILABLE"], blockers)
        self.assertEqual([], failures)
        self.assertEqual(
            quick.WEB_TRAFFIC_OBSERVER_TIMEOUT_SECONDS,
            run.call_args.kwargs["timeout"],
        )

    def test_missing_deep_client_continues_and_final_summary_is_blocked(self) -> None:
        self._run_complete_fixture(missing_deep=True)
        status, output = self._run("evaluate-quick")
        self.assertEqual(quick.EXIT_BLOCKED, status, output)
        summary = json.loads((self.run_dir / "quick-summary.json").read_text())
        self.assertEqual("BLOCKED", summary["outcome"])
        self.assertIn("DEEP_CHECK_BLOCKED", summary["reasonCodes"])

    def test_ui_logical_failure_continues_and_has_final_fail_precedence(self) -> None:
        self._run_complete_fixture(failing_ui=True)
        status, output = self._run("evaluate-quick")
        self.assertEqual(quick.EXIT_FAIL, status, output)
        summary = json.loads((self.run_dir / "quick-summary.json").read_text())
        self.assertEqual("FAIL", summary["outcome"])
        self.assertIn("UI_SMOKE_FAILED", summary["reasonCodes"])

    def test_runtime_fixture_is_checked_against_candidate_full_shas(self) -> None:
        self._bind()
        observer = "#!/usr/bin/env python3\n" + textwrap.dedent(
            """
            import datetime, json, os
            from pathlib import Path
            run_dir = Path(os.environ['STAGING_ACCEPT_RUN_DIR'])
            builds = json.loads((run_dir / 'candidate-manifest.json').read_text())['builds']
            now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
            def artifact(commit, role=None):
                value = {'kind': 'runtime-revision', 'identity': commit, 'observedCommit': commit, 'observedAt': now}
                if role:
                    value['role'] = role
                return value
            payload = {
                'schemaVersion': 1,
                'environment': 'test1',
                'generatedAt': now,
                'components': {
                    'backend': {'expectedCommit': builds['backend'], 'artifact': artifact(builds['backend'])},
                    'frontend': {'expectedCommit': builds['frontend'], 'artifact': artifact(builds['frontend'])},
                    'webProtocol': {'expectedCommit': builds['webProtocol'], 'artifact': artifact(builds['webProtocol'])},
                    'androidProtocol': {
                        'expectedCommit': builds['androidProtocol'],
                        'artifacts': [artifact(builds['androidProtocol'], role) for role in ('coordinator', 'node-01', 'node-02', 'node-03')],
                    },
                },
            }
            (run_dir / 'runtime-manifest.json').write_text(json.dumps(payload) + '\\n')
            """
        )
        self.runtime_client.write_text(observer, encoding="utf-8")
        self.runtime_client.chmod(0o755)
        status, output = self._run("runtime-versions")
        self.assertEqual(0, status, output)

    def test_production_entrypoint_rejects_all_arguments(self) -> None:
        completed = subprocess.run(
            [sys.executable, str(WRAPPER_PATH), "unexpected"],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(quick.EXIT_BLOCKED, completed.returncode)
        self.assertIn("arguments are not accepted", completed.stderr)


if __name__ == "__main__":
    unittest.main()
