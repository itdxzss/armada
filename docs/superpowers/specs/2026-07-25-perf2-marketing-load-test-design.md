# Perf2 营销恢复压测与 Kafka 峰值监控设计

## 背景与目标

第二套性能测试环境（`perf2`）计划在 2026-07-25 晚间恢复业务量。现有 Zhuan 代理流量网页看板已重置为新的 24 小时采集窗口，但该看板只统计代理流量，不包含主机或容器 CPU、内存，也不能计算 Kafka 营销命令 Topic 的生产速率、消费速率和积压。

本次需要在不直接修改业务数据库、不重启 Armada 或 Zhuan 业务服务的前提下：

1. 统计 `perf2` 当前全部暂停的普通营销任务；
2. 通过现有营销任务恢复接口并发恢复固定快照中的全部暂停任务，制造一次真实业务突发；
3. 同步采集 Armada 后端和 Zhuan 协议机的主机、业务容器 CPU 与内存；
4. 采集 Android 营销消息 Kafka Topic 的最新 Offset、消费组提交 Offset、Lag、生产速率与消费速率；
5. 产出可复核的逐秒 CSV 和 JSON 汇总，给出观察到的消费峰值、最大积压和清空积压耗时。

## 已确认环境事实

- 环境：`perf2`，即第二套性能测试环境。
- Armada 主机：由 `armada-deploy/envs/perf2.conf` 的 `PROFILE_ARMADA_*` 配置解析。
- Zhuan 主机：由同一配置的 `PROFILE_ZHUAN_*` 解析。
- 营销命令 Topic：`armada.perf.protocol.android.message.commands.v1`。
- Zhuan 消费组：`armada-perf-android-zhuan-message-v1`。
- Topic 分区数：12。
- Zhuan 营销消息消费者并发数：4。
- 调研时 `demo` 租户有 34 个暂停任务，`demo2` 为 0；34 个任务当时全部处于计划时间窗口内，合计覆盖 248 个账号和 2,243 个群，发送间隔均为 600 秒。
- 任务结束时间为 2026-07-25 23:59:59（北京时间）；正式执行前必须重新统计，不能把调研时快照当作执行时事实。
- 营销恢复接口会把 `status` 从 `PAUSED(5)` 更新为 `SENDING(2)`，并把 `next_round_at` 设为接口执行时刻；不会改写原 `task_start_at` 或 `task_end_at`。
- 营销轮次调度器默认每秒扫描，单次最多选 20 个任务，执行线程池大小为 5。因此并发恢复会让全部任务立即到期，但实际命令生成会受调度器限流分波执行。
- Zhuan 消费者在消息已经进入实际发送段后提交源 Kafka Offset，ACK 与结果收尾异步完成。因此本文的“消费条数/秒”表示 Kafka 消费并进入发送段的速率，不等于 WhatsApp ACK 成功条数/秒。

## 方案选择

### 采用：独立 Go 监控器 + API 快照并发恢复

增加一个独立 Go 监控器，复用 Zhuan 已使用的 `kafka-go` 依赖和 TLS 规则，在 Zhuan 主机上以运维工具方式运行。它不加入业务 Compose，不修改现有业务进程，也不需要重启账号。Armada 侧增加一个 `perf2` 专用编排脚本：先验证监控器和基线，再冻结暂停任务快照并调用现有恢复接口。

优点是测量链路贴近真实消费者、不会改变业务服务、可以逐秒留证，并且恢复仍经过 Service 与 Mapper 的既有校验。

### 不采用：扩展现有流量网页看板

把 CPU、内存和 Kafka 指标加入流量网页看板可以统一展示，但需要扩展看板数据源、重新构建镜像并再次重建 Zhuan 容器。晚间压测前再次触发全部账号重连没有必要，且网页展示不是本次测量准确性的前置条件。

### 不采用：直接 SQL 批量更新任务

直接执行 `UPDATE marketing_task SET status=2, next_round_at=...` 虽然能把时间戳压得更近，但会绕过租户上下文、计划窗口校验、状态并发校验、业务日志和接口错误处理。压测工具不得采用该路径。

