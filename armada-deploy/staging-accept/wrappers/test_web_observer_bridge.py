import hashlib
import importlib.util
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
sys.path.insert(0, str(ROOT))


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path.name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


contract = load_module("web_observer_contract", ROOT / "web_observer_contract.py")
dispatch_module = load_module("web_observer_dispatch", ROOT / "web-observer-dispatch.py")
client_module = load_module("web_observer_client", ROOT / "web-observer-client.py")


RUN_ID = "run-1"


class ContractTest(unittest.TestCase):
    def test_forced_command_accepts_only_the_canonical_shape(self):
        manifest_hash = "sha256:" + "a" * 64
        command = contract.parse_forced_command(
            "observe --action kafka --phase start --window-seconds 0 "
            f"--run-id {RUN_ID} --manifest-hash {manifest_hash}"
        )
        self.assertEqual("kafka", command.action)
        self.assertEqual(0, command.window_seconds)

        rejected = (
            "observe --phase start --action kafka --window-seconds 0 "
            f"--run-id {RUN_ID} --manifest-hash {manifest_hash}",
            "observe --action kafka;id --phase start --window-seconds 0 "
            f"--run-id {RUN_ID} --manifest-hash {manifest_hash}",
            "observe  --action kafka --phase start --window-seconds 0 "
            f"--run-id {RUN_ID} --manifest-hash {manifest_hash}",
            "observe --action kafka --phase start --window-seconds 86401 "
            f"--run-id {RUN_ID} --manifest-hash {manifest_hash}",
        )
        for raw in rejected:
            with self.subTest(raw=raw), self.assertRaises(contract.ContractError):
                contract.parse_forced_command(raw)


class DispatchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.protocol = self.root / "protocol"
        self.observability = self.root / "observability"
        self.capture = self.root / "capture"
        self.protocol.mkdir()
        self.observability.mkdir()
        self.capture.mkdir()
        (self.protocol / "package.json").write_text("{}\n", encoding="utf-8")
        for name in ("kafka.mjs", "redis.mjs", "collect.py", "web_capture.py"):
            (self.observability / name).write_text("# fixture\n", encoding="utf-8")
        self.command = contract.validate_explicit_values(
            "kafka", "start", 0, RUN_ID, "sha256:" + "b" * 64
        )

    def tearDown(self):
        self.temporary.cleanup()

    def test_collector_commands_are_fixed_to_test1_identities(self):
        fixed_tools = {
            "node": "/usr/bin/node",
            "python3": "/usr/bin/python3",
            "pm2": "/usr/local/bin/pm2",
        }
        with mock.patch.object(dispatch_module.shutil, "which", side_effect=fixed_tools.get):
            kafka = dispatch_module.collector_argv(
                self.command, self.protocol, self.observability, self.capture
            )
            redis = dispatch_module.collector_argv(
                contract.validate_explicit_values(
                    "redis", "peak", 3600, RUN_ID, self.command.manifest_hash
                ),
                self.protocol,
                self.observability,
                self.capture,
            )
            host = dispatch_module.collector_argv(
                contract.validate_explicit_values(
                    "host", "end", 3600, RUN_ID, self.command.manifest_hash
                ),
                self.protocol,
                self.observability,
                self.capture,
            )

        kafka_pairs = [kafka[index + 1] for index, value in enumerate(kafka) if value == "--pair"]
        redis_sources = [redis[index + 1] for index, value in enumerate(redis) if value == "--source"]
        host_processes = [host[index + 1] for index, value in enumerate(host) if value == "--process"]
        self.assertEqual(list(dispatch_module.KAFKA_PAIRS), kafka_pairs)
        self.assertEqual(list(dispatch_module.REDIS_SOURCES), redis_sources)
        self.assertEqual(list(dispatch_module.PM2_PROCESSES), host_processes)
        self.assertNotIn("protocol.account.group-sync.events.v1.DLT", " ".join(kafka))

    def test_dispatch_replaces_private_collector_output_with_a_sanitized_block(self):
        payload = contract.blocked_payload(self.command, "SAFE_REASON")
        payload["raw"] = {"authorization": "must-not-appear"}
        completed = subprocess.CompletedProcess(
            args=[], returncode=2, stdout=contract.encode_payload(payload), stderr=b""
        )
        with (
            mock.patch.object(dispatch_module, "collector_argv", return_value=["/bin/true"]),
            mock.patch.object(dispatch_module.subprocess, "run", return_value=completed),
        ):
            result, exit_code = dispatch_module.dispatch(
                " ".join(self.command.remote_argv()),
                self.protocol,
                self.observability,
                self.capture,
            )

        self.assertEqual(2, exit_code)
        self.assertEqual(["WEB_OBSERVER_EXECUTION_FAILED"], result["health"]["blockedReasons"])
        self.assertNotIn("must-not-appear", json.dumps(result))

    def test_server_library_suppresses_env_output_and_rejects_command_injection(self):
        sentinel = "source-output-must-not-appear"
        env_file = self.root / "web.env"
        env_file.write_text(
            "REDIS_URL='fixture-redis-alias'\n"
            f"printf '{sentinel}\\n'\n",
            encoding="utf-8",
        )
        shell = (
            'set +e; . "$1"; web_observer_server_main "$2" "$3" "$4" '
            '"$5" "$6" "$7" "$8"; exit $?'
        )
        completed = subprocess.run(
            [
                "/bin/bash",
                "-c",
                shell,
                "bridge-test",
                str(ROOT / "web-observer-server.lib.sh"),
                str(env_file),
                sys.executable,
                str(ROOT / "web-observer-dispatch.py"),
                str(self.protocol),
                str(self.observability),
                str(self.capture),
                "observe --action kafka;id --phase start --window-seconds 0",
            ],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(40, completed.returncode, completed.stderr)
        self.assertEqual(1, len(completed.stdout.splitlines()))
        self.assertNotIn(sentinel, completed.stdout)
        self.assertNotIn("fixture-redis-alias", completed.stdout)
        self.assertEqual("WEB_OBSERVER_COMMAND_REJECTED", json.loads(completed.stdout)["health"]["blockedReasons"][0])

    def test_missing_web_env_returns_one_identity_bound_blocked_json(self):
        original = " ".join(self.command.remote_argv())
        shell = (
            'set +e; . "$1"; web_observer_server_main "$2" "$3" "$4" '
            '"$5" "$6" "$7" "$8"; exit $?'
        )
        completed = subprocess.run(
            [
                "/bin/bash",
                "-c",
                shell,
                "bridge-test",
                str(ROOT / "web-observer-server.lib.sh"),
                str(self.root / "missing.env"),
                sys.executable,
                str(ROOT / "web-observer-dispatch.py"),
                str(self.protocol),
                str(self.observability),
                str(self.capture),
                original,
            ],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(2, completed.returncode, completed.stderr)
        self.assertEqual(1, len(completed.stdout.splitlines()))
        payload = json.loads(completed.stdout)
        self.assertEqual(RUN_ID, payload["runId"])
        self.assertEqual(self.command.manifest_hash, payload["candidateManifestSha256"])
        self.assertEqual(["WEB_ENV_UNAVAILABLE"], payload["health"]["blockedReasons"])

    def test_production_server_entrypoint_rejects_argv(self):
        completed = subprocess.run(
            [str(ROOT / "web-observer-server.sh"), "unexpected"],
            check=False,
            capture_output=True,
            text=True,
            env={**os.environ, "SSH_ORIGINAL_COMMAND": " ".join(self.command.remote_argv())},
            timeout=10,
        )
        self.assertEqual(40, completed.returncode, completed.stderr)
        self.assertEqual(1, len(completed.stdout.splitlines()))
        self.assertEqual(
            ["WEB_OBSERVER_COMMAND_REJECTED"],
            json.loads(completed.stdout)["health"]["blockedReasons"],
        )


class ClientTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.run_directory = self.root / RUN_ID
        self.run_directory.mkdir()
        self.manifest_content = b'{"environment":"test1","schemaVersion":1}\n'
        (self.run_directory / "candidate-manifest.json").write_bytes(self.manifest_content)
        self.identity = self.root / "observer-key"
        self.known_hosts = self.root / "known-hosts"
        self.identity.write_text("fixture-not-a-real-key\n", encoding="utf-8")
        self.known_hosts.write_text("fixture.invalid ssh-ed25519 fixture\n", encoding="utf-8")
        self.identity.chmod(0o600)
        self.known_hosts.chmod(0o600)
        self.invocation = self.root / "ssh-invocation.json"
        self.fake_ssh = self.root / "fake-ssh"
        self.fake_ssh.write_text(
            """#!/usr/bin/env python3
import json
import os
import sys

args = sys.argv[1:]
with open(os.environ['FAKE_SSH_INVOCATION'], 'w', encoding='utf-8') as handle:
    json.dump(args, handle)
action = args[args.index('--action') + 1]
phase = args[args.index('--phase') + 1]
run_id = args[args.index('--run-id') + 1]
manifest_hash = args[args.index('--manifest-hash') + 1]
collector = {'kafka': 'kafka', 'redis': 'redis', 'host': 'host-resource', 'web-traffic': 'web-traffic'}[action]
status = os.environ.get('FAKE_SSH_STATUS', 'COLLECTED')
payload = {
    'schemaVersion': 1,
    'collector': collector,
    'environment': 'test1',
    'phase': phase,
    'runId': run_id,
    'candidateManifestSha256': manifest_hash,
    'provenance': 'live',
    'observedAt': '2027-01-15T08:00:00Z',
    'status': status,
    'health': {'ok': status == 'COLLECTED', 'checks': [], 'blockedReasons': [] if status == 'COLLECTED' else ['FIXTURE_BLOCK']},
    'semantics': {},
    'raw': {},
}
if action == 'host':
    payload['source'] = 'web'
if os.environ.get('FAKE_SSH_PRIVATE') == '1':
    payload['raw'] = {'token': 'must-not-appear'}
print(json.dumps(payload, separators=(',', ':')))
sys.exit(0 if status == 'COLLECTED' else 2)
""",
            encoding="utf-8",
        )
        self.fake_ssh.chmod(0o755)
        self.config = client_module.ClientConfig(
            ssh_binary=self.fake_ssh,
            identity_file=self.identity,
            known_hosts_file=self.known_hosts,
            target="observer@fixture.invalid",
        )
        self.environment = {
            "STAGING_ACCEPT_RUN_ID": RUN_ID,
            "STAGING_ACCEPT_RUN_DIR": str(self.run_directory),
        }

    def tearDown(self):
        self.temporary.cleanup()

    def run_client(self, action="kafka", phase="start", window=0, **extra_environment):
        fake_environment = {
            "FAKE_SSH_INVOCATION": str(self.invocation),
            **extra_environment,
        }
        with mock.patch.dict(os.environ, fake_environment, clear=False):
            return client_module.execute(
                action, phase, window, self.environment, self.config
            )

    def test_fake_ssh_receives_recomputed_manifest_hash_and_evidence_is_atomic(self):
        self.assertEqual(0, self.run_client("host", "end", 3600))
        arguments = json.loads(self.invocation.read_text(encoding="utf-8"))
        expected_hash = "sha256:" + hashlib.sha256(self.manifest_content).hexdigest()
        self.assertEqual(expected_hash, arguments[arguments.index("--manifest-hash") + 1])
        self.assertEqual("observe", arguments[-11])
        self.assertIn("BatchMode=yes", arguments)
        self.assertIn("StrictHostKeyChecking=yes", arguments)
        evidence = self.run_directory / "observability" / "host-end.json"
        self.assertEqual("web", json.loads(evidence.read_text(encoding="utf-8"))["source"])
        self.assertEqual(0o600, stat.S_IMODE(evidence.stat().st_mode))
        self.assertEqual([], list(evidence.parent.glob(".host-end.json.*")))

    def test_blocked_remote_evidence_is_saved_and_exit_two_is_preserved(self):
        self.assertEqual(
            2,
            self.run_client(
                "redis", "peak", 21600, FAKE_SSH_STATUS="BLOCKED"
            ),
        )
        evidence = self.run_directory / "observability" / "redis-peak.json"
        self.assertEqual("BLOCKED", json.loads(evidence.read_text(encoding="utf-8"))["status"])

    def test_private_or_malformed_remote_output_is_not_written(self):
        with self.assertRaises(client_module.ClientError):
            self.run_client("web-traffic", "end", 86400, FAKE_SSH_PRIVATE="1")
        self.assertFalse(
            (self.run_directory / "observability" / "web-traffic-end.json").exists()
        )

    def test_symlinked_candidate_manifest_is_rejected_before_ssh(self):
        manifest = self.run_directory / "candidate-manifest.json"
        target = self.root / "manifest-target.json"
        target.write_bytes(self.manifest_content)
        manifest.unlink()
        manifest.symlink_to(target)
        with self.assertRaises(client_module.ClientError):
            self.run_client()
        self.assertFalse(self.invocation.exists())


if __name__ == "__main__":
    unittest.main()
