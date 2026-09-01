-- 记录账号业务风控的人工解除水位，阻止解除前的延迟/重放事实回灌。
-- DDL 带 information_schema 守卫，兼容环境中断后人工修复再重跑。
SET @manual_restriction_clear_schema := DATABASE();

SET @manual_restriction_clear_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@manual_restriction_clear_schema
             AND TABLE_NAME='account_state'
             AND COLUMN_NAME='manual_restriction_cleared_at'),
    'SELECT 1',
    'ALTER TABLE account_state ADD COLUMN manual_restriction_cleared_at BIGINT DEFAULT NULL COMMENT ''业务风控最近人工解除时间水位(epoch毫秒)'' AFTER pulling_restriction_until');
PREPARE manual_restriction_clear_stmt FROM @manual_restriction_clear_sql;
EXECUTE manual_restriction_clear_stmt;
DEALLOCATE PREPARE manual_restriction_clear_stmt;
