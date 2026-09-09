-- 复用既有五张表，支持 LID-only 与独立回执；不创建业务表。
ALTER TABLE account_contact
    MODIFY contact_phone VARCHAR(32) NULL COMMENT '真实手机号;LID-only 时为空',
    MODIFY contact_jid VARCHAR(64) NOT NULL COMMENT '规范身份:digits@lid 或 digits@s.whatsapp.net';
ALTER TABLE contact_friend_task_recipient
    MODIFY contact_phone VARCHAR(32) NULL COMMENT '展开时真实手机号;LID-only 时为空',
    MODIFY send_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING SENDING SUCCESS FAILED UNKNOWN SKIPPED';
ALTER TABLE contact_friend_task
    MODIFY retry_max INT NOT NULL DEFAULT 0 COMMENT '保留兼容字段;通讯录任务不自动重发',
    MODIFY success_message_num INT NOT NULL DEFAULT 0 COMMENT '协议确认发送成功数;不代表送达';

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'contact_friend_task_recipient' AND column_name = 'delivered_at') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD COLUMN delivered_at BIGINT NULL COMMENT ''收到送达回执时间(epoch毫秒)''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'contact_friend_task_recipient' AND column_name = 'read_at') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD COLUMN read_at BIGINT NULL COMMENT ''收到已读回执时间(epoch毫秒)''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'contact_friend_task_account' AND column_name = 'stop_reason') = 0,
    'ALTER TABLE contact_friend_task_account ADD COLUMN stop_reason VARCHAR(255) NULL COMMENT ''停止发送的异常原因''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'account_contact' AND index_name = 'uq_account_contact_jid') = 0,
    'ALTER TABLE account_contact ADD UNIQUE KEY uq_account_contact_jid (tenant_id, account_id, contact_jid)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'account_contact' AND index_name = 'uq_account_contact') > 0,
    'ALTER TABLE account_contact DROP INDEX uq_account_contact',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'contact_friend_task_recipient' AND index_name = 'uq_contact_task_recipient_jid') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD UNIQUE KEY uq_contact_task_recipient_jid (tenant_id, task_id, task_account_id, contact_jid)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'contact_friend_task_recipient' AND index_name = 'uq_contact_task_recipient') > 0,
    'ALTER TABLE contact_friend_task_recipient DROP INDEX uq_contact_task_recipient',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'contact_friend_task_recipient' AND index_name = 'idx_contact_recipient_command') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD KEY idx_contact_recipient_command (tenant_id, command_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
