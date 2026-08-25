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

    def test_plan_is_fixed_read_only_and_contains_no_active_or_credential_path(self) -> None:
        plan = json.loads(PLAN_PATH.read_text(encoding="utf-8"))
        self.assertEqual("test1-quick", plan["profile"])
        self.assertEqual("read-only", plan["safety"])
        self.assertEqual([stage for stage, _ in quick.QUICK_STAGES], [row["id"] for row in plan["stages"]])
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
        self.assertTrue(all(not row["arguments"] for row in calls if row["client"] == "backend"))

        evaluator = next(row for row in calls if row["client"] == "evaluator")["arguments"]
        self.assertEqual(15, evaluator.count("--input"))
        self.assertEqual(6, evaluator.count("--expected-kafka-pair"))
        self.assertEqual(6, evaluator.count("--expected-host-process"))
        self.assertEqual(5, evaluator.count("--expected-redis-source"))
        self.assertEqual(5, evaluator.count("--expected-redis-node"))
        self.assertIn("default=primary", evaluator)
        self.assertIn("runtime=primary", evaluator)
        self.assertIn("backend=armada-backend", evaluator)
        self.assertNotIn("--test-mode", evaluator)
        summary = json.loads((self.run_dir / "quick-summary.json").read_text())
        self.assertEqual("PASS", summary["outcome"])

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
