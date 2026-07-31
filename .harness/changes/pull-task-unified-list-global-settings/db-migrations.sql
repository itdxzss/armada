-- 前向变更以 Flyway 文件为唯一事实源，避免维护两份可能漂移的 DDL。
-- 仅在用户确认目标环境后，从 armada 仓库根目录使用 MySQL 客户端执行。
-- 变更内容:pull_task公共字段、pull_task_group_marketing_summary、
-- pull_task_group_marketing_setting、tenant:pull_task:settings权限。
SOURCE armada-api/src/main/resources/db/migration/V088__pull_task_unified_list_and_global_settings.sql;
