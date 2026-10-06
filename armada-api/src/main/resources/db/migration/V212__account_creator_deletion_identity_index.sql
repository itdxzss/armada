-- account.ws_phone 为 VARCHAR(32)，规范化仅删除字符，不会扩大字段。
-- 相同 WhatsApp 身份的多个导入行必须共用顺序一致的索引范围锁，禁止全表函数扫描锁住无关账号。
SET @creator_identity_column_sql := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='account' AND column_name='creator_deletion_identity_phone')=0,
  'ALTER TABLE account ADD COLUMN creator_deletion_identity_phone VARCHAR(32) GENERATED ALWAYS AS (REPLACE(REPLACE(TRIM(ws_phone),''+'',''''),'' '','''')) STORED COMMENT ''永久注销使用的规范化身份索引，不包含授权材料''',
  'SELECT 1');
PREPARE creator_identity_column_stmt FROM @creator_identity_column_sql;
EXECUTE creator_identity_column_stmt;
DEALLOCATE PREPARE creator_identity_column_stmt;
SET @creator_identity_index_sql := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='account' AND index_name='idx_account_creator_deletion_identity')=0,
  'ALTER TABLE account ADD KEY idx_account_creator_deletion_identity (creator_deletion_identity_phone,id)',
  'SELECT 1');
PREPARE creator_identity_index_stmt FROM @creator_identity_index_sql;
EXECUTE creator_identity_index_stmt;
DEALLOCATE PREPARE creator_identity_index_stmt;
