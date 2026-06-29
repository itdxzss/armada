# 变更记录：账号导入明细上线跟踪字段

- 日期 / 分支 / worktree: 2026-06-29 / main
- 需求来源: 账号导入对齐一期需求 + wheel 老导入上线链路
- 状态: 已完成数据模型与导入写入侧

## 目标

在 `account_import_detail` 上保存每行导入后的自动上线阶段、派发时间、登录冻结时间、重试次数和登录原因，为后续导入自动上线 job 与协议状态回写提供落库锚点。

## 影响模块

- 账号导入明细表:新增 5 个上线跟踪字段和 3 个索引。
- 账号导入写入:成功入库行写为待派发;重复、格式错误、凭据不全行写为跳过。
- 数据模型文档:按真库 `information_schema` 重新生成。

## 数据库变更

- Flyway: `V016__account_import_online_tracking.sql`
- `account_import_detail` 当前 10 个字段,新增 5 个字段后共 15 个字段:
  - `online_phase`
  - `online_dispatched_at`
  - `login_settled_at`
  - `dispatch_attempts`
  - `login_reason`
- 新增索引:
  - `idx_import_detail_online_phase (online_phase, id)`
  - `idx_tenant_batch_login_result (tenant_id, batch_id, login_result)`
  - `idx_tenant_batch_online_phase (tenant_id, batch_id, online_phase)`

## API 变更

无。本次不改前端契约,不暴露新字段。

## 关键约束

- `login_result` 继续沿用既有列表达首次登录终态,不新增并行结果列。
- `online_phase=1` 仅表示导入成功行待派发,不代表协议已上线。
- 批次 `login_success/login_failed/login_abnormal` 本次不回填,后续按明细聚合对齐。

## 验证

- `xmllint --noout armada-api/src/main/resources/mapper/account/AccountImportDetailMapper.xml`
- `./armada-api/dbtest.sh AccountImportWriteMapperDbTest,AccountImportServiceImplDbTest`

## 回滚

见 `rollback.sql`。回滚会删除新增索引与新增字段,历史上线跟踪数据将丢失。
