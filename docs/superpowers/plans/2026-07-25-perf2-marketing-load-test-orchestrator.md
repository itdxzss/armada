# Perf2 Marketing Resume Load-Test Orchestrator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fail-closed `perf2` operations tool that inventories paused marketing tasks, proves a clean Kafka/resource baseline, optionally resumes one frozen task snapshot through the existing API, and produces auditable peak/lag/resource reports.

**Architecture:** A small Bash entry point invokes a Python 3 standard-library package. The Python orchestrator parses the checked-in `perf2` profile without evaluating shell, deploys the standalone Go monitor from the Zhuan plan to both hosts, maintains two long-lived SSH streams, calls the Armada HTTP API, and writes a run-scoped artifact set. Business mutation is isolated behind an injected `TaskAPI.resume_snapshot_once` boundary and can only be reached when `--execute`, a matching `--expected-count`, and all preflight gates are present.

**Tech Stack:** Bash, Python 3.11+ standard library (`argparse`, `concurrent.futures`, `dataclasses`, `json`, `subprocess`, `urllib`), `unittest`, the Go `perf-monitor` binary, SSH/SCP.

---

## File map

- Modify `.gitignore`: ignore generated `armada-deploy/perf-results/` run artifacts.
- Create `armada-deploy/tools/perf2-marketing-load-test.sh`: stable executable entry point.
- Create `armada-deploy/tools/perf2_loadtest/__init__.py`: package marker.
- Create `armada-deploy/tools/perf2_loadtest/model.py`: immutable profile, task, monitor, resume, and summary records.
- Create `armada-deploy/tools/perf2_loadtest/config.py`: CLI and non-evaluating `perf2.conf` parser.
- Create `armada-deploy/tools/perf2_loadtest/task_api.py`: read-only inventory, one-shot concurrent resume, and reconciliation.
- Create `armada-deploy/tools/perf2_loadtest/report.py`: sample alignment, stop window, CSV, percentile, and summary calculations.
- Create `armada-deploy/tools/perf2_loadtest/remote.py`: monitor build/upload/probe and long-lived SSH process management.
- Create `armada-deploy/tools/perf2_loadtest/orchestrator.py`: fail-closed dry-run/execute state machine.
- Create `armada-deploy/tools/perf2_loadtest/cli.py`: dependency wiring, signals, output, and exit codes.
- Create `armada-deploy/tools/tests/__init__.py`: test package marker.
- Create `armada-deploy/tools/tests/test_perf2_config.py`: CLI/profile safety contracts.
- Create `armada-deploy/tools/tests/test_perf2_task_api.py`: API snapshot/resume/reconciliation contracts.
- Create `armada-deploy/tools/tests/test_perf2_report.py`: rate alignment and summary contracts.
- Create `armada-deploy/tools/tests/test_perf2_remote.py`: command construction, privacy, and stream lifecycle contracts.
- Create `armada-deploy/tools/tests/test_perf2_orchestrator.py`: end-to-end state-machine tests with fakes.
- Create `armada-deploy/tools/perf2-marketing-load-test.test.sh`: wrapper smoke and mutation-guard test.
- Create `.harness/changes/2026-07-25-perf2-marketing-load-test.md`: non-secret implementation and verification evidence.

## Fixed external contracts

The implementation must use these existing API contracts and must not add or change backend endpoints:

```text
GET  /api/marketing-tasks?status=5&page=1&pageSize=1000
GET  /api/marketing-tasks?id=<taskId>&page=1&pageSize=1
POST /api/marketing-tasks/<taskId>/resume
X-Tenant-Code: <explicit --tenant value>
```

An API response is successful only when HTTP is 2xx, JSON is an object, and top-level `code == 0`. The list payload is `data.list`; task fields use the existing camel-case names `id`, `taskName`, `status`, `selectedAccountCount`, `targetGroupCount`, `targetPairCount`, `sendIntervalSeconds`, `taskStartAt`, and `taskEndAt`.

The monitor JSONL contract comes from the Zhuan plan: `schemaVersion`, `at`, `node`, optional `kafka`, and `resource`. Only the `zhuan` stream carries Kafka metrics; the `armada` monitor is launched with `-no-kafka`.

