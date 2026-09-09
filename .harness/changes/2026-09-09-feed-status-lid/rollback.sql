-- 先回滚应用，再删除本功能快照表；不影响已有通讯录。
DROP TABLE IF EXISTS account_status_audience;
