-- WhatsApp 协议实时 add 通知形成的最近一次进群事实；当前成员仍在导出时实时查询，不落快照。
CREATE TABLE whatsapp_group_member_join_fact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '进群事实主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群JID',
    participant_jid VARCHAR(191) NOT NULL COMMENT '进群成员WhatsApp JID',
    phone VARCHAR(32) DEFAULT NULL COMMENT '协议可解析的成员手机号',
    joined_at BIGINT NOT NULL COMMENT 'WhatsApp进群时间(epoch毫秒)',
    event_at BIGINT NOT NULL COMMENT '用于新旧事件比较的协议事实时间(epoch毫秒)',
    source_event_id VARCHAR(255) NOT NULL COMMENT 'Android协议源事件ID',
    observer_account_id BIGINT NOT NULL COMMENT '观察到该事件的Armada账号ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_join (tenant_id, group_jid, participant_jid),
    KEY idx_whatsapp_group_member_join_group (tenant_id, group_jid, joined_at),
    KEY idx_whatsapp_group_member_join_source_event (tenant_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp群成员最近一次协议进群事实';
