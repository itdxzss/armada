# 营销任务群组执行情况

## 变更概述

营销任务详情按账号和实际群组返回最新有效轮次的发送结果，供前端展示“发送成功 / 发送失败”。

## 影响模块

- Mapper：在现有账号+实际群组聚合查询中增加 `executionResult`。
- API：`GET /api/marketing-tasks/{id}` 的 `accountTargets[].groups[]` 新增可空字段 `executionResult`。
- Service / VO：透传 `SUCCESS | FAILED | null`。

## 数据库变更

无表结构、索引或数据迁移；继续读取现有 `marketing_task_send_attempt` 发送事实。

## API 变更

- `SUCCESS`：最新有效轮次发送成功。
- `FAILED`：最新有效轮次发送失败。
- `null`：该群组尚无成功或失败结果。

## Redis 变更

无。

## 关键约束

- 仅发送尝试状态 1/2 形成有效执行结果。
- 按 `round_no DESC, attempt_no DESC, id DESC` 选择最新有效结果。
- 已提交和已跳过记录不覆盖之前确认的成功/失败。
- 不修改协议层、Kafka 或数据库结构，沿用现有租户拦截。

## 验证

- `xmllint --noout src/main/resources/mapper/marketing/MarketingTaskMapper.xml`：通过。
- `mvn -Dtest=MarketingTaskMapperSqlShapeTest test`：通过，14 条测试，0 失败、0 错误、0 跳过。
- 两条新增真库 DbTest 已连接本机 MySQL，但未执行到业务断言：本机库存在 V055/V056 Flyway 校验和不一致；关闭 Flyway 后又确认 schema 缺少当前分支依赖的 `account_group_send_interval_ms`。未执行 `flyway repair`，也未修改本机 schema。

## 回滚方案

回退详情聚合 SQL、原始行字段、VO 字段、Service 映射及对应测试；无数据回滚。
