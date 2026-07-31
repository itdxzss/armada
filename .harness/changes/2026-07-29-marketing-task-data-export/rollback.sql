-- 应用回滚时优先保留这两个兼容列及历史退群事实；旧版本不会读取它们。
-- 仅在确认无需保留导出事实、应用已回滚且无新版本依赖时，才人工执行以下 DDL：

ALTER TABLE account_group_membership
    DROP COLUMN last_exited_at,
    DROP COLUMN last_exit_type;
