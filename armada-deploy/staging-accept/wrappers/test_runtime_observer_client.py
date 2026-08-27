from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


ROOT = Path(__file__).resolve().parent
MODULE_PATH = ROOT / "runtime-observer-client.py"
RUN_ID = "20260826T010203Z-a1b2c3d4"


def load_module():
    spec = importlib.util.spec_from_file_location("runtime_observer_client", MODULE_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load runtime observer client")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class RuntimeObserverClientTest(unittest.TestCase):
    def setUp(self) -> None:
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.run_root = self.root / "runs"
        self.run_dir = self.run_root / RUN_ID
        self.run_dir.mkdir(parents=True)
        self.source = self.root / "runtime-manifest-source.json"
        self.content = (
            json.dumps(
                {
                    "schemaVersion": 1,
                    "environment": "test1",
                    "generatedAt": "2026-08-26T01:00:00Z",
                    "components": {},
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode("utf-8")
        self.source.write_bytes(self.content)
        self.source.chmod(0o644)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def environment(self) -> dict[str, str]:
        return {
            "STAGING_ACCEPT_RUN_ID": RUN_ID,
            "STAGING_ACCEPT_RUN_DIR": str(self.run_dir),
            "STAGING_ACCEPT_TEST_MODE": "1",
            "STAGING_ACCEPT_TEST_RUNTIME_MANIFEST_SOURCE": str(self.source),
            "STAGING_ACCEPT_TEST_RUN_ROOT": str(self.run_root),
        }

    def assert_blocked(self, environment: dict[str, str] | None = None) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = self.module.main([], environment or self.environment())
        self.assertEqual(40, status)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual("runtime-observer-client: blocked\n", stderr.getvalue())

    def test_atomically_copies_manifest_with_private_mode(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            status = self.module.main([], self.environment())

        destination = self.run_dir / "runtime-manifest.json"
        self.assertEqual(0, status)
        self.assertEqual("", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())
        self.assertEqual(self.content, destination.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(destination.stat().st_mode))
        self.assertEqual([], list(self.run_dir.glob(".runtime-manifest.json.*")))

    def test_source_symlink_unsafe_permissions_or_non_owner_is_blocked(self) -> None:
        target = self.root / "manifest-target.json"
        target.write_bytes(self.content)
        self.source.unlink()
        self.source.symlink_to(target)
        self.assert_blocked()

        self.source.unlink()
        self.source.write_bytes(self.content)
        self.source.chmod(0o666)
        self.assert_blocked()

        self.source.chmod(0o644)
        original_fstat = os.fstat

        def non_owner(fd: int):
            metadata = original_fstat(fd)
            return SimpleNamespace(
                st_mode=metadata.st_mode,
                st_uid=metadata.st_uid + 1,
                st_size=metadata.st_size,
                st_dev=metadata.st_dev,
                st_ino=metadata.st_ino,
                st_mtime_ns=metadata.st_mtime_ns,
            )

        with mock.patch.object(self.module.os, "fstat", side_effect=non_owner):
            self.assert_blocked()

    def test_source_size_and_strict_top_level_json_are_enforced(self) -> None:
        for content in (
            b"x" * (64 * 1024 + 1),
            b'{"schemaVersion":1,"schemaVersion":1,"environment":"test1",'
            b'"generatedAt":"now","components":{}}',
            b'{"schemaVersion":1,"environment":"test1","generatedAt":"now",'
            b'"components":{},"extra":true}',
            b'{"schemaVersion":true,"environment":"test1","generatedAt":"now",'
            b'"components":{}}',
        ):
            with self.subTest(content=content[:40]):
                self.source.write_bytes(content)
                self.source.chmod(0o644)
                self.assert_blocked()

    def test_run_directory_must_be_canonical_direct_child_matching_run_id(self) -> None:
        valid = self.environment()
        cases = []

        relative = dict(valid)
        relative["STAGING_ACCEPT_RUN_DIR"] = RUN_ID
        cases.append(relative)

        mismatch = dict(valid)
        mismatch["STAGING_ACCEPT_RUN_ID"] = "20260826T010204Z-b1b2c3d4"
        cases.append(mismatch)

        sibling = self.root / RUN_ID
        sibling.mkdir()
        outside = dict(valid)
        outside["STAGING_ACCEPT_RUN_DIR"] = str(sibling)
        cases.append(outside)

        target = self.run_root / "target"
        target.mkdir()
        alias = self.run_root / "20260826T010205Z-c1b2c3d4"
        alias.symlink_to(target, target_is_directory=True)
        symlink = dict(valid)
        symlink["STAGING_ACCEPT_RUN_ID"] = alias.name
        symlink["STAGING_ACCEPT_RUN_DIR"] = str(alias)
        cases.append(symlink)

        for environment in cases:
            with self.subTest(run_dir=environment["STAGING_ACCEPT_RUN_DIR"]):
                self.assert_blocked(environment)

    def test_different_existing_manifest_is_preserved_and_same_is_idempotent(self) -> None:
        destination = self.run_dir / "runtime-manifest.json"
        different = b'{"doNotOverwrite":true}\n'
        destination.write_bytes(different)
        destination.chmod(0o600)
        self.assert_blocked()
        self.assertEqual(different, destination.read_bytes())

        destination.unlink()
        destination.write_bytes(self.content)
        destination.chmod(0o600)
        before = destination.stat()
        status = self.module.main([], self.environment())
        after = destination.stat()
        self.assertEqual(0, status)
        self.assertEqual((before.st_dev, before.st_ino, before.st_mtime_ns),
                         (after.st_dev, after.st_ino, after.st_mtime_ns))

    def test_test_path_overrides_require_explicit_test_mode(self) -> None:
        environment = self.environment()
        environment.pop("STAGING_ACCEPT_TEST_MODE")
        with self.assertRaises(self.module.ClientError):
            self.module.config_from_environment(environment)

        config = self.module.config_from_environment(self.environment())
        self.assertTrue(config.test_mode)
        self.assertEqual(self.source, config.source)
        self.assertEqual(self.run_root, config.run_root)

    def test_arguments_are_rejected_without_echoing_manifest(self) -> None:
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            status = self.module.main(["--source", str(self.source)], self.environment())
        self.assertEqual(40, status)
        self.assertEqual("runtime-observer-client: blocked\n", stderr.getvalue())
        self.assertNotIn("components", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