### Task 1: Add strict profile parsing and CLI mutation guards

**Files:**
- Modify: `.gitignore`
- Create: `armada-deploy/tools/perf2_loadtest/__init__.py`
- Create: `armada-deploy/tools/perf2_loadtest/model.py`
- Create: `armada-deploy/tools/perf2_loadtest/config.py`
- Create: `armada-deploy/tools/tests/__init__.py`
- Create: `armada-deploy/tools/tests/test_perf2_config.py`

- [ ] **Step 1: Write failing parser and CLI tests**

Use `tempfile.TemporaryDirectory` and write minimal profile fixtures. Cover all of these contracts:

- only `--env perf2` is accepted;
- default mode is dry-run and does not require `--expected-count`;
- `--execute` requires a positive `--expected-count`;
- `--expected-count` without `--execute` is rejected to prevent false confidence;
- tenant must match `[A-Za-z0-9][A-Za-z0-9_.-]{0,63}` and defaults to `demo`;
- resume concurrency defaults to 10 and is restricted to 1..32;
- baseline/zero-window/timeout defaults are 30/60/1800 seconds;
- only the allowlisted profile keys are parsed, quoted values are decoded with `shlex.split`, duplicate keys fail, and command substitutions/backticks are rejected as plain invalid syntax rather than executed;
- `ENV_ID`, expected Topic/group declarations, host/user, absolute `/home/...` remote directory, and existing key file must all validate;
- profile or key contents never appear in `repr`, raised exceptions, or log-safe dictionaries.

Define and assert these exact records:

```python
@dataclass(frozen=True)
class SSHProfile:
    host: str
    user: str
    key_path: Path
    remote_dir: PurePosixPath
    compose_file: str

@dataclass(frozen=True)
class Perf2Profile:
    env_id: str
    armada: SSHProfile
    zhuan: SSHProfile
    public_url: str
    topic: str
    group_id: str
    expected_partitions: int

@dataclass(frozen=True)
class RunOptions:
    env: str
    tenant: str
    execute: bool
    expected_count: int | None
    resume_concurrency: int
    baseline_seconds: int
    zero_window_seconds: int
    timeout_seconds: int
    min_free_gib: int
```

- [ ] **Step 2: Run the tests to prove the package is missing**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_config -v
```

Expected: FAIL with missing `perf2_loadtest.config`/model symbols.

- [ ] **Step 3: Implement the minimal strict parser**

Expose:

```python
ALLOWED_PROFILE_KEYS: frozenset[str]
def parse_args(argv: Sequence[str]) -> RunOptions
def parse_profile_assignments(path: Path) -> Mapping[str, str]
def load_perf2_profile(repo_root: Path, env: str) -> Perf2Profile
```

Do not `source` or execute the `.conf`. Parse `KEY=value` lines, reject any non-comment/non-blank line outside that grammar, and allow exactly zero or one shell word on the right through `shlex.split`. Derive Topic and group from `EXPECTED_KAFKA_TOPICS`/`EXPECTED_KAFKA_GROUPS`, requiring exactly `armada.perf.protocol.android.message.commands.v1=12` and `armada-perf-android-zhuan-message-v1`. Resolve key paths under repository root and reject traversal outside it.

Add this exact ignore entry:

```gitignore
/armada-deploy/perf-results/
```

- [ ] **Step 4: Verify and commit**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_config -v
python3 -m py_compile tools/perf2_loadtest/*.py tools/tests/test_perf2_config.py
cd ..
git diff --check -- .gitignore armada-deploy/tools
git add .gitignore armada-deploy/tools/perf2_loadtest/__init__.py armada-deploy/tools/perf2_loadtest/model.py armada-deploy/tools/perf2_loadtest/config.py armada-deploy/tools/tests/__init__.py armada-deploy/tools/tests/test_perf2_config.py
git commit -m "feat(ops): guard perf2 load-test configuration"
```

Expected: tests PASS and generated results remain ignored.

### Task 2: Inventory and resume one frozen API snapshot

