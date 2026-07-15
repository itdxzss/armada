# 变更记录：Android 营销消息 Kafka 适配

- 日期 / 分支 / worktree: 2026-07-15 / `1.0.1-snapshot` / 主 worktree
- 需求来源: 用户本次确认；`docs/superpowers/specs/2026-07-15-web-android-marketing-message-kafka-design.md`
- 状态: 实现、聚焦回归、独立代码复核及双仓提交推送完成，待确认测试环境后灰度

## 目标（一句话）

让 Armada 通过统一消息协议端口把 Web/Android 营销命令路由到各自 Kafka backend，并由 Android Zhuan 完成五种消息与真正提醒所有人的发送和统一结果回写。

## 缺口拆解 / 任务清单

- [x] 对账 Armada 现有营销 outbox、Web 消费者和 Android 原生消息能力。
- [x] 确认 Android Kafka 通道、单跳转按钮、Armada 校验、图片说明文字与提醒所有人口径。
- [x] 产出双协议营销消息设计文档。
- [x] 用户确认设计边界并要求开始实施计划。
- [x] 编写双仓逐步实施计划：
  - `docs/superpowers/plans/2026-07-15-armada-marketing-message-routing-implementation.md`
  - `docs/superpowers/plans/2026-07-15-android-zhuan-marketing-message-implementation.md`
- [x] TDD 实现 Armada 统一消息 port、routing 和 Web/Android backend。
- [x] TDD 实现 Android 消息命令消费、原生消息增强、幂等和结果事件。
- [x] 运行 Java、Go 聚焦回归、Go vet 和 Web 协议契约回归。
- [x] 增加 Android 成功、mention-all 失败和不确定结果到 Armada 消费契约回归。
- [x] 修复复核发现的迟到发送结果竞争和 mention-all 成员身份不完整问题，并完成二次复核。
- [x] 补充 Android backend、Kafka commit 时序、HTTP 兼容组包、原生 payload、publisher、sender 和 Redis 状态机设计注释。
- [x] 用户审阅本地 diff 并授权提交推送。
- [ ] 确认测试环境后执行真实 Redis/Kafka/WhatsApp 灰度验收。

## 关键设计决策

- 复用 `ProtocolAccountRef` 和现有 routing backend 模式，营销业务不出现协议实现分支。
- Android 消息走 `protocol.android.commands.v1`，不由 Armada 同步调用 Zhuan HTTP。
- Android 按钮必须恰好一个有效跳转链接；校验由 Armada Android backend 完成，Web 能力不受限。
- Android 需要开发图片 caption、单 CTA URL 按钮和真实 mention-all。
- 统一结果继续使用 `message.send_result_reported`。
- 崩溃后的不确定副作用选择不自动重发，避免营销消息重复触达。

## 验证（evidence-before-done）

- Armada（`armada-api/`）聚焦测试：
  - 命令：`mvn -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='RoutingMessageSendPortTest,WebMessageSendBackendTest,AndroidMessageSendBackendTest,ProtocolCommandOutboxServiceImplTest,ProtocolConfigurationTest,MarketingRoundWorkerTest,GroupCreationMarketingWorkerTest,ProtocolMessageEventConsumerTest,MarketingSendResultServiceImplTest,MarketingTaskMapperSqlShapeTest,GroupCreationMarketingTaskMapperSqlShapeTest#accountCandidatesReadCurrentProtocolRoutingFact' test`
  - 结果：95 tests，0 failure，0 error，0 skipped，BUILD SUCCESS。
- Android Zhuan 聚焦测试：
  - `go test ./internal/configs ./internal/service/entity ./internal/service/node ./internal/service/app ./api/service`：通过。
  - 允许 miniredis 绑定本机随机端口后执行 `go test ./internal/armada -count=1`：通过。
  - `go test ./internal/armada -run 'Test(ParseMessageCommand|ParseCommand|ParseRawProtocolCommand|BuildMessageResultEvent|MessageEventPublisher|NormalizeOptions|OptionsFromConfig|ConfigDecodesKafka|MessageCommandExecutor|UnifiedCommandHandler|ZhuanMessageSender|CommandConsumerMessage)'`：通过。
  - `go vet ./internal/armada ./internal/configs ./internal/service/entity ./internal/service/node ./internal/service/app ./api/service`：通过。
- Web 协议契约：
  - `npm test -- src/commands/types.test.ts src/commands/worker-consumer.test.ts src/messages/card-content.test.ts --runInBand`
  - 结果：3 suites / 33 tests 全部通过。
- 注释补充后的聚焦回归：
  - `AndroidMessageSendBackendTest`：9 tests 全部通过，BUILD SUCCESS。
  - `go test ./internal/service/node ./api/service`：通过。
  - `go test ./internal/armada -run 'Test(CommandConsumer|Start|BuildKafkaReaderConfig|BuildConsumerRunners|GroupSnapshotRuntime|LifecycleCommandHandler|UnifiedCommandHandler|MessageEventPublisher|ZhuanMessageSender)'`：通过。
  - `go vet ./internal/armada ./internal/service/node ./api/service`：通过。
- 完整 `go test ./...` 未通过，已核对失败均不在本次业务 diff：
  - `internal/service/appstate/appstate_test.go` 既有参数类型编译错误；
  - 沙箱禁止 `miniredis` / `httptest` 监听本地端口；
  - `pkg/noise` 既有向量/工作目录依赖失败。
- 代码复核结果：两轮只读复核后无剩余 Critical / Important / Minor correctness 问题；迟到 sender 只发布 Redis 权威结果，`workerId/resultAt` 固定；mention-all 对零成员、空身份、非法身份和无法映射 LID 均整体失败。
- Redis 消息状态 key 固定为 `armada:zhuan:message:command:<commandId>`，TTL 复用 `contextttlseconds`；状态只保存 phase、时间、worker ID 和发送结果，不保存正文、base64、缩略图或按钮 URL。
- `messagecommandsenabled` 在代码和两份示例 TOML 中均默认 `false`。
- `git diff --check` 与相关 Go `gofmt` 已通过；未运行真库 DbTest、远程 Kafka/Redis 或真实 WhatsApp 发送。

## 部署

- commit / push：
  - Armada `ea6dd6a 安卓协议接入营销功能`、`85d3458 补充安卓营销消息链路注释` 已推送到 `origin/1.0.1-snapshot`。
  - Android Zhuan `66d1edf 接入营销消息 Kafka 原生发送` 已推送到 `origin/1.0.1-snapshot`。
- 环境 / 部署后验证结果：尚未部署；未获目标环境确认，不执行 SSH、部署或共享中间件写入。

## 遗留 / 跟进

- Armada 工作区原有 `.claude/worktrees/*` 状态未触碰；Android Zhuan 开始实施时为干净工作区。
- 测试环境灰度顺序：先部署 Android 且保持消息开关关闭，再部署 Armada routing，停止新 Android outbox 后才开启 Android 消息消费；回滚时反向操作且不删除 outbox/Redis 审计状态。
