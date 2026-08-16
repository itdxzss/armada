-- 已发送命令保留期清理需要 (status, created_at) 前缀；现有 idx_dispatch(status, next_retry_at, id)
-- 不含 created_at，百万级已发送行下每批删除都要在状态范围内额外过滤。
-- 纯加索引，不改列、不动业务数据。

SET @protocol_command_outbox_cleanup_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND index_name = 'idx_protocol_command_outbox_cleanup') = 0,
    'ALTER TABLE protocol_command_outbox ADD KEY idx_protocol_command_outbox_cleanup (status, created_at)',
    'SELECT 1'
);
PREPARE protocol_command_outbox_cleanup_stmt FROM @protocol_command_outbox_cleanup_ddl;
EXECUTE protocol_command_outbox_cleanup_stmt;
DEALLOCATE PREPARE protocol_command_outbox_cleanup_stmt;