**Files:**
- Modify: `armada-deploy/tools/perf2_loadtest/model.py`
- Create: `armada-deploy/tools/perf2_loadtest/task_api.py`
- Create: `armada-deploy/tools/tests/test_perf2_task_api.py`

- [ ] **Step 1: Write failing HTTP boundary tests**

Use an injected `HTTPTransport.request(method, url, headers, timeout)` fake; do not start a server. Cover:

- paused inventory requests `status=5&page=1&pageSize=1000` and validates `total == len(list)` when `totalPages <= 1`;
- more than 1000 paused tasks fails before execution rather than silently truncating;
- snapshot rows exclude marketing template body/content/link and retain only load-test metadata;
- duplicate, non-positive, missing, or non-integer IDs fail;
- HTTP non-2xx, invalid JSON, `code != 0`, unexpected response shape, and response larger than 4 MiB fail with stable error classes and no response body in the error;
- `resume_snapshot_once` invokes exactly one POST for every frozen ID, even when another call fails;
- calls overlap when concurrency is greater than one, and outcomes preserve snapshot order;
- a timeout/connection failure is `transport_unknown`, never retried;
- reconciliation uses one read-only `GET ...?id=<id>&page=1&pageSize=1` per frozen ID and classifies final status as `sending`, `paused`, `other`, or `missing`.

Use these exact types:

```python
@dataclass(frozen=True)
class TaskSnapshot:
    id: int
    task_name: str
    status: int
    selected_account_count: int
    target_group_count: int
    target_pair_count: int
    send_interval_seconds: int
    task_start_at: int | None
    task_end_at: int | None

@dataclass(frozen=True)
class ResumeOutcome:
    task_id: int
    started_at: datetime
    finished_at: datetime
    result: str
    http_status: int | None

@dataclass(frozen=True)
class ReconciledTask:
    task_id: int
    final_status: int | None
    classification: str
```

- [ ] **Step 2: Prove the API implementation is absent**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_task_api -v
```

Expected: FAIL with undefined transport/client functions.

- [ ] **Step 3: Implement the API client and one-shot concurrency**

Expose:

```python
class HTTPTransport(Protocol):
    def request(self, method: str, url: str, headers: Mapping[str, str], timeout: float) -> HTTPResponse: ...

class TaskAPI:
    def list_paused(self) -> tuple[TaskSnapshot, ...]: ...
    def resume_snapshot_once(self, snapshot: Sequence[TaskSnapshot], concurrency: int) -> tuple[ResumeOutcome, ...]: ...
    def reconcile(self, snapshot: Sequence[TaskSnapshot], concurrency: int) -> tuple[ReconciledTask, ...]: ...
```

The default transport must use `urllib.request` with an explicit 10-second timeout and `X-Tenant-Code`. POST has an empty body and `Content-Length: 0`. Do not log URLs containing task IDs at INFO; the run artifact already records IDs. Never store response bodies or backend `message` strings in `resume-results.json`.

- [ ] **Step 4: Verify and commit**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_task_api -v
python3 -m py_compile tools/perf2_loadtest/*.py tools/tests/test_perf2_task_api.py
cd ..
git add armada-deploy/tools/perf2_loadtest/model.py armada-deploy/tools/perf2_loadtest/task_api.py armada-deploy/tools/tests/test_perf2_task_api.py
git commit -m "feat(ops): resume frozen marketing task snapshot"
```

Expected: tests PASS; no SQL or database client is introduced.

### Task 3: Align monitor samples and calculate auditable metrics

**Files:**
- Modify: `armada-deploy/tools/perf2_loadtest/model.py`
- Create: `armada-deploy/tools/perf2_loadtest/report.py`
- Create: `armada-deploy/tools/tests/test_perf2_report.py`

- [ ] **Step 1: Write failing report tests**

Build in-memory monitor records and cover:

