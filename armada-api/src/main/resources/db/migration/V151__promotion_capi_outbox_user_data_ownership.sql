-- 推广 CAPI 异步事件归属快照。历史行不根据渠道或会话反推 owner，NULL 事件禁止投递。

SET @promotion_capi_owner_column_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'promotion_capi_event_outbox'
       AND column_name = 'owner_user_id') = 0,
    'ALTER TABLE promotion_capi_event_outbox ADD COLUMN owner_user_id BIGINT DEFAULT NULL COMMENT ''归属用户ID快照;NULL历史事件禁止投递'' AFTER tenant_id',
    'SELECT 1'
);
PREPARE promotion_capi_owner_column_stmt FROM @promotion_capi_owner_column_ddl;
EXECUTE promotion_capi_owner_column_stmt;
DEALLOCATE PREPARE promotion_capi_owner_column_stmt;

SET @promotion_capi_owner_index_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'promotion_capi_event_outbox'
       AND index_name = 'idx_promotion_capi_owner') = 0,
    'ALTER TABLE promotion_capi_event_outbox ADD KEY idx_promotion_capi_owner (tenant_id, owner_user_id, created_at, id)',
    'SELECT 1'
);
PREPARE promotion_capi_owner_index_stmt FROM @promotion_capi_owner_index_ddl;
EXECUTE promotion_capi_owner_index_stmt;
DEALLOCATE PREPARE promotion_capi_owner_index_stmt;
