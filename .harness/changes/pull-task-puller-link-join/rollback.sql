-- 先回滚使用该字段的应用版本，再执行本脚本。
ALTER TABLE pull_task_standard_setting
    DROP COLUMN is_puller_join_by_link;