- strict ISO-8601 UTC timestamps, `schemaVersion == 1`, node in `{armada,zhuan}`, non-negative finite numeric fields, and valid flags;
- at most one record per node per UTC second; duplicate seconds are invalid rather than overwritten;
- only a second containing valid records from both nodes becomes a merged CSV row;
- a Kafka-invalid second resets the consecutive-zero counter;
- stop becomes true only after resume calls are complete and 60 consecutive complete seconds have `lag == 0` and `producedPerSecond == 0`;
- nearest-rank P95 is `sorted[ceil(0.95*n)-1]`, with empty input returning `None`;
- peak rates, total latest-Offset delta, maximum Lag, the first maximum-Lag time, and Lag drain seconds are deterministic;
- `drainPeakConsumedPerSecond` considers only samples with `lag > 0` and `producedPerSecond == 0`;
- no positive Lag yields `capacityConclusion="observed_lower_bound"`;
- any timeout, stream failure, invalid Kafka sample, reconciliation failure, or interrupt sets `incomplete=true` and records counts without substituting zeros;
- CSV uses this exact header and never contains task names, brokers, accounts, payloads, or API responses:

```text
at,kafkaLatestOffset,kafkaCommittedOffset,kafkaLag,producedPerSecond,consumedPerSecond,armadaHostCpuPercent,armadaHostMemoryUsedBytes,armadaHostMemoryPercent,armadaContainerCpuPercent,armadaContainerMemoryBytes,armadaContainerMemoryPercent,zhuanHostCpuPercent,zhuanHostMemoryUsedBytes,zhuanHostMemoryPercent,zhuanContainerCpuPercent,zhuanContainerMemoryBytes,zhuanContainerMemoryPercent
```

- [ ] **Step 2: Run failing tests**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_report -v
```

Expected: FAIL because sample/report functions are absent.

- [ ] **Step 3: Implement pure parsing and aggregation**

Expose:

```python
def parse_monitor_line(line: bytes, expected_node: str) -> MonitorSample
def merge_samples(armada: MonitorSample, zhuan: MonitorSample) -> MergedSample
def nearest_rank_p95(values: Sequence[float]) -> float | None

class ZeroWindow:
    def observe(self, sample: MergedSample, resumes_complete: bool) -> bool: ...

def build_summary(
    samples: Sequence[MergedSample],
    snapshot: Sequence[TaskSnapshot],
    outcomes: Sequence[ResumeOutcome],
    reconciled: Sequence[ReconciledTask],
    *,
    invalid_kafka_samples: int,
    invalid_resource_samples: int,
    timed_out: bool,
    interrupted: bool,
) -> Mapping[str, object]: ...

def write_samples_csv(path: Path, samples: Sequence[MergedSample]) -> None
```

Round published rates/percentiles to three decimals, but make stop decisions from parsed values before report rounding. `topicProducedMessages` is the non-negative difference between first and last valid summed latest Offset. `lagDrainSeconds` is from the first occurrence of maximum positive Lag through the first second of the final qualifying zero window. Compute resource max/P95 independently per node/scope/metric and omit a statistic when no valid value exists.

- [ ] **Step 4: Verify and commit**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_report -v
python3 -m py_compile tools/perf2_loadtest/*.py tools/tests/test_perf2_report.py
cd ..
git add armada-deploy/tools/perf2_loadtest/model.py armada-deploy/tools/perf2_loadtest/report.py armada-deploy/tools/tests/test_perf2_report.py
git commit -m "feat(ops): summarize kafka and resource peaks"
```

Expected: tests PASS and summary arithmetic is independent of network/process code.

### Task 4: Manage two read-only monitors over long-lived SSH

**Files:**
- Create: `armada-deploy/tools/perf2_loadtest/remote.py`
- Create: `armada-deploy/tools/tests/test_perf2_remote.py`

- [ ] **Step 1: Write failing command/lifecycle tests**

Inject a `ProcessRunner` fake for build/SCP/check commands and a `PopenFactory` fake for streams. Prove:

