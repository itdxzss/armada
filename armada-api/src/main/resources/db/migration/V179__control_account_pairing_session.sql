-- 将推广配对会话表泛化为推广与控台导号共用的短时会话表。
-- active_phone 唯一键继续跨场景生效，避免同一手机号同时发起两个配对流程。
SET @control_pairing_schema := DATABASE();

SET @control_pairing_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@control_pairing_schema
             AND TABLE_NAME='promotion_pairing_session'
             AND COLUMN_NAME='pairing_scene'),
    'SELECT 1',
    'ALTER TABLE promotion_pairing_session ADD COLUMN pairing_scene TINYINT NOT NULL DEFAULT 1 COMMENT ''场景:1推广落地页 2控台认证码导号'' AFTER id');
PREPARE control_pairing_stmt FROM @control_pairing_sql;
EXECUTE control_pairing_stmt;
DEALLOCATE PREPARE control_pairing_stmt;

SET @control_pairing_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@control_pairing_schema
             AND TABLE_NAME='promotion_pairing_session'
             AND COLUMN_NAME='account_group_id'),
    'SELECT 1',
    'ALTER TABLE promotion_pairing_session ADD COLUMN account_group_id BIGINT DEFAULT NULL COMMENT ''控台导号目标账号分组ID'' AFTER owner_user_id');
PREPARE control_pairing_stmt FROM @control_pairing_sql;
EXECUTE control_pairing_stmt;
DEALLOCATE PREPARE control_pairing_stmt;

SET @control_pairing_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@control_pairing_schema
             AND TABLE_NAME='promotion_pairing_session'
             AND COLUMN_NAME='remark'),
    'SELECT 1',
    'ALTER TABLE promotion_pairing_session ADD COLUMN remark VARCHAR(255) DEFAULT NULL COMMENT ''控台导号备注'' AFTER account_group_id');
PREPARE control_pairing_stmt FROM @control_pairing_sql;
EXECUTE control_pairing_stmt;
DEALLOCATE PREPARE control_pairing_stmt;

SET @control_pairing_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@control_pairing_schema
             AND TABLE_NAME='promotion_pairing_session'
             AND COLUMN_NAME='promotion_channel_id'
             AND IS_NULLABLE='YES'),
    'SELECT 1',
    'ALTER TABLE promotion_pairing_session MODIFY COLUMN promotion_channel_id BIGINT DEFAULT NULL COMMENT ''推广场景获客渠道ID,控台场景为空''');
PREPARE control_pairing_stmt FROM @control_pairing_sql;
EXECUTE control_pairing_stmt;
DEALLOCATE PREPARE control_pairing_stmt;

SET @control_pairing_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@control_pairing_schema
             AND TABLE_NAME='promotion_pairing_session'
             AND COLUMN_NAME='channel_name'
             AND IS_NULLABLE='YES'),
    'SELECT 1',
    'ALTER TABLE promotion_pairing_session MODIFY COLUMN channel_name VARCHAR(128) DEFAULT NULL COMMENT ''推广场景渠道名称快照,控台场景为空''');
PREPARE control_pairing_stmt FROM @control_pairing_sql;
EXECUTE control_pairing_stmt;
DEALLOCATE PREPARE control_pairing_stmt;

SET @control_pairing_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@control_pairing_schema
             AND TABLE_NAME='promotion_pairing_session'
             AND INDEX_NAME='idx_pairing_tenant_scene_created'),
    'SELECT 1',
    'ALTER TABLE promotion_pairing_session ADD INDEX idx_pairing_tenant_scene_created (tenant_id, pairing_scene, created_at)');
PREPARE control_pairing_stmt FROM @control_pairing_sql;
EXECUTE control_pairing_stmt;
DEALLOCATE PREPARE control_pairing_stmt;
