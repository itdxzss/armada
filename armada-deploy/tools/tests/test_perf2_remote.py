import io
import json
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path, PurePosixPath

from perf2_loadtest.model import Perf2Profile, SSHProfile
from perf2_loadtest.remote import RemoteError, RemoteMonitorManager


def monitor_line(node, kafka):
    value = {
        "schemaVersion": 1,
        "at": "2026-07-25T02:00:00Z",
        "node": node,
        "resource": {
            "hostCpuPercent": 1,
            "hostMemoryUsedBytes": 2,
            "hostMemoryPercent": 3,
            "containerCpuPercent": 4,
            "containerMemoryBytes": 5,
            "containerMemoryPercent": 6,
            "valid": True,
        },
    }
    if kafka:
        value["kafka"] = {
            "latestOffset": 10,
            "committedOffset": 10,
            "lag": 0,
            "producedPerSecond": 0,
            "consumedPerSecond": 0,
            "valid": True,
        }
    return json.dumps(value).encode("utf-8") + b"\n"


class FakeRunner:
    def __init__(self):
        self.calls = []
        self.preflight_count = 0

    def run(self, argv, *, cwd=None, env=None, timeout=None, input=None):
        argv = list(argv)
        self.calls.append(
            {"argv": argv, "cwd": cwd, "env": dict(env or {}), "timeout": timeout, "input": input}
        )
        if argv[:2] == ["git", "status"]:
            return subprocess.CompletedProcess(argv, 0, b"", b"")
        if argv[:2] == ["go", "build"]:
            output = Path(argv[argv.index("-o") + 1])
            output.write_bytes(b"static-monitor-binary")
            return subprocess.CompletedProcess(argv, 0, b"", b"")
        if argv[0] == "ssh" and input and b"PRECHECK" in input:
            self.preflight_count += 1
            return subprocess.CompletedProcess(
                argv, 0, b"x86_64\nrunning healthy\n7340032\nstats-ok\n", b""
            )
        if argv[0] == "ssh" and "-check" in argv:
            node = argv[argv.index("-node") + 1]
            return subprocess.CompletedProcess(argv, 0, monitor_line(node, node == "zhuan"), b"")
        return subprocess.CompletedProcess(argv, 0, b"", b"")


class FakeProcess:
    def __init__(self, stdout):
        self.stdout = io.BytesIO(stdout)
        self.stderr = io.BytesIO(b"")
        self.returncode = None
        self.terminated = False
        self.killed = False

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True
        self.returncode = 0

    def wait(self, timeout=None):
        if self.returncode is None:
            self.returncode = 0
        return self.returncode

    def kill(self):
        self.killed = True
        self.returncode = -9


class FakePopenFactory:
    def __init__(self):
        self.calls = []
        self.processes = []

    def __call__(self, argv, **kwargs):
        node = argv[argv.index("-node") + 1]
        process = FakeProcess(monitor_line(node, node == "zhuan"))
        self.calls.append((list(argv), kwargs))
        self.processes.append(process)
        return process


class RemoteMonitorManagerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.armada_key = self.root / "armada key.pem"
        self.zhuan_key = self.root / "zhuan key.pem"
        self.armada_key.touch()
        self.zhuan_key.touch()
        self.profile = Perf2Profile(
            env_id="perf2",
            armada=SSHProfile(
                "armada.example", "ec2-user", self.armada_key,
                PurePosixPath("/home/app/armada-deploy"), "docker-compose.rds.yml",
            ),
            zhuan=SSHProfile(
                "zhuan.example", "ec2-user", self.zhuan_key,
                PurePosixPath("/home/ec2-user/whatsapp-android-zhuan"), "docker-compose.perf.yml",
            ),
            public_url="http://armada.example",
            topic="armada.perf.protocol.android.message.commands.v1",
            group_id="armada-perf-android-zhuan-message-v1",
            expected_partitions=12,
        )
        self.zhuan_repo = self.root / "zhuan"
        (self.zhuan_repo / "cmd/perf-monitor").mkdir(parents=True)
        (self.zhuan_repo / "cmd/perf-monitor/main.go").write_text("package main\n", encoding="utf-8")
        (self.zhuan_repo / "go.mod").write_text("module fixture\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_builds_static_linux_amd64_binary_and_hashes_it(self) -> None:
        runner = FakeRunner()
        manager = RemoteMonitorManager(self.profile, min_free_gib=5, runner=runner)

        built = manager.build(self.zhuan_repo)

        build_call = next(call for call in runner.calls if call["argv"][:2] == ["go", "build"])
        self.assertEqual(
            ["go", "build", "-trimpath", "-o", str(built.path), "./cmd/perf-monitor"],
            build_call["argv"],
        )
        self.assertEqual("0", build_call["env"]["CGO_ENABLED"])
        self.assertEqual("linux", build_call["env"]["GOOS"])
        self.assertEqual("amd64", build_call["env"]["GOARCH"])
        self.assertEqual(64, len(built.sha256))
        self.assertTrue(built.path.is_file())
        manager.close()

    def test_preflight_requires_arch_health_disk_and_docker_stats(self) -> None:
        runner = FakeRunner()
        manager = RemoteMonitorManager(self.profile, min_free_gib=5, runner=runner)

        evidence = manager.preflight()

        self.assertEqual("x86_64", evidence.armada.architecture)
        self.assertTrue(evidence.armada.container_healthy)
        self.assertGreater(evidence.zhuan.free_bytes, 5 * 1024**3)
        self.assertEqual(2, runner.preflight_count)
        for call in runner.calls:
            if call["argv"] and call["argv"][0] == "ssh":
                self.assertNotIn("shell=True", repr(call))

    def test_upload_check_and_stream_commands_are_run_scoped(self) -> None:
        runner = FakeRunner()
        popen = FakePopenFactory()
        manager = RemoteMonitorManager(
            self.profile, min_free_gib=5, runner=runner, popen_factory=popen
        )
        built = manager.build(self.zhuan_repo)
        run_id = "20260725T020000Z-deadbeef"

        manager.upload_and_check(built, run_id)
        streams = manager.start()

        scp_calls = [call for call in runner.calls if call["argv"] and call["argv"][0] == "scp"]
        self.assertEqual(2, len(scp_calls))
        self.assertTrue(all(run_id in " ".join(call["argv"]) for call in scp_calls))
        chmod_calls = [
            call for call in runner.calls
            if call["argv"]
            and call["argv"][0] == "ssh"
            and call["input"]
            and b"CHMOD_MONITOR" in call["input"]
        ]
        self.assertEqual(2, len(chmod_calls))
        self.assertEqual(2, len(popen.calls))
        armada_command = next(argv for argv, _ in popen.calls if "armada" in argv)
        zhuan_command = next(argv for argv, _ in popen.calls if "zhuan" in argv)
        self.assertIn("-no-kafka", armada_command)
        self.assertNotIn("-no-kafka", zhuan_command)
        self.assertIn("-expected-partitions", zhuan_command)
        self.assertIn("deploy/configs/prod_configs.toml", " ".join(zhuan_command))

        events = []
        deadline = time.time() + 1
        while len([event for event in events if event.kind == "sample"]) < 2 and time.time() < deadline:
            events.append(streams.events.get(timeout=1))
        self.assertEqual(2, len([event for event in events if event.kind == "sample"]))
        manager.close()
        self.assertTrue(all(process.terminated for process in popen.processes))

    def test_rejects_bad_run_id_before_remote_commands(self) -> None:
        runner = FakeRunner()
        manager = RemoteMonitorManager(self.profile, min_free_gib=5, runner=runner)
        built = manager.build(self.zhuan_repo)
        call_count = len(runner.calls)
        with self.assertRaises(RemoteError):
            manager.upload_and_check(built, "../../bad")
        self.assertEqual(call_count, len(runner.calls))

    def test_remote_errors_are_stable_and_do_not_include_stderr(self) -> None:
        class FailingRunner(FakeRunner):
            def run(self, argv, **kwargs):
                return subprocess.CompletedProcess(argv, 71, b"", b"secret host broker key")

        manager = RemoteMonitorManager(self.profile, min_free_gib=5, runner=FailingRunner())
        with self.assertRaises(RemoteError) as raised:
            manager.preflight()
        self.assertEqual("remote_preflight", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
