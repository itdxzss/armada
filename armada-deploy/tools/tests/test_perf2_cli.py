import io
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from perf2_loadtest.cli import CLIDependencies, run


class CLITest(unittest.TestCase):
    def test_help_and_invalid_arguments_do_not_construct_dependencies(self) -> None:
        calls = []
        dependencies = CLIDependencies(
            repo_root=Path("/unused"),
            load_profile=lambda *_: calls.append("profile"),
            task_api_factory=lambda *_: calls.append("api"),
            remote_factory=lambda *_: calls.append("remote"),
            orchestrator_factory=lambda **_: calls.append("orchestrator"),
        )
        stdout, stderr = io.StringIO(), io.StringIO()
        self.assertEqual(0, run(["--help"], stdout, stderr, dependencies))
        self.assertIn("--execute", stdout.getvalue())
        self.assertEqual([], calls)

        stdout, stderr = io.StringIO(), io.StringIO()
        self.assertEqual(2, run(["--env", "prod"], stdout, stderr, dependencies))
        self.assertEqual("invalid_arguments\n", stderr.getvalue())
        self.assertEqual([], calls)

    def test_wires_dry_run_and_prints_only_safe_summary(self) -> None:
        calls = []
        fake_profile = SimpleNamespace(env_id="perf2", public_url="http://private-host")

        class FakeOrchestrator:
            run_id = "20260725T020000Z-deadbeef"
            run_dir = Path("/safe/results/20260725T020000Z-deadbeef")
            last_summary = {
                "mode": "dry-run",
                "snapshotTaskCount": 34,
                "selectedAccountCount": 248,
                "targetGroupCount": 2243,
                "targetPairCount": 248,
                "baselineFinalLag": 0,
                "incomplete": False,
                "private": "must not print",
            }

            def run(self):
                calls.append("run")
                return 0

        dependencies = CLIDependencies(
            repo_root=Path("/repo"),
            load_profile=lambda root, env: calls.append(("profile", env)) or fake_profile,
            task_api_factory=lambda profile, options: calls.append("api") or object(),
            remote_factory=lambda profile, options: calls.append("remote") or object(),
            orchestrator_factory=lambda **kwargs: calls.append("orchestrator") or FakeOrchestrator(),
        )
        stdout, stderr = io.StringIO(), io.StringIO()

        exit_code = run(["--env", "perf2"], stdout, stderr, dependencies)

        self.assertEqual(0, exit_code)
        self.assertEqual("", stderr.getvalue())
        self.assertIn('"snapshotTaskCount": 34', stdout.getvalue())
        self.assertNotIn("private-host", stdout.getvalue())
        self.assertNotIn("must not print", stdout.getvalue())
        self.assertEqual([("profile", "perf2"), "api", "remote", "orchestrator", "run"], calls)

    def test_boundary_failure_is_redacted(self) -> None:
        dependencies = CLIDependencies(
            repo_root=Path("/repo"),
            load_profile=lambda *_: (_ for _ in ()).throw(RuntimeError("secret host key broker")),
            task_api_factory=lambda *_: object(),
            remote_factory=lambda *_: object(),
            orchestrator_factory=lambda **_: object(),
        )
        stdout, stderr = io.StringIO(), io.StringIO()
        self.assertEqual(1, run(["--env", "perf2"], stdout, stderr, dependencies))
        self.assertEqual("setup_failed\n", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
