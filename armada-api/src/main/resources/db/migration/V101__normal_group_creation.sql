-- 群组列表“新建普群”异步任务、计划群及成员冻结快照。

CREATE TABLE IF NOT EXISTS normal_group_creation_admission_lock (
    tenant_id BIGINT NOT NULL COMMENT '租户ID;同租户创建任务容量准入串行锁',
    created_at BIGINT NOT NULL COMMENT '首次创建时间(epoch毫秒)',
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新建普群租户级容量准入锁';

CREATE TABLE IF NOT EXISTS normal_group_creation_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    idempotency_key VARCHAR(64) NOT NULL COMMENT '客户端幂等键',
    admin_account_group_id BIGINT NOT NULL COMMENT '管理员账号分组ID',
    member_account_group_id BIGINT NOT NULL COMMENT '成员账号分组ID',
    member_count INT NOT NULL COMMENT '每群初始成员数量',
    group_count INT NOT NULL COMMENT '请求冻结群数;创建后不可变',
    group_name_template VARCHAR(128) NOT NULL COMMENT '群名模板;支持{no}',
    start_no INT NOT NULL DEFAULT 1 COMMENT '群名起始序号',
    creator_leave_policy VARCHAR(16) NOT NULL DEFAULT 'KEEP' COMMENT '建群人策略:KEEP/LEAVE',
    speed VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '执行速度档位',
    folder_id BIGINT DEFAULT NULL COMMENT '群组运营分组ID;空表示未分组',
    success_migration_group_id BIGINT DEFAULT NULL COMMENT '成功后建群账号迁移分组ID',
    failed_migration_group_id BIGINT DEFAULT NULL COMMENT '失败后建群账号迁移分组ID',
    send_messages_allowed TINYINT NOT NULL DEFAULT 1 COMMENT '所有成员可发言',
    edit_group_settings_allowed TINYINT NOT NULL DEFAULT 0 COMMENT '普通成员可修改群资料',
    add_members_allowed TINYINT NOT NULL DEFAULT 1 COMMENT '所有成员可添加成员',
    join_approval_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '入群审批开关',
    ephemeral_duration_seconds INT NOT NULL DEFAULT 0 COMMENT '限时消息秒数;0=关闭',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/PARTIAL/FAILED',
    total_count INT NOT NULL DEFAULT 0 COMMENT '实际生成计划群明细数;任务汇总口径',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功群数',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败群数',
    created_by BIGINT NOT NULL COMMENT '创建用户ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_normal_group_creation_task_idem (tenant_id, idempotency_key),
    KEY idx_normal_group_creation_task_status (tenant_id, deleted_at, status, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新建普群任务';

CREATE TABLE IF NOT EXISTS normal_group_creation_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '任务ID',
    item_no INT NOT NULL COMMENT '任务内序号',
    group_subject VARCHAR(128) NOT NULL COMMENT '本群名称',
    creator_account_id BIGINT NOT NULL COMMENT '建群Armada账号ID',
    creator_protocol_account_id VARCHAR(128) NOT NULL COMMENT '建群协议账号ID',
    creator_protocol_backend VARCHAR(16) NOT NULL COMMENT '协议类型:WEB/ANDROID',
    creator_ws_phone VARCHAR(32) NOT NULL COMMENT '建群WhatsApp号码',
    group_jid VARCHAR(128) DEFAULT NULL COMMENT '建群成功后的WhatsApp群JID',
    group_link_id BIGINT DEFAULT NULL COMMENT '群组列表入口ID',
    create_partial TINYINT NOT NULL DEFAULT 0 COMMENT '协议建群结果是否部分成功',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/CREATED/CREATED_PARTIAL/FAILED/RESULT_UNKNOWN',
    current_step VARCHAR(32) NOT NULL DEFAULT 'PREPARING_CONTACTS' COMMENT 'PREPARING_CONTACTS/CREATING_GROUP/POST_PROCESSING/DONE',
    dispatch_stage VARCHAR(24) NOT NULL DEFAULT 'PREPARE' COMMENT '下一次待发布阶段',
    dispatch_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/PROCESSING/FAILED/NONE',
    next_dispatch_at BIGINT NOT NULL COMMENT '下次可补偿发布时间',
    processing_started_at BIGINT DEFAULT NULL COMMENT '阶段执行租约开始时间(epoch毫秒)',
    prepare_attempt_count INT NOT NULL DEFAULT 0 COMMENT '联系人准备尝试数',
    create_attempt_count INT NOT NULL DEFAULT 0 COMMENT '建群尝试数',
    post_attempt_count INT NOT NULL DEFAULT 0 COMMENT '群后处理尝试数',
    settings_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '权限处理状态',
    creator_leave_status VARCHAR(16) NOT NULL DEFAULT 'SKIPPED' COMMENT '建群人退群状态',
    last_event_id VARCHAR(64) DEFAULT NULL COMMENT '当前执行租约或最近处理的业务事件ID',
    last_error_code VARCHAR(64) DEFAULT NULL COMMENT '最近错误码',
    last_error_message VARCHAR(512) DEFAULT NULL COMMENT '最近脱敏错误摘要',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_normal_group_creation_item_no (tenant_id, task_id, item_no),
    UNIQUE KEY uq_normal_group_creation_creator (tenant_id, task_id, creator_account_id),
    KEY idx_normal_group_creation_item_dispatch (dispatch_status, next_dispatch_at, id),
    KEY idx_normal_group_creation_item_processing (dispatch_status, processing_started_at, id),
    KEY idx_normal_group_creation_item_task (tenant_id, task_id, deleted_at, id),
    KEY idx_normal_group_creation_item_jid (tenant_id, group_jid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新建普群计划群明细';

CREATE TABLE IF NOT EXISTS normal_group_creation_item_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '任务ID',
    item_id BIGINT NOT NULL COMMENT '计划群明细ID',
    member_order INT NOT NULL COMMENT '群内成员顺序',
    member_account_id BIGINT NOT NULL COMMENT '成员Armada账号ID',
    member_protocol_account_id VARCHAR(128) NOT NULL COMMENT '成员协议账号ID',
    member_protocol_backend VARCHAR(16) NOT NULL COMMENT '协议类型:WEB/ANDROID',
    member_ws_phone VARCHAR(32) NOT NULL COMMENT '成员WhatsApp号码',
    creator_saved_member_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '建群人保存成员状态',
    member_saved_creator_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '成员保存建群人状态',
    participant_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '一次性建群逐成员结果',
    participant_raw_status VARCHAR(128) DEFAULT NULL COMMENT '协议原始状态摘要',
    last_error_code VARCHAR(64) DEFAULT NULL COMMENT '最近错误码',
    last_error_message VARCHAR(512) DEFAULT NULL COMMENT '最近脱敏错误摘要',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_normal_group_creation_member (tenant_id, item_id, member_account_id),
    KEY idx_normal_group_creation_member_task (tenant_id, task_id, member_account_id),
    KEY idx_normal_group_creation_member_item (tenant_id, item_id, member_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新建普群成员冻结快照';

-- 群组列表页的新建普群操作权限。接口仍使用方法级鉴权，按钮权限仅用于前端控显。
SET @normal_group_permission_now := UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL, NULL,
       permission.perm_key, NULL, permission.sort_no, 1,
       @normal_group_permission_now, NULL, @normal_group_permission_now, NULL
FROM tenant
JOIN sys_menu parent
  ON parent.tenant_id = tenant.id
 AND parent.menu_key = 'GroupList'
CROSS JOIN (
    SELECT '新建普群' AS menu_name,
           'NormalGroupCreate' AS menu_key,
           'tenant:normal_group:create' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT '查看普群任务', 'NormalGroupView', 'tenant:normal_group:view', 20
    UNION ALL
    SELECT '重试普群失败项', 'NormalGroupRetry', 'tenant:normal_group:retry', 30
) permission
WHERE tenant.status = 1;
