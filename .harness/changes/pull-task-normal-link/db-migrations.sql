-- 普通群链接拉群任务执行域:6 张新表 + pull_task 3 个生命周期列。
-- 不修改拉群营销表,不迁移历史营销任务。设计见
-- docs/superpowers/specs/2026-08-02-pull-task-normal-link-data-model-design.md。

-- pull_task 增加生命周期列;ADD COLUMN 用 information_schema 守卫保证幂等。
SET @pull_task_started_at_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'started_at') = 0,
    'ALTER TABLE pull_task ADD COLUMN started_at BIGINT DEFAULT NULL COMMENT ''首次真实启动时间(epoch毫秒)'' AFTER status',
    'SELECT 1'
);
PREPARE pull_task_started_at_stmt FROM @pull_task_started_at_sql;
EXECUTE pull_task_started_at_stmt;
DEALLOCATE PREPARE pull_task_started_at_stmt;

SET @pull_task_finished_at_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'finished_at') = 0,
    'ALTER TABLE pull_task ADD COLUMN finished_at BIGINT DEFAULT NULL COMMENT ''进入COMPLETED或ENDED的时间(epoch毫秒)'' AFTER started_at',
    'SELECT 1'
);
PREPARE pull_task_finished_at_stmt FROM @pull_task_finished_at_sql;
EXECUTE pull_task_finished_at_stmt;
DEALLOCATE PREPARE pull_task_finished_at_stmt;

SET @pull_task_version_sql := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task'
       AND column_name = 'version') = 0,
    'ALTER TABLE pull_task ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT ''生命周期更新乐观锁版本号''',
    'SELECT 1'
);
PREPARE pull_task_version_stmt FROM @pull_task_version_sql;
EXECUTE pull_task_version_stmt;
DEALLOCATE PREPARE pull_task_version_stmt;

