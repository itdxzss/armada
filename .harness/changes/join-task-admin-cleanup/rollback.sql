-- 审阅用逆向脚本，只有明确授权目标环境、停止调度并确认无在途操作后才可执行。
-- 已经踢出成员或退群的 WhatsApp 外部事实无法通过本脚本恢复。
-- 优先保留字段与执行记录，仅回退入口；下列结构逆向会删除清理审计记录。
DROP TABLE IF EXISTS join_task_cleanup;
ALTER TABLE join_task DROP COLUMN is_clear_admins_and_leave_enabled;
