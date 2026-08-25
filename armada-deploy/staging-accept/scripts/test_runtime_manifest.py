import datetime as dt
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parent
GENERATOR = ROOT / "runtime-manifest.py"
CHECKER = ROOT / "preflight-manifest-check.py"
OBSERVER = ROOT / "runtime-artifact-observer.py"
SHAS = {
    "backend": "1" * 40,
    "frontend": "2" * 40,
    "webProtocol": "3" * 40,
    "androidProtocol": "4" * 40,
}


def artifact(commit: str, suffix: str, observed_at: str | None = None) -> dict:
    return {
        "kind": "artifact-sha256",
        "identity": f"sha256:{suffix * 64}",
        "observedCommit": commit,
        "observedAt": observed_at
        or dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
    }


class RuntimeManifestTest(unittest.TestCase):
    def test_generates_atomic_manifest_only_from_explicit_artifacts_and_commits(self):
        artifacts = {
            "schemaVersion": 1,
            "environment": "test1",
            "components": {
                "backend": {"artifact": artifact(SHAS["backend"], "a")},
                "frontend": {"artifact": artifact(SHAS["frontend"], "b")},
                "webProtocol": {"artifact": artifact(SHAS["webProtocol"], "c")},
                "androidProtocol": {
                    "artifacts": [
                        {"role": role, **artifact(SHAS["androidProtocol"], suffix)}
                        for role, suffix in (
                            ("coordinator", "d"),
                            ("node-01", "e"),
                            ("node-02", "f"),
                            ("node-03", "0"),
                        )
                    ]
                },
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            artifact_path = Path(directory) / "artifacts.json"
            manifest_path = Path(directory) / "runtime.json"
            artifact_path.write_text(json.dumps(artifacts), encoding="utf-8")
            common = [
                "--backend-sha",
                SHAS["backend"],
                "--frontend-sha",
                SHAS["frontend"],
                "--web-protocol-sha",
                SHAS["webProtocol"],
                "--android-protocol-sha",
                SHAS["androidProtocol"],
            ]
            generated = subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    "--environment",
                    "test1",
                    "--artifacts",
                    str(artifact_path),
                    "--output",
                    str(manifest_path),
                    *common,
                    "--max-age-seconds",
                    "300",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, generated.returncode, generated.stderr)
            checked = subprocess.run(
                [
                    sys.executable,
                    str(CHECKER),
                    "--manifest",
                    str(manifest_path),
                    "--environment",
                    "test1",
                    "--max-age-seconds",
                    "300",
                    "--android-role",
                    "coordinator",
                    "--android-role",
                    "node-01",
                    "--android-role",
                    "node-02",
                    "--android-role",
                    "node-03",
                    *common,
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        self.assertEqual(0, checked.returncode, checked.stderr)
        self.assertEqual("sha256:" + "a" * 64, manifest["components"]["backend"]["artifact"]["identity"])

    def test_rejects_missing_observed_artifact_instead_of_guessing(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact_path = Path(directory) / "artifacts.json"
            manifest_path = Path(directory) / "runtime.json"
            artifact_path.write_text(
                json.dumps({"schemaVersion": 1, "environment": "test1", "components": {}}),
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    "--environment",
                    "test1",
                    "--artifacts",
                    str(artifact_path),
                    "--output",
                    str(manifest_path),
                    "--backend-sha",
                    SHAS["backend"],
                    "--frontend-sha",
                    SHAS["frontend"],
                    "--web-protocol-sha",
                    SHAS["webProtocol"],
                    "--android-protocol-sha",
                    SHAS["androidProtocol"],
                    "--max-age-seconds",
                    "300",
                ],
                check=False,
                capture_output=True,
                text=True,
            )

        self.assertEqual(40, completed.returncode)
        self.assertFalse(manifest_path.exists())

    def assert_generator_rejects_observation(self, observed_at: str, expected_error: str):
        artifacts = {
            "schemaVersion": 1,
            "environment": "test1",
            "components": {
                "backend": {"artifact": artifact(SHAS["backend"], "a", observed_at)},
                "frontend": {"artifact": artifact(SHAS["frontend"], "b")},
                "webProtocol": {"artifact": artifact(SHAS["webProtocol"], "c")},
                "androidProtocol": {
                    "artifacts": [
                        {"role": "coordinator", **artifact(SHAS["androidProtocol"], "d")}
                    ]
                },
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            artifact_path = Path(directory) / "artifacts.json"
            manifest_path = Path(directory) / "runtime.json"
            artifact_path.write_text(json.dumps(artifacts), encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    "--environment",
                    "test1",
                    "--artifacts",
                    str(artifact_path),
                    "--output",
                    str(manifest_path),
                    "--backend-sha",
                    SHAS["backend"],
                    "--frontend-sha",
                    SHAS["frontend"],
                    "--web-protocol-sha",
                    SHAS["webProtocol"],
                    "--android-protocol-sha",
                    SHAS["androidProtocol"],
                    "--max-age-seconds",
                    "300",
                ],
                check=False,
                capture_output=True,
                text=True,
            )

        self.assertEqual(40, completed.returncode)
        self.assertIn(expected_error, completed.stderr)
        self.assertFalse(manifest_path.exists())

    def test_rejects_stale_artifact_observation_instead_of_refreshing_it(self):
        stale = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(seconds=301)).isoformat().replace(
            "+00:00", "Z"
        )
        self.assert_generator_rejects_observation(stale, "observedAt is stale")

    def test_rejects_artifact_observation_too_far_in_the_future(self):
        future = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=31)).isoformat().replace(
            "+00:00", "Z"
        )
        self.assert_generator_rejects_observation(
            future, "observedAt is too far in the future"
        )

    def test_live_observer_hashes_only_explicit_local_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifacts = {}
            for name, body in (
                ("backend", b"backend-runtime"),
                ("frontend", b"frontend-runtime"),
                ("web", b"web-runtime"),
                ("android", b"android-runtime"),
            ):
                path = root / f"{name}.artifact"
                path.write_bytes(body)
                artifacts[name] = path
            output = root / "observed.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(OBSERVER),
                    "--environment",
                    "test1",
                    "--output",
                    str(output),
                    "--backend-artifact",
                    str(artifacts["backend"]),
                    "--backend-commit",
                    SHAS["backend"],
                    "--frontend-artifact",
                    str(artifacts["frontend"]),
                    "--frontend-commit",
                    SHAS["frontend"],
                    "--web-protocol-artifact",
                    str(artifacts["web"]),
                    "--web-protocol-commit",
                    SHAS["webProtocol"],
                    "--android-artifact",
                    f"coordinator={artifacts['android']}",
                    "--android-protocol-commit",
                    SHAS["androidProtocol"],
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            payload = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(0, completed.returncode, completed.stderr)
        backend = payload["components"]["backend"]["artifact"]
        self.assertEqual(
            "sha256:" + hashlib.sha256(b"backend-runtime").hexdigest(), backend["identity"]
        )
        self.assertEqual(
            {"kind", "identity", "observedCommit", "observedAt"}, set(backend)
        )
        self.assertNotIn("path", json.dumps(payload).lower())


if __name__ == "__main__":
    unittest.main()