- the monitor source must be a clean-enough Git worktree containing `cmd/perf-monitor` and `go.mod` (unrelated tracked changes are reported but do not get staged or modified);
- the build argv is exactly `go build -trimpath -o <tempfile> ./cmd/perf-monitor` with `CGO_ENABLED=0`, `GOOS=linux`, `GOARCH=amd64` in a copied environment;
- local SHA-256 is calculated before upload;
- SSH/SCP always use argument arrays, `BatchMode=yes`, `ConnectTimeout=10`, `StrictHostKeyChecking=accept-new`, and the profile key; no `shell=True`;
- a read-only preflight verifies remote `uname -m` is `x86_64`, expected container state is healthy/running, at least 5 GiB is free, and `sudo -n docker stats` works;
- upload destinations are run-scoped `/home/<user>/.armada-perf-tools/<runId>/perf-monitor`, where `runId` matches `^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$`;
- Zhuan command includes its existing `deploy/configs/prod_configs.toml`, Topic partition count 12, node/container, interval, and timeout; Armada command includes `-no-kafka -node armada -container armada-backend`;
- `-check` must emit exactly one valid JSON line and exit zero before a stream starts;
- the two sampling processes are started once and remain open; no per-second SSH handshake;
- stderr is drained separately and sanitized to stable classes; key paths, hosts, brokers, config contents, and raw stderr never enter run logs;
- EOF/non-zero exit publishes a stream failure to the orchestrator;
- cleanup sends terminate, waits five seconds, kills only if needed, and removes only the validated run-scoped remote directory.

- [ ] **Step 2: Prove remote support is absent**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_remote -v
```

Expected: FAIL with missing remote manager symbols.

- [ ] **Step 3: Implement shell-free process management**

Expose:

```python
class ProcessRunner(Protocol):
    def run(self, argv: Sequence[str], *, cwd: Path | None = None, env: Mapping[str, str] | None = None,
            timeout: float | None = None, input: bytes | None = None) -> CompletedProcess[bytes]: ...

class RemoteMonitorManager:
    def build(self, zhuan_repo: Path) -> BuiltMonitor: ...
    def preflight(self) -> PreflightEvidence: ...
    def upload_and_check(self, built: BuiltMonitor, run_id: str) -> None: ...
    def start(self) -> MonitorStreams: ...
    def close(self) -> None: ...
```

Use one `subprocess.Popen` per node with `stdout=PIPE`, `stderr=PIPE`, `bufsize=0`; reader threads put bounded `MonitorEvent` values onto a queue. If the queue is full, publish one `sample_overflow` failure and cancel the run instead of silently dropping measurements. Validate every dynamic argument before command construction. The only remote deletion is the exact validated run directory created by this run.

- [ ] **Step 4: Verify and commit**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_remote -v
python3 -m py_compile tools/perf2_loadtest/*.py tools/tests/test_perf2_remote.py
cd ..
git add armada-deploy/tools/perf2_loadtest/remote.py armada-deploy/tools/tests/test_perf2_remote.py
git commit -m "feat(ops): stream perf2 monitors over ssh"
```

Expected: tests PASS, command logs contain no secrets, and no business container restart command exists.

### Task 5: Implement the dry-run/execute state machine and artifacts

**Files:**
- Create: `armada-deploy/tools/perf2_loadtest/orchestrator.py`
- Create: `armada-deploy/tools/perf2_loadtest/cli.py`
- Create: `armada-deploy/tools/tests/test_perf2_orchestrator.py`

- [ ] **Step 1: Write failing end-to-end tests with fakes**

Use fake clock, Task API, remote manager, and monitor queue. Assert this exact order:

```text
load profile -> build monitor -> preflight -> upload/check -> start streams
-> initial paused inventory -> 30 complete valid baseline seconds -> second/frozen inventory
-> expected-count gate -> concurrent one-shot resume -> zero-window/timeout
-> reconcile frozen IDs -> write artifacts -> close monitors
```

Cover all control paths:

