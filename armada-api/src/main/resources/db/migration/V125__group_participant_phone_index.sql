-- 补群组数据模型设计 §5.4 已定义、V120 建表时遗漏的成员手机号反查索引。
-- 缺该索引时按 (tenant_id, phone) 反查只能退化为 tenant_id 前缀扫描：test1 实测 EXPLAIN
-- 选中 uq_wa_group_participant_pn 且 key_len 仅 8，预估扫描约 26 万行。
-- wa_group_participant 为 50 万行级大表，使用在线 DDL，避免阻塞实时账号快照写入。

SET @index_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'wa_group_participant'
    AND index_name = 'idx_wa_group_participant_phone'
);

SET @ddl := IF(@index_exists = 0,
  'ALTER TABLE wa_group_participant
     ADD KEY idx_wa_group_participant_phone (tenant_id, phone, group_id),
     ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
