import importlib.util
import inspect
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
MODULE_PATH = ROOT / "backend_docker_snapshot.py"
COLLECTOR = ROOT / "observability" / "collect.py"
FIXTURES = ROOT / "observability" / "fixtures"
SYSTEMD_DIR = ROOT.parent / "systemd"
EXPECTED_CONTAINERS = (
    "armada-backend",
    "armada-nginx",
    "zhuan-native-probe-mysql",
    "zhuan-coordinator",
)


def load_module():
    spec = importlib.util.spec_from_file_location("backend_docker_snapshot", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load backend docker snapshot module")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BackendDockerSnapshotContractTest(unittest.TestCase):
    def make_fake_docker(self, directory: Path) -> Path:
        docker = directory / "fake-docker"
        docker.write_text(
            """#!/bin/sh
set -eu
printf '%s\\n' "$1" >> "${0}.calls"
case "$1" in
  stats)
    [ "$#" -eq 8 ]
    [ "$2" = "--no-stream" ]
    [ "$3" = "--format" ]
    [ "$4" = "{{json .}}" ]
    [ "$5" = "armada-backend" ]
    [ "$6" = "armada-nginx" ]
    [ "$7" = "zhuan-native-probe-mysql" ]
    [ "$8" = "zhuan-coordinator" ]
    printf '%s\\n' \
      '{"Name":"zhuan-native-probe-mysql","CPUPerc":"3.00%","MemUsage":"700MiB / 768MiB","MemPerc":"91.15%","NetIO":"1GB / 2GB"}' \
      '{"Name":"armada-backend","CPUPerc":"12.50%","MemUsage":"512MiB / 2GiB","MemPerc":"25.00%","NetIO":"3GB / 4GB"}' \
      '{"Name":"armada-nginx","CPUPerc":"0.30%","MemUsage":"32MiB / 2GiB","MemPerc":"1.56%","NetIO":"5GB / 6GB"}' \
      '{"Name":"zhuan-coordinator","CPUPerc":"0.40%","MemUsage":"40MiB / 2GiB","MemPerc":"1.95%","NetIO":"7GB / 8GB"}'
    ;;
  inspect)
    [ "$#" -eq 7 ]
    [ "$2" = "--format" ]
    [ "$4" = "armada-backend" ]
    [ "$5" = "armada-nginx" ]
    [ "$6" = "zhuan-native-probe-mysql" ]
    [ "$7" = "zhuan-coordinator" ]
    printf '%s\\n' \
      '{"name":"/armada-nginx","restartCount":0,"oomKilled":false,"status":"running","startedAt":"2026-08-25T00:00:00Z"}' \
      '{"name":"/zhuan-native-probe-mysql","restartCount":1,"oomKilled":false,"status":"running","startedAt":"2026-08-25T00:00:00Z"}' \
      '{"name":"/armada-backend","restartCount":2,"oomKilled":false,"status":"running","startedAt":"2026-08-25T00:00:00Z"}' \
      '{"name":"/zhuan-coordinator","restartCount":0,"oomKilled":false,"status":"running","startedAt":"2026-08-25T00:00:00Z"}'
    ;;
  *) exit 90 ;;
esac
""",
            encoding="utf-8",
        )
        docker.chmod(0o755)
        return docker

    def test_fake_docker_snapshot_is_accepted_by_existing_collector(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            docker = self.make_fake_docker(root)
            output = root / "run" / "staging-accept"

            module.collect_and_write(docker_bin=docker, output_dir=output, ownership=None)

            calls = Path(f"{docker}.calls").read_text(encoding="utf-8").splitlines()
            self.assertEqual(["stats", "inspect"], calls)
            stats_path = output / "docker-stats.jsonl"
            inspect_path = output / "docker-inspect.jsonl"
            self.assertEqual(0o750, stat.S_IMODE(output.stat().st_mode))
            self.assertEqual(0o640, stat.S_IMODE(stats_path.stat().st_mode))
            self.assertEqual(0o640, stat.S_IMODE(inspect_path.stat().st_mode))

            stats = [
                json.loads(line)
                for line in stats_path.read_text(encoding="utf-8").splitlines()
            ]
            inspect_rows = [
                json.loads(line) for line in inspect_path.read_text(encoding="utf-8").splitlines()
            ]
            self.assertEqual(list(EXPECTED_CONTAINERS), [row["Name"] for row in stats])
            self.assertEqual(
                list(EXPECTED_CONTAINERS),
                [row["name"].removeprefix("/") for row in inspect_rows],
            )
            expected_stats_fields = {
                "Name",
                "CPUPerc",
                "MemUsage",
                "MemPerc",
                "snapshotGeneration",
            }
            self.assertTrue(all(set(row) == expected_stats_fields for row in stats))
            generations = {row["snapshotGeneration"] for row in stats + inspect_rows}
            self.assertEqual(1, len(generations))

            completed = subprocess.run(
                [
                    sys.executable,
                    str(COLLECTOR),
                    "host",
                    "--phase",
                    "start",
                    "--run-id",
                    "docker-contract",
                    "--candidate-manifest-sha256",
                    "sha256:" + "a" * 64,
                    "--proc-stat-before",
                    str(FIXTURES / "proc-stat.before"),
                    "--proc-stat-after",
                    str(FIXTURES / "proc-stat.after"),
                    "--meminfo",
                    str(FIXTURES / "meminfo"),
                    "--docker-stats-file",
                    str(stats_path),
                    "--docker-inspect-file",
                    str(inspect_path),
                    *sum((["--container", name] for name in EXPECTED_CONTAINERS), []),
                ],
                check=False,
                capture_output=True,
                text=True,
                timeout=10,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual("COLLECTED", result["status"])
            self.assertEqual(
                sorted(EXPECTED_CONTAINERS),
                [row["name"] for row in result["raw"]["containers"]],
            )

    def test_failed_collection_preserves_last_good_files(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "run" / "staging-accept"
            output.mkdir(parents=True)
            stats_path = output / "docker-stats.jsonl"
            inspect_path = output / "docker-inspect.jsonl"
            stats_path.write_text("last-good-stats\n", encoding="utf-8")
            inspect_path.write_text("last-good-inspect\n", encoding="utf-8")
            broken = root / "broken-docker"
            broken.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
            broken.chmod(0o755)

            with self.assertRaises(module.SnapshotError):
                module.collect_and_write(docker_bin=broken, output_dir=output, ownership=None)

            self.assertEqual("last-good-stats\n", stats_path.read_text(encoding="utf-8"))
            self.assertEqual("last-good-inspect\n", inspect_path.read_text(encoding="utf-8"))


class BackendDockerSnapshotStaticTest(unittest.TestCase):
    def test_runtime_has_exact_allowlist_and_no_container_parameter(self):
        module = load_module()
        self.assertEqual(EXPECTED_CONTAINERS, module.ALLOWED_CONTAINERS)
        self.assertEqual(Path("/usr/bin/docker"), module.DOCKER_BIN)
        self.assertEqual(Path("/run/staging-accept"), module.OUTPUT_DIR)
        self.assertEqual(
            ("docker_bin", "output_dir", "ownership"),
            tuple(inspect.signature(module.collect_and_write).parameters),
        )
        self.assertEqual(
            '{"name":{{json .Name}},"restartCount":{{json .RestartCount}},'
            '"oomKilled":{{json .State.OOMKilled}},"status":{{json .State.Status}},'
            '"startedAt":{{json .State.StartedAt}}}',
            module.INSPECT_TEMPLATE,
        )

    def test_systemd_units_keep_root_only_docker_access_and_thirty_second_schedule(self):
        service = (SYSTEMD_DIR / "staging-accept-docker-snapshot.service").read_text(
            encoding="utf-8"
        )
        timer = (SYSTEMD_DIR / "staging-accept-docker-snapshot.timer").read_text(encoding="utf-8")

        self.assertIn("Type=oneshot", service)
        self.assertIn("User=root", service)
        self.assertIn("Group=staging-accept", service)
        self.assertIn("RuntimeDirectory=staging-accept", service)
        self.assertIn("RuntimeDirectoryMode=0750", service)
        self.assertIn("RuntimeDirectoryPreserve=yes", service)
        self.assertNotIn("SupplementaryGroups=docker", service)
        self.assertNotIn("User=staging-accept", service)
        exec_lines = [line for line in service.splitlines() if line.startswith("ExecStart=")]
        self.assertEqual(
            [
                "ExecStart=/usr/bin/python3 "
                "/usr/local/libexec/staging-accept/backend_docker_snapshot.py"
            ],
            exec_lines,
        )
        self.assertIn("OnBootSec=30s", timer)
        self.assertIn("OnUnitActiveSec=30s", timer)
        self.assertIn("WantedBy=timers.target", timer)

    def test_production_entrypoint_rejects_arguments_before_docker_execution(self):
        completed = subprocess.run(
            [sys.executable, str(MODULE_PATH), "armada-backend"],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
            env={"PATH": os.environ.get("PATH", "")},
        )
        self.assertEqual(2, completed.returncode)
        self.assertEqual("backend docker snapshot accepts no arguments\n", completed.stderr)


if __name__ == "__main__":
    unittest.main()
