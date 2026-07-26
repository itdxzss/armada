-- IP 国家统计会按租户、国家和软删状态聚合。历史 SQL 对 region 使用 TRIM，
-- 会使索引失效并导致统计接口超时；先清理历史首尾空格，再建立查询所需索引。
-- 新增/导入链路已统一保存规范化国家名称，因此这里仅负责兼容存量数据。
UPDATE ip_proxy
SET region = TRIM(region)
WHERE region IS NOT NULL
  AND region != TRIM(region);

SET @index_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'ip_proxy'
    AND index_name = 'idx_ip_proxy_tenant_region_deleted_status'
);

SET @ddl := IF(@index_exists = 0,
  'ALTER TABLE ip_proxy ADD KEY idx_ip_proxy_tenant_region_deleted_status (tenant_id, region, deleted_at, status)',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
