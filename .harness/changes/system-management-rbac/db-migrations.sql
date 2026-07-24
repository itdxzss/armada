-- 租户系统管理 RBAC 手工执行入口。
-- 正式部署由 Flyway 自动执行 V071；仅在按仓库根目录启动 mysql 客户端时使用本文件。
-- 执行前请确认当前数据库、Flyway 历史和备份。

SOURCE armada-api/src/main/resources/db/migration/V071__system_management_rbac.sql;
