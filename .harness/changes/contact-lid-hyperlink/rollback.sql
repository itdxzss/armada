-- 只读回滚前检查；不可自动删除 LID 联系人或回执事实。
-- 先暂停任务并排干在途命令；保留兼容扩展列，优先前向修复。
SELECT COUNT(*) AS lid_only_contacts FROM account_contact WHERE contact_phone IS NULL;
SELECT COUNT(*) AS in_flight_recipients FROM contact_friend_task_recipient WHERE send_status = 'SENDING';
-- 存在 LID-only 数据时禁止恢复旧手机号 NOT NULL 约束或重开旧版通讯录任务。
