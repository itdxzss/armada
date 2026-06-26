-- 统一旧业务表时间列为 BIGINT epoch 毫秒。
-- 旧 DATETIME 值按 UTC 墙钟解释: 2024-01-01 00:00:00 -> 1704067200000。

-- marketing_template
ALTER TABLE marketing_template
    DROP INDEX uq_tenant_name,
    ADD COLUMN created_at_ms BIGINT DEFAULT NULL COMMENT '创建时间(epoch毫秒)',
    ADD COLUMN updated_at_ms BIGINT DEFAULT NULL COMMENT '更新时间(epoch毫秒)',
    ADD COLUMN deleted_at_ms BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒);NULL=未删';

UPDATE marketing_template
SET created_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', created_at) DIV 1000,
    updated_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', updated_at) DIV 1000,
    deleted_at_ms = CASE
        WHEN deleted_at IS NULL THEN NULL
        ELSE TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', deleted_at) DIV 1000
    END;

ALTER TABLE marketing_template
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    DROP COLUMN deleted_at,
    CHANGE COLUMN created_at_ms created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    CHANGE COLUMN updated_at_ms updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    CHANGE COLUMN deleted_at_ms deleted_at BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒);NULL=未删',
    ADD UNIQUE KEY uq_tenant_name (tenant_id, template_name, deleted_at);

-- ip_proxy
ALTER TABLE ip_proxy
    DROP INDEX uq_active_dedup,
    DROP COLUMN is_active,
    ADD COLUMN created_at_ms BIGINT DEFAULT NULL COMMENT '创建时间(epoch毫秒)',
    ADD COLUMN updated_at_ms BIGINT DEFAULT NULL COMMENT '更新时间(epoch毫秒)',
    ADD COLUMN deleted_at_ms BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删';

UPDATE ip_proxy
SET created_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', created_at) DIV 1000,
    updated_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', updated_at) DIV 1000,
    deleted_at_ms = CASE
        WHEN deleted_at IS NULL THEN NULL
        ELSE TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', deleted_at) DIV 1000
    END;

ALTER TABLE ip_proxy
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    DROP COLUMN deleted_at,
    CHANGE COLUMN created_at_ms created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    CHANGE COLUMN updated_at_ms updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    CHANGE COLUMN deleted_at_ms deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删',
    ADD COLUMN is_active TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL
        COMMENT '软删唯一键辅助:活行=1 软删=NULL',
    ADD UNIQUE KEY uq_active_dedup (tenant_id, host, port, username, password, is_active);

-- group_link_label
ALTER TABLE group_link_label
    DROP INDEX idx_tenant_created,
    ADD COLUMN created_at_ms BIGINT DEFAULT NULL COMMENT '创建时间(epoch毫秒)',
    ADD COLUMN updated_at_ms BIGINT DEFAULT NULL COMMENT '更新时间(epoch毫秒)',
    ADD COLUMN deleted_at_ms BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删';

UPDATE group_link_label
SET created_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', created_at) DIV 1000,
    updated_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', updated_at) DIV 1000,
    deleted_at_ms = CASE
        WHEN deleted_at IS NULL THEN NULL
        ELSE TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', deleted_at) DIV 1000
    END;

ALTER TABLE group_link_label
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    DROP COLUMN deleted_at,
    CHANGE COLUMN created_at_ms created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    CHANGE COLUMN updated_at_ms updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    CHANGE COLUMN deleted_at_ms deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删',
    ADD KEY idx_tenant_created (tenant_id, created_at);

-- group_link
ALTER TABLE group_link
    ADD COLUMN created_at_ms BIGINT DEFAULT NULL COMMENT '创建时间(epoch毫秒)',
    ADD COLUMN updated_at_ms BIGINT DEFAULT NULL COMMENT '更新时间(epoch毫秒)',
    ADD COLUMN deleted_at_ms BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删';

UPDATE group_link
SET created_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', created_at) DIV 1000,
    updated_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', updated_at) DIV 1000,
    deleted_at_ms = CASE
        WHEN deleted_at IS NULL THEN NULL
        ELSE TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', deleted_at) DIV 1000
    END;

ALTER TABLE group_link
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    DROP COLUMN deleted_at,
    CHANGE COLUMN created_at_ms created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    CHANGE COLUMN updated_at_ms updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    CHANGE COLUMN deleted_at_ms deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL=未删';

-- group_link_import_batch
ALTER TABLE group_link_import_batch
    DROP INDEX idx_tenant_created,
    ADD COLUMN created_at_ms BIGINT DEFAULT NULL COMMENT '导入时间(epoch毫秒)',
    ADD COLUMN deleted_at_ms BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);随分组级联软删';

UPDATE group_link_import_batch
SET created_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', created_at) DIV 1000,
    deleted_at_ms = CASE
        WHEN deleted_at IS NULL THEN NULL
        ELSE TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', deleted_at) DIV 1000
    END;

ALTER TABLE group_link_import_batch
    DROP COLUMN created_at,
    DROP COLUMN deleted_at,
    CHANGE COLUMN created_at_ms created_at BIGINT NOT NULL COMMENT '导入时间(epoch毫秒)',
    CHANGE COLUMN deleted_at_ms deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);随分组级联软删',
    ADD KEY idx_tenant_created (tenant_id, created_at);

-- group_link_import_detail
ALTER TABLE group_link_import_detail
    ADD COLUMN created_at_ms BIGINT DEFAULT NULL COMMENT '创建时间(epoch毫秒)';

UPDATE group_link_import_detail
SET created_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', created_at) DIV 1000;

ALTER TABLE group_link_import_detail
    DROP COLUMN created_at,
    CHANGE COLUMN created_at_ms created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)';

-- tenant
ALTER TABLE tenant
    ADD COLUMN created_at_ms BIGINT DEFAULT NULL COMMENT '创建时间(epoch毫秒)',
    ADD COLUMN updated_at_ms BIGINT DEFAULT NULL COMMENT '更新时间(epoch毫秒)';

UPDATE tenant
SET created_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', created_at) DIV 1000,
    updated_at_ms = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', updated_at) DIV 1000;

ALTER TABLE tenant
    DROP COLUMN created_at,
    DROP COLUMN updated_at,
    CHANGE COLUMN created_at_ms created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    CHANGE COLUMN updated_at_ms updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)';
