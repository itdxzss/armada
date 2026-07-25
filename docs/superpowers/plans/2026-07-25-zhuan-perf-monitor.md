# Zhuan Kafka and Resource Performance Monitor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a standalone, read-only Linux monitor that emits one JSON sample per second for Zhuan Kafka message offsets, lag, host CPU/memory, and a selected Docker container.

**Architecture:** `cmd/perf-monitor` loads only the required Kafka keys from the existing TOML, uses `kafka-go` admin APIs without joining the consumer group, and combines Kafka data with `/proc` and `docker stats`. Focused code under `internal/perfmonitor` keeps parsing and arithmetic testable without Kafka, Docker, or sleeps.

**Tech Stack:** Go 1.25.1, `github.com/segmentio/kafka-go` 0.4.49, Viper, Linux `/proc`, Docker CLI JSON, standard `testing`.

---

## File map

- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/model.go`: JSON sample and snapshot types.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/rate.go`: Offset/Lag/rate and CPU delta calculations.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/rate_test.go`: arithmetic contracts.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/kafka.go`: TOML loading and Kafka admin reads.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/kafka_test.go`: settings and partition contracts.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/resource.go`: `/proc` and Docker readers/parsers.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/resource_test.go`: resource parser contracts.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/runner.go`: timed concurrent sampling and JSONL output.
- Create `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/runner_test.go`: runner/cancellation/privacy contracts.
- Create `../whatsapp-server-feature-android-zhuan/cmd/perf-monitor/main.go`: flags, signals, and exit codes.
- Create `../whatsapp-server-feature-android-zhuan/cmd/perf-monitor/main_test.go`: CLI contracts.

### Task 1: Implement Offset, Lag, rate, and CPU arithmetic

