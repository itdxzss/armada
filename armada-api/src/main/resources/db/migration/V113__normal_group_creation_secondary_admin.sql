-- 新建普群增加每群次管理员快照、同群好友锚点和群级提权状态。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_task'
       AND column_name = 'secondary_admin_account_group_id') = 0,
    'ALTER TABLE normal_group_creation_task ADD COLUMN secondary_admin_account_group_id BIGINT DEFAULT NULL COMMENT ''次管理员账号分组ID;0名次管理员的历史任务为空'' AFTER admin_account_group_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'normal_group_creation_task'
       AND column_name = 'secondary_admin_count') = 0,
    'ALTER TABLE normal_group_creation_task ADD COLUMN secondary_admin_count INT NOT NULL DEFAULT 0 COMMENT ''每群冻结次管理员数量'' AFTER secondary_admin_account_group_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS normal_group_creation_item_secondary_admin (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '新建普群任务ID',
    item_id BIGINT NOT NULL COMMENT '计划群明细ID',
    secondary_admin_order INT NOT NULL COMMENT '本群次管理员顺序',
    secondary_admin_account_id BIGINT NOT NULL COMMENT '次管理员Armada账号ID',
    secondary_admin_protocol_account_id VARCHAR(128) NOT NULL COMMENT '次管理员协议账号ID',
    secondary_admin_protocol_backend VARCHAR(16) NOT NULL COMMENT '次管理员协议类型:WEB/ANDROID',
    secondary_admin_ws_phone VARCHAR(32) NOT NULL COMMENT '次管理员WhatsApp号码',
    anchor_member_account_id BIGINT NOT NULL COMMENT '同一计划群内的好友锚点普通成员账号ID',
    creator_saved_secondary_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '创群账号保存次管理员状态',
    secondary_saved_creator_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '次管理员保存创群账号状态',
    secondary_saved_anchor_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '次管理员保存同群锚点成员状态',
    anchor_saved_secondary_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '同群锚点成员保存次管理员状态',
    creator_save_command_id VARCHAR(64) DEFAULT NULL COMMENT '创群账号保存次管理员命令ID',
    secondary_save_creator_command_id VARCHAR(64) DEFAULT NULL COMMENT '次管理员保存创群账号命令ID',
    secondary_save_anchor_command_id VARCHAR(64) DEFAULT NULL COMMENT '次管理员保存同群锚点成员命令ID',
    anchor_save_secondary_command_id VARCHAR(64) DEFAULT NULL COMMENT '同群锚点成员保存次管理员命令ID',
    participant_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '一次性建群入群状态:PENDING/CONFIRMED/FAILED/UNKNOWN',
    promotion_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '管理员提权确认状态:PENDING/SUCCESS/FAILED/UNKNOWN',
    last_error_code VARCHAR(64) DEFAULT NULL COMMENT '最近错误码',
    last_error_message VARCHAR(512) DEFAULT NULL COMMENT '可向页面展示的最近脱敏错误',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_normal_group_creation_secondary_admin
        (tenant_id, item_id, secondary_admin_account_id),
    UNIQUE KEY uq_normal_group_creation_secondary_creator_save (creator_save_command_id),
    UNIQUE KEY uq_normal_group_creation_secondary_save_creator (secondary_save_creator_command_id),
    UNIQUE KEY uq_normal_group_creation_secondary_save_anchor (secondary_save_anchor_command_id),
    UNIQUE KEY uq_normal_group_creation_anchor_save_secondary (anchor_save_secondary_command_id),
    KEY idx_normal_group_creation_secondary_task
        (tenant_id, task_id, secondary_admin_account_id),
    KEY idx_normal_group_creation_secondary_item
        (tenant_id, item_id, secondary_admin_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新建普群次管理员冻结快照';