-- 普通群链接任务冻结执行配置;一条任务一行。
CREATE TABLE IF NOT EXISTS pull_task_standard_setting (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    auto_start TINYINT(1) NOT NULL DEFAULT 0 COMMENT '创建后是否自动启动:0否 1是',
    material_admin_timing TINYINT NOT NULL COMMENT '料子内管理员设置时点:1=成员入群后立即 2=本群料子全部终态后',
    pull_count_min INT NOT NULL COMMENT '单次拉人料子人数下限(闭区间,不含站台)',
    pull_count_max INT NOT NULL COMMENT '单次拉人料子人数上限(闭区间,不含站台)',
    pull_interval_seconds INT NOT NULL COMMENT '同一拉手账号连续拉人调用的最小间隔(秒)',
    puller_count_per_group INT NOT NULL COMMENT '每条执行行的计划拉手数',
    station_count_per_call INT NOT NULL COMMENT '每一次拉人调用叠加的站台数',
    concurrent_group_count INT NOT NULL COMMENT '同一父任务最大同时运行执行行数',
    puller_risk_minutes INT NOT NULL DEFAULT 0 COMMENT '拉手风控冷却分钟;0=不建立定时恢复',
    required_manager_count INT NOT NULL DEFAULT 0 COMMENT '任务启动时按管理分组可用账号数冻结的要求管理员人数N',
    manager_group_id BIGINT NOT NULL COMMENT '管理账号分组ID(→account_group.id)',
    puller_group_id BIGINT NOT NULL COMMENT '拉手账号分组ID(→account_group.id)',
    station_group_id BIGINT NOT NULL COMMENT '站台账号分组ID(→account_group.id)',
    manager_group_name VARCHAR(100) NOT NULL COMMENT '管理分组名称快照',
    puller_group_name VARCHAR(100) NOT NULL COMMENT '拉手分组名称快照',
    station_group_name VARCHAR(100) NOT NULL COMMENT '站台分组名称快照',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (tenant_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接任务冻结执行配置';

-- 群链接与TXT的一对一冻结配对;一行就是一条可独立调度的执行行。
CREATE TABLE IF NOT EXISTS pull_task_group_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '执行行主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id);草稿期也非空',
    seq INT NOT NULL COMMENT '任务内展示与执行顺序',
    group_link_id BIGINT DEFAULT NULL COMMENT '群入口ID(→group_link.id);最终创建时回填',
    normalized_link VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '归一化群链接chat.whatsapp.com/<邀请码>',
    invite_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '群邀请码;大小写敏感',
    source_link_line_no INT NOT NULL COMMENT '粘贴内容中的原始行号',
    group_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'WhatsApp群JID;启动校验时回填',
    source_file_index INT NOT NULL COMMENT '上传TXT的序号',
    source_file_name VARCHAR(255) NOT NULL COMMENT 'TXT原始文件名',
    total_line_count INT NOT NULL DEFAULT 0 COMMENT 'TXT总行数',
    valid_member_count INT NOT NULL DEFAULT 0 COMMENT '去重后有效料子数',
    invalid_line_count INT NOT NULL DEFAULT 0 COMMENT '非法号码行数',
    duplicate_line_count INT NOT NULL DEFAULT 0 COMMENT '文件内重复号码行数',
    execution_status TINYINT NOT NULL DEFAULT 0 COMMENT '执行状态:0=草稿 1=待启动 2=执行中 3=等待资源 4=已完成 5=失败终态 6=已放弃',
    stage TINYINT NOT NULL DEFAULT 1 COMMENT '业务阶段:1=链接校验 2=管理入群 3=管理拉手联系人 4=管理邀请拉手 5=拉人执行 6=料子提权 7=收口',
    manual_paused TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工暂停:0否 1是;与资源等待独立',
    wait_resource_type TINYINT DEFAULT NULL COMMENT '资源等待类型:1=管理员 2=拉手 3=站台;NULL=非资源等待',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '当前状态原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '当前状态原因描述(已脱敏)',
    next_manager_index INT NOT NULL DEFAULT 0 COMMENT '管理账号轮询游标',
    next_puller_index INT NOT NULL DEFAULT 0 COMMENT '拉手轮询游标',
    next_run_at BIGINT NOT NULL DEFAULT 0 COMMENT '下次可调度时间(epoch毫秒);0=立即可调度',
    lock_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '抢占调度的实例标识',
    lock_expires_at BIGINT DEFAULT NULL COMMENT '调度锁过期时间(epoch毫秒);过期可被抢占回收',
    version INT NOT NULL DEFAULT 1 COMMENT '执行行更新乐观锁版本号',
    started_at BIGINT DEFAULT NULL COMMENT '本行首次启动时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '本行进入终态时间(epoch毫秒)',
    last_business_executed_at BIGINT DEFAULT NULL COMMENT '最近一次真实业务动作时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    link_occupancy_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            CASE WHEN execution_status IN (1, 2, 3) THEN normalized_link ELSE NULL END
        ) STORED COMMENT '群链接跨任务占用唯一键辅助列;草稿与终态为NULL不占用',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_execution_seq (tenant_id, task_id, seq),
    UNIQUE KEY uq_pull_task_execution_link (tenant_id, task_id, normalized_link),
    UNIQUE KEY uq_pull_task_execution_file (tenant_id, task_id, source_file_index),
    UNIQUE KEY uq_pull_task_execution_link_occupancy (tenant_id, link_occupancy_key),
    KEY idx_pull_task_execution_page (tenant_id, task_id, id),
    KEY idx_pull_task_execution_dispatch (execution_status, manual_paused, next_run_at, id),
    KEY idx_pull_task_execution_group (tenant_id, group_link_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行(群链接与TXT一对一冻结配对)';

-- TXT有效料子号码及其入群、提权结果。
CREATE TABLE IF NOT EXISTS pull_task_material_member (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '料子成员主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    member_seq INT NOT NULL COMMENT '文件内去重后稳定顺序',
    source_line_no INT NOT NULL COMMENT '首次有效出现的原始行号',
    normalized_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '归一化号码(7-15位含国家码纯数字)',
    admin_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否带A/a需设群管理员标识:0否 1是',
    pull_call_id BIGINT DEFAULT NULL COMMENT '消费本料子的拉人调用ID;NULL=尚未消费',
    pull_status TINYINT NOT NULL DEFAULT 0 COMMENT '入群结果:0=未消费 1=已提交 2=成功 3=失败 4=结果未知 5=取消',
    pull_reason_code VARCHAR(64) DEFAULT NULL COMMENT '入群失败原因码',
    pull_reason_message VARCHAR(255) DEFAULT NULL COMMENT '入群失败原因描述(已脱敏)',
    wa_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '成功入群后的成员JID',
    pull_result_at BIGINT DEFAULT NULL COMMENT '入群结果回写时间(epoch毫秒)',
    admin_status TINYINT NOT NULL DEFAULT 0 COMMENT '提权结果:0=不需要 1=待执行 2=已提交 3=成功 4=失败 5=结果未知 6=取消',
    admin_command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '提权协议命令ID',
    admin_reason_code VARCHAR(64) DEFAULT NULL COMMENT '提权失败原因码',
    admin_result_at BIGINT DEFAULT NULL COMMENT '提权结果回写时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_material_seq (tenant_id, group_execution_id, member_seq),
    UNIQUE KEY uq_pull_task_material_phone (tenant_id, group_execution_id, normalized_phone),
    KEY idx_pull_task_material_pending (tenant_id, group_execution_id, pull_status, member_seq),
    KEY idx_pull_task_material_admin (tenant_id, group_execution_id, admin_required, admin_status, id),
    KEY idx_pull_task_material_admin_command (tenant_id, admin_command_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的TXT料子号码与逐号码结果';

-- 管理、拉手、站台在某条执行行中的选择、在群状态与拉手占用。
CREATE TABLE IF NOT EXISTS pull_task_group_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色账号主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    account_id BIGINT NOT NULL COMMENT '账号ID(→account.id)',
    account_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '账号号码展示快照',
    role_type TINYINT NOT NULL COMMENT '角色:1=管理 2=拉手 3=站台',
    role_seq INT NOT NULL COMMENT '同角色内顺序;补充时递增',
    source_type TINYINT NOT NULL DEFAULT 1 COMMENT '来源:1=初始选择 2=人工补充',
    selection_mode TINYINT NOT NULL DEFAULT 1 COMMENT '选号方式:1=自动 2=手动',
    entry_mode TINYINT DEFAULT NULL COMMENT '进群方式:1=踩链接 2=管理员邀请 3=拉手拉入;站台补充为NULL',
    membership_status TINYINT NOT NULL DEFAULT 0 COMMENT '在群状态:0=未入群 1=入群中 2=在群 3=入群失败 4=结果未知',
    joined_at BIGINT DEFAULT NULL COMMENT '确认在群时间(epoch毫秒)',
    pull_call_id BIGINT DEFAULT NULL COMMENT '站台由哪次拉人调用拉入(→pull_task_pull_call.id)',
    admin_status TINYINT NOT NULL DEFAULT 0 COMMENT '群管理员权限:0=不适用 1=待设置 2=已提交 3=成功 4=失败 5=结果未知',
    availability_status TINYINT NOT NULL DEFAULT 1 COMMENT '可用性:1=可用 2=风控冷却 3=离线或不可用 4=已移出本行',
    unavailable_reason_code VARCHAR(64) DEFAULT NULL COMMENT '不可用原因码',
    cooldown_until BIGINT DEFAULT NULL COMMENT '风控冷却到期时间(epoch毫秒)',
    occupied_at BIGINT DEFAULT NULL COMMENT '拉手占用开始时间(epoch毫秒)',
    released_at BIGINT DEFAULT NULL COMMENT '拉手占用释放时间(epoch毫秒);NULL=当前占用中',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    occupancy_key BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN role_type = 2 AND released_at IS NULL THEN account_id ELSE NULL END
        ) STORED COMMENT '拉手跨任务互斥唯一键辅助列;非拉手或已释放为NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_group_account_occupancy (tenant_id, occupancy_key),
    UNIQUE KEY uq_pull_task_group_account_role (tenant_id, group_execution_id, role_type, account_id),
    UNIQUE KEY uq_pull_task_group_account_seq (tenant_id, group_execution_id, role_type, role_seq),
    KEY idx_pull_task_group_account_pick (tenant_id, group_execution_id, role_type, availability_status, id),
    KEY idx_pull_task_group_account_account (tenant_id, account_id, role_type, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的角色账号、在群状态与拉手占用';

-- 账号之间的真实协议动作:保存联系人、邀请入群、踩链接入群。
CREATE TABLE IF NOT EXISTS pull_task_account_action (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号动作主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    action_type TINYINT NOT NULL COMMENT '动作类型:1=保存联系人 2=邀请入群 3=踩链接入群',
    actor_group_account_id BIGINT NOT NULL COMMENT '动作发起方角色行ID;踩链接入群时为目标账号自身ID(MySQL唯一索引中NULL互不相等,留空会让幂等键失效)',
    target_group_account_id BIGINT NOT NULL COMMENT '动作对象角色行ID(→pull_task_group_account.id)',
    action_status TINYINT NOT NULL DEFAULT 1 COMMENT '动作结果:1=待执行 2=已提交 3=成功 4=失败 5=结果未知 6=取消',
    command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '协议命令ID;回调按此定位',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '失败原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '失败原因描述(已脱敏)',
    submitted_at BIGINT DEFAULT NULL COMMENT '命令提交时间(epoch毫秒)',
    result_at BIGINT DEFAULT NULL COMMENT '结果回写时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_action_pair
        (tenant_id, group_execution_id, action_type, actor_group_account_id, target_group_account_id),
    UNIQUE KEY uq_pull_task_action_command (tenant_id, command_id),
    KEY idx_pull_task_action_pending (tenant_id, group_execution_id, action_status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的账号动作(联系人/邀请/踩链接)';

-- 一个拉手对同一群JID的一次真实批量加成员请求。
CREATE TABLE IF NOT EXISTS pull_task_pull_call (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '拉人调用主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID(→pull_task_group_execution.id)',
    call_seq INT NOT NULL COMMENT '本执行行内调用序号',
    puller_group_account_id BIGINT NOT NULL COMMENT '执行本次调用的拉手角色行ID',
    puller_account_id BIGINT NOT NULL COMMENT '执行本次调用的拉手账号ID(→account.id)',
    planned_material_count INT NOT NULL COMMENT '本次计划料子人数(闭区间随机结果)',
    planned_station_count INT NOT NULL COMMENT '本次计划站台数',
    call_status TINYINT NOT NULL DEFAULT 1 COMMENT '调用状态:1=计划 2=已提交 3=已回写 4=结果未知 5=取消',
    command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '协议命令ID;回调按此定位',
    idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '计划阶段生成的幂等键;崩溃恢复用原键重投',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '失败原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '失败原因描述(已脱敏)',
    submitted_at BIGINT DEFAULT NULL COMMENT '命令提交时间(epoch毫秒)',
    result_at BIGINT DEFAULT NULL COMMENT '结果回写时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_call_seq (tenant_id, group_execution_id, call_seq),
    UNIQUE KEY uq_pull_task_call_idempotency (tenant_id, idempotency_key),
    UNIQUE KEY uq_pull_task_call_command (tenant_id, command_id),
    KEY idx_pull_task_call_puller_time (tenant_id, puller_account_id, submitted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接执行行的单次批量加成员调用';
