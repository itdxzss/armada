-- 仅供确认回滚窗口后人工执行；不得直接在共享库运行。
-- 回滚应用版本后再处理数据库。恢复站台列 NOT NULL 前，必须先补齐 NULL 数据。

DROP TABLE IF EXISTS pull_task_standard_group_setting;

ALTER TABLE pull_task_standard_setting
    DROP COLUMN source_group_folder_id,
    DROP COLUMN source_group_folder_name,
    DROP COLUMN puller_sync_mode,
    DROP COLUMN is_clear_existing_members,
    DROP COLUMN manager_finish_group_id,
    DROP COLUMN manager_finish_group_name,
    DROP COLUMN puller_finish_group_id,
    DROP COLUMN puller_finish_group_name;

-- 仅当以下查询返回 0 时，才允许恢复 NOT NULL：
-- SELECT COUNT(*) FROM pull_task_standard_setting
-- WHERE station_group_id IS NULL OR station_group_name IS NULL;
ALTER TABLE pull_task_standard_setting
    MODIFY COLUMN station_group_id BIGINT NOT NULL COMMENT '站台账号分组ID(→account_group.id)',
    MODIFY COLUMN station_group_name VARCHAR(100) NOT NULL COMMENT '站台分组名称快照';

-- group_folder.name 放宽不会破坏旧版本，不做缩短，避免已有长名称回滚失败。
-- 本脚本不删除 pull_task.config_json/group_name，也不删除本地头像文件。
