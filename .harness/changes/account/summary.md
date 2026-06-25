# 变更记录：账号块 V005 六表迁移 + schema DbTest

- 日期 / 分支 / worktree: 2026-06-25 / main
- 需求来源: docs/business/account-data-model.md + sdd/task-0.1-brief.md
- 状态: 已完成

## 目标（一句话）

建立账号块六张表(account / account_state / account_group / account_credential / account_import_batch / account_import_detail)的 Flyway 迁移 V005，并以真库 DbTest 验证表结构就位。

## 缺口拆解 / 任务清单
- [x] 写 V005__account.sql（六表 DDL，逐字照 account-data-model.md）
- [x] 写 AccountSchemaDbTest（extends DbTestBase，验六表存在）
- [x] 真库跑 armada-api/dbtest.sh AccountSchemaDbTest（PASS，Flyway 应用 V005）
- [x] 写 harness 变更档（summary.md / db-migrations.sql / rollback.sql）
- [x] 刷新 .harness/wiki/数据模型.md
- [x] Commit

## 关键设计决策

- 时间列一律 BIGINT(epoch 毫秒)，无 DATETIME；应用层写 System.currentTimeMillis()。
- is_active 虚拟列写法照 V002：GENERATED ALWAYS AS (IF(deleted_at IS NULL,1,NULL)) VIRTUAL。
- account.ws_phone 列级 CHARACTER SET utf8mb4 COLLATE utf8mb4_bin（按字节精确去重），唯一键 uq_tenant_phone(tenant_id, ws_phone, is_active)。
- account_state 无独立软删（随 account JOIN 过滤），状态列可空，NULL=未上报，step3 Kafka 接入后点亮。
- account_import_batch 无 is_active 虚拟列（仅有 deleted_at，未建软删唯一键），照数据模型原样。
- 迁移版本号 V005（V004 已被 V004__tenant.sql 占用）。

## 验证（evidence-before-done）

armada-api/dbtest.sh AccountSchemaDbTest
# Flyway: Migrating schema `armada` to version "005 - account" -- Successfully applied 1 migration
# 第二次运行: Schema `armada` is up to date. No migration necessary.
# 测试: EXIT=0 (1/1 PASS)

## 部署

- 本地 MySQL 9.3 armada 库已应用 V005。
- 测试服待随 step1 CRUD 主提交一并部署（本任务只建表，无业务代码，无单独部署需求）。

## 遗留 / 跟进

- step1 CRUD 切片：账号分组 CRUD + 账号导入 + 账号列表静态读。
- TODO-7：V001-V003 老表时间列统一改 BIGINT（需与前端对接 agent 协调时序）。
- step3：Kafka 回写点亮 account_state 状态列；account_credential proxy 字段填写。
