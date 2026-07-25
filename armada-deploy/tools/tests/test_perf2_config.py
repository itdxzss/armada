import tempfile
import unittest
from pathlib import Path

from perf2_loadtest.config import (
    ConfigError,
    load_perf2_profile,
    parse_args,
    parse_profile_assignments,
)


class RunOptionsTest(unittest.TestCase):
    def test_defaults_are_dry_run_and_perf2_only(self) -> None:
        options = parse_args(["--env", "perf2"])
        self.assertEqual("perf2", options.env)
        self.assertEqual("demo", options.tenant)
        self.assertFalse(options.execute)
        self.assertIsNone(options.expected_count)
        self.assertEqual(10, options.resume_concurrency)
        self.assertEqual(30, options.baseline_seconds)
        self.assertEqual(60, options.zero_window_seconds)
        self.assertEqual(1800, options.timeout_seconds)
        self.assertEqual(5, options.min_free_gib)

    def test_execute_requires_positive_expected_count(self) -> None:
        for argv in (
            ["--env", "perf2", "--execute"],
            ["--env", "perf2", "--execute", "--expected-count", "0"],
            ["--env", "perf2", "--expected-count", "3"],
        ):
            with self.subTest(argv=argv), self.assertRaises(ConfigError):
                parse_args(argv)

    def test_execute_accepts_matching_guard_shape(self) -> None:
        options = parse_args(
            ["--env", "perf2", "--tenant", "demo", "--execute", "--expected-count", "34"]
        )
        self.assertTrue(options.execute)
        self.assertEqual(34, options.expected_count)

    def test_rejects_other_environments_and_unsafe_values(self) -> None:
        invalid = (
            ["--env", "prod"],
            ["--env", "perf2", "--tenant", "bad tenant"],
            ["--env", "perf2", "--resume-concurrency", "0"],
            ["--env", "perf2", "--resume-concurrency", "33"],
            ["--env", "perf2", "--baseline-seconds", "0"],
        )
        for argv in invalid:
            with self.subTest(argv=argv), self.assertRaises(ConfigError):
                parse_args(argv)


class ProfileParserTest(unittest.TestCase):
    def test_parses_allowlisted_assignments_without_evaluating_shell(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "executed"
            profile = root / "perf2.conf"
            profile.write_text(
                "\n".join(
                    (
                        "ENV_ID=perf2",
                        'PROFILE_ARMADA_HOST="armada.example"',
                        "IGNORED_VALUE=$(touch %s)" % marker,
                    )
                ),
                encoding="utf-8",
            )
            values = parse_profile_assignments(profile)
            self.assertEqual("perf2", values["ENV_ID"])
            self.assertEqual("armada.example", values["PROFILE_ARMADA_HOST"])
            self.assertNotIn("IGNORED_VALUE", values)
            self.assertFalse(marker.exists())

    def test_rejects_shell_syntax_in_allowlisted_value(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory) / "perf2.conf"
            profile.write_text("ENV_ID=$(printf perf2)\n", encoding="utf-8")
            with self.assertRaises(ConfigError):
                parse_profile_assignments(profile)

    def test_rejects_duplicate_or_malformed_assignments(self) -> None:
        for contents in ("ENV_ID=perf2\nENV_ID=perf2\n", "export ENV_ID=perf2\n"):
            with self.subTest(contents=contents), tempfile.TemporaryDirectory() as directory:
                profile = Path(directory) / "perf2.conf"
                profile.write_text(contents, encoding="utf-8")
                with self.assertRaises(ConfigError):
                    parse_profile_assignments(profile)

    def test_loads_and_validates_perf2_profile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "armada-deploy/envs").mkdir(parents=True)
            (root / "keys").mkdir()
            (root / "keys/armada.pem").write_text("armada-secret", encoding="utf-8")
            (root / "keys/zhuan.pem").write_text("zhuan-secret", encoding="utf-8")
            (root / "armada-deploy/envs/perf2.conf").write_text(
                self._valid_profile(), encoding="utf-8"
            )

            profile = load_perf2_profile(root, "perf2")

            self.assertEqual("perf2", profile.env_id)
            self.assertEqual("armada.example", profile.armada.host)
            self.assertEqual("zhuan.example", profile.zhuan.host)
            self.assertEqual("armada.perf.protocol.android.message.commands.v1", profile.topic)
            self.assertEqual("armada-perf-android-zhuan-message-v1", profile.group_id)
            self.assertEqual(12, profile.expected_partitions)
            representation = repr(profile)
            self.assertNotIn("armada.example", representation)
            self.assertNotIn("armada.pem", representation)
            self.assertNotIn("armada-secret", representation)
            self.assertEqual(
                {
                    "env": "perf2",
                    "topic": "armada.perf.protocol.android.message.commands.v1",
                    "group": "armada-perf-android-zhuan-message-v1",
                    "partitions": 12,
                },
                profile.log_safe(),
            )

    def test_rejects_wrong_identity_contracts(self) -> None:
        replacements = {
            "environment": ("ENV_ID=perf2", "ENV_ID=prod"),
            "topic partitions": (
                "armada.perf.protocol.android.message.commands.v1=12",
                "armada.perf.protocol.android.message.commands.v1=6",
            ),
            "consumer group": (
                "armada-perf-android-zhuan-message-v1",
                "other-message-group",
            ),
            "remote directory": (
                "PROFILE_ZHUAN_REMOTE_DIR=/home/ec2-user/zhuan",
                "PROFILE_ZHUAN_REMOTE_DIR=/opt/zhuan",
            ),
        }
        for name, (old, new) in replacements.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "armada-deploy/envs").mkdir(parents=True)
                (root / "keys").mkdir()
                (root / "keys/armada.pem").touch()
                (root / "keys/zhuan.pem").touch()
                profile = self._valid_profile().replace(old, new)
                (root / "armada-deploy/envs/perf2.conf").write_text(profile, encoding="utf-8")
                with self.assertRaises(ConfigError):
                    load_perf2_profile(root, "perf2")

    @staticmethod
    def _valid_profile() -> str:
        return """
ENV_ID=perf2
PROFILE_ARMADA_HOST=armada.example
PROFILE_ARMADA_USER=ec2-user
PROFILE_ARMADA_KEY_REL=keys/armada.pem
PROFILE_ARMADA_REMOTE_DIR=/home/app/armada-deploy
PROFILE_ARMADA_COMPOSE_FILE=docker-compose.rds.yml
PROFILE_ARMADA_PUBLIC_URL=http://armada.example/
PROFILE_ZHUAN_HOST=zhuan.example
PROFILE_ZHUAN_USER=ec2-user
PROFILE_ZHUAN_KEY_REL=keys/zhuan.pem
PROFILE_ZHUAN_REMOTE_DIR=/home/ec2-user/zhuan
PROFILE_ZHUAN_COMPOSE_FILE=docker-compose.perf.yml
EXPECTED_KAFKA_TOPICS="other=3,armada.perf.protocol.android.message.commands.v1=12"
EXPECTED_KAFKA_GROUPS="other-group,armada-perf-android-zhuan-message-v1"
"""


if __name__ == "__main__":
    unittest.main()
