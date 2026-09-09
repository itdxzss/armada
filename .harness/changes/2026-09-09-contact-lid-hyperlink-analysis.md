# 变更记录：通讯录 LID 超链需求分析

- 日期：2026-09-09。
- 分支 / worktree：`1.0.3-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`。
- 需求来源：用户“先分析”；附件仅作为需求和历史证据材料。
- 状态：用户已确认五项首版范围；提出复用现有五表的最小数据模型调整建议，实现未开始。

## 目标

核对附件与现有代码，明确 LID-only 通讯录超链任务的真实缺口。

## 任务清单

- [x] 读取仓库规则和 request-analysis 技能，确认三个仓库分支及已有修改。
- [x] 核对现有通讯录任务、前端、快照同步、Kafka 发送和回执链路。
- [x] 产出[分析与设计建议](/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/specs/2026-09-09-contact-lid-hyperlink-design.md)。
- [x] 根据用户反馈确定 Kafka 入口、Armada 不直连协议 HTTP、发送间隔由页面配置且不强制 60 秒下限。
- [x] 根据用户纠正移除 Armada 按间隔定时投递的建议，明确协议层基于实际网络派发时刻控制账号级间隔。
- [x] 根据用户认可的五项范围收缩设计，核对 V162/V163/V165/V166 与现有 Mapper/实体，提出无需新增业务表的方案。
- [ ] 用户确认产品口径后再制定实现变更。

## 已确认设计决策

- 本任务发送及通讯录刷新经 outbox/Kafka 到协议节点，Armada 不直接 HTTP 调用协议层；不要求新增协议 HTTP 路由。
- 发送间隔由页面配置，取消附件 60 秒固定值/强制下限的实现约束。建议复用既有最小/最大间隔字段，Armada 校验保存，节点按账号控制实际派发。
- 协议层为实际发送间隔的唯一执行方；Armada 下发配置，Kafka 可靠传递命令，不按间隔延迟投递/消费。本功能需解除 worker 按间隔设置逐条 notBeforeAt 的依赖。协议以实际网络派发时刻更新账号时钟，晚发顺延，不追赶补发，ACK 到达不作为计时起点。
- 用户已确认首版五项：LID-only 入库、Kafka 接通 LID 超链、页面配置且协议控速、送达/已读回执、异常停发及结果不明不重发。统一配额、快照多版本平台、通用控制框架等扩展项移出首版；既有每任务每号上限保留，不新增账号日额度功能。
- 数据模型建议复用 account_contact、account_contact_sync、contact_friend_task、contact_friend_task_account、contact_friend_task_recipient；调整手机号可空和 JID 唯一键，补明细送达/已读时间及结果不明状态，按需求补账号停发原因。仅建议，未执行 DDL。

## 关键发现

- 现有 contact_friend_task 三表和页面已存在，建议增量改造。
- 手机号强制校验、去重和 NOT NULL 索引不支持 LID-only；Go 快照还有将数字 LID 改写为 PN 的路径。
- 业务发送走 Kafka 内部 sender，HTTP 路由改造不足以接通。
- 通讯录 success 当前来自服务器 ACK；送达/已读关联限于普通 hyperlink_task。
- 默认 0.5–1 秒、两层重试、任务内 50 条上限与附件的发送约束不一致。
- 云端查询探针源码本地未找到；正式 app-state 快照与探针来源对应关系待确认。

## 验证

执行了本地 git status/branch/worktree 检查和 rg/sed/nl 静态阅读，证据路径见设计稿。未运行测试、未 SSH、未连接数据库、未触发真实账号行为。已有其他会话的业务修改未动。

后续设计修订静态阅读了 ws-go `message_account_scheduler.go` 中的 `dispatchReady` 及账号调度结构；已有协议层派发时钟可以复用，网络实际派发测量点仍需实现阶段核对。增加协议侧实际间隔验收要求，未执行测试。

## 变更与回滚

本次仅新增本记录和分析稿。API、DB、Redis、业务代码无变更，无部署。无需数据库回滚。

## 遗留

间隔默认值与数值范围、是否包含无名字联系人、异常停止范围及具体最小 DDL 尚未确定。模板按钮、联系人分组及账号日额度扩展不纳入已确认的五项范围。真机账号在真实验收前再明确。附件历史实测结果本次未重新验证。

## 设计审查（2026-09-09）

用户要求检查设计稿还有哪些不合理之处。新增[设计审查意见](/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/reviews/2026-09-09-contact-lid-hyperlink-design-review.md)，保留当前设计稿供对照，未将审查建议自动视为已确认方案。

审查发现八项缺口：协议停发控制、幂等有效期、快照一致性、任务完成状态、PN/LID 身份与映射、间隔 wire 契约及精度验收、跨日额度、端到端验收顺序。静态核对了任务动作/完成逻辑、快照 sink/mapper、协议消费队列及 Redis 发送状态；未执行测试或真实账号操作。
