# 营销任务 WhatsApp 群成员进群事实导出

## 变更概述

- 当前群成员继续在导出时实时查询 WhatsApp，不保存成员快照。
- Android 复用 WhatsApp `w:gp2 add` 通知，通过群同步 Topic 上报最小进群事实。
- Armada 幂等保存最近一次进群事实，为全量明细、按国家明细的进群时间及群统计累计进群数量提供数据。
- 优化群查询失败日志，仅增加候选账号、解析结果、尝试次数和异常类型，不改变原判断与失败策略。

## 影响模块

- Android 协议层：群通知旁路上报 `account.group_participant_joined`。
- Armada：Kafka consumer、群域进群事实 Mapper/Service、营销任务导出数据提供器。
- 数据库：`whatsapp_group_member_join_fact`。

## 关键约束

- 普通 WhatsApp 成员不要求是 Armada 控端账号。
- 只使用 WhatsApp 实际下发的事件时间，不推断部署前历史进群时间。
- 同一成员重新进群时更新最近进群时间；累计数量按任务时间窗口内去重手机号统计。
- 不修改 `GetGroupMember`、营销任务执行或账号上下线公共逻辑。

## 验证

- [x] Armada 相关 Java 编译通过。
- [x] Kafka 事件解析、进群 sink、H2 Mapper、全量/国家导出单测及 Flyway 合同测试：28/28 通过。
- [ ] Android Go 测试：本机无可用 Go 工具链，待具备环境后执行。
- [ ] 第二套测试环境真实 WhatsApp `add` 事件端到端验证。
