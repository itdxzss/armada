ALTER TABLE whatsapp_group_member_snapshot
    ADD INDEX idx_whatsapp_group_jid_snapshot
        (tenant_id, group_jid, snapshot_at, group_link_id);
