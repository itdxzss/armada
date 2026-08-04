-- 普通群链接执行域回滚:按依赖逆序删表,再删 pull_task 新增列。
-- 共享库或生产执行前必须单独确认目标环境。

DROP TABLE IF EXISTS pull_task_pull_call;
DROP TABLE IF EXISTS pull_task_account_action;
DROP TABLE IF EXISTS pull_task_group_account;
DROP TABLE IF EXISTS pull_task_material_member;
DROP TABLE IF EXISTS pull_task_group_execution;
DROP TABLE IF EXISTS pull_task_standard_setting;

ALTER TABLE pull_task DROP COLUMN version;
ALTER TABLE pull_task DROP COLUMN finished_at;
ALTER TABLE pull_task DROP COLUMN started_at;

-- 回滚后必须手工删除 flyway_schema_history 中 version='090' 的记录,
-- 否则重新迁移会因 checksum 校验失败导致启动 crash-loop。
DELETE FROM flyway_schema_history WHERE version = '090';
