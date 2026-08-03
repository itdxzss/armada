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
