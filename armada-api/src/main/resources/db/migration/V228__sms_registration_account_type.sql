-- 保存办理开始时选择的账号类型；历史记录均来自个人版，默认补为 1。
-- 类型与 go_profile_id 一起冻结，后台重放和验码不得改变。
SET @sms_reg_schema := DATABASE();
SET @sms_reg_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@sms_reg_schema AND TABLE_NAME='sms_registration'
             AND COLUMN_NAME='account_type'),
    'SELECT 1',
    'ALTER TABLE sms_registration ADD COLUMN account_type TINYINT NOT NULL DEFAULT 1 COMMENT ''注册账号类型:1个人2商业,开始时冻结''');
PREPARE sms_reg_stmt FROM @sms_reg_sql;
EXECUTE sms_reg_stmt;
DEALLOCATE PREPARE sms_reg_stmt;
