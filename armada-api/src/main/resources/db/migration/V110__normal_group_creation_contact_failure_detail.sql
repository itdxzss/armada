-- 新建普群加好友降级为可选前置动作后，失败明细必须完整保留。
-- 两个联系人方向原先共用 normal_group_creation_item_member 上的一对 last_error_* 列，
-- 加好友失败不再终止明细后，对向方向的成功回执会把已记录的失败原因覆写为 NULL。
-- 这里按方向拆出独立的错误列，并在计划群上落一个「加好友有失败」标记，
-- 让群建成功（status=CREATED）时仍能在列表里识别出哪些群的加好友没有全部成功。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND column_name = 'creator_save_error_code') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD COLUMN creator_save_error_code VARCHAR(64) DEFAULT NULL COMMENT ''建群人保存该成员失败的原因码'' AFTER member_save_command_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND column_name = 'creator_save_error_message') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD COLUMN creator_save_error_message VARCHAR(512) DEFAULT NULL COMMENT ''建群人保存该成员失败的脱敏摘要'' AFTER creator_save_error_code',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND column_name = 'member_save_error_code') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD COLUMN member_save_error_code VARCHAR(64) DEFAULT NULL COMMENT ''该成员保存建群人失败的原因码'' AFTER creator_save_error_message',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item_member'
       AND column_name = 'member_save_error_message') = 0,
    'ALTER TABLE normal_group_creation_item_member ADD COLUMN member_save_error_message VARCHAR(512) DEFAULT NULL COMMENT ''该成员保存建群人失败的脱敏摘要'' AFTER member_save_error_code',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_item'
       AND column_name = 'contact_prepare_failed') = 0,
    'ALTER TABLE normal_group_creation_item ADD COLUMN contact_prepare_failed TINYINT NOT NULL DEFAULT 0 COMMENT ''进入建群阶段时是否存在未成功的加好友方向:0否1是'' AFTER leave_command_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 已有明细的历史失败原因仍留在 last_error_* 上，按方向状态回填到新列，避免升级后旧数据看不到原因。
UPDATE normal_group_creation_item_member
SET creator_save_error_code = last_error_code,
    creator_save_error_message = last_error_message
WHERE last_error_code IS NOT NULL
  AND creator_save_error_code IS NULL
  AND creator_saved_member_status IN ('FAILED', 'UNKNOWN');

UPDATE normal_group_creation_item_member
SET member_save_error_code = last_error_code,
    member_save_error_message = last_error_message
WHERE last_error_code IS NOT NULL
  AND member_save_error_code IS NULL
  AND member_saved_creator_status IN ('FAILED', 'UNKNOWN');

UPDATE normal_group_creation_item item
SET item.contact_prepare_failed = 1
WHERE item.current_step <> 'PREPARING_CONTACTS'
  AND EXISTS (
    SELECT 1 FROM normal_group_creation_item_member member
    WHERE member.tenant_id = item.tenant_id
      AND member.item_id = item.id
      AND (member.creator_saved_member_status <> 'SUCCESS'
        OR member.member_saved_creator_status <> 'SUCCESS')
  );
