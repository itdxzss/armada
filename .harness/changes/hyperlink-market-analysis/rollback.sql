-- 回滚前先停止 HyperlinkMarketingStatScheduler，确认无需保留聚合数据。
DROP TABLE IF EXISTS hyperlink_stat_hourly;
DROP TABLE IF EXISTS hyperlink_stat_daily;
DELETE FROM sys_menu WHERE menu_key='HyperlinkAnalysis';
ALTER TABLE hyperlink_task_recipient DROP INDEX idx_hyperlink_recipient_market_stat;
ALTER TABLE hyperlink_task_recipient DROP COLUMN sender_device_os_snapshot;
ALTER TABLE hyperlink_task_account_usage DROP COLUMN sender_device_os_snapshot;