## 组件设计

### 1. Zhuan Kafka 性能监控器

在 Zhuan 仓库新增独立命令及内聚实现包：

- `cmd/perf-monitor`：参数解析、信号处理和进程退出码；
- `internal/perfmonitor`：Kafka Offset 采样、系统资源采样、速率计算、CSV 与汇总输出。

监控器读取远端现有 `prod_configs.toml`，只取 Kafka broker、TLS、营销 Topic 和消费组配置，不输出 broker 地址或其他配置内容。Kafka 采样使用：

- `kafka.Client.ListOffsets` 获取 12 个分区的最新 Offset；
- `kafka.Client.OffsetFetch` 获取消费组在各分区的已提交 Offset；
- `Lag = Σ max(latestOffset - committedOffset, 0)`；
- `producedPerSecond = ΔΣlatestOffset / Δt`；
- `consumedPerSecond = ΔΣcommittedOffset / Δt`。

每秒生成一个样本。所有 Offset 都按分区先校验再汇总；分区错误或 Offset 回退会使该样本标记为无效，不能悄悄按零计入峰值。

资源采样包括：

- Zhuan 主机 CPU 使用率和内存已用量/使用率；
- `whatsapp-android-zhuan` 容器 CPU 使用率和内存已用量/使用率；
- Armada 主机 CPU 使用率和内存已用量/使用率；
- `armada-backend` 容器 CPU 使用率和内存已用量/使用率。

Zhuan 侧由 Go 监控器读取 `/proc` 并调用只读 `docker stats --no-stream`。Armada 侧由编排脚本建立一条长连接 SSH，在远端循环执行同样的只读采样并回传统一格式，不能每秒重新握手干扰结果。单次远程采样超时不会停止业务，但会把对应字段留空并累计 `invalidResourceSamples`。

### 2. Perf2 压测编排脚本

在 Armada 仓库新增 `armada-deploy/tools/perf2-marketing-load-test.sh`，且只允许 `--env perf2`。脚本使用 `armada-deploy/envs/perf2.conf` 解析主机和密钥位置，不复制、打印或持久化私钥及远端配置。

脚本分为两个模式：

- 默认 `dry-run`：只做目标环境验证、暂停任务统计、Kafka/资源采样探测和预估规模，不改变任务状态；
- `--execute --expected-count N`：仅当执行时冻结的暂停任务数量严格等于 `N` 时继续，否则在任何恢复请求前退出。

执行快照通过 Armada 本机 Nginx API 查询 `status=5&pageSize=1000`。已确认测试环境当前使用 `demo` 租户，但脚本仍把租户码作为显式参数，不自动跨租户枚举。脚本冻结任务 ID 后不再把后续新暂停的任务加入本轮。

恢复阶段对快照中的任务并发调用：

```text
POST /api/marketing-tasks/{id}/resume
```

每个任务只主动调用一次。网络错误后不盲目重试，因为请求可能已经成功提交但响应丢失；脚本会重新查询任务状态，把快照 ID 分类为 `SENDING`、仍 `PAUSED` 或其他状态，再决定整体退出码。脚本不会自动关闭、暂停或回滚已经恢复的任务。

### 3. 统一执行入口与时序

正式执行顺序固定如下：

1. 验证本地仓库、`perf2` 配置、SSH 目标身份、业务容器健康和磁盘剩余空间；
2. 查询暂停任务并输出任务数、账号数、群数、计划开始/结束时间；
3. 验证 Kafka 12 个分区、目标消费组和首个有效 Offset 样本；
4. 启动资源与 Kafka 监控，连续采集 30 秒有效基线，并要求基线结束时 Lag 为 0；
5. 再次查询并冻结暂停任务快照，校验 `--expected-count`；
6. 并发调用全部恢复接口，并记录第一个/最后一个请求的时间差；
7. 持续采样，直到全部恢复请求已经结束，且 Kafka Lag 与生产速率连续 60 秒均为 0，或达到默认 30 分钟超时；
8. 重新核对快照任务状态，生成本地结果目录并退出。

