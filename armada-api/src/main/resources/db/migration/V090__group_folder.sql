-- 群组列表运营分组与 WS 链接导入分组相互独立。
CREATE TABLE IF NOT EXISTS group_folder (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    name VARCHAR(64) NOT NULL COMMENT '群组运营分组名称',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒);NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_folder_name (tenant_id, name),
    KEY idx_group_folder_active (tenant_id, deleted_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='群组列表运营分组';

SET @group_folder_ddl = IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link'
       AND column_name = 'folder_id') = 0,
    'ALTER TABLE group_link ADD COLUMN folder_id BIGINT DEFAULT NULL COMMENT ''群组运营分组(关联group_folder.id);NULL=未分组'' AFTER label_id',
    'SELECT 1'
);
PREPARE group_folder_stmt FROM @group_folder_ddl;
EXECUTE group_folder_stmt;
DEALLOCATE PREPARE group_folder_stmt;

SET @group_folder_ddl = IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link'
       AND index_name = 'idx_group_link_folder') = 0,
    'ALTER TABLE group_link ADD KEY idx_group_link_folder (tenant_id, deleted_at, folder_id)',
    'SELECT 1'
);
PREPARE group_folder_stmt FROM @group_folder_ddl;
EXECUTE group_folder_stmt;
DEALLOCATE PREPARE group_folder_stmt;
