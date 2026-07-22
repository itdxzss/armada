-- 有效绑定标记：未删除行为1，软删行为NULL；利用 MySQL 唯一索引允许多个NULL保留历史记录。
-- 删除最后一个渠道后可释放模板和域名，后续可重新建立新的有效绑定。
-- 先释放升级前已经因渠道删除而遗留的孤立绑定，保证部署后无需再次手工清理。
SET @promotion_domain_release_at := CAST(
    UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED
);
UPDATE promotion_domain d
LEFT JOIN promotion_channel c
    ON c.tenant_id = d.tenant_id
   AND c.promotion_domain_id = d.id
   AND c.deleted_at IS NULL
SET d.deleted_at = @promotion_domain_release_at,
    d.updated_at = @promotion_domain_release_at
WHERE d.deleted_at IS NULL
  AND c.id IS NULL;

ALTER TABLE promotion_domain
    ADD COLUMN is_active TINYINT(1)
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED
        COMMENT '软删唯一键辅助标记:有效记录为1,已删除为NULL,例如 1',
    DROP INDEX uq_promotion_domain_host,
    DROP INDEX uq_promotion_domain_tenant_template,
    ADD UNIQUE KEY uq_promotion_domain_active_host (domain_host, is_active),
    ADD UNIQUE KEY uq_promotion_domain_active_template (tenant_id, landing_template_id, is_active);

-- 删除最后一个渠道时按域名查询剩余有效引用，避免扫描并锁定租户下全部渠道。
ALTER TABLE promotion_channel
    ADD INDEX idx_promotion_channel_domain_active
        (tenant_id, promotion_domain_id, deleted_at, id);
