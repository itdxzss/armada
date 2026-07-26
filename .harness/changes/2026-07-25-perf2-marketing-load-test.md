# Perf2 marketing load-test tooling

Date: 2026-07-25

## Scope

- Added a standalone, read-only Go monitor for host/container CPU and memory plus Kafka produced, consumed, and lag rates.
- Added a perf2-only Python/Bash orchestrator that inventories paused marketing tasks through the existing API.
- The default mode is dry-run. State-changing resume requires both `--execute` and a fresh matching `--expected-count`.
- Resume uses the existing HTTP API once per frozen task ID. No SQL, direct database update, business-code change, or service restart was added.
- The live Zhuan configuration must match the expected topic, consumer group, and 12-partition contract before execution can reach any resume request.

## Repositories and commits

Armada (`1.0.1-snapshot`, head `685a11b`):

- `bfcd43d` guard perf2 load-test configuration
- `2d2ab4c` inventory and resume one frozen task snapshot
- `cd160bb` summarize Kafka and resource peaks
- `9643980` stream perf2 monitors over SSH
- `22da490` orchestrate the perf2 load test
- `a6f481f` add the stable Bash entry point
- `6ab2e41` harden profile paths and uploaded monitor permissions
- `fae947b` close observation, reporting, and signal-handling gaps
- `7e0aa99` separate peak observation from the post-resume idle boundary
- `1b13eaa` restart the full baseline candidate after startup gaps
- `3c6284f` preserve stable monitor/report failure classes
- `685a11b` preserve stable remote failure classes

Zhuan (`1.0.1-snapshot`, head `d35a99b`):

- `0e0b7de` calculate Kafka and CPU rates
- `a71ae3c` sample Kafka consumer lag through read-only admin APIs
- `6ba9707` sample host and container resources
- `5e35c8c` add the standalone monitor command
- `bd1ed06` align recurring samples to wall-clock boundaries
- `78da771` harden read-only sampling and error redaction
- `d35a99b` verify the live Kafka topic and group identity

## Local verification

Passed:

- 50 perf2 Python unit tests.
- Python bytecode compilation for the package and tests.
- Bash syntax and wrapper mutation-guard tests.
- Existing `deploy-test.test.sh` and `package-prod.test.sh` regression suites.
- Go tests and race tests for `internal/perfmonitor` and `cmd/perf-monitor`.
- Targeted Go vet and a CGO-disabled Linux amd64 monitor build.
- Full Zhuan `go build ./...`.
- Git whitespace checks and mutation-boundary/static command audits.

Known pre-existing full-repository Zhuan failures remain outside this change: an appstate test compile mismatch, existing vet findings, deployment/Redis-prefix expectations, sandbox-blocked local listeners, and existing Noise test/vector failures. The newly added monitor packages pass independently.

## Remote verification

Status: incomplete read-only dry-run; stopped without retry or further implementation at user request.

- Final run: `20260725T041153Z-5f63a8c9`.
- Result: `monitor_timeout`; 30 consecutive aligned baseline seconds were not obtained within the 90-second baseline deadline.
- Both expected containers passed architecture, health, Docker stats, and minimum free-disk preflight checks.
- The uploaded monitor passed its one-shot live Topic/group/12-partition probe.
- Monitor SHA-256: `fb09d12602bd47e64caeb620292a68b15a60a5fbef5f2c68249c47fd43124736`.
- No valid final baseline, peak metrics, or frozen paused-task aggregate was produced. The zero task count in the incomplete summary is not a live paused-task count.
- No resume POST was sent and no task was resumed.
- Artifacts: `armada-deploy/perf-results/20260725T041153Z-5f63a8c9/`.
- Artifact permissions are restricted and the final artifact secret scan passed.

Bulk resume remains separately gated: a successful dry-run and a fresh explicit instruction using the observed paused-task count are required before `--execute` may be used.
