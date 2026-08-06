# JSON 账号导入协议标识修正设计

## 背景

普通 JSON 账号导入通过 `AccountImportRowWriter` 创建 `account` 行。当前实现只在六段格式导入时写入
`protocol_id = 'ANDROID'`，JSON 格式不写该字段，导致新导入的 Web 账号继续产生
`protocol_id = NULL`。虽然统一协议路由会把空值兼容为 Web，拉群管理员随机选号仍要求该字段非空，
因此在线正常的 JSON 账号可能被误判为不可用。

## 目标

1. 新增 JSON 格式账号在创建时明确写入 `protocol_id = 'WEB'`。
2. 保持六段格式账号写入 `protocol_id = 'ANDROID'`。
3. `PARAMS` 格式本次保持现状，不扩大修改范围。
4. 提供第一套测试环境的人工修复 SQL，但 Codex 不执行远程数据修改。

## 代码设计

只修改 `AccountImportRowWriter.buildAccount` 的协议标识赋值分支：

- `ImportFormat.SIX`：写入 `ProtocolBackend.ANDROID.name()`；
- `ImportFormat.JSON`：写入 `ProtocolBackend.WEB.name()`；
- `ImportFormat.PARAMS`：不赋值，保持现有行为。

不修改协议路由兼容规则、管理员选号 SQL、数据库列定义或其他账号创建入口。

## 数据修复 SQL

SQL 由用户在已确认的第一套测试环境人工执行。更新前先检查影响行，事务内把全部
`protocol_id IS NULL` 的账号赋值为 `WEB`，同时刷新 `updated_at`：

```sql
SELECT id, protocol_account_id
FROM account
WHERE protocol_id IS NULL;

START TRANSACTION;

UPDATE account
SET protocol_id = 'WEB',
    updated_at = CAST(FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000) AS UNSIGNED)
WHERE protocol_id IS NULL;

SELECT ROW_COUNT() AS updated_rows;

COMMIT;
```

执行后按协议标识聚合验证：

```sql
SELECT protocol_id, COUNT(*) AS account_count
FROM account
GROUP BY protocol_id;
```

## 测试设计

在 `AccountImportRowWriterTest` 中先增加 JSON 导入回归用例，断言捕获到的 `Account.protocolId`
为 `WEB`。测试应在生产代码修改前失败，并在最小实现后通过。保留现有 SIX 导入断言，证明
Android 路径未回归；聚焦测试通过后再运行账号导入相关测试。

## 非目标

- 不自动连接或修改测试环境数据库。
- 不增加 Flyway 数据迁移。
- 不改变 `PARAMS` 导入行为。
- 不部署、不重启服务。
