# 账号状态与群同步 Topic 隔离

## 目标

把账号状态和账号群同步事件拆到独立 Kafka Topic 与 consumer group，消除群快照重事务对 ONLINE 回写的阻塞。

## 缺口拆解

- [x] Armada 拆分 listener、配置、并发和测试。
- [x] Web 协议新增状态/群同步 Topic 路由和配置。
- [x] Android Zhuan 新增状态/群同步 Topic 路由和配置。
- [x] 更新三端部署配置、perf2 深度检查清单和回退说明。
- [x] 完成代码级验证；perf2 验收由用户执行。

## 关键设计决策

- 新 Topic：`protocol.account.state.events.v1`、`protocol.account.group-sync.events.v1`。
- 各部署环境必须使用独立 Topic 和 consumer group；perf2 使用 `armada.perf` 前缀，避免不同数据库的
  Armada 实例瓜分同一状态事件分区。
- 两个 Topic 均 12 分区、总消费并发均为 4。
- Web 其他账号遥测事件继续写旧 `protocol.account.events.v1`，Armada 不再订阅。
- perf2 一次性切换，旧积压直接废弃，通过协议重启重建当前状态。
- passive 不变，禁止自动回退；回退只接受用户明确命令。

## 影响

- Armada Kafka 消费配置和账号事件 consumer。
- Web/Baileys Kafka producer 路由和环境变量。
- Android Zhuan Kafka producer 路由和 TOML 配置。
- 无数据库结构、API、Redis 变更。

## 回滚

停三个服务，恢复旧代码和 Topic 配置，将旧 consumer group offset 移到末尾，账号展示状态置为 OFFLINE，先启动 Armada 再启动协议重新上报。新 Topic 保留排障。

## 验证

- Armada 聚焦测试：
  `mvn -q -Dtest=ProtocolKafkaConfigurationTest,ProtocolKafkaListenerConfigurationTest,ProtocolAccountEventConsumerPropertiesTest,ProtocolAccountEventConsumerTest test` 通过。
- Armada 部署脚本：`bash armada-deploy/deploy-test.test.sh` 与
  `bash armada-deploy/package-prod.test.sh` 通过。
- Armada 完整 `mvn test` 已执行，但本机未提供 MySQL 密码，测试上下文以
  `Access denied for user 'root'@'localhost' (using password: NO)` 失败；不是本次变更的断言失败。
- Web 协议：完整 `npm test -- --runInBand` 通过，共 54 个 suite、460 个 test；
  `npm run lint` 与 `npm run build` 通过。
- Android Zhuan：`go test ./internal/configs`、`go test ./internal/armada`、
  `go vet ./internal/armada ./internal/configs` 和 `go build ./...` 通过。
- Android Zhuan 完整 `go vet ./...` 与 `go test ./...` 已执行；受仓库既有问题影响未全绿，
  包括 `internal/service/network/gosocket` IPv6 格式检查、`promise.go` context leak、
  appstate 测试签名不匹配，以及 deploy/db/noise 的既有断言或测试向量问题。本次涉及的
  `internal/armada` 与 `internal/configs` 包通过。
- 三个仓库 `git diff --check` 均通过。
- 本地改动未提交；未修改旧 Kafka offset、账号业务数据或 passive 配置。
- perf2 环境验收由用户负责。

## perf2 部署记录

- 目标环境：第二套测试环境 `perf2`；部署时间：2026-07-24 UTC。
- Baileys 使用用户提供的公网入口直连部署，本次未使用 Armada 跳板链路。
- 新建并验证 4 个 Kafka Topic：两个主 Topic 与两个 `.DLT` 均为 12 分区、3 副本。
- 三端人工回退点 ID：`20260724T032323Z`；包含 Armada 部署目录/旧镜像、
  Baileys 源码/PM2 状态、Android 旧镜像/部署资产。
- 启动顺序：停止 Baileys、Android、Armada → 先部署并启动 Armada →
  确认两个新 consumer group 各 4 个成员 → 启动 Baileys → 启动 Android。
- Armada backend：运行中，restart=0，HTTP=200。
- Baileys：master + 4 worker 全部 online，`readyz` 通过。
- Android Zhuan：callback、协议主服务 healthy，traffic dashboard running，restart=0；
  启动日志确认 `accountStateEventTopic` 与 `accountGroupSyncEventTopic` 已生效。
- Kafka 最终检查：状态 Topic records=15/lag=0，群同步 Topic records=11/lag=0；
  状态 DLT=0，群同步 DLT=1。
- 群同步 DLT 的 1 条消息由群快照写库与并行消息事件发生 MySQL deadlock，
  按 1 秒 × 3 次重试耗尽后进入 DLT；稳定窗口内未继续增长。未擅自重放或回退。
- Android 远端 Compose v5.1.2 与 buildx 0.12.1 不兼容（要求 buildx >=0.17）；
  本次未升级系统插件，使用 Docker 25 legacy builder 按同一 Dockerfile 完成镜像构建。

## perf2 环境隔离修正（2026-07-25）

- 修正原因：perf2 与另一套环境误用同一个状态 Topic/consumer group，12 个分区被
  `172.31.5.135` 与 `172.31.13.65` 瓜分，导致状态事件随机写入错误环境。
- perf2 三端已一次性切换到 `armada.perf.protocol.account.state.events.v1` 与
  `armada.perf.protocol.account.group-sync.events.v1`；consumer group 分别为
  `armada-perf-api-account-state-events` 与 `armada-perf-api-account-group-sync-events`。
- 验证新组均为 4 个 consumer、接管 12 个分区且仅来自 `172.31.5.135`；旧状态组仅剩
  `172.31.13.65`，不再包含 perf2。
- 重建前状态快照保存在远端
  `/home/app/armada-deploy/rollback/account-state.pre-rebuild-20260725.tsv`，共 1128 行。
- 按 `desired_login_state` 重建：明确离线账号已自然收敛，无需修正；986 个目标在线且
  卡在 PENDING 的账号重置为 `OFFLINE/PROXY_FAILED`，由持续补偿调度器重新上线。
- 重建后状态事件能正确回传并消费；中间阶段大量 PENDING 的直接原因已收敛为 Android
  `proxy_connect` 失败（rawCode 302），不再是 Kafka 命令积压或跨环境误消费。用户随后再次
  批量下线，最终 1128 个显式目标账号均为 `desired=OFFLINE/login=OFFLINE`。
- 未自动回退，未修改旧 Topic offset，未提交本地代码。
