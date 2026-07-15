# 变更记录：Android 营销消息 Kafka 适配

- 日期 / 分支 / worktree: 2026-07-15 / `1.0.1-snapshot` / 主 worktree
- 需求来源: 用户本次确认；`docs/superpowers/specs/2026-07-15-web-android-marketing-message-kafka-design.md`
- 状态: 进行中

## 目标（一句话）

让 Armada 通过统一消息协议端口把 Web/Android 营销命令路由到各自 Kafka backend，并由 Android Zhuan 完成五种消息与真正提醒所有人的发送和统一结果回写。

## 缺口拆解 / 任务清单

- [x] 对账 Armada 现有营销 outbox、Web 消费者和 Android 原生消息能力。
- [x] 确认 Android Kafka 通道、单跳转按钮、Armada 校验、图片说明文字与提醒所有人口径。
- [x] 产出双协议营销消息设计文档。
- [ ] 用户评审书面设计。
- [ ] 编写逐步实现计划。
- [ ] TDD 实现 Armada 统一消息 port、routing 和 Web/Android backend。
- [ ] TDD 实现 Android 消息命令消费、原生消息增强、幂等和结果事件。
- [ ] 运行 Java、Go、Web 协议回归和端到端验证。
- [ ] 完成后端专家评审、部署确认与测试环境验收。

## 关键设计决策

- 复用 `ProtocolAccountRef` 和现有 routing backend 模式，营销业务不出现协议实现分支。
- Android 消息走 `protocol.android.commands.v1`，不由 Armada 同步调用 Zhuan HTTP。
- Android 按钮必须恰好一个有效跳转链接；校验由 Armada Android backend 完成，Web 能力不受限。
- Android 需要开发图片 caption、单 CTA URL 按钮和真实 mention-all。
- 统一结果继续使用 `message.send_result_reported`。
- 崩溃后的不确定副作用选择不自动重发，避免营销消息重复触达。

## 验证（evidence-before-done）

当前只完成设计，尚未执行实现验证。后续记录实际命令和完整结果。

## 部署

- commit / 环境 / 部署后验证结果: 尚未部署。

## 遗留 / 跟进

- Android Zhuan 当前 worktree 存在群快照相关在途修改；实现前需重新检查重叠文件并保留其它会话改动。
- 用户书面评审设计后再进入实现计划与编码。
