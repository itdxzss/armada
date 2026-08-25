import contextlib
import hashlib
import importlib.util
import io
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parent
MODULE_PATH = ROOT / "backend-observer-client.py"
RUN_ID = "20260825T080000Z-a1b2c3d4"
CONTAINERS = (
    "armada-backend",
    "armada-nginx",
    "zhuan-native-probe-mysql",
)


def load_module():
    spec = importlib.util.spec_from_file_location("backend_observer_client", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load backend observer client")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class BackendObserverClientTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.run_root = self.root / "runs"
        self.run_dir = self.run_root / RUN_ID
        self.run_dir.mkdir(parents=True, mode=0o700)
        self.manifest_content = (
            json.dumps(
                {
                    "schemaVersion": 1,
                    "profile": "test1-quick",
                    "environment": "test1",
                    "safety": "read-only",
                    "builds": {
                        "backend": "1" * 40,
                        "frontend": "2" * 40,
                        "webProtocol": "3" * 40,
                        "androidProtocol": "4" * 40,
                    },
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode("utf-8")
        self.manifest = self.run_dir / "candidate-manifest.json"
        self.manifest.write_bytes(self.manifest_content)
        self.manifest.chmod(0o600)
        self.stats = self.root / "docker-stats.jsonl"
        self.inspect = self.root / "docker-inspect.jsonl"
        self.snapshot_generation = "a" * 32
        self.stats.write_text(
            "\n".join(
                json.dumps(
                    {
                        "Name": name,
                        "CPUPerc": "1.00%",
                        "MemUsage": "1MiB / 1GiB",
                        "MemPerc": "0.10%",
                        "snapshotGeneration": self.snapshot_generation,
                    },
                    separators=(",", ":"),
                )
                for name in CONTAINERS
            )
            + "\n",
            encoding="utf-8",
        )
        self.inspect.write_text(
            "\n".join(
                json.dumps(
                    {
                        "name": f"/{name}",
                        "restartCount": 0,
                        "oomKilled": False,
                        "status": "running",
                        "startedAt": "2026-08-25T00:00:00Z",
                        "snapshotGeneration": self.snapshot_generation,
                    },
                    separators=(",", ":"),
                )
                for name in CONTAINERS
            )
            + "\n",
            encoding="utf-8",
        )
        self.stats.chmod(0o640)
        self.inspect.chmod(0o640)
        self.collector = self.root / "fake-collector.py"
        self.calls = self.root / "fake-collector.calls.json"
        self.install_fake_collector()
        self.config = self.module.Config(
            run_root=self.run_root,
            python=Path(sys.executable).resolve(),
            collector=self.collector,
            stats_file=self.stats,
            inspect_file=self.inspect,
            snapshot_owner=(os.geteuid(), os.getegid()),
        )

    def tearDown(self):
        self.temporary.cleanup()

    def install_fake_collector(
        self,
        *,
        status: str = "COLLECTED",
        private: bool = False,
        malformed: bool = False,
    ) -> None:
        raw_value = "{'token': 'must-not-appear'}" if private else "{'containers': []}"
        body = f"""import json
import sys
from pathlib import Path

arguments = sys.argv[1:]
Path(__file__).with_name('fake-collector.calls.json').write_text(json.dumps(arguments))
if {malformed!r}:
    print('not-json secret=must-not-appear')
    raise SystemExit(0)
def value(name):
    return arguments[arguments.index(name) + 1]
status = {status!r}
payload = {{
    'schemaVersion': 1,
    'collector': 'host-resource',
    'environment': 'test1',
    'phase': value('--phase'),
    'runId': value('--run-id'),
    'candidateManifestSha256': value('--candidate-manifest-sha256'),
    'provenance': 'fixture',
    'source': value('--label'),
    'observedAt': '2026-08-25T08:00:00Z',
    'status': status,
    'health': {{
        'ok': status == 'COLLECTED',
        'checks': [],
        'blockedReasons': [] if status == 'COLLECTED' else ['CONTAINER_RESOURCE_UNOBSERVABLE'],
    }},
    'semantics': {{}},
    'raw': {raw_value},
}}
print(json.dumps(payload, separators=(',', ':')))
raise SystemExit(0 if status == 'COLLECTED' else 2)
"""
        self.collector.write_text(body, encoding="utf-8")

    def environment(self, stage: str = "observe-peak") -> dict[str, str]:
        return {
            "STAGING_ACCEPT_RUN_ID": RUN_ID,
            "STAGING_ACCEPT_STAGE_ID": stage,
            "STAGING_ACCEPT_RUN_DIR": str(self.run_dir),
        }

    def test_fake_collector_receives_only_fixed_backend_snapshot_arguments(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            status = self.module.execute(self.environment(), self.config)

        self.assertEqual(0, status)
        self.assertEqual("", stdout.getvalue())
        candidate_hash = "sha256:" + hashlib.sha256(self.manifest_content).hexdigest()
        self.assertEqual(
            [
                "host",
                "--environment",
                "test1",
                "--phase",
                "peak",
                "--label",
                "backend",
                "--run-id",
                RUN_ID,
                "--candidate-manifest-sha256",
                candidate_hash,
                "--docker-stats-file",
                mock.ANY,
                "--docker-inspect-file",
                mock.ANY,
                "--container",
                "armada-backend",
                "--container",
                "armada-nginx",
                "--container",
                "zhuan-native-probe-mysql",
            ],
            json.loads(self.calls.read_text(encoding="utf-8")),
        )
        arguments = json.loads(self.calls.read_text(encoding="utf-8"))
        for flag in ("--docker-stats-file", "--docker-inspect-file"):
            snapshot_path = Path(arguments[arguments.index(flag) + 1])
            self.assertEqual(self.run_dir, snapshot_path.parent)
            self.assertFalse(snapshot_path.exists())
        evidence = self.run_dir / "observability" / "host-backend-peak.json"
        payload = json.loads(evidence.read_text(encoding="utf-8"))
        self.assertEqual("live", payload["provenance"])
        self.assertEqual("backend", payload["source"])
        self.assertEqual(candidate_hash, payload["candidateManifestSha256"])
        self.assertEqual(0o600, stat.S_IMODE(evidence.stat().st_mode))
        self.assertEqual([], list(evidence.parent.glob(".host-backend-peak.json.*")))

    def test_phase_is_derived_only_from_the_runner_stage(self):
        for stage, phase in (
            ("observe-start", "start"),
            ("observe-peak", "peak"),
            ("observe-end", "end"),
        ):
            with self.subTest(stage=stage):
                self.assertEqual(0, self.module.execute(self.environment(stage), self.config))
                arguments = json.loads(self.calls.read_text(encoding="utf-8"))
                self.assertEqual(phase, arguments[arguments.index("--phase") + 1])
        with self.assertRaises(self.module.ClientError):
            self.module.execute(self.environment("quick-midpoint"), self.config)

    def test_blocked_collector_evidence_is_saved_and_exit_two_is_preserved(self):
        self.install_fake_collector(status="BLOCKED")

        self.assertEqual(2, self.module.execute(self.environment("observe-end"), self.config))

        evidence = self.run_dir / "observability" / "host-backend-end.json"
        payload = json.loads(evidence.read_text(encoding="utf-8"))
        self.assertEqual("BLOCKED", payload["status"])
        self.assertEqual("live", payload["provenance"])

    def test_symlinked_manifest_or_unsafe_snapshot_is_blocked_before_collector(self):
        manifest_target = self.root / "manifest-target.json"
        manifest_target.write_bytes(self.manifest_content)
        self.manifest.unlink()
        self.manifest.symlink_to(manifest_target)
        with self.assertRaises(self.module.ClientError):
            self.module.execute(self.environment(), self.config)
        self.assertFalse(self.calls.exists())

    def test_mismatched_snapshot_generation_is_blocked_before_collector(self):
        rows = [json.loads(line) for line in self.inspect.read_text().splitlines()]
        for row in rows:
            row["snapshotGeneration"] = "b" * 32
        self.inspect.write_text(
            "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows),
            encoding="utf-8",
        )
        self.inspect.chmod(0o640)

        with self.assertRaises(self.module.ClientError):
            self.module.execute(self.environment(), self.config)
        self.assertFalse(self.calls.exists())

        self.manifest.unlink()
        self.manifest.write_bytes(self.manifest_content)
        self.manifest.chmod(0o600)
        self.stats.chmod(0o666)
        with self.assertRaises(self.module.ClientError):
            self.module.execute(self.environment(), self.config)
        self.assertFalse(self.calls.exists())

    def test_private_or_malformed_collector_stdout_is_never_forwarded_or_written(self):
        for private, malformed in ((True, False), (False, True)):
            with self.subTest(private=private, malformed=malformed):
                self.install_fake_collector(private=private, malformed=malformed)
                stdout = io.StringIO()
                with contextlib.redirect_stdout(stdout), self.assertRaises(
                    self.module.ClientError
                ):
                    self.module.execute(self.environment(), self.config)
                self.assertEqual("", stdout.getvalue())
                evidence = self.run_dir / "observability" / "host-backend-peak.json"
                self.assertFalse(evidence.exists())

    def test_production_entrypoint_rejects_arguments_without_echoing_them(self):
        sentinel = "secret=must-not-appear"
        completed = subprocess.run(
            [sys.executable, str(MODULE_PATH), sentinel],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(40, completed.returncode)
        self.assertEqual("", completed.stdout)
        self.assertNotIn(sentinel, completed.stderr)
        self.assertEqual("backend-observer-client: observation blocked\n", completed.stderr)

    def test_production_constants_are_fixed(self):
        self.assertEqual(Path("/var/lib/staging-accept/runs"), self.module.RUN_ROOT)
        self.assertEqual(Path("/usr/bin/python3"), self.module.PYTHON)
        self.assertEqual(
            Path("/usr/local/libexec/staging-accept/scripts/observability/collect.py"),
            self.module.COLLECTOR,
        )
        self.assertEqual(Path("/run/staging-accept/docker-stats.jsonl"), self.module.STATS_FILE)
        self.assertEqual(
            Path("/run/staging-accept/docker-inspect.jsonl"), self.module.INSPECT_FILE
        )
        self.assertEqual(CONTAINERS, self.module.ALLOWED_CONTAINERS)


if __name__ == "__main__":
    unittest.main()
