-- WhatsApp 群成员完整快照缓存。导出首次实时查询成功后落库，后续导出可直接复用。
CREATE TABLE whatsapp_group_member_cache (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群成员缓存主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群JID',
    subject VARCHAR(255) DEFAULT NULL COMMENT 'WhatsApp真实群名',
    announce_only TINYINT(1) DEFAULT NULL COMMENT '是否仅管理员可发言',
    snapshot_at BIGINT NOT NULL COMMENT '完整快照时间(epoch毫秒)',
    snapshot_version VARCHAR(64) NOT NULL COMMENT '完整快照版本',
    observer_account_id BIGINT NOT NULL COMMENT '完成实时查询的Armada账号ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_cache (tenant_id, group_jid),
    KEY idx_whatsapp_group_member_cache_snapshot (tenant_id, snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp群成员完整快照缓存';

-- 当前成员状态由完整快照和 WhatsApp add/leave/remove 事件共同维护。
CREATE TABLE whatsapp_group_member_state (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群成员状态主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群JID',
    participant_jid VARCHAR(191) NOT NULL COMMENT '成员WhatsApp JID',
    phone VARCHAR(32) DEFAULT NULL COMMENT '协议可解析的成员手机号',
    is_admin TINYINT(1) DEFAULT NULL COMMENT '是否管理员',
    is_owner TINYINT(1) DEFAULT NULL COMMENT '是否群主',
    role VARCHAR(32) DEFAULT NULL COMMENT '协议原始角色',
    is_in_group TINYINT(1) NOT NULL COMMENT '是否当前在群',
    state_source VARCHAR(32) NOT NULL COMMENT '状态来源',
    state_updated_at BIGINT NOT NULL COMMENT '状态事实时间(epoch毫秒)',
    source_event_id VARCHAR(255) NOT NULL COMMENT '协议事件或快照来源ID',
    snapshot_version VARCHAR(64) DEFAULT NULL COMMENT '最近完整快照版本',
    observer_account_id BIGINT DEFAULT NULL COMMENT '观察到状态的Armada账号ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_state (tenant_id, group_jid, participant_jid),
    KEY idx_whatsapp_group_member_state_group
        (tenant_id, group_jid, is_in_group, state_updated_at),
    KEY idx_whatsapp_group_member_state_event (tenant_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp群成员最新状态';
