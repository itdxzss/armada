#!/usr/bin/env python3
"""续期的暂停/恢复边界和真实 OpenSSL 证书安装验证，不连接 ACME 或 Docker。"""
import importlib.util
import json
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("renew_tls", Path(__file__).with_name("renew-tls.py"))
TLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TLS)


class CertificateRenewalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="armada-acme-unit-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config = {"acmePath": str(self.root), "tlsPath": str(self.root / "installed"),
                       "hostname": "ingest.test", "composeProject": "test-project",
                       "legoPath": "/not-a-real-lego", "accountId": "unit-test-only"}

    def test_fresh_certificate_does_not_pause_gateway(self):
        with patch.object(TLS.subprocess, "run", return_value=Mock(returncode=0)), \
                patch.object(TLS, "pause") as pause:
            TLS.renew(self.config)
            pause.assert_not_called()

    def test_acme_failure_restores_gateway_without_installing(self):
        failure = subprocess.CalledProcessError(1, ["unit-acme-failure"])
        with patch.object(TLS.subprocess, "run", side_effect=[Mock(returncode=1), failure]), \
                patch.object(TLS, "pause"), patch.object(TLS, "restore") as restore, \
                patch.object(TLS, "install") as install:
            with self.assertRaises(subprocess.CalledProcessError):
                TLS.renew(self.config)
            restore.assert_called_once_with(self.config)
            install.assert_not_called()

    def test_foreign_port_occupation_still_restores_paused_gateway(self):
        with patch.object(TLS.subprocess, "run", return_value=Mock(returncode=1)), \
                patch.object(TLS, "pause", side_effect=OSError("port occupied")), \
                patch.object(TLS, "restore") as restore:
            with self.assertRaises(OSError):
                TLS.renew(self.config)
            restore.assert_called_once_with(self.config)

    def test_restore_rejects_unrelated_container(self):
        marker = self.root / "paused-containers.json"
        marker.write_text('["unrelated"]')
        with patch.object(TLS, "run", return_value='{"com.docker.compose.service":"nginx"}') as run:
            with self.assertRaises(RuntimeError):
                TLS.restore(self.config)
            self.assertEqual(run.call_count, 1)
            self.assertTrue(marker.exists())

    def test_restore_is_idempotent_and_only_starts_recorded_gateway(self):
        marker = self.root / "paused-containers.json"
        marker.write_text('["owned"]')
        labels = {"com.docker.compose.service": "device-ingest-nginx",
                  "com.docker.compose.project": "test-project"}
        with patch.object(TLS, "run", side_effect=[json.dumps(labels), "owned"]) as run:
            TLS.restore(self.config)
            TLS.restore(self.config)
            self.assertEqual(run.call_count, 2)
            run.assert_called_with("docker", "start", "owned")
            self.assertFalse(marker.exists())

    def test_install_checks_real_chain_hostname_key_and_permissions(self):
        source = self.root / "certificates"
        source.mkdir()
        certificate, key = source / "ingest.test.crt", source / "ingest.test.key"
        TLS.run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                "-subj", "/CN=ingest.test", "-addext", "subjectAltName=DNS:ingest.test",
                "-keyout", str(key), "-out", str(certificate))
        (source / "ingest.test.issuer.crt").write_bytes(certificate.read_bytes())
        with patch.dict(os.environ, {"SSL_CERT_FILE": str(certificate)}):
            TLS.install(self.config)
            target = Path(self.config["tlsPath"])
            self.assertEqual((target / "fullchain.pem").read_bytes(), certificate.read_bytes())
            self.assertEqual((target / "privkey.pem").stat().st_mode & 0o777, 0o600)
            before = (target / "privkey.pem").read_bytes()
            TLS.run("openssl", "genrsa", "-out", str(key), "2048")
            with self.assertRaises(ssl.SSLError):
                TLS.install(self.config)
            self.assertEqual((target / "privkey.pem").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