- dry-run performs monitor preflight/baseline and inventory, writes a dry-run summary, and makes zero POST calls;
- execute cannot reach resume if target count changed between initial and frozen inventory;
- execute cannot reach resume for unhealthy containers, low disk, wrong partition count, any invalid/gapped baseline sample, or a non-zero Lag in the final baseline sample;
- expected count is checked against the second frozen inventory immediately before any POST;
- a valid run freezes tuple order, persists `task-snapshot.json` atomically, then calls each ID once;
- task snapshot contains IDs/names/counts/times but no phone, template content, promotion link, target identity, or message;
- stop requires resume completion plus the configured consecutive zero window and never stops on zero Lag while production is still positive;
- timeout still reconciles and writes `incomplete=true`;
- `KeyboardInterrupt` stops monitors, reconciles if any POST started, and writes `incomplete=true` without pausing/closing/rolling back tasks;
- every run creates `task-snapshot.json`, `samples.csv`, `resume-results.json`, `summary.json`, and `run.log` under only `armada-deploy/perf-results/<runId>/`;
- JSON writes use temporary siblings plus `os.replace`; permissions are `0700` for the run directory and `0600` for artifacts;
- a failed mutation run returns non-zero when any frozen task is not reconciled as status `SENDING(2)`.

- [ ] **Step 2: Prove orchestration is absent**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_orchestrator -v
```

Expected: FAIL with missing orchestrator/CLI symbols.

- [ ] **Step 3: Implement explicit states and fail-closed transitions**

Expose:

```python
class RunState(Enum):
    CREATED = auto()
    PREFLIGHTED = auto()
    MONITORING = auto()
    BASELINED = auto()
    SNAPSHOT_FROZEN = auto()
    RESUMING = auto()
    DRAINING = auto()
    RECONCILED = auto()
    REPORTED = auto()

class Orchestrator:
    def run(self) -> int: ...
```

Only the transition `SNAPSHOT_FROZEN -> RESUMING` may call `resume_snapshot_once`, and it must re-check `options.execute`, `expected_count is not None`, `len(snapshot) == expected_count`, and all preflight/baseline evidence in the same method immediately before the call.

Use a UTC run ID `<YYYYMMDD>T<HHMMSS>Z-<8 hex>` from a cryptographically random suffix. Keep log records structured as time/level/event/counts only; pass stable error classes, never raw exception strings from HTTP/SSH boundaries. Write `resume-results.json` as `{outcomes, reconciliation, requestSpreadMilliseconds}`. `summary.json` must include the design fields plus `runId`, `env="perf2"`, `tenant`, `mode`, baseline evidence, monitor SHA-256, and `incomplete`.

- [ ] **Step 4: Verify and commit**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest tools.tests.test_perf2_orchestrator -v
python3 -m py_compile tools/perf2_loadtest/*.py tools/tests/test_perf2_orchestrator.py
cd ..
git add armada-deploy/tools/perf2_loadtest/orchestrator.py armada-deploy/tools/perf2_loadtest/cli.py armada-deploy/tools/tests/test_perf2_orchestrator.py
git commit -m "feat(ops): orchestrate perf2 marketing load test"
```

Expected: tests PASS and mutation is reachable through exactly one guarded call site.

### Task 6: Add the executable wrapper and mutation-guard smoke test

**Files:**
- Create: `armada-deploy/tools/perf2-marketing-load-test.sh`
- Create: `armada-deploy/tools/perf2-marketing-load-test.test.sh`

- [ ] **Step 1: Write a failing shell contract test**

Use a temporary `python3` stub injected through `PATH`. Test:

- wrapper is executable and `--help` is forwarded to the Python CLI;
- working directory does not matter;
- arguments are passed without `eval` or string re-parsing;
- no `--execute` invocation is synthesized by the wrapper;
- a normal dry-run argv reaches the stub unchanged;
- wrapper exits with the Python exit code;
- source contains no `mysql`, `UPDATE`, `docker restart`, `docker compose up`, or `docker compose down` command.

- [ ] **Step 2: Run it and see failure**

```bash
cd armada-deploy
bash tools/perf2-marketing-load-test.test.sh
```

Expected: FAIL because the wrapper does not exist.

- [ ] **Step 3: Implement the minimal entry point**

The wrapper must contain only strict mode, repository path resolution, `PYTHONPATH` setup limited to `armada-deploy/tools`, and:

```bash
exec python3 -m perf2_loadtest.cli "$@"
```

It must not source the environment profile; Python performs safe parsing.

- [ ] **Step 4: Verify and commit**

