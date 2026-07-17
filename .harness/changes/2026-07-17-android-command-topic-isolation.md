# 变更记录：Android 命令 Topic 隔离

- 日期 / 分支 / worktree: 2026-07-17 / `1.0.1-snapshot` / 当前 checkout
- 需求来源: 用户确认；`docs/superpowers/specs/2026-07-17-android-command-topic-isolation-design.md`
- 状态: 进行中

## 目标（一句话）

把 Android 生命周期、营销消息和进群命令拆成三个 Kafka topic 和三个独立 consumer pool，避免营销或进群积压拖慢批量上线、下线。

## 缺口拆解 / 任务清单

- [x] Armada 三 topic 配置与启动校验
- [x] Armada 四类 Android command type 精确路由
- [x] Zhuan 三组 TOML 配置与启动校验
- [x] Zhuan 三个独立 consumer pool 与错路由永久提交
- [x] 离线营销 `ACCOUNT_OFFLINE`、离线进群 `ACCOUNT_NOT_ONLINE` 回归
- [x] 本地 Java/Go/部署模板聚焦验证
- [ ] dev-1 停机切换与隔离验收

## 关键设计决策

- 停机切换，不双写、不双读、不迁移旧 topic 消息。
- 三个 topic 都以 `protocolAccountId` 为 key，每个默认 4 分区、Zhuan 每组 4 consumer。
- 营销发送不预查、不等待账号上线；账号实例不可用时回报 `ACCOUNT_OFFLINE` 后提交 source offset。
- 进群离线继续回报 `ACCOUNT_NOT_ONLINE`。
- event topic、outbox 状态机和 Web/master 路由不变。

## 执行前基线

- Armada: `mvn -Dtest='ProtocolAndroidCommandPropertiesTest,ProtocolKafkaConfigurationTest,ProtocolCommandOutboxServiceImplTest,AndroidMessageSendBackendTest' test`，35 tests，0 failure，0 error，BUILD SUCCESS。
- Android Zhuan: `go test ./internal/configs ./internal/armada -count=1`，两个 package 均通过。
- 两个仓库均在 `1.0.1-snapshot`；Zhuan 原有按钮消息改动已按用户要求分别提交为 `114232f 修正安卓按钮消息版本号`、`0a3dbb9 调整安卓按钮消息顶层结构`。

## 验证（evidence-before-done）

- Zhuan 聚焦：`go test ./internal/configs ./internal/armada -count=1`，两个 package 通过。
- Zhuan race：`go test -race ./internal/armada -run 'Test(CommandPool|CommandConsumerRunStops|StartCommandPools)' -count=1`，通过。
- Zhuan vet：`go vet ./internal/configs ./internal/armada`，通过。
- Armada 聚焦：39 tests，0 failure，0 error，BUILD SUCCESS。
- 部署模板：`node armada-deploy/verify-config.mjs` 与 `bash armada-deploy/package-prod.test.sh` 均通过。
- 完整质量门禁和 dev-1 验收结果在后续步骤追加。

## 部署

- 目标: dev-1 (`65.2.123.53`)，用户已确认允许停机和丢弃旧 topic 未消费命令。
- 切换手册：`docs/operations/android-command-topic-isolation-cutover.md`。
- 当前实现 commit：Armada `808cd42`；Zhuan `5c169ce`、`1dda337`。
- 实施后追加 commit、镜像、切换时间和验收结果。

## 遗留 / 跟进

- 旧 `protocol.android.commands.v1` 保留；后续删除必须单独批准。
