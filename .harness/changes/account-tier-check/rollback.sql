-- 仅回滚方案，未执行。删快照不可恢复，须先备份并单独授权目标环境。
DELETE FROM sys_menu WHERE menu_key='AccountTierCheck';
DROP TABLE IF EXISTS account_tier_check_item;
DROP TABLE IF EXISTS account_tier_check_task;
-- 生产回滚还须新增 Flyway 修复迁移；禁止手改共享库或删除已应用 Flyway 记录。
