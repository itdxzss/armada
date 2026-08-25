#!/usr/bin/env python3

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path


EXIT_RUNTIME_EVIDENCE = 40
EXIT_REVISION_MISMATCH = 41
FULL_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
SHA256_IDENTITY = re.compile(r"^sha256:[0-9a-fA-F]{64}$")
SAFE_ROLE = re.compile(r"^[a-z][a-z0-9-]{0,63}$")
COMPONENTS = ("backend", "frontend", "webProtocol", "androidProtocol")
ARTIFACT_KINDS = {"artifact-sha256", "docker-image-id", "oci-image-digest", "runtime-revision"}
MAX_MANIFEST_FUTURE_SKEW_SECONDS = 30


class ManifestBlocked(Exception):
    pass


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ManifestBlocked(f"duplicate key {key}")
        value[key] = item
    return value


def require_object(value, label):
    if not isinstance(value, dict):
        raise ManifestBlocked(f"{label} must be an object")
    return value


def require_exact_keys(value, expected, label):
    actual = set(value)
    missing = sorted(expected - actual)
    unknown = sorted(actual - expected)
    if missing:
        raise ManifestBlocked(f"{label} missing {','.join(missing)}")
    if unknown:
        raise ManifestBlocked(f"{label} contains unknown {','.join(unknown)}")


def require_full_sha(value, label):
    if not isinstance(value, str) or FULL_SHA.fullmatch(value) is None:
        raise ManifestBlocked(f"{label} must be a full 40-character Git commit")
    return value.lower()


def validate_observed_at(value, max_age_seconds, label="generatedAt"):
    if not isinstance(value, str):
        raise ManifestBlocked(f"{label} must be an RFC3339 timestamp")
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ManifestBlocked(f"{label} must be an RFC3339 timestamp") from error
    if parsed.tzinfo is None:
        raise ManifestBlocked(f"{label} must include a timezone")
    now = dt.datetime.now(dt.timezone.utc)
    age_seconds = (now - parsed.astimezone(dt.timezone.utc)).total_seconds()
    if age_seconds > max_age_seconds:
        raise ManifestBlocked(f"{label} is stale")
    if age_seconds < -MAX_MANIFEST_FUTURE_SKEW_SECONDS:
        raise ManifestBlocked(f"{label} is too far in the future")


def validate_artifact(value, label, require_role, max_age_seconds):
    artifact = require_object(value, label)
    required = {"kind", "identity", "observedCommit", "observedAt"}
    if require_role:
        required.add("role")
    require_exact_keys(artifact, required, label)

    kind = artifact["kind"]
    identity = artifact["identity"]
    observed = require_full_sha(artifact["observedCommit"], f"{label}.observedCommit")
    validate_observed_at(artifact["observedAt"], max_age_seconds, f"{label}.observedAt")
    if not isinstance(kind, str) or kind not in ARTIFACT_KINDS:
        raise ManifestBlocked(f"{label}.kind is unsupported")
    if kind == "runtime-revision":
        identity_commit = require_full_sha(identity, f"{label}.identity")
        if identity_commit != observed:
            raise ManifestBlocked(f"{label}.identity does not match observedCommit")
    elif not isinstance(identity, str) or SHA256_IDENTITY.fullmatch(identity) is None:
        raise ManifestBlocked(f"{label}.identity must be sha256:<64 hexadecimal characters>")

    role = "runtime"
    if require_role:
        role = artifact["role"]
        if not isinstance(role, str) or SAFE_ROLE.fullmatch(role) is None:
            raise ManifestBlocked(f"{label}.role is invalid")
    return {"kind": kind, "identity": identity.lower(), "observed": observed, "role": role}


def validate_component(name, value, max_age_seconds):
    component = require_object(value, f"components.{name}")
    if name == "androidProtocol":
        require_exact_keys(component, {"expectedCommit", "artifacts"}, f"components.{name}")
        artifacts = component["artifacts"]
        if not isinstance(artifacts, list) or not artifacts:
            raise ManifestBlocked("components.androidProtocol.artifacts must be a non-empty array")
        observed_artifacts = [
            validate_artifact(
                item,
                f"components.androidProtocol.artifacts[{index}]",
                True,
                max_age_seconds,
            )
            for index, item in enumerate(artifacts)
        ]
        roles = [item["role"] for item in observed_artifacts]
        if len(roles) != len(set(roles)):
            raise ManifestBlocked("components.androidProtocol.artifacts contains duplicate roles")
    else:
        require_exact_keys(component, {"expectedCommit", "artifact"}, f"components.{name}")
        observed_artifacts = [
            validate_artifact(
                component["artifact"], f"components.{name}.artifact", False, max_age_seconds
            )
        ]
    return require_full_sha(component["expectedCommit"], f"components.{name}.expectedCommit"), observed_artifacts


