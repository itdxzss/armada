-- 拉群营销任务：复用公共营销任务，新增分组占用事实和五张拉群业务表。

SET @business_type_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'business_type'
);
SET @sql := IF(
    @business_type_col_exists = 0,
    'ALTER TABLE marketing_task
       ADD COLUMN business_type TINYINT NOT NULL DEFAULT 1
       COMMENT ''业务类型:1普通营销 2拉群营销''
       AFTER task_name',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @business_page_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND index_name = 'idx_marketing_task_business_page'
);
SET @sql := IF(
    @business_page_idx_exists = 0,
    'ALTER TABLE marketing_task
       ADD KEY idx_marketing_task_business_page
       (tenant_id, business_type, deleted_at, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @occupancy_type_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND column_name = 'marketing_occupancy_type'
);
SET @sql := IF(
    @occupancy_type_col_exists = 0,
    'ALTER TABLE account_group
       ADD COLUMN marketing_occupancy_type TINYINT DEFAULT NULL
       COMMENT ''营销占用类型:1单纯营销 2拉群营销 3拉群模式二 4拉群模式三 5其他营销''
       AFTER remark',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @occupancy_task_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND column_name = 'marketing_occupancy_task_id'
);
SET @sql := IF(
    @occupancy_task_col_exists = 0,
    'ALTER TABLE account_group
       ADD COLUMN marketing_occupancy_task_id BIGINT DEFAULT NULL
       COMMENT ''当前营销占用任务ID;NULL为空闲''
       AFTER marketing_occupancy_type',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @marketing_locked_at_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND column_name = 'marketing_locked_at'
);
SET @sql := IF(
    @marketing_locked_at_col_exists = 0,
    'ALTER TABLE account_group
       ADD COLUMN marketing_locked_at BIGINT DEFAULT NULL
       COMMENT ''营销分组锁定时间(epoch毫秒)''
       AFTER marketing_occupancy_task_id',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @group_occupancy_idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group'
      AND index_name = 'idx_account_group_marketing_occupancy'
);
SET @sql := IF(
    @group_occupancy_idx_exists = 0,
    'ALTER TABLE account_group
       ADD KEY idx_account_group_marketing_occupancy
       (tenant_id, marketing_occupancy_type, marketing_occupancy_task_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE marketing_account_occupancy
    COMMENT = '营销任务账号当前占用关系';

CREATE TABLE IF NOT EXISTS group_pull_marketing_task (
    marketing_task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    builder_group_id BIGINT NOT NULL COMMENT '建群账号分组ID',
    success_group_id BIGINT DEFAULT NULL COMMENT '建群成功转入分组ID',
    failure_group_id BIGINT DEFAULT NULL COMMENT '建群失败转入分组ID',
    marketing_account_group_limit INT NOT NULL DEFAULT 10 COMMENT '单营销账号当前任务最大群数',
    group_name_prefix VARCHAR(100) DEFAULT NULL COMMENT '群名前缀;NULL时使用任务名称',
    friend_retry_limit INT NOT NULL DEFAULT 3 COMMENT '加好友失败后的重试次数;不含首次',
    material_per_group INT NOT NULL DEFAULT 3 COMMENT '单群抽取料子数量',
    speak_permission TINYINT NOT NULL DEFAULT 1 COMMENT '发言权限:1不操作 2禁言 3不禁言',
    builder_exit_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '建群账号是否退出:0否 1是',
    block_reason TINYINT NOT NULL DEFAULT 0 COMMENT '阻塞原因:0无 1建群账号 2营销账号 3料子 4系统异常 5人工处理',
    resource_status TINYINT NOT NULL DEFAULT 1 COMMENT '资源状态:1未锁定 2已锁定 3释放中 4已释放',
    marketing_account_total_count INT DEFAULT NULL COMMENT '启动锁组时营销账号总数',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (marketing_task_id),
    KEY idx_gpmt_tenant_resource (tenant_id, resource_status, marketing_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群营销任务扩展配置';

CREATE TABLE IF NOT EXISTS group_pull_marketing_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    builder_account_id BIGINT NOT NULL COMMENT '建群账号ID',
    marketing_account_id BIGINT DEFAULT NULL COMMENT '营销账号ID',
    group_name VARCHAR(100) DEFAULT NULL COMMENT '正式建群前冻结的群名称',
    group_jid VARCHAR(128) DEFAULT NULL COMMENT 'WhatsApp群JID',
    group_link_id BIGINT DEFAULT NULL COMMENT '统一群入口ID',
    group_invite_url VARCHAR(255) DEFAULT NULL COMMENT '群邀请链接',
    execution_status TINYINT NOT NULL DEFAULT 1 COMMENT '执行状态:1准备中 2执行中 3成功 4失败 5建群前跳过 6取消 7异常待处理',
    current_stage TINYINT NOT NULL DEFAULT 1 COMMENT '执行阶段:1资源 2好友 3建群 4营销号 5料子 6管理员 7权限 8群信息 9退群 10收口 11完成',
    stage_retry_count INT NOT NULL DEFAULT 0 COMMENT '当前阶段已发生的业务重试次数',
    next_execute_at BIGINT NOT NULL DEFAULT 0 COMMENT '下次业务推进时间及短期执行租约(epoch毫秒)',
    group_status TINYINT DEFAULT NULL COMMENT '群状态:1正常 2封禁;未创建为空',
    group_member_count INT DEFAULT NULL COMMENT '群成员总数',
    marketer_admin_status TINYINT NOT NULL DEFAULT 0 COMMENT '管理员状态:0不需要/未设置 1待设置 2已设置 3设置失败',
    builder_exit_status TINYINT NOT NULL DEFAULT 0 COMMENT '退群状态:0关闭/未退出 1待退出 2已退出 3退出失败',
    marketing_target_id BIGINT DEFAULT NULL COMMENT '现有营销固定目标ID',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '非致命异常或最终失败原因;分号拼接',
    group_created_at BIGINT DEFAULT NULL COMMENT '群实际创建成功时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '本次执行收口时间(epoch毫秒)',
    released_at BIGINT DEFAULT NULL COMMENT '建群账号任务占用释放时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    active_builder_account_id BIGINT GENERATED ALWAYS AS (
        IF(released_at IS NULL, builder_account_id, NULL)
    ) STORED COMMENT '当前仍占用的建群账号',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpme_task_builder (tenant_id, task_id, builder_account_id),
    UNIQUE KEY uq_gpme_active_builder (tenant_id, active_builder_account_id),
    UNIQUE KEY uq_gpme_group_jid (tenant_id, group_jid),
    KEY idx_gpme_task_due (tenant_id, task_id, execution_status, next_execute_at, id),
    KEY idx_gpme_task_page (tenant_id, task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群营销单建群账号执行';

CREATE TABLE IF NOT EXISTS group_pull_marketing_material (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    line_no INT NOT NULL COMMENT '有效料子稳定顺序',
    phone VARCHAR(32) NOT NULL COMMENT '清洗后手机号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1可用 2已预留 3成功群已使用 4失败群已使用',
    current_execution_id BIGINT DEFAULT NULL COMMENT '当前预留或最终使用执行ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpmm_task_phone (tenant_id, task_id, phone),
    UNIQUE KEY uq_gpmm_task_line (tenant_id, task_id, line_no),
    KEY idx_gpmm_task_status_line (tenant_id, task_id, status, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群营销料子池';

CREATE TABLE IF NOT EXISTS group_pull_marketing_execution_material (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    execution_id BIGINT NOT NULL COMMENT '建群执行ID',
    material_id BIGINT NOT NULL COMMENT '料子ID',
    allocation_no INT NOT NULL COMMENT '本群抽取顺序',
    friend_status TINYINT NOT NULL DEFAULT 1 COMMENT '好友状态:1待执行 2成功 3失败 4已存在',
    friend_failure_reason VARCHAR(255) DEFAULT NULL COMMENT '好友失败原因',
    entry_status TINYINT NOT NULL DEFAULT 1 COMMENT '进群状态:1待执行 2成功 3失败',
    entry_failure_reason VARCHAR(255) DEFAULT NULL COMMENT '进群失败原因',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpmem_execution_material (tenant_id, execution_id, material_id),
    UNIQUE KEY uq_gpmem_execution_order (tenant_id, execution_id, allocation_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群执行料子历史';

CREATE TABLE IF NOT EXISTS group_pull_marketing_account_stat (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '统一营销任务ID',
    account_id BIGINT NOT NULL COMMENT '营销账号ID',
    reserved_group_count INT NOT NULL DEFAULT 0 COMMENT '已匹配尚未确认进群额度',
    joined_group_count INT NOT NULL DEFAULT 0 COMMENT '已成功进群永久消耗额度',
    created_at BIGINT NOT NULL COMMENT '首次实际调用时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '最近调用时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_gpmas_task_account (tenant_id, task_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群营销账号任务内额度';