如果 30 秒基线内 Kafka Offset 查询失败、基线结束时已有 Lag、目标 Topic/消费组不匹配、容器不健康或磁盘剩余空间低于安全阈值，脚本必须在恢复前失败关闭。

## 输出与指标口径

每次运行使用 UTC `runId`，输出到 Git 忽略的 `armada-deploy/perf-results/<runId>/`：

- `task-snapshot.json`：执行前冻结的任务 ID、名称和汇总规模，不包含账号手机号或消息内容；
- `samples.csv`：每秒 Kafka、主机和容器指标；
- `resume-results.json`：各任务 HTTP 结果和执行后状态；
- `summary.json`：峰值、P95、最大 Lag、清空耗时和数据完整性；
- `run.log`：人读执行日志，不记录密钥、Kafka broker、请求正文或业务消息。

核心汇总字段：

- `topicProducedMessages`：压测窗口内 Topic 最新 Offset 总增量；若环境中还有其他生产者并发写入，该值包含它们，报告不得全部归因于本轮恢复；
- `observedPeakProducedPerSecond`；
- `observedPeakConsumedPerSecond`；
- `maxLag`；
- `lagDrainSeconds`：从最大 Lag 时刻到首次进入连续 60 秒零 Lag 窗口的耗时；
- 两台主机和两个业务容器的 CPU `max/p95`；
- 两台主机和两个业务容器的内存 `max/p95`；
- `invalidKafkaSamples`、`invalidResourceSamples`；
- `allSnapshotTasksResumed`。

若整个观察窗口从未出现正 Lag，`summary.json` 必须把结论标记为 `observed_lower_bound`，只表述“本次业务量下观察到的峰值”，不能称为消费者能力上限。若出现积压并在生产速率明显下降后继续消化，则额外计算该纯消化阶段的 `drainPeakConsumedPerSecond`。

## 安全与失败处理

- 只允许 `perf2`，环境或主机身份不匹配即退出；不提供生产环境开关。
- 默认 dry-run；正式恢复必须显式传入 `--execute` 和执行时预期任务数。
- 所有业务变更只走现有恢复接口，不执行写 SQL。
- 监控器只发 Kafka 管理类读请求，不消费消息、不提交 Offset、不加入目标消费组。
- 不把 Kafka broker、数据库地址、私钥、代理、手机号、消息内容写入输出。
- 监控器失败不能回滚已经进入发送链路的消息；报告必须明确标记结果不完整。
- 用户中断后停止监控并生成 `incomplete=true` 的汇总，不改变已经恢复的任务。
- 流量网页看板继续独立运行；本工具不清理其数据，不重建 Zhuan 容器。

## 测试与验收

实现阶段采用 TDD：

1. Go 单元测试覆盖 Offset 聚合、Lag、速率、Offset 回退、分区错误、峰值/P95、零 Lag 连续窗口和不完整运行汇总；
2. Go 资源解析测试覆盖 `/proc/stat`、`/proc/meminfo` 和 `docker stats` JSON 的正常与异常输入；
3. Shell 测试使用命令桩验证默认 dry-run、环境拒绝、`expected-count` 拦截、监控基线失败时零恢复请求、并发恢复及事后状态对账；
4. Zhuan 仓库执行 `gofmt`、`go vet ./...`、`go build ./...`、相关 `go test`，并对并发监控逻辑运行 `go test -race`；
5. Armada 部署脚本测试验证新增脚本语法、配置解析和命令构造；
6. 远端先执行只读 dry-run 和短时监控探测，确认有有效 Kafka/资源样本；
7. 实际 34 任务恢复属于 `perf2` 批量业务变更，必须在执行前再次展示实时数量并取得明确执行指令。

## 非目标

- 不改营销调度器、Kafka 消费者并发、Topic 分区或 Outbox 配置；
- 不新增 Prometheus、Grafana、Netdata 等常驻监控平台；
- 不把性能指标加入现有代理流量网页；
- 不通过暂停 Kafka 消费者、停止 Zhuan 进程或写入伪造 Kafka 消息制造积压；
- 不把本次观察峰值直接当作生产容量承诺。