def parse_args():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--environment", required=True)
    parser.add_argument("--backend-sha", required=True)
    parser.add_argument("--frontend-sha", required=True)
    parser.add_argument("--web-protocol-sha", required=True)
    parser.add_argument("--android-protocol-sha", required=True)
    parser.add_argument("--android-role", action="append", required=True)
    parser.add_argument("--max-age-seconds", required=True, type=int)
    return parser.parse_args()


def load_manifest(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except ManifestBlocked:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ManifestBlocked("manifest is not readable strict JSON") from error


def check_manifest(args):
    manifest = require_object(load_manifest(Path(args.manifest)), "manifest")
    require_exact_keys(
        manifest,
        {"schemaVersion", "environment", "generatedAt", "components"},
        "manifest",
    )
    if type(manifest["schemaVersion"]) is not int or manifest["schemaVersion"] != 1:
        raise ManifestBlocked("schemaVersion must be 1")
    if not isinstance(manifest["environment"], str):
        raise ManifestBlocked("environment must be a string")
    if manifest["environment"] != args.environment:
        raise ManifestBlocked(
            f"environment mismatch expected={args.environment} observed={manifest['environment']}"
        )
    if args.max_age_seconds <= 0:
        raise ManifestBlocked("max age must be a positive integer")
    validate_observed_at(manifest["generatedAt"], args.max_age_seconds)

    components = require_object(manifest["components"], "components")
    require_exact_keys(components, set(COMPONENTS), "components")
    requested = {
        "backend": require_full_sha(args.backend_sha, "requested backend SHA"),
        "frontend": require_full_sha(args.frontend_sha, "requested frontend SHA"),
        "webProtocol": require_full_sha(args.web_protocol_sha, "requested webProtocol SHA"),
        "androidProtocol": require_full_sha(args.android_protocol_sha, "requested androidProtocol SHA"),
    }

    parsed = {}
    for name in COMPONENTS:
        parsed[name] = validate_component(name, components[name], args.max_age_seconds)

    expected_android_roles = set(args.android_role)
    if len(expected_android_roles) != len(args.android_role):
        raise ManifestBlocked("requested Android roles contain duplicates")
    for role in expected_android_roles:
        if SAFE_ROLE.fullmatch(role) is None:
            raise ManifestBlocked("requested Android role is invalid")
    observed_android_roles = {artifact["role"] for artifact in parsed["androidProtocol"][1]}
    if observed_android_roles != expected_android_roles:
        missing = sorted(expected_android_roles - observed_android_roles)
        unexpected = sorted(observed_android_roles - expected_android_roles)
        details = []
        if missing:
            details.append(f"missing={','.join(missing)}")
        if unexpected:
            details.append(f"unexpected={','.join(unexpected)}")
        raise ManifestBlocked(f"Android roles do not match expected set {' '.join(details)}")

    mismatch = False
    for name in COMPONENTS:
        declared_expected, artifacts = parsed[name]
        candidate = requested[name]
        if declared_expected != candidate:
            print(
                f"VERSION {name} FAIL requested={candidate} declaredExpected={declared_expected}",
                file=sys.stderr,
            )
            mismatch = True
        for artifact in artifacts:
            if artifact["observed"] != declared_expected:
                print(
                    f"VERSION {name} FAIL role={artifact['role']} expected={declared_expected} "
                    f"observed={artifact['observed']} kind={artifact['kind']} "
                    f"identity={artifact['identity']}",
                    file=sys.stderr,
                )
                mismatch = True
            elif declared_expected == candidate:
                print(
                    f"VERSION {name} PASS role={artifact['role']} expected={candidate} "
                    f"observed={artifact['observed']} kind={artifact['kind']} "
                    f"identity={artifact['identity']}"
                )
    return EXIT_REVISION_MISMATCH if mismatch else 0


def main():
    try:
        return check_manifest(parse_args())
    except ManifestBlocked as error:
        print(f"MANIFEST BLOCKED {error}", file=sys.stderr)
        return EXIT_RUNTIME_EVIDENCE


if __name__ == "__main__":
    sys.exit(main())
