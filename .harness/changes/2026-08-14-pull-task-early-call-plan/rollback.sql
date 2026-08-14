-- 必须先回退依赖新列的前后端代码，再执行本结构回滚。
ALTER TABLE pull_task_standard_setting
    DROP COLUMN early_pull_call_count,
    DROP COLUMN early_pull_count;
