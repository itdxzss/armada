# 变更记录：账号当前群同步

## 变更概述

- 新增账号当前群同步定时链路：扫描在线正常且已拍 baseline 的账号，写入协议层 master outbox 命令 `account.groups_sync.requested`。
- 新增 `account.groups_reported` Kafka 回填：协议层返回账号当前参与群后，Armada 只展示“当前群 - 登录前 baseline”的差集。
- 新增账号维度群关系表 `account_group_membership`，营销任务账号树和建任务校验改为基于账号实际在群关系。
- 新增账号群同步调度水位 `account_group_baseline.last_group_sync_requested_at`，避免定时任务只反复扫描同一批账号。

## 影响模块

- `account`：账号群同步候选扫描、定时 job、调度水位。
- `platform.protocol`：新增账号群同步 outbox 命令。
- `platform.kafka`：新增账号群列表回报事件解析。
- `group`：账号群回填、群入口/预览/健康/账号 membership 落库。
- `marketing`：账号树和任务目标候选改为读取账号当前在群关系。

## 数据库变更

- `V017__account_group_membership.sql`
  - `group_link.origin` 注释补充 `5=账号同步`。
  - 新建 `account_group_membership`。
- `V018__account_group_sync_watermark.sql`
  - `account_group_baseline` 新增 `last_group_sync_requested_at`。
  - 新增索引 `idx_account_baseline_group_sync_requested`。

## API 变更

- 无新增 HTTP API。

## Kafka 变更

- 新增 outbox command type：`account.groups_sync.requested`。
- 新增 account event type：`account.groups_reported`。

## Redis 变更

- 无。

## 关键约束

- 不覆盖 `account_group_baseline.baseline_group_jids`。
- 账号群同步只扫描 `group_baseline_state=CAPTURED` 的账号。
- `memberCount` 缺失时不清空 `group_link_health.current_count`。
- 定时链路异步走 Kafka；点击详情类实时读取仍走 HTTP master/worker 路由。

## 回滚方案

见 `rollback.sql`。回滚会删除账号 membership 表和调度水位列，营销任务账号树需同步回退到旧的群池口径。
