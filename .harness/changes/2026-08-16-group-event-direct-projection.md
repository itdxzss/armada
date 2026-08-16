# 变更记录：WhatsApp 群变更事件直投影

- 日期 / 分支 / worktree: 2026-08-16 / `1.0.3-group` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户确认普通成员加入/退出与群名、描述、群设置事件不应再次查询 metadata
- 设计文档: `docs/superpowers/specs/2026-08-16-group-event-direct-projection-design.md`
- 实施计划: `docs/superpowers/plans/2026-08-16-group-event-direct-projection-implementation.md`
- 状态: 进行中（方案和实施计划待评审，尚未实施）

## 目标（一句话）

把 Web/Android 已明确携带的群成员和群资料变化直接投影到 Armada，完整 metadata 仅用于首次建档、人工刷新和异常修复。

## 缺口拆解 / 任务清单

- [x] 对账 Web `group-participants.update/groups.update` 当前报文与多余查询链
- [x] 对账 Android WGP2 已支持/缺失的群事件
- [x] 对账 Armada Kafka consumer、成员事实、账号群关系和群资料写入口
- [x] 形成统一成员增量与群资料 fieldMask 契约
- [x] 拆分三仓实施任务、依赖、验证门禁和发布/回滚顺序
- [ ] 评审并确认 Android 群资料 WGP2 脱敏 fixtures
- [ ] 评审 `wa_group_profile` 逐字段版本水位落地方式
- [ ] Armada 先行接入 consumer 和 reducer
- [ ] Web 移除两处事件后的 metadata 请求并发布直接事件
- [ ] Android 接入已确认的群资料 patch
- [ ] Android 补齐 subject/description/announcement/not_announcement/locked/unlocked/member_add_mode/membership_approval_mode/ephemeral/not_ephemeral 解析
- [ ] Android metadata patch 接入 Kafka 重试与本地 DLQ，ACK 与业务成功指标彻底分离
- [ ] test1 联调、流量对比、页面验收和回滚演练

## 关键设计决策

- 使用“事件增量主链 + 完整 metadata 低频校准”，不在 Kafka consumer 同步查询协议。
- Web `group.participant_changed` 扩展消费 add/remove/modify；Android 旧 joined/departed 事件滚动期兼容并汇入同一 reducer。
- 复用已预留的 `group.metadata_updated`，通过 `fieldMask` 区分未出现、false/0 和明确清空。
- notification ACK 只表示协议收包，不作为成功；成功口径固定为解析、可靠投递、Armada 落库、页面可读四段闭环。
- Android 群设置正反节点成对映射，关闭设置也必须实时投影，不能仅支持开启状态。
- 限时消息纳入同一字段级 patch：`ephemeral` 解析秒数，`not_ephemeral` 明确投影为 0。
- `inviteCode` 继续走 `group.invite_link_changed`，避免双写。
- 部分 patch 使用逐字段版本，不能只靠整行 `metadata_observed_at`。
- 目标群尚未建档时创建最小群身份，不因缺少旧 `group_link` 静默丢事件。
- 限时消息已纳入事件直投影；头像没有确认事件和内容获取口径前不猜值，保留 metadata 校准。
- 部署顺序固定为 Armada consumer 先行，再 Web，最后 Android；协议端保留 metadata fallback 紧急开关。

## 验证（evidence-before-done）

方案阶段已执行：

```text
Web 事件测试：2 suites / 61 tests passed
Armada Kafka consumer：51 tests passed
Android WGP2 processor：passed
Android Armada 相关定向测试：passed
```

全量 Android `internal/armada` 测试受当前沙箱禁止 localhost 监听影响，miniredis 用例无法启动；
与群事件定向测试无关。实施阶段仍需在允许本地监听的环境补跑全量测试。

待实施验证命令和 test1 证据见设计文档第 14 节。

## 部署

- commit / 环境 / 部署后验证结果: 尚未实施、未提交、未部署。

## 遗留 / 跟进

- Android 群资料节点真实结构尚待脱敏 fixture 确认。
- 群头像事件能力尚未确认。
- 当前工作区存在群数据模型重建的其他在途改动；实施时必须避免覆盖并统一 Flyway 版本。