```bash
cd armada-deploy
chmod +x tools/perf2-marketing-load-test.sh tools/perf2-marketing-load-test.test.sh
bash -n tools/perf2-marketing-load-test.sh tools/perf2-marketing-load-test.test.sh
bash tools/perf2-marketing-load-test.test.sh
./tools/perf2-marketing-load-test.sh --help
cd ..
git add armada-deploy/tools/perf2-marketing-load-test.sh armada-deploy/tools/perf2-marketing-load-test.test.sh
git commit -m "feat(ops): add perf2 load-test entry point"
```

Expected: smoke tests PASS; help clearly labels dry-run as default and execute as state-changing.

### Task 7: Run local gates and a read-only perf2 dry-run

**Files:**
- Create: `.harness/changes/2026-07-25-perf2-marketing-load-test.md`
- Verify all implementation files.

- [ ] **Step 1: Run every local gate separately**

```bash
cd armada-deploy
PYTHONPATH=tools python3 -m unittest discover -s tools/tests -p 'test_perf2_*.py' -v
python3 -m py_compile tools/perf2_loadtest/*.py tools/tests/test_perf2_*.py
bash -n tools/perf2-marketing-load-test.sh tools/perf2-marketing-load-test.test.sh
bash tools/perf2-marketing-load-test.test.sh
cd ..
git diff --check
git status --short
```

Expected: all commands exit zero; unrelated existing work remains untouched.

- [ ] **Step 2: Audit the mutation boundary and privacy contract**

```bash
cd armada-deploy
rg -n "resume_snapshot_once|/resume|urlopen|subprocess|Popen" tools/perf2_loadtest tools/tests
rg -n "mysql|UPDATE marketing_task|docker (restart|stop)|docker compose (up|down)" tools/perf2_loadtest tools/perf2-marketing-load-test.sh
rg -n "broker|password|payload|marketingTemplateContent|marketingTemplateBodyText|promotionLink" tools/perf2_loadtest
```

Expected: one production resume call site behind the orchestrator guard; no SQL/container lifecycle action; sensitive field names appear only in explicit rejection/redaction logic or tests.

- [ ] **Step 3: Create the change record**

Document scope, repository commits, test outputs, dry-run run ID, UTC timestamps, monitor SHA-256, partition count, baseline Lag, paused task count and aggregate counts, sample-validity counts, container health, free disk, and result artifact path. Do not record host IPs, key paths, brokers, API bodies, phone numbers, message/template content, or remote config.

- [ ] **Step 4: Obtain explicit approval, then run dry-run only**

Target must be reconfirmed as `perf2`. Run:

```bash
cd armada-deploy
./tools/perf2-marketing-load-test.sh --env perf2 --tenant demo
```

Expected: both read-only probes and a 30-second valid baseline pass; current paused task totals are printed; no POST occurs; artifacts are written under the ignored results directory.

- [ ] **Step 5: Inspect dry-run artifacts for secrets and consistency**

```bash
cd armada-deploy
find perf-results -mindepth 2 -maxdepth 2 -type f -print
rg -n "BEGIN .*PRIVATE KEY|broker|password|payload|marketingTemplateContent|marketingTemplateBodyText|promotionLink" perf-results
```

Expected: exactly the documented artifact names; the secret scan returns no matches; task count and aggregate counts agree across snapshot/summary.

- [ ] **Step 6: Commit verification evidence only**

```bash
cd ..
git add .harness/changes/2026-07-25-perf2-marketing-load-test.md
git commit -m "docs: record perf2 load-test verification"
```

Do not add `armada-deploy/perf-results/` or either PEM file.

## Formal execution handoff

The implementation and dry-run do **not** authorize the bulk resume. Immediately before a real run, show the new frozen count and aggregate account/group/target-pair counts, then obtain an explicit state-changing instruction. The approved command shape is:

```bash
cd armada-deploy
./tools/perf2-marketing-load-test.sh --env perf2 --tenant demo --execute --expected-count <live-count>
```

If the live count differs by even one task, the command must exit before every POST. Never replace `<live-count>` with the historical value 34 without a fresh dry-run and explicit approval.
