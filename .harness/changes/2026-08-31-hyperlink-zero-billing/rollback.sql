-- 仅在确认目标环境尚无任何超链任务审计事实时，才允许与应用版本一起回滚。
DROP TABLE IF EXISTS hyperlink_task_audit_event;
