-- 正向变更由 Flyway V116__pull_task_puller_join_by_link.sql 执行。
ALTER TABLE pull_task_standard_setting
    ADD COLUMN is_puller_join_by_link TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '拉手是否踩链接进群:0否 1是'
    AFTER is_clear_existing_members;
