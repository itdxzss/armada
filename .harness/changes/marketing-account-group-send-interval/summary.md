# 普通营销任务单账号下群组发送间隔

## 变更概述

- 新增页面字段“单账号下群组发送间隔”，默认 0.5 秒，范围 0.5～3.0 秒，步长 0.1 秒。
- 普通营销任务保存该配置，每轮按账号分别计算各群消息的最早投递时间。
- Web 与 Android 共用 Armada 侧排期；Kafka payload 不新增字段，协议层无改动。

## 实现方式

- `marketing_task` 以整数毫秒保存任务配置。
- `MarketingRoundWorker` 对每个账号独立计数，第 `n` 个群的发送时间为
  `roundStartedAt + n * intervalMs`，其中 `n` 从 0 开始。
- `MessageSendCommand.notBeforeAt` 只在 Armada 内部传递，不进入协议 payload。
- outbox 直接复用现有 `next_retry_at` 保存最早投递时间。
- 事务提交后，到期行立即交给现有 dispatcher；未来行复用应用现有调度器到点提交。
- 原有低频 outbox 扫描继续负责服务重启或本机定时任务丢失后的兜底。

## 数据库变更

- 仅新增 `marketing_task.account_group_send_interval_ms INT NOT NULL DEFAULT 500`。
- 没有新增 outbox 列或独立节流表。
- Flyway 迁移为 V058，归档 SQL 见同目录 `db-migrations.sql`。

## API 变更

- 创建普通营销任务请求新增 `accountGroupSendIntervalSeconds`。
- 任务列表与详情返回同名字段。
- 旧调用未传值时统一使用 0.5 秒。

## 边界

- 固定的是 Armada 向 Kafka 推送同一账号群消息命令的时间差，不等待上一条 WhatsApp 发送完成。
- 不同账号各自从本轮开始时间排期，互不等待。
- 只作用于普通营销任务；建群营销和历史群营销保持原行为。
- 这是用户确认的简单实现；不增加多实例全局节流状态。

## 回滚

- 先回滚依赖新字段的 Armada 代码，再执行同目录 `rollback.sql`。
- 回滚会删除任务间隔字段，已保存的间隔配置不可恢复。

## 验证

- 后端最终相关单元测试 90 个全绿，Java 17 打包成功。
- 前端页面测试 12 个全绿，双类型检查、ESLint、Prettier 和生产构建通过。
- MyBatis XML 与前后端 `git diff --check` 通过。
- 真库 DbTest 尚未运行，等待用户确认非敏感目标 `localhost:3306 / armada`。