**Files:**
- Create: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/model.go`
- Create: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/rate.go`
- Test: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/rate_test.go`

- [ ] **Step 1: Write failing table-driven tests**

Create tests that assert: two partitions changing latest by 60 and committed by 50 over two seconds produce 30/s and 25/s; Lag is the sum of `max(latest-committed, 0)`; missing partitions, negative offsets, committed/latest rollback, and non-positive elapsed time fail; CPU counters `{total:1000,idle:700}` to `{total:1100,idle:720}` produce 80%.

Use these exact core types in the test:

```go
previous := OffsetSnapshot{
    At: time.Unix(10, 0),
    Latest: map[int]int64{0: 100, 1: 200},
    Committed: map[int]int64{0: 90, 1: 190},
}
current := OffsetSnapshot{
    At: time.Unix(12, 0),
    Latest: map[int]int64{0: 140, 1: 220},
    Committed: map[int]int64{0: 120, 1: 210},
}
```

- [ ] **Step 2: Run the test to prove the package is missing**

```bash
cd ../whatsapp-server-feature-android-zhuan
go test ./internal/perfmonitor -run 'TestCalculateKafkaMetrics|TestCPUPercent' -v
```

Expected: FAIL because `OffsetSnapshot`, `CalculateKafkaMetrics`, and `CPUPercent` do not exist.

- [ ] **Step 3: Add the minimal model and implementation**

`model.go` must define:

```go
type OffsetSnapshot struct { At time.Time; Latest, Committed map[int]int64 }
type CPUCounter struct { Total, Idle uint64 }
type KafkaMetrics struct {
    LatestOffset int64 `json:"latestOffset"`
    CommittedOffset int64 `json:"committedOffset"`
    Lag int64 `json:"lag"`
    ProducedPerSecond float64 `json:"producedPerSecond"`
    ConsumedPerSecond float64 `json:"consumedPerSecond"`
    Valid bool `json:"valid"`
    ErrorClass string `json:"errorClass,omitempty"`
}
type ResourceMetrics struct {
    HostCPUPercent float64 `json:"hostCpuPercent"`
    HostMemoryUsedBytes uint64 `json:"hostMemoryUsedBytes"`
    HostMemoryPercent float64 `json:"hostMemoryPercent"`
    ContainerCPUPercent float64 `json:"containerCpuPercent"`
    ContainerMemoryBytes uint64 `json:"containerMemoryBytes"`
    ContainerMemoryPercent float64 `json:"containerMemoryPercent"`
    Valid bool `json:"valid"`
    ErrorClass string `json:"errorClass,omitempty"`
}
type Sample struct {
    SchemaVersion int `json:"schemaVersion"`
    At time.Time `json:"at"`
    Node string `json:"node"`
    Kafka *KafkaMetrics `json:"kafka,omitempty"`
    Resource ResourceMetrics `json:"resource"`
}
```

`CalculateKafkaMetrics(previous, current)` must validate equal partition sets and monotonic offsets before summing. Round rates to three decimal places. `CPUPercent(previous,current)` must reject counter rollback and zero total delta.

- [ ] **Step 4: Format and verify**

```bash
cd ../whatsapp-server-feature-android-zhuan
gofmt -w internal/perfmonitor/model.go internal/perfmonitor/rate.go internal/perfmonitor/rate_test.go
go test ./internal/perfmonitor -run 'TestCalculateKafkaMetrics|TestCPUPercent' -v
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd ../whatsapp-server-feature-android-zhuan
git add internal/perfmonitor/model.go internal/perfmonitor/rate.go internal/perfmonitor/rate_test.go
git commit -m "feat(perf): calculate kafka and cpu rates"
```

### Task 2: Read Kafka configuration and group offsets

**Files:**
- Create: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/kafka.go`
- Test: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/kafka_test.go`

- [ ] **Step 1: Write failing settings and admin-response tests**

Use a temporary TOML containing `[kafka]` keys `brokers`, `securityprotocol`, `messagecommandtopic`, and `messageconsumergroup`. Assert trimming/deduplication, TLS normalization, and rejection of blank values. Define a fake for this exact boundary:

```go
type KafkaAdmin interface {
    Metadata(context.Context, *kafka.MetadataRequest) (*kafka.MetadataResponse, error)
    ListOffsets(context.Context, *kafka.ListOffsetsRequest) (*kafka.ListOffsetsResponse, error)
    OffsetFetch(context.Context, *kafka.OffsetFetchRequest) (*kafka.OffsetFetchResponse, error)
}
```

Fake a 12-partition metadata response and assert one latest/committed value per partition. Missing offsets, any partition error, or a count other than 12 must fail.

- [ ] **Step 2: Run and see the missing implementation fail**

```bash
cd ../whatsapp-server-feature-android-zhuan
go test ./internal/perfmonitor -run 'TestLoadKafkaSettings|TestKafkaSampler' -v
```

Expected: FAIL with undefined loader/sampler symbols.

- [ ] **Step 3: Implement strict read-only sampling**

Expose these signatures:

```go
type KafkaSettings struct {
    Brokers []string
    SecurityProtocol string
    Topic string
    GroupID string
    ExpectedPartitions int
}
func LoadKafkaSettings(path string, expectedPartitions int) (KafkaSettings, error)
func NewKafkaClient(KafkaSettings) *kafka.Client
func NewKafkaSampler(KafkaSettings, KafkaAdmin) (*KafkaSampler, error)
func (s *KafkaSampler) Snapshot(context.Context, time.Time) (OffsetSnapshot, error)
```

Use `viper.New()` with `SetConfigFile`, never the global Zhuan config singleton. Accept only `PLAINTEXT`, `TLS`, and `SSL`; TLS/SSL require TLS 1.2. Query metadata for only the target Topic, then `LastOffsetOf` for its partitions and `OffsetFetch` for the configured group. Error messages use stable classes (`metadata`, `list_offsets`, `offset_fetch`, `partition_contract`) and never contain brokers, Topic payloads, or config contents.

- [ ] **Step 4: Format, test, and commit**

```bash
cd ../whatsapp-server-feature-android-zhuan
gofmt -w internal/perfmonitor/kafka.go internal/perfmonitor/kafka_test.go
go test ./internal/perfmonitor -run 'TestLoadKafkaSettings|TestKafkaSampler' -v
git add internal/perfmonitor/kafka.go internal/perfmonitor/kafka_test.go
git commit -m "feat(perf): sample kafka consumer lag"
```

Expected: tests PASS and the commit contains only these files.

### Task 3: Read Linux and Docker resources

**Files:**
- Create: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/resource.go`
- Test: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/resource_test.go`

- [ ] **Step 1: Write failing parser tests**

Assert that `cpu 100 20 30 400 10 5 3 2 0 0` parses to total 570 and idle 410; `MemTotal: 8000000 kB` plus `MemAvailable: 2000000 kB` parses to 8,192,000,000 total bytes and 75% used; Docker JSON `{"CPUPerc":"12.50%","MemUsage":"512MiB / 8GiB","MemPerc":"6.25%"}` parses to 12.5%, 536,870,912 bytes, and 6.25%. Reject missing fields, unsupported units, malformed percentages, and empty output.

- [ ] **Step 2: Verify failure**

```bash
cd ../whatsapp-server-feature-android-zhuan
go test ./internal/perfmonitor -run 'TestParseProcStat|TestParseMemInfo|TestParseDockerStats' -v
```

Expected: FAIL because the parsers are undefined.

- [ ] **Step 3: Implement injected readers and a shell-free Docker call**

Expose:

```go
type FileReader interface { ReadFile(string) ([]byte, error) }
type CommandRunner interface { Run(context.Context, string, ...string) ([]byte, error) }
func NewResourceSampler(FileReader, CommandRunner, string) (*ResourceSampler, error)
func (s *ResourceSampler) Snapshot(context.Context) (ResourceMetrics, error)
func ParseProcStat([]byte) (CPUCounter, error)
func ParseMemInfo([]byte) (MemorySnapshot, error)
func ParseDockerStats([]byte) (ContainerSnapshot, error)
```

Validate container names with `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}`. Invoke `sudo -n docker stats --no-stream --format {{json .}} <container>` with `exec.CommandContext`, not a shell. Classify errors without including Docker output.

- [ ] **Step 4: Format, test, and commit**

```bash
cd ../whatsapp-server-feature-android-zhuan
gofmt -w internal/perfmonitor/resource.go internal/perfmonitor/resource_test.go
go test ./internal/perfmonitor -run 'TestParseProcStat|TestParseMemInfo|TestParseDockerStats|TestResourceSampler' -v
git add internal/perfmonitor/resource.go internal/perfmonitor/resource_test.go
git commit -m "feat(perf): sample host and container resources"
```

Expected: PASS.

### Task 4: Add the timed JSONL runner and CLI

**Files:**
- Create: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/runner.go`
- Create: `../whatsapp-server-feature-android-zhuan/internal/perfmonitor/runner_test.go`
- Create: `../whatsapp-server-feature-android-zhuan/cmd/perf-monitor/main.go`
- Create: `../whatsapp-server-feature-android-zhuan/cmd/perf-monitor/main_test.go`

