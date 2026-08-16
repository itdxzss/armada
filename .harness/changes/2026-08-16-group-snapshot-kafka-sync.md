# 变更记录：WhatsApp 群快照与邀请码 Kafka 同步

- 日期 / 分支 / worktree: 2026-08-16 / `1.0.3-group` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户确认批量账号首次发现大量群时，群详情和邀请码查询应走 Kafka；复用现有 topic 与 Outbox
- 设计文档: `docs/superpowers/specs/2026-08-16-group-snapshot-kafka-sync-design.md`
- 实施计划: `docs/superpowers/plans/2026-08-16-group-snapshot-kafka-sync-implementation.md`
- 状态: 进行中（方案和实施计划已形成，待评审，尚未实施）

## 目标（一句话）

把首次建档、人工刷新和异常修复的群详情/邀请码主动查询从 Armada 批量同步 HTTP 改为现有 Outbox + Kafka
异步命令/结果闭环，并按唯一群去重和按权限轮换受控账号。

## 缺口拆解 / 任务清单

- [x] 对账现有 Outbox、Web master command、Android group-action command 和 group event topic
- [x] 对账当前 metadata/invite HTTP 查询入口与管理员硬过滤
- [x] 确认不新增 topic、不新增 Outbox 表
- [x] 形成 `group.snapshot_sync.requested` 命令契约
- [x] 形成 `group.snapshot_sync_result_reported` 部分成功结果契约
- [x] 形成 100 账号/500 唯一群去重、候选轮换和背压方案
- [x] 拆分三仓实施任务、依赖、验收门禁和发布顺序
- [ ] 评审命令/结果字段、scope 和稳定错误码
- [ ] 核对目标环境 Android group event topic 实际配置
- [ ] 固化 Web/Android 最大成员群与普通成员邀请码权限脱敏 fixtures
- [ ] Armada Flyway 与任务状态机改造
- [ ] Armada Outbox、result consumer、reducer 和 selector 实现
- [ ] Web Kafka executor、错误归一和可靠结果发布
- [ ] Android Kafka executor、错误归一和可靠结果发布
- [ ] 自动首次建档和手工批量刷新接入
- [ ] test1 联调与 100 账号/500 群压测

## 关键设计决策

- 不新增 Kafka topic；Web 复用 master command，Android 复用 group-action command，结果复用 group event。
- 不新增 Outbox 表；通过新 commandType 和现有每行 kafka_topic/kafka_key 路由。
- 自动任务按 `tenantId + groupLinkId` 唯一，账号群关系数量不放大群查询数量。
- metadata 与邀请码按 scope 独立结算；邀请码权限失败不丢 metadata，也不重复查询完整成员。
- 邀请码候选管理员优先、普通成员兜底，最多 4 个，以 WhatsApp 服务端鉴权结果为准。
- 只读取当前邀请码，不执行 revoke/reset；失败保留旧邀请码。
- 协议端必须解析成结构化结果，ACK、日志或原始 node 都不能代替控端落库。
- 普通群变更继续事件直投影，本方案只覆盖首次建档、人工刷新和异常修复旁路。
- 一群一条结果；超过安全消息阈值不得截断后伪装完整成功。

## 验证（evidence-before-done）

方案阶段完成当前代码静态对账，尚未修改业务代码、未运行新增测试。

实施验证以设计文档第 18 节为准，必须包含：

- 100 账号 × 500 重叠群只形成 500 个自动任务；
- Outbox 与任务状态同事务；
- Web/Android topic 路由和 protocolAccountId key；
- metadata 成功 + invite 权限失败的部分成功与候选轮换；
- 重复、迟到、超时、重启和取消；
- test1 普通成员邀请访问权限开/关；
- 最大成员群 Kafka payload 实测。

## 部署

- commit / 环境 / 部署后验证结果: 尚未实施、未提交、未部署。

## 遗留 / 跟进

- Android group event topic 的代码默认值与 Armada/Web 默认值存在命名差异，需以目标环境配置核对。
- 普通成员邀请权限对应的真实协议设置节点尚未固化。
- 当前群数据模型存在 V121/V122 在途 migration，本功能 Flyway 必须顺延且避免冲突。
- 人工批量任务取消后的晚到只读结果建议仍允许更新群事实，但不改变已取消任务计数，待评审确认。
