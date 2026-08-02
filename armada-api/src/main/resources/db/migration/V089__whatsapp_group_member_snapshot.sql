-- WhatsApp 群全成员当前状态快照。
-- 该表独立于 Armada account；member_jid 可为 PN 或 LID，phone 在协议可解析时保存。

CREATE TABLE IF NOT EXISTS whatsapp_group_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_link_id BIGINT DEFAULT NULL COMMENT '关联group_link.id',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群JID',
    member_jid VARCHAR(128) NOT NULL COMMENT '成员稳定身份键，优先LID，其次PN JID',
    participant_jid VARCHAR(128) DEFAULT NULL COMMENT '协议返回的原始成员JID',
    phone VARCHAR(32) DEFAULT NULL COMMENT '可解析的WhatsApp号码，只含数字',
    role VARCHAR(32) DEFAULT NULL COMMENT '协议原始角色',
    is_admin TINYINT(1) DEFAULT NULL COMMENT '是否管理员',
    is_owner TINYINT(1) DEFAULT NULL COMMENT '是否群主/超级管理员',
    membership_status TINYINT NOT NULL COMMENT '关系状态:1=在群 3=被移出 4=主动退出 5=已不在群但方式未知',
    status_source VARCHAR(32) NOT NULL COMMENT '状态事实来源',
    status_source_event_id VARCHAR(191) NOT NULL COMMENT '胜出状态的协议事件ID',
    status_updated_at BIGINT NOT NULL COMMENT '状态事实时间(epoch毫秒)',
    joined_at BIGINT DEFAULT NULL COMMENT '最近一次明确进群时间(epoch毫秒)',
    last_exit_type TINYINT DEFAULT NULL COMMENT '最近退出方式:3=被移出 4=主动退出',
    last_exited_at BIGINT DEFAULT NULL COMMENT '最近一次明确退出时间(epoch毫秒)',
    first_seen_at BIGINT NOT NULL COMMENT '首次观察到该成员时间(epoch毫秒)',
    last_seen_at BIGINT DEFAULT NULL COMMENT '最近一次在群快照观察时间(epoch毫秒)',
    observer_account_id BIGINT DEFAULT NULL COMMENT '最近观察该事实的Armada账号ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_identity (tenant_id, group_jid, member_jid),
    KEY idx_whatsapp_group_member_group_status
        (tenant_id, group_jid, membership_status, deleted_at),
    KEY idx_whatsapp_group_member_phone
        (tenant_id, phone, deleted_at),
    KEY idx_whatsapp_group_member_link
        (tenant_id, group_link_id, membership_status, deleted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'WhatsApp群全成员当前关系快照';

-- 追加式事实用于异步导出按 snapshotAt 回放，避免任务排队期间的新事件改写旧快照。
CREATE TABLE IF NOT EXISTS whatsapp_group_member_fact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_link_id BIGINT DEFAULT NULL COMMENT '关联group_link.id',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群JID',
    member_jid VARCHAR(128) NOT NULL COMMENT '成员稳定身份键',
    participant_jid VARCHAR(128) DEFAULT NULL COMMENT '协议返回的原始成员JID',
    phone VARCHAR(32) DEFAULT NULL COMMENT '可解析的WhatsApp号码，只含数字',
    role VARCHAR(32) DEFAULT NULL COMMENT '协议原始角色',
    is_admin TINYINT(1) DEFAULT NULL COMMENT '是否管理员',
    is_owner TINYINT(1) DEFAULT NULL COMMENT '是否群主/超级管理员',
    membership_status TINYINT NOT NULL COMMENT '关系状态:1=在群 3=被移出 4=主动退出 5=方式未知',
    status_source VARCHAR(32) NOT NULL COMMENT '事实来源',
    occurred_at BIGINT NOT NULL COMMENT '事实发生时间(epoch毫秒)',
    source_event_id VARCHAR(191) NOT NULL COMMENT '协议事件ID，用于幂等及同毫秒确定性排序',
    observer_account_id BIGINT DEFAULT NULL COMMENT '观察该事实的Armada账号ID',
    created_at BIGINT NOT NULL COMMENT '入库时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_fact_event
        (tenant_id, source_event_id, group_jid, member_jid),
    KEY idx_whatsapp_group_member_fact_snapshot
        (tenant_id, group_jid, occurred_at, member_jid),
    KEY idx_whatsapp_group_member_fact_member
        (tenant_id, group_jid, member_jid, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'WhatsApp群成员追加式时序事实';

-- 只有落入此表的快照才确认 participant 列表完整，可安全计算人数和标记缺失成员。
CREATE TABLE IF NOT EXISTS whatsapp_group_member_snapshot_fact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_link_id BIGINT DEFAULT NULL COMMENT '关联group_link.id',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群JID',
    member_count INT NOT NULL COMMENT '协议声明且与去重成员数一致的人数',
    announce_only TINYINT(1) DEFAULT NULL COMMENT '是否仅管理员可发言',
    observer_is_admin TINYINT(1) DEFAULT NULL COMMENT '观察账号当时是否管理员',
    snapshot_at BIGINT NOT NULL COMMENT '完整快照时间(epoch毫秒)',
    source_event_id VARCHAR(191) NOT NULL COMMENT '协议事件ID',
    observer_account_id BIGINT NOT NULL COMMENT '观察账号ID',
    created_at BIGINT NOT NULL COMMENT '入库时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_snapshot_event
        (tenant_id, source_event_id, group_jid),
    KEY idx_whatsapp_group_member_snapshot_time
        (tenant_id, group_jid, snapshot_at),
    KEY idx_whatsapp_group_member_snapshot_observer
        (tenant_id, group_jid, observer_account_id, snapshot_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'WhatsApp群成员完整快照水位';
