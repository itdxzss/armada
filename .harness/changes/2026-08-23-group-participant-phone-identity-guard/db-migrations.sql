-- V139 部署前预检与可恢复备份。执行前先暂停群成员事实写入；Flyway 迁移本身仍由
-- 应用启动执行，禁止手工 ALTER 共享库。

-- 1) 预检所有同租户、同群、同 phone 的重复形态。
--    exact_pair=1 才会被 V139 自动归并；若存在 exact_pair=0，先人工处理，不能强行建唯一键。
SELECT tenant_id,
       group_id,
       phone,
       COUNT(*) AS row_count,
       SUM(pn_jid IS NOT NULL AND lid_jid IS NULL) AS pn_only_count,
       SUM(pn_jid IS NULL AND lid_jid IS NOT NULL) AS lid_only_count,
       COUNT(*) = 2
         AND SUM(pn_jid IS NOT NULL AND lid_jid IS NULL) = 1
         AND SUM(pn_jid IS NULL AND lid_jid IS NOT NULL) = 1 AS exact_pair
FROM wa_group_participant
WHERE phone IS NOT NULL AND phone <> ''
GROUP BY tenant_id, group_id, phone
HAVING COUNT(*) > 1;

-- 2) 备份 V139 将处理的确定配对两侧完整成员行。
CREATE TABLE IF NOT EXISTS bak_participant_phone_guard_20260823 AS
SELECT participant.*
FROM wa_group_participant participant
JOIN (
  SELECT MAX(CASE WHEN pn_jid IS NOT NULL AND lid_jid IS NULL THEN id END) AS pn_id,
         MAX(CASE WHEN pn_jid IS NULL AND lid_jid IS NOT NULL THEN id END) AS lid_id
  FROM wa_group_participant
  WHERE phone IS NOT NULL AND phone <> ''
  GROUP BY tenant_id, group_id, phone
  HAVING COUNT(*) = 2
     AND SUM(pn_jid IS NOT NULL AND lid_jid IS NULL) = 1
     AND SUM(pn_jid IS NULL AND lid_jid IS NOT NULL) = 1
) pair ON participant.id IN (pair.pn_id, pair.lid_id);

-- 3) 备份被迁移的账号绑定原指向。
CREATE TABLE IF NOT EXISTS bak_binding_phone_guard_20260823 AS
SELECT binding.id AS binding_id,
       binding.participant_id AS old_participant_id
FROM wa_account_group_binding binding
JOIN bak_participant_phone_guard_20260823 participant
  ON participant.id = binding.participant_id
WHERE participant.pn_jid IS NOT NULL
  AND participant.lid_jid IS NULL;

-- 4) 记录备份基线；成员备份行数必须是确定配对数的两倍。
SELECT COUNT(*) AS backed_participant_rows
FROM bak_participant_phone_guard_20260823;
SELECT COUNT(*) AS backed_binding_rows
FROM bak_binding_phone_guard_20260823;

-- 5) Flyway 执行 V139 后验收：重复 phone 与悬空绑定均应为 0，唯一键应为 1。
SELECT COUNT(*) AS duplicate_phone_groups
FROM (
  SELECT 1
  FROM wa_group_participant
  WHERE phone IS NOT NULL AND phone <> ''
  GROUP BY tenant_id, group_id, phone
  HAVING COUNT(*) > 1
) duplicate_group;

SELECT COUNT(*) AS dangling_bindings
FROM wa_account_group_binding binding
LEFT JOIN wa_group_participant participant ON participant.id = binding.participant_id
WHERE participant.id IS NULL;

SELECT COUNT(DISTINCT index_name) AS phone_unique_index_count
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'wa_group_participant'
  AND index_name = 'uq_wa_group_participant_phone';
