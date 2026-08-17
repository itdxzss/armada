-- 归并同群同号码的 PN/LID 双行，使一个 WhatsApp 人在一个群内只有一行 participant。
--
-- 背景：Web 与 Android 协议的群成员列表均只返回 LID 身份（test1 抽样 4 个群 188 名成员，
-- PN 形式 0 个），PN 行是账号快照用 account.ws_phone 自行构造的镜像。表结构本就支持
-- 一行同时保存 pn_jid 与 lid_jid，但从未有写入方把两个身份关联起来，导致 56928 组双行。
--
-- 归并方向：保留 LID 行（协议权威身份，且承载完整成员快照事实），回填 pn_jid，
-- 账号绑定改指 LID 行后删除 PN 行。事实列按群组数据模型设计 §7.2 的来源可信度分级归并，
-- 低可信来源不得覆盖高可信来源的精确值。
--
-- 语句顺序是正确性前提，不可调整：
--   账号绑定必须先改指 LID 行，否则删除 PN 行会留下悬空 participant_id；
--   pn_jid 必须等 PN 行删除后才能回填，否则与 uq_wa_group_participant_pn 唯一键冲突。
--
-- 幂等性：归并完成后每组只剩一行，配对条件 COUNT(*)=2 不再命中，重跑为空操作。

-- 1) 仅锁定形态确定的配对：恰好 1 个 PN 行 + 1 个 LID 行。
--    phone 为空的成员无法按号码判定同一人，一律排除，只留待人工审计。
CREATE TEMPORARY TABLE tmp_participant_merge_pair AS
SELECT tenant_id,
       group_id,
       phone,
       MAX(CASE WHEN pn_jid IS NOT NULL THEN id END) AS pn_id,
       MAX(CASE WHEN lid_jid IS NOT NULL THEN id END) AS lid_id,
       MAX(CASE WHEN pn_jid IS NOT NULL THEN pn_jid END) AS pn_jid
FROM wa_group_participant
WHERE phone IS NOT NULL
  AND phone <> ''
GROUP BY tenant_id, group_id, phone
HAVING COUNT(*) = 2
   AND SUM(pn_jid IS NOT NULL) = 1
   AND SUM(lid_jid IS NOT NULL) = 1;

