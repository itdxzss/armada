# 变更记录：Android 同账号群消息串行发送

- 日期 / 分支 / worktree: 2026-07-19 / 当前分支 / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户确认的同账号 Android 群消息串行设计；跨仓设计见 `../whatsapp-server-feature-android-zhuan/docs/superpowers/specs/2026-07-19-android-account-group-message-serialization-design.md`
- 状态: 进行中（设计已确认，待用户审阅书面规格与编写实施计划）

## 目标（一句话）

Armada 把账号群发送间隔写入 Android 消息命令，使 Go 能按账号独立队列完成“发送结果回传后等待间隔，再处理下一条”。

## 缺口拆解 / 任务清单

- [x] 确认普通营销 `accountGroupSendIntervalMs` 当前仅用于 `notBeforeAt` 错峰投递。
- [x] 确认 Android wire payload 当前没有 `sendIntervalMs`。
- [x] 确认 Android 结果先写 Kafka、再提交输入 offset 的现有契约。
- [ ] 为协议无关消息命令补充账号群发送间隔，并由各群消息来源显式赋值。
- [ ] Android backend 把间隔编码为 `sendIntervalMs`，Web backend 保持不变。
- [ ] 补充普通营销、建群营销、历史群营销和 Android payload 契约测试。
- [ ] 与 Go 账号队列实现联调，验证成功/失败结果事件继续更新 attempt/target。

## 关键设计决策

- 范围校验仍由 Armada 负责，Android 不重复校验；旧 payload 缺失字段时 Go 使用 `500ms`。
- `notBeforeAt` 继续作为 Armada outbox 内部投递时间，不复用为 Go 发送完成后的间隔。
- 普通营销传页面配置；没有该页面配置的其他群消息来源显式传 `500ms`。
- 同账号串行属于 Android Go 进程内执行模型；Armada 不使用 Kafka 节奏模拟发送完成，也不引入 Redis 锁。
- 发送成功或业务失败都必须先发布 `message.send_result_reported`，再提交输入 offset。
- 不增加数据库列、Redis schema、Kafka topic 或对外 API。

否决方案：

- 固定 worker 加账号锁：等待同账号锁的消息会占满 worker，拖慢其他账号。
- 小容量单账号队列：正常账号可能一次发送上百个群，会误丢业务消息。
- Kafka 分区串行或全局调度器：会阻塞无关账号或引入当前不需要的复杂调度。

## 验证（evidence-before-done）

设计阶段已核对：

- `MarketingRoundWorker` 使用 `accountGroupSendIntervalMs` 计算 `notBeforeAt`。
- `MessageSendCommand.notBeforeAt` 明确不进入协议 payload。
- `AndroidMessageSendBackend.AndroidMessagePayload` 当前没有发送间隔字段。
- Go `MessageCommandExecutor` 当前顺序为 StoreResult -> Publish result event -> MarkPublished；consumer 随后 Commit。

实施阶段必须补充并运行定向单测；涉及并发的 Go 变更必须运行 `go test -race`。未执行测试前不得标记完成。

## 部署

- commit / 环境 / 部署后验证结果: 尚未实施、未部署。

## 遗留 / 跟进

- 等用户审阅书面规格后编写跨仓实施计划。
- Go 内存队列不设业务容量上限；上线后观察账号队列深度和进程总排队量。
