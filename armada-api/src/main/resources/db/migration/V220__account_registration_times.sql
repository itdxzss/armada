-- 号质量分析用的两个时间，只在导入后第一次上线（账号类型识别那一次）写入，之后重连不再改。
-- wa_creation_at：Android 登录 success 节点的 creation，即该号在这台设备注册/激活的时间（号商重新激活会刷新），不是最初注册时间。
-- wa_business_member_since_at：商业资料 member_since_ts，即成为 WhatsApp Business 的时间；个人号为空。
-- 只加可空列，旧代码不读写这两列，不影响现有业务。

SET @account_reg_schema := DATABASE();

SET @account_reg_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_reg_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='wa_creation_at'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN wa_creation_at BIGINT NULL COMMENT ''WhatsApp登录success节点creation:该号在当前设备注册/激活时间(epoch毫秒),导入后首次上线写入一次,例如 1793175171000''');
PREPARE account_reg_stmt FROM @account_reg_sql;
EXECUTE account_reg_stmt;
DEALLOCATE PREPARE account_reg_stmt;

SET @account_reg_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@account_reg_schema AND TABLE_NAME='account'
             AND COLUMN_NAME='wa_business_member_since_at'),
    'SELECT 1',
    'ALTER TABLE account ADD COLUMN wa_business_member_since_at BIGINT NULL COMMENT ''商业资料member_since_ts:成为WhatsApp Business的时间(epoch毫秒),个人号为空,导入后首次上线写入一次,例如 1793175176000''');
PREPARE account_reg_stmt FROM @account_reg_sql;
EXECUTE account_reg_stmt;
DEALLOCATE PREPARE account_reg_stmt;
