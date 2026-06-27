-- 群组列表数据模型第一刀:
-- 1) group_link 补入口来源/关系态
-- 2) group_link_preview 承载协议群元数据
-- 3) group_link_health 承载可用性/运行态
-- 4) 导入链接统计与明细语义收敛

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link' AND column_name = 'origin') = 0,
    'ALTER TABLE group_link ADD COLUMN origin TINYINT NOT NULL DEFAULT 1 COMMENT ''首次进入群组池来源:1=导入链接 2=进群任务 3=拉群任务 4=自建群'' AFTER import_batch_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link' AND column_name = 'membership_state') = 0,
    'ALTER TABLE group_link ADD COLUMN membership_state TINYINT NOT NULL DEFAULT 1 COMMENT ''我方与群关系:1=目标未进群 2=已进群 3=自建拥有'' AFTER origin',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link' AND index_name = 'idx_group_link_origin') = 0,
    'ALTER TABLE group_link ADD KEY idx_group_link_origin (tenant_id, deleted_at, origin)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'group_link' AND index_name = 'idx_group_link_membership') = 0,
    'ALTER TABLE group_link ADD KEY idx_group_link_membership (tenant_id, deleted_at, membership_state)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS group_link_preview (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_link_id BIGINT NOT NULL COMMENT '关联group_link.id',
    group_jid VARCHAR(64) DEFAULT NULL COMMENT 'WhatsApp群JID,协议层操作群的真实标识',
    invite_code VARCHAR(64) DEFAULT NULL COMMENT '群邀请链接里的邀请码code',
    wa_subject VARCHAR(255) DEFAULT NULL COMMENT '协议层返回的WhatsApp真实群名称',
    member_size INT DEFAULT NULL COMMENT '预览时刻返回的群成员数量',
    owner_phone VARCHAR(32) DEFAULT NULL COMMENT '群主号码',
    announce_only TINYINT(1) DEFAULT NULL COMMENT '是否仅管理员可发言:NULL=未知 0=否 1=是',
    avatar_url VARCHAR(512) DEFAULT NULL COMMENT '群头像URL',
    last_preview_at BIGINT DEFAULT NULL COMMENT '最近一次预览/解析成功时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_link_preview_link (tenant_id, group_link_id),
    KEY idx_group_link_preview_jid (tenant_id, group_jid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接协议预览元数据';

CREATE TABLE IF NOT EXISTS group_link_health (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_link_id BIGINT NOT NULL COMMENT '关联group_link.id',
    health_status TINYINT DEFAULT NULL COMMENT '健康状态:1=可用 2=链接失效 3=不可用;NULL=未检测',
    is_banned TINYINT(1) DEFAULT NULL COMMENT '是否被WhatsApp封禁:NULL=未知 0=未封禁 1=已封禁',
    current_count INT DEFAULT NULL COMMENT '当前群成员数量',
    last_check_at BIGINT DEFAULT NULL COMMENT '最近一次健康检测时间(epoch毫秒)',
    last_health_error VARCHAR(64) DEFAULT NULL COMMENT '最近一次健康检测失败原因',
    health_failure_count INT NOT NULL DEFAULT 0 COMMENT '连续健康检测失败次数;检测成功后归零',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_link_health_link (tenant_id, group_link_id),
    KEY idx_group_link_health_status (tenant_id, health_status, is_banned)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群链接健康状态';

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link_import_batch' AND column_name = 'duplicate_rows') = 0,
    'ALTER TABLE group_link_import_batch ADD COLUMN duplicate_rows INT NOT NULL DEFAULT 0 COMMENT ''重复失败数量(批内重复 + 已在导入链接中重复导入)'' AFTER adopted_rows',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE group_link_import_batch
SET duplicate_rows = skipped_rows + adopted_rows,
    failed_rows = failed_rows + skipped_rows + adopted_rows,
    adopted_rows = 0;

ALTER TABLE group_link_import_batch
    DROP COLUMN skipped_rows;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link_import_detail' AND column_name = 'success_type') = 0,
    'ALTER TABLE group_link_import_detail ADD COLUMN success_type TINYINT DEFAULT NULL COMMENT ''成功类型:1=新增 2=收编已有群;失败时为空'' AFTER result',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'group_link_import_detail' AND column_name = 'existing_origin') = 0,
    'ALTER TABLE group_link_import_detail ADD COLUMN existing_origin TINYINT DEFAULT NULL COMMENT ''收编成功时记录已有群入口来源:2=进群任务 3=拉群任务 4=自建群'' AFTER fail_reason',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE group_link_import_detail
SET success_type = CASE WHEN result = 1 THEN 1 ELSE NULL END,
    fail_reason = CASE
        WHEN result IN (2, 3) THEN '重复'
        WHEN result = 4 THEN COALESCE(fail_reason, '格式错误')
        ELSE NULL
    END,
    group_link_id = CASE WHEN result = 1 THEN group_link_id ELSE NULL END,
    result = CASE WHEN result = 1 THEN 1 ELSE 2 END;

-- V011: 对齐群链接导入统计/明细列注释到 V010 后的新语义。
ALTER TABLE group_link_import_batch
    MODIFY COLUMN inserted_rows INT NOT NULL DEFAULT 0 COMMENT '新增成功数量',
    MODIFY COLUMN adopted_rows INT NOT NULL DEFAULT 0 COMMENT '收编已有群入口的成功数量',
    MODIFY COLUMN failed_rows INT NOT NULL DEFAULT 0 COMMENT '失败总数(重复 + 格式错误)';

ALTER TABLE group_link_import_detail
    MODIFY COLUMN group_name VARCHAR(128) DEFAULT NULL COMMENT '群名称(保留旧列,导入链接不再写)',
    MODIFY COLUMN result TINYINT NOT NULL COMMENT '导入结果:1=成功 2=失败',
    MODIFY COLUMN fail_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因:重复/格式错误;成功时为空',
    MODIFY COLUMN group_link_id BIGINT DEFAULT NULL COMMENT '成功时关联group_link.id;失败时为空';

-- V012: 对齐 group_link 注释到群组列表新语义。
ALTER TABLE group_link COMMENT = '群组入口主表(跨业务共享群组池)';

ALTER TABLE group_link
    MODIFY COLUMN group_name VARCHAR(128) DEFAULT NULL COMMENT '运营侧自定义群名称;导入链接不写;群组列表可编辑';