- [ ] **Step 1: Write failing runner/CLI tests**

Using channel-controlled fakes, prove: an initial sample emits immediately; Kafka/resource reads are concurrent; later samples follow the interval; first Kafka rates are zero while current Lag remains visible; one read error emits `valid=false` and later sampling continues; cancellation returns within one interval; serialized JSON contains none of `brokers`, `password`, `payload`, or `message`; `-check` emits exactly one sample; intervals below 200 ms, invalid nodes/containers, and missing config fail before sampling.

- [ ] **Step 2: Verify failure**

```bash
cd ../whatsapp-server-feature-android-zhuan
go test ./internal/perfmonitor ./cmd/perf-monitor -run 'TestRunner|TestRun' -v
```

Expected: FAIL because runner and command are missing.

- [ ] **Step 3: Implement the exact runner and flags**

Expose:

```go
type OffsetSampler interface { Snapshot(context.Context, time.Time) (OffsetSnapshot, error) }
type SystemSampler interface { Snapshot(context.Context) (ResourceMetrics, error) }
type RunnerOptions struct {
    Node string
    Interval time.Duration
    SampleTimeout time.Duration
    Kafka OffsetSampler
    Resource SystemSampler
    Output io.Writer
    Now func() time.Time
}
func NewRunner(RunnerOptions) (*Runner, error)
func (r *Runner) Run(context.Context) error
func (r *Runner) SampleOnce(context.Context) error
```

CLI flags: `-config`, `-node`, `-container`, `-expected-partitions` (default 12), `-interval` (default 1s), `-sample-timeout` (default 5s), `-no-kafka`, and `-check`. `-no-kafka` supports the Armada host sampler. Use `signal.NotifyContext`; stdout is JSONL only and diagnostics go to stderr.

- [ ] **Step 4: Format, test, cross-build, and commit**

```bash
cd ../whatsapp-server-feature-android-zhuan
gofmt -w internal/perfmonitor/runner.go internal/perfmonitor/runner_test.go cmd/perf-monitor/main.go cmd/perf-monitor/main_test.go
go test ./internal/perfmonitor ./cmd/perf-monitor -run 'TestRunner|TestRun' -v
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o /private/tmp/zhuan-perf-monitor ./cmd/perf-monitor
git add internal/perfmonitor/runner.go internal/perfmonitor/runner_test.go cmd/perf-monitor/main.go cmd/perf-monitor/main_test.go
git commit -m "feat(perf): add standalone perf monitor"
```

Expected: tests PASS and the Linux binary exists.

### Task 5: Run gates and a read-only perf2 probe

**Files:**
- Verify only.

- [ ] **Step 1: Run all mandatory Zhuan gates separately**

```bash
cd ../whatsapp-server-feature-android-zhuan
gofmt -w internal/perfmonitor/*.go cmd/perf-monitor/*.go
go vet ./...
go build ./...
go test ./...
go test -race ./internal/perfmonitor ./cmd/perf-monitor
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 2: Confirm repository scope**

```bash
cd ../whatsapp-server-feature-android-zhuan
git status --short
git log -4 --oneline
```

Expected: only intended monitor files changed/committed; unrelated user changes remain untouched.

- [ ] **Step 3: Probe perf2 only after reconfirming the target**

Build and copy the static binary to a run-scoped path under the perf2 SSH user's home, then run:

```text
perf-monitor -check -config /home/ec2-user/whatsapp-android-zhuan/deploy/configs/prod_configs.toml -node zhuan -container whatsapp-android-zhuan -expected-partitions 12
```

Expected: one JSON sample with `kafka.valid=true` and `resource.valid=true`; no broker, task payload, account, or message content; the monitor never commits an Offset.

- [ ] **Step 4: Record non-secret evidence**

Record UTC timestamp, binary SHA-256, partition count, Lag, resource validity, container health, and exit code in the change record created by the orchestrator plan. Do not record broker addresses or TOML contents.
