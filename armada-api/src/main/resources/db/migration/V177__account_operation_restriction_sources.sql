-- 分源保存账号操作限制，允许平台解除只清自己的消息限制，并防止消息失败推断推远平台明确截止。
-- 每个 DDL 都带 information_schema 守卫，兼容环境中断后人工修复再重跑的场景。
SET @restriction_source_schema := DATABASE();

SET @restriction_source_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@restriction_source_schema AND TABLE_NAME='account_state'
             AND COLUMN_NAME='fallback_message_restriction_until'),
    'SELECT 1',
    'ALTER TABLE account_state ADD COLUMN fallback_message_restriction_until BIGINT DEFAULT NULL COMMENT ''单条消息失败推断的消息发送限制截止(epoch毫秒)'' AFTER cooldown_until');
PREPARE restriction_source_stmt FROM @restriction_source_sql;
EXECUTE restriction_source_stmt;
DEALLOCATE PREPARE restriction_source_stmt;

SET @restriction_source_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@restriction_source_schema AND TABLE_NAME='account_state'
             AND COLUMN_NAME='platform_message_restriction_until'),
    'SELECT 1',
    'ALTER TABLE account_state ADD COLUMN platform_message_restriction_until BIGINT DEFAULT NULL COMMENT ''平台account.restricted消息限制截止(epoch毫秒)'' AFTER fallback_message_restriction_until');
PREPARE restriction_source_stmt FROM @restriction_source_sql;
EXECUTE restriction_source_stmt;
DEALLOCATE PREPARE restriction_source_stmt;

SET @restriction_source_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@restriction_source_schema AND TABLE_NAME='account_state'
             AND COLUMN_NAME='platform_message_restriction_active'),
    'SELECT 1',
    'ALTER TABLE account_state ADD COLUMN platform_message_restriction_active TINYINT DEFAULT NULL COMMENT ''平台account.restricted最近状态:1生效 0解除或到期 NULL未观察'' AFTER platform_message_restriction_until');
PREPARE restriction_source_stmt FROM @restriction_source_sql;
EXECUTE restriction_source_stmt;
DEALLOCATE PREPARE restriction_source_stmt;

SET @restriction_source_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@restriction_source_schema AND TABLE_NAME='account_state'
             AND COLUMN_NAME='platform_message_restriction_reported_at'),
    'SELECT 1',
    'ALTER TABLE account_state ADD COLUMN platform_message_restriction_reported_at BIGINT DEFAULT NULL COMMENT ''平台account.restricted最近生效或解除事实时间(epoch毫秒)'' AFTER platform_message_restriction_active');
PREPARE restriction_source_stmt FROM @restriction_source_sql;
EXECUTE restriction_source_stmt;
DEALLOCATE PREPARE restriction_source_stmt;

SET @restriction_source_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@restriction_source_schema AND TABLE_NAME='account_state'
             AND COLUMN_NAME='pulling_restriction_until'),
    'SELECT 1',
    'ALTER TABLE account_state ADD COLUMN pulling_restriction_until BIGINT DEFAULT NULL COMMENT ''拉人限制独立截止(epoch毫秒)'' AFTER platform_message_restriction_reported_at');
PREPARE restriction_source_stmt FROM @restriction_source_sql;
EXECUTE restriction_source_stmt;
DEALLOCATE PREPARE restriction_source_stmt;

-- V173 存量统一投影按能力回填到来源列；历史消息限制来源不可判定，归入保守兜底来源。
UPDATE account_state
SET fallback_message_restriction_until = CASE
      WHEN mute_status IN (1, 3) THEN cooldown_until
      ELSE NULL
    END,
    pulling_restriction_until = CASE
      WHEN mute_status IN (2, 3) THEN cooldown_until
      ELSE NULL
    END
WHERE mute_status IN (1, 2, 3)
  AND cooldown_until IS NOT NULL;

SET @restriction_source_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@restriction_source_schema AND TABLE_NAME='account_state'
             AND INDEX_NAME='idx_account_state_message_restriction_due'),
    'SELECT 1',
    'ALTER TABLE account_state ADD KEY idx_account_state_message_restriction_due (fallback_message_restriction_until, platform_message_restriction_until, id)');
PREPARE restriction_source_stmt FROM @restriction_source_sql;
EXECUTE restriction_source_stmt;
DEALLOCATE PREPARE restriction_source_stmt;

SET @restriction_source_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@restriction_source_schema AND TABLE_NAME='account_state'
             AND INDEX_NAME='idx_account_state_pulling_restriction_due'),
    'SELECT 1',
    'ALTER TABLE account_state ADD KEY idx_account_state_pulling_restriction_due (pulling_restriction_until, id)');
PREPARE restriction_source_stmt FROM @restriction_source_sql;
EXECUTE restriction_source_stmt;
DEALLOCATE PREPARE restriction_source_stmt;
