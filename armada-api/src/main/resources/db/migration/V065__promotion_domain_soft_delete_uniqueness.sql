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

-- V065 可能因历史索引漂移或 MySQL DDL 中断而重试；每个结构动作必须按当前真实状态决定。
SET @promotion_domain_is_active_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_domain'
      AND column_name = 'is_active'
);
SET @promotion_domain_old_host_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_domain'
      AND index_name = 'uq_promotion_domain_host'
);
SET @promotion_domain_old_template_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_domain'
      AND index_name = 'uq_promotion_domain_tenant_template'
);
SET @promotion_domain_active_host_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_domain'
      AND index_name = 'uq_promotion_domain_active_host'
);
SET @promotion_domain_active_template_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_domain'
      AND index_name = 'uq_promotion_domain_active_template'
);

SET @promotion_domain_alter_clauses := CONCAT_WS(', ',
    IF(@promotion_domain_is_active_exists = 0,
       'ADD COLUMN is_active TINYINT(1) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED COMMENT ''软删唯一键辅助标记:有效记录为1,已删除为NULL,例如 1''',
       NULL),
    IF(@promotion_domain_old_host_index_exists > 0,
       'DROP INDEX uq_promotion_domain_host',
       NULL),
    IF(@promotion_domain_old_template_index_exists > 0,
       'DROP INDEX uq_promotion_domain_tenant_template',
       NULL),
    IF(@promotion_domain_active_host_index_exists = 0,
       'ADD UNIQUE KEY uq_promotion_domain_active_host (domain_host, is_active)',
       NULL),
    IF(@promotion_domain_active_template_index_exists = 0,
       'ADD UNIQUE KEY uq_promotion_domain_active_template (tenant_id, landing_template_id, is_active)',
       NULL)
);
SET @promotion_domain_alter_sql := IF(
    @promotion_domain_alter_clauses = '',
    'SELECT 1',
    CONCAT('ALTER TABLE promotion_domain ', @promotion_domain_alter_clauses)
);
PREPARE promotion_domain_alter_stmt FROM @promotion_domain_alter_sql;
EXECUTE promotion_domain_alter_stmt;
DEALLOCATE PREPARE promotion_domain_alter_stmt;

-- 删除最后一个渠道时按域名查询剩余有效引用，避免扫描并锁定租户下全部渠道。
SET @promotion_channel_domain_active_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_channel'
      AND index_name = 'idx_promotion_channel_domain_active'
);
SET @promotion_channel_domain_active_index_sql := IF(
    @promotion_channel_domain_active_index_exists = 0,
    'ALTER TABLE promotion_channel ADD INDEX idx_promotion_channel_domain_active (tenant_id, promotion_domain_id, deleted_at, id)',
    'SELECT 1'
);
PREPARE promotion_channel_domain_active_index_stmt
    FROM @promotion_channel_domain_active_index_sql;
EXECUTE promotion_channel_domain_active_index_stmt;
DEALLOCATE PREPARE promotion_channel_domain_active_index_stmt;
