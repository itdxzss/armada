-- 首选仅回滚应用并保留 nullable 新列，避免丢失审计证据。
-- 以下结构回滚仅可在停用所有依赖 V209 的应用、备份证据并确认目标环境后执行。
-- 本次未执行任何真实数据库变更；本脚本不处理 V208 随机间隔列。
ALTER TABLE pull_task_group_execution
    DROP COLUMN profile_verified_command_id,
    DROP COLUMN profile_verified_at;
