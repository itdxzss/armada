-- 通讯录营销发送引擎补列。
-- V158 建表时只覆盖 CRUD 需要的列，轮次号和命令 ID 是发送闭环才用到的。
-- round_no 是协议 payload 的必填四字段之一（contactTaskId/taskAccountId/recipientId/roundNo），
-- 缺了协议层会判 invalid message send payload 直接丢弃。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'contact_friend_task'
       AND column_name = 'current_round_no') = 0,
    'ALTER TABLE contact_friend_task ADD COLUMN current_round_no BIGINT NOT NULL DEFAULT 0 COMMENT ''已抢占的最新轮次号;每轮加一,写进协议关联供回执定位''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'contact_friend_task_recipient'
       AND column_name = 'round_no') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD COLUMN round_no BIGINT DEFAULT NULL COMMENT ''本条最近一次投递所属轮次号;未投递为NULL''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'contact_friend_task_recipient'
       AND column_name = 'command_id') = 0,
    'ALTER TABLE contact_friend_task_recipient ADD COLUMN command_id VARCHAR(64) DEFAULT NULL COMMENT ''本条最近一次投递的协议命令ID;跨层排查用''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
