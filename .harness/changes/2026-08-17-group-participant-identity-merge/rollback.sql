-- 群成员 PN/LID 身份归并（V126）回滚脚本
--
-- ⚠️ 仅在 db-migrations.sql 的三张备份表存在时可用。V126 删除了 PN 行，
-- 表内没有任何冗余可供自我还原，没有备份就只能从数据库全量备份恢复。
--
-- 回滚顺序不可调整：必须先清空 LID 行上回填的 pn_jid，才能重新插入 PN 行，
-- 否则两行会同时持有相同 (tenant_id, group_id, pn_jid)，撞 uq_wa_group_participant_pn。
--
-- 注意：回滚只还原数据库状态。若归并后已有新的实时事件写入这些成员行，
-- 回滚会把那些新事实一并覆盖为归并前的值，执行前需确认停写窗口。

-- 1) 清除 LID 行上回填的 pn_jid（必须最先执行）
UPDATE wa_group_participant lid
JOIN bak_participant_merge_lid_20260817 bak ON bak.id = lid.id
SET lid.pn_jid = NULL;

-- 2) 还原 LID 行被归并改写的事实列
UPDATE wa_group_participant lid
JOIN bak_participant_merge_lid_20260817 bak ON bak.id = lid.id
SET lid.role = bak.role,
    lid.role_source = bak.role_source,
    lid.role_observed_at = bak.role_observed_at,
    lid.role_event_id = bak.role_event_id,
    lid.presence_status = bak.presence_status,
    lid.presence_source = bak.presence_source,
    lid.presence_observed_at = bak.presence_observed_at,
    lid.presence_event_id = bak.presence_event_id,
    lid.updated_at = bak.updated_at;

-- 3) 重建被删除的 PN 行（保留原主键，使绑定可按原 ID 还原）
INSERT INTO wa_group_participant
SELECT * FROM bak_participant_merge_pn_20260817;

-- 4) 还原账号绑定指向
UPDATE wa_account_group_binding b
JOIN bak_binding_merge_20260817 bak ON bak.binding_id = b.id
SET b.participant_id = bak.old_participant_id;

-- 5) 回滚验收：双行组应恢复为 56928，悬空绑定为 0
SELECT (
  SELECT COUNT(*) FROM (
    SELECT 1 FROM wa_group_participant
    WHERE phone IS NOT NULL AND phone <> ''
    GROUP BY tenant_id, group_id, phone
    HAVING COUNT(*) = 2
       AND SUM(pn_jid IS NOT NULL) = 1
       AND SUM(lid_jid IS NOT NULL) = 1
  ) t
) AS 恢复的双行组,
(
  SELECT COUNT(*) FROM wa_account_group_binding b
  LEFT JOIN wa_group_participant p ON p.id = b.participant_id
  WHERE p.id IS NULL
) AS 悬空绑定;

-- 6) 确认回滚无误后再清理备份表（人工执行，不要写进自动化流程）
-- DROP TABLE bak_binding_merge_20260817;
-- DROP TABLE bak_participant_merge_lid_20260817;
-- DROP TABLE bak_participant_merge_pn_20260817;
