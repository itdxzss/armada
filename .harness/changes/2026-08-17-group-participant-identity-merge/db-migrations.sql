-- 群成员 PN/LID 身份归并（V126）配套数据脚本
--
-- ⚠️ 执行 V126 之前必须先跑本文件的备份语句。V126 会删除 56928 个 PN 行并改写
-- LID 行的事实列与账号绑定指向，删除后无法从表内自行还原，只能靠这里的备份回滚。
--
-- 前置条件：V125（成员手机号反查索引）与 incomingRoleWins 降级保护必须已上线。
-- 后者未上线时禁止归并——pn_jid 一旦落到 LID 行，账号快照会用布尔 admin 把
-- 完整成员快照确认的精确群主角色逐步覆盖成管理员。

-- 1) 备份将被删除的 PN 行（完整行，供回滚重建）
CREATE TABLE IF NOT EXISTS bak_participant_merge_pn_20260817 AS
SELECT p.*
FROM wa_group_participant p
JOIN (
  SELECT MAX(CASE WHEN pn_jid IS NOT NULL THEN id END) AS pn_id
  FROM wa_group_participant
  WHERE phone IS NOT NULL AND phone <> ''
  GROUP BY tenant_id, group_id, phone
  HAVING COUNT(*) = 2
     AND SUM(pn_jid IS NOT NULL) = 1
     AND SUM(lid_jid IS NOT NULL) = 1
) m ON m.pn_id = p.id;

-- 2) 备份将被改写的 LID 行（事实列会按来源分级归并，需原值才能回滚）
CREATE TABLE IF NOT EXISTS bak_participant_merge_lid_20260817 AS
SELECT p.*
FROM wa_group_participant p
JOIN (
  SELECT MAX(CASE WHEN lid_jid IS NOT NULL THEN id END) AS lid_id
  FROM wa_group_participant
  WHERE phone IS NOT NULL AND phone <> ''
  GROUP BY tenant_id, group_id, phone
  HAVING COUNT(*) = 2
     AND SUM(pn_jid IS NOT NULL) = 1
     AND SUM(lid_jid IS NOT NULL) = 1
) m ON m.lid_id = p.id;

-- 3) 备份账号绑定的原 participant_id 指向
CREATE TABLE IF NOT EXISTS bak_binding_merge_20260817 AS
SELECT b.id AS binding_id, b.participant_id AS old_participant_id
FROM wa_account_group_binding b
JOIN bak_participant_merge_pn_20260817 p ON p.id = b.participant_id;

-- 4) 备份完成校验：三张备份表行数应分别为 56928 / 56928 / 54609（test1 dry-run 基线）
SELECT (SELECT COUNT(*) FROM bak_participant_merge_pn_20260817) AS 备份PN行,
       (SELECT COUNT(*) FROM bak_participant_merge_lid_20260817) AS 备份LID行,
       (SELECT COUNT(*) FROM bak_binding_merge_20260817) AS 备份绑定;

-- 5) 归并后验收：应为 0 组残留双行、0 条悬空绑定
SELECT (
  SELECT COUNT(*) FROM (
    SELECT 1 FROM wa_group_participant
    WHERE phone IS NOT NULL AND phone <> ''
    GROUP BY tenant_id, group_id, phone HAVING COUNT(*) > 1
  ) t
) AS 残留重复组,
(
  SELECT COUNT(*) FROM wa_account_group_binding b
  LEFT JOIN wa_group_participant p ON p.id = b.participant_id
  WHERE p.id IS NULL
) AS 悬空绑定;
