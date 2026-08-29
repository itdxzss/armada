-- 超链任务账号画像共享前置：画像事实独立于账号身份主表，并按各自水位更新。

CREATE TABLE IF NOT EXISTS account_profile (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号画像主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT '账号ID;关联account.id',
    friend_count INT DEFAULT NULL COMMENT '通讯录双向好友数;NULL=未采集',
    friend_count_synced_at BIGINT DEFAULT NULL COMMENT '好友数最近同步时间(epoch毫秒)',
    is_group_invite_allowed TINYINT(1) DEFAULT NULL COMMENT '是否允许被拉群:0否 1是;NULL=未采集',
    group_invite_synced_at BIGINT DEFAULT NULL COMMENT '拉群隐私最近同步时间(epoch毫秒)',
    rotation_status TINYINT DEFAULT NULL COMMENT '轮号状态:0未轮号 1轮号中 2成功 3失败;NULL=未知',
    rotation_updated_at BIGINT DEFAULT NULL COMMENT '轮号状态最近更新时间(epoch毫秒)',
    registered_at BIGINT DEFAULT NULL COMMENT 'WhatsApp估算注册时间(epoch毫秒);NULL=未知',
    registered_at_source TINYINT DEFAULT NULL COMMENT '注册时间来源:1供应商准确日期 2供应商号龄反推 3人工维护',
    marketing_source TINYINT DEFAULT NULL COMMENT '运营来源:0买量 1自登 2买入 3转入 4群扫码;NULL=未知',
    marketing_source_updated_at BIGINT DEFAULT NULL COMMENT '运营来源最近更新时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '最近落库时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_profile (tenant_id, account_id),
    KEY idx_account_profile_friend (tenant_id, friend_count),
    KEY idx_account_profile_friend_sync (tenant_id, friend_count_synced_at, account_id),
    KEY idx_account_profile_invite (tenant_id, is_group_invite_allowed, account_id),
    KEY idx_account_profile_invite_sync (tenant_id, group_invite_synced_at, account_id),
    KEY idx_account_profile_rotation (tenant_id, rotation_status, account_id),
    KEY idx_account_profile_registered (tenant_id, registered_at, account_id),
    KEY idx_account_profile_source (tenant_id, marketing_source, account_id),
    CONSTRAINT ck_account_profile_friend CHECK (friend_count IS NULL OR friend_count >= 0),
    CONSTRAINT ck_account_profile_invite
        CHECK (is_group_invite_allowed IS NULL OR is_group_invite_allowed IN (0, 1)),
    CONSTRAINT ck_account_profile_rotation
        CHECK (rotation_status IS NULL OR rotation_status IN (0, 1, 2, 3)),
    CONSTRAINT ck_account_profile_registered_source
        CHECK (registered_at_source IS NULL OR registered_at_source IN (1, 2, 3)),
    CONSTRAINT ck_account_profile_source
        CHECK (marketing_source IS NULL OR marketing_source IN (0, 1, 2, 3, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号营销筛选画像;各事实独立水位;NULL表示未知';

-- 设备/账号类型/协议后端是现有事实的组合筛选，只补组合索引，不复制派生列。
SET @account_profile_schema := DATABASE();
SET @account_profile_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@account_profile_schema AND TABLE_NAME='account'
             AND INDEX_NAME='idx_account_hyperlink_platform'),
    'SELECT 1',
    'ALTER TABLE account ADD KEY idx_account_hyperlink_platform (tenant_id, device_os, account_type, protocol_id, id)');
PREPARE account_profile_stmt FROM @account_profile_sql;
EXECUTE account_profile_stmt;
DEALLOCATE PREPARE account_profile_stmt;
