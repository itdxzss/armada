-- 旧 group_link.id 继续作为现有 API/任务兼容句柄，只新增到六表实体的稳定引用。
-- 群资料、邀请、健康和成员事实仍以六张新表为主，不反向复制到兼容句柄。

ALTER TABLE group_link
    ADD COLUMN group_id BIGINT DEFAULT NULL COMMENT '兼容句柄关联wa_group.id' AFTER id,
    ADD COLUMN group_invite_id BIGINT DEFAULT NULL
        COMMENT '兼容句柄关联wa_group_invite.id' AFTER group_id,
    ADD KEY idx_group_link_group (tenant_id, group_id, id),
    ADD KEY idx_group_link_invite (tenant_id, group_invite_id, id);

UPDATE group_link handle
LEFT JOIN group_link_preview preview
  ON preview.tenant_id = handle.tenant_id
 AND preview.group_link_id = handle.id
LEFT JOIN wa_group current_group
  ON current_group.tenant_id = handle.tenant_id
 AND current_group.group_jid = LOWER(TRIM(preview.group_jid))
LEFT JOIN wa_group_invite current_invite
  ON current_invite.tenant_id = handle.tenant_id
 AND current_invite.invite_code = TRIM(preview.invite_code)
SET handle.group_id = current_group.id,
    handle.group_invite_id = current_invite.id
WHERE current_group.id IS NOT NULL
   OR current_invite.id IS NOT NULL;
