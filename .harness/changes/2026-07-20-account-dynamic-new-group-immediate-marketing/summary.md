# 账号动态营销新群即时发送

## 变更概述

- 账号完整群列表回报刷新 membership 时，在内存中计算本次新增群。
- 仅为发送中的 `ACCOUNT_DYNAMIC` 普通营销任务生成 `round_no=0` 首次即时 attempt。
- 首次即时消息沿用任务当前模板、账号内群发送间隔、协议路由和现有 outbox。
- 即时发送不推进正常轮次；新群随后由现有动态目标解析加入统一下一轮。
- 同一任务、账号、群 JID 只触发一次；任务允许自动重试时，协议失败最多重试一次。

## 影响模块

- 账号群关系：群快照刷新返回当前群和新增群差量，首次 baseline 不触发即时营销。
- 普通营销：新增即时发送、一次重试、共享消息命令工厂和 commandId 结果幂等。
- 协议 outbox：复用现有 `MessageSendPort` 和 `protocol_command_outbox`，未增加新队列表或扫描器。
- 调度配置：文本/图片 outbox 批大小参数在所有 profile 下可注入。
- Web、Android Zhuan 和前端：无代码改动。

## 数据库变更

- Flyway `V059__marketing_new_group_immediate_round.sql` 只更新
  `marketing_task_send_attempt.round_no` 列注释：`0=新群首次即时发送，1+=正常任务轮次`。
- 不新增表、列、索引或扫描游标；继续复用
  `uq_marketing_task_attempt_group_round(tenant_id, target_id, round_no, attempt_group_key)` 幂等。
- 前滚说明见 `db-migrations.sql`，回滚只恢复旧列注释，见 `rollback.sql`。

## API 变更

- 无新增或修改 HTTP API、请求字段、响应字段。
- Kafka topic 和 payload 无变更；Web 与 Android Zhuan 不需要理解 `round_no=0` 的营销内部语义。

## Redis 变更

- 无。

## 关键约束

- 只处理账号动态目标，不处理固定群目标。
- 幂等粒度为租户 + 任务 + 账号 target + 群 JID；同群由多个已选账号加入时，每个账号分别触发。
- 首次 baseline 只建立历史群事实，不批量触发即时营销。
- 即时路径不得更新任务 `current_round_no`、`next_round_at`、`last_round_started_at` 或独立建立周期。
- 多个新群按 `account_group_send_interval_ms` 排期，并按现有文本/图片 outbox 上限分批。
- 重试复用同一 attempt 行，切换到 `attempt_no=2` 并生成新 commandId；旧命令迟到结果不能覆盖当前状态。

## 验证

- Java 17 下 `mvn -q -DskipTests test` 通过，全部测试源码（含新增 DbTest）编译成功。
- 两个 Mapper XML 经 `xmllint --noout` 校验通过。
- 12 个聚焦非数据库测试类共 86 tests，0 failures、0 errors、0 skipped；包含 Web/Android 路由后端回归。
- `git diff --check` 和生产/测试代码临时输出扫描通过。
- 真库 DbTest 尚未执行：必须先由用户确认 `.env` 指向可迁移、可回滚的隔离测试库。
- `.harness/wiki/数据模型.md` 尚未更新：V059 真库迁移后按真实 schema 生成，禁止手改生成物。

## 回滚方案

- 未部署时只回退本任务本地代码和 V059 文件；保留用户及其他会话的无关修改。
- 已部署时先停止产生新的群回报即时发送，再回退应用代码；如需恢复旧注释，执行 `rollback.sql`。
- 已写入的 attempt/outbox 是审计与待执行事实，不能通过恢复列注释撤回；回滚前需先确认 outbox 状态。

## 当前状态

- 分支：`1.0.1-snapshot`。
- 按用户要求直接保留当前 worktree 本地修改，未 commit、未部署、未访问远程环境。
