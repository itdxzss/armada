-- 账号类型由单一导入值拆为“导入申报事实 + 当前有效类型 + 协议校验状态”。
-- 存量账号不在迁移时触发协议查询，只回填申报事实并标记为存量未校验。

SET @account_type_schema := DATABASE();

SET @account_type_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_type_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='declared_account_type'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN declared_account_type TINYINT NULL COMMENT ''导入申报账号类型:1个人 2商业'' AFTER account_type');
PREPARE account_type_stmt FROM @account_type_sql;
EXECUTE account_type_stmt;
DEALLOCATE PREPARE account_type_stmt;

SET @account_type_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_type_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='business_verification_level'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN business_verification_level TINYINT NULL COMMENT ''商业认证级别:1蓝标高认证 2明确非高认证;NULL未确认'' AFTER declared_account_type');
PREPARE account_type_stmt FROM @account_type_sql;
EXECUTE account_type_stmt;
DEALLOCATE PREPARE account_type_stmt;

SET @account_type_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_type_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='business_verification_source'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN business_verification_source TINYINT NULL COMMENT ''商业认证识别来源:1凭据元数据 2配对结果 3商业资料查询'' AFTER business_verification_level');
PREPARE account_type_stmt FROM @account_type_sql;
EXECUTE account_type_stmt;
DEALLOCATE PREPARE account_type_stmt;

SET @account_type_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_type_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='business_verification_verified_at'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN business_verification_verified_at BIGINT NULL COMMENT ''商业认证级别最后确认时间(epoch毫秒)'' AFTER business_verification_source');
PREPARE account_type_stmt FROM @account_type_sql;
EXECUTE account_type_stmt;
DEALLOCATE PREPARE account_type_stmt;

SET @account_type_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_type_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='account_type_verify_status'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN account_type_verify_status TINYINT NOT NULL DEFAULT 4 COMMENT ''账号类型校验状态:0待校验 1已匹配 2已纠正 3无法确认 4存量未校验'' AFTER declared_account_type');
PREPARE account_type_stmt FROM @account_type_sql;
EXECUTE account_type_stmt;
DEALLOCATE PREPARE account_type_stmt;

SET @account_type_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_type_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='account_type_verify_source'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN account_type_verify_source TINYINT NULL COMMENT ''账号类型校验来源:1凭据元数据 2配对结果 3商业资料查询'' AFTER account_type_verify_status');
PREPARE account_type_stmt FROM @account_type_sql;
EXECUTE account_type_stmt;
DEALLOCATE PREPARE account_type_stmt;

SET @account_type_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_type_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='account_type_verified_at'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN account_type_verified_at BIGINT NULL COMMENT ''账号类型最后校验时间(epoch毫秒)'' AFTER account_type_verify_source');
PREPARE account_type_stmt FROM @account_type_sql;
EXECUTE account_type_stmt;
DEALLOCATE PREPARE account_type_stmt;

UPDATE account
SET declared_account_type = account_type
WHERE declared_account_type IS NULL;

ALTER TABLE account
    MODIFY COLUMN account_type TINYINT NOT NULL COMMENT '当前有效账号类型:1个人 2商业;初始取申报值,协议可靠结果可纠正',
    MODIFY COLUMN declared_account_type TINYINT NOT NULL COMMENT '导入申报账号类型:1个人 2商业';