-- 2) 预先判定各事实域由哪一行胜出，避免后续 UPDATE 重复书写分级表达式。
--    role 分级与 AccountGroupCurrentSnapshotMapper.incomingRoleWins 保持一致；
--    presence 分级与 incomingPresenceWins 保持一致（退群事件优先级最高）。
CREATE TEMPORARY TABLE tmp_participant_merge AS
SELECT pair.pn_id,
       pair.lid_id,
       pair.pn_jid,
       pn.role AS pn_role,
       pn.role_source AS pn_role_source,
       pn.role_observed_at AS pn_role_observed_at,
       pn.role_event_id AS pn_role_event_id,
       pn.presence_status AS pn_presence_status,
       pn.presence_source AS pn_presence_source,
       pn.presence_observed_at AS pn_presence_observed_at,
       pn.presence_event_id AS pn_presence_event_id,
       CASE WHEN pn.role <> 0
             AND (
               (CASE pn.role_source
                  WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
                  WHEN 'WGP2_ADD' THEN 3
                  WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
                  WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2 WHEN 'LEGACY_METADATA_OWNER' THEN 2
                  WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
               >
               (CASE lid.role_source
                  WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
                  WHEN 'WGP2_ADD' THEN 3
                  WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
                  WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2 WHEN 'LEGACY_METADATA_OWNER' THEN 2
                  WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
               OR (
                 (CASE pn.role_source
                    WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
                    WHEN 'WGP2_ADD' THEN 3
                    WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
                    WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2 WHEN 'LEGACY_METADATA_OWNER' THEN 2
                    WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
                 =
                 (CASE lid.role_source
                    WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
                    WHEN 'WGP2_ADD' THEN 3
                    WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
                    WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2 WHEN 'LEGACY_METADATA_OWNER' THEN 2
                    WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
                 AND COALESCE(pn.role_observed_at, -1) > COALESCE(lid.role_observed_at, -1)
               )
             )
            THEN 1 ELSE 0 END AS pn_role_wins,
       CASE WHEN
             (CASE pn.presence_source
                WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
                WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
                WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
                WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
                WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
                WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
                WHEN 'SNAPSHOT_ABSENT' THEN 2
                WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
             >
             (CASE lid.presence_source
                WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
                WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
                WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
                WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
                WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
                WHEN 'GROUP_MEMBER_QUERY' THEN 2 WHEN 'FULL_SNAPSHOT' THEN 2
                WHEN 'SNAPSHOT_ABSENT' THEN 2
                WHEN 'GROUP_SNAPSHOT' THEN 1 ELSE 0 END)
            THEN 1 ELSE 0 END AS pn_presence_wins
FROM tmp_participant_merge_pair pair
JOIN wa_group_participant pn ON pn.id = pair.pn_id
JOIN wa_group_participant lid ON lid.id = pair.lid_id;

-- 2.1) 给映射表补索引。CREATE TABLE ... AS SELECT 产出的表没有任何索引，
--      后续三条 JOIN 写入若以映射表为被驱动侧会退化成嵌套循环全表扫描：
--      test1 实测未加索引时第 3 步 UPDATE 单条即运行 47 分钟并阻塞实时成员写入。
ALTER TABLE tmp_participant_merge
    ADD INDEX idx_tmp_merge_pn (pn_id),
    ADD INDEX idx_tmp_merge_lid (lid_id);

-- 3) 账号绑定改指 LID 行。必须先于删除，否则 participant_id 悬空。
UPDATE wa_account_group_binding b
JOIN tmp_participant_merge m ON m.pn_id = b.participant_id
SET b.participant_id = m.lid_id,
    b.updated_at = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED);

-- 4) 把 PN 行胜出的事实并入 LID 行。事实时间沿用原行观察时间，
--    禁止改写为迁移执行时间，否则会压过后续实时事实。
UPDATE wa_group_participant lid
JOIN tmp_participant_merge m ON m.lid_id = lid.id
SET lid.role = IF(m.pn_role_wins = 1, m.pn_role, lid.role),
    lid.role_source = IF(m.pn_role_wins = 1, m.pn_role_source, lid.role_source),
    lid.role_observed_at = IF(m.pn_role_wins = 1, m.pn_role_observed_at, lid.role_observed_at),
    lid.role_event_id = IF(m.pn_role_wins = 1, m.pn_role_event_id, lid.role_event_id),
    lid.presence_status =
        IF(m.pn_presence_wins = 1, m.pn_presence_status, lid.presence_status),
    lid.presence_source =
        IF(m.pn_presence_wins = 1, m.pn_presence_source, lid.presence_source),
    lid.presence_observed_at =
        IF(m.pn_presence_wins = 1, m.pn_presence_observed_at, lid.presence_observed_at),
    lid.presence_event_id =
        IF(m.pn_presence_wins = 1, m.pn_presence_event_id, lid.presence_event_id),
    lid.updated_at = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED)
WHERE m.pn_role_wins = 1
   OR m.pn_presence_wins = 1;

-- 5) 删除已被并入的 PN 行。此时已无账号绑定引用它。
DELETE p
FROM wa_group_participant p
JOIN tmp_participant_merge m ON m.pn_id = p.id;

-- 6) 回填 pn_jid，使 LID 行同时持有两个身份。必须在删除之后执行。
UPDATE wa_group_participant lid
JOIN tmp_participant_merge m ON m.lid_id = lid.id
SET lid.pn_jid = m.pn_jid,
    lid.updated_at = CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED);

DROP TEMPORARY TABLE IF EXISTS tmp_participant_merge;
DROP TEMPORARY TABLE IF EXISTS tmp_participant_merge_pair;
