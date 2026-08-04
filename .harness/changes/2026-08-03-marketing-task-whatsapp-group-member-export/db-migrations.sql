-- 正式迁移文件：armada-api/src/main/resources/db/migration/V091__whatsapp_group_departed_member.sql
-- 本文件用于变更记录归档，执行时以 Flyway 文件为准，禁止手工修改共享库。

CREATE TABLE whatsapp_group_departed_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_jid VARCHAR(128) NOT NULL,
    participant_jid VARCHAR(191) NOT NULL,
    phone VARCHAR(32) DEFAULT NULL,
    exited_at BIGINT NOT NULL,
    exit_type VARCHAR(16) NOT NULL,
    event_at BIGINT NOT NULL,
    source_event_id VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_departed_member (tenant_id, group_jid, participant_jid),
    KEY idx_whatsapp_departed_group (tenant_id, group_jid, exited_at),
    KEY idx_whatsapp_departed_source_event (tenant_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 正式迁移文件：armada-api/src/main/resources/db/migration/V092__whatsapp_group_member_join_fact.sql
CREATE TABLE whatsapp_group_member_join_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_jid VARCHAR(128) NOT NULL,
    participant_jid VARCHAR(191) NOT NULL,
    phone VARCHAR(32) DEFAULT NULL,
    joined_at BIGINT NOT NULL,
    event_at BIGINT NOT NULL,
    source_event_id VARCHAR(255) NOT NULL,
    observer_account_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_join (tenant_id, group_jid, participant_jid),
    KEY idx_whatsapp_group_member_join_group (tenant_id, group_jid, joined_at),
    KEY idx_whatsapp_group_member_join_source_event (tenant_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 正式迁移文件：armada-api/src/main/resources/db/migration/V093__whatsapp_group_member_cache.sql
-- 群人数由成员状态实时计算，不保存重复统计值。
CREATE TABLE whatsapp_group_member_cache (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_jid VARCHAR(128) NOT NULL,
    subject VARCHAR(255) DEFAULT NULL,
    announce_only TINYINT(1) DEFAULT NULL,
    snapshot_at BIGINT NOT NULL,
    snapshot_version VARCHAR(64) NOT NULL,
    observer_account_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_cache (tenant_id, group_jid),
    KEY idx_whatsapp_group_member_cache_snapshot (tenant_id, snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE whatsapp_group_member_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    group_jid VARCHAR(128) NOT NULL,
    participant_jid VARCHAR(191) NOT NULL,
    phone VARCHAR(32) DEFAULT NULL,
    is_admin TINYINT(1) DEFAULT NULL,
    is_owner TINYINT(1) DEFAULT NULL,
    role VARCHAR(32) DEFAULT NULL,
    is_in_group TINYINT(1) NOT NULL,
    state_source VARCHAR(32) NOT NULL,
    state_updated_at BIGINT NOT NULL,
    source_event_id VARCHAR(255) NOT NULL,
    snapshot_version VARCHAR(64) DEFAULT NULL,
    observer_account_id BIGINT DEFAULT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member_state (tenant_id, group_jid, participant_jid),
    KEY idx_whatsapp_group_member_state_group
        (tenant_id, group_jid, is_in_group, state_updated_at),
    KEY idx_whatsapp_group_member_state_event (tenant_id, source_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
