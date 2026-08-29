-- V158 超链任务 H3：配置、内容、运行态、唯一 recipient、计费、轮次、账号使用、领取和累计投影。

-- ADD COLUMN 按项目规范使用 information_schema 守卫，允许已由预备迁移落列的环境安全执行。
SET @h3_schema := DATABASE();
SET @h3_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@h3_schema AND TABLE_NAME='data_package_phone'
             AND COLUMN_NAME='claimed_by_hyperlink_task_id'),
    'SELECT 1',
    'ALTER TABLE data_package_phone ADD COLUMN claimed_by_hyperlink_task_id BIGINT DEFAULT NULL COMMENT ''当前领取该号码的超链任务ID;释放后为NULL'' AFTER pool_status');
PREPARE h3_stmt FROM @h3_sql;
EXECUTE h3_stmt;
DEALLOCATE PREPARE h3_stmt;

SET @h3_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@h3_schema AND TABLE_NAME='data_package_phone'
             AND COLUMN_NAME='claimed_at'),
    'SELECT 1',
    'ALTER TABLE data_package_phone ADD COLUMN claimed_at BIGINT DEFAULT NULL COMMENT ''最近一次任务领取时间(epoch毫秒)'' AFTER claimed_by_hyperlink_task_id');
PREPARE h3_stmt FROM @h3_sql;
EXECUTE h3_stmt;
DEALLOCATE PREPARE h3_stmt;

SET @h3_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@h3_schema AND TABLE_NAME='data_package_phone'
             AND INDEX_NAME='idx_data_package_phone_claim'),
    'SELECT 1',
    'ALTER TABLE data_package_phone ADD KEY idx_data_package_phone_claim (tenant_id, claimed_by_hyperlink_task_id, pool_status, id)');
PREPARE h3_stmt FROM @h3_sql;
EXECUTE h3_stmt;
DEALLOCATE PREPARE h3_stmt;

-- 模板与任务共享同一内容能力，标题统一无损扩到 1024。
ALTER TABLE hyperlink_template
    MODIFY COLUMN title VARCHAR(1024) NOT NULL COMMENT '消息标题或按钮气泡上方标题;最多1024字符';

CREATE TABLE IF NOT EXISTS hyperlink_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '超链任务主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称;租户内允许重名',
    task_type TINYINT NOT NULL COMMENT '任务模式:1即时 2预发布 3周期',
    start_mode TINYINT NOT NULL DEFAULT 1 COMMENT '启动方式:1立即 2延后',
    task_delay_minutes INT NOT NULL DEFAULT 0 COMMENT '延后分钟;立即任务为0',
    task_planned_end_at BIGINT DEFAULT NULL COMMENT '预发布计划结束时间(epoch毫秒)',
    task_interval_minutes INT NOT NULL DEFAULT 0 COMMENT '周期间隔分钟;非周期为0',
    data_package_id BIGINT DEFAULT NULL COMMENT '数据包ID;仅保存时可为空',
    data_package_generation INT DEFAULT NULL COMMENT '首次启用时冻结的数据包代次',
    data_package_name_snapshot VARCHAR(128) DEFAULT NULL COMMENT '数据包名称冻结快照',
    target_country_iso2s_snapshot JSON DEFAULT NULL COMMENT '冻结受众国家ISO2去重数组',
    source_template_id BIGINT DEFAULT NULL COMMENT '引用模板ID;仅追溯',
    source_template_version INT DEFAULT NULL COMMENT '引用模板版本',
    hyperlink_strategy_id BIGINT DEFAULT NULL COMMENT '引用策略ID;仅追溯',
    account_filter JSON NOT NULL COMMENT 'filterSchemaVersion=1的账号筛选快照',
    max_use_account INT NOT NULL DEFAULT 0 COMMENT '最大使用账号数;周期表示每轮上限',
    concurrent_num INT NOT NULL DEFAULT 10 COMMENT '最大执行账号数',
    account_max_send_num INT NOT NULL DEFAULT 0 COMMENT '单账号任务内成功上限;0不限',
    account_send_concurrency INT NOT NULL DEFAULT 20 COMMENT '单账号在途消息上限',
    msg_interval_min_ms INT NOT NULL DEFAULT 500 COMMENT '账号相邻消息随机间隔下界毫秒',
    msg_interval_max_ms INT NOT NULL DEFAULT 700 COMMENT '账号相邻消息随机间隔上界毫秒',
    is_short_link_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '内容按钮useShortLink派生投影',
    version INT NOT NULL DEFAULT 1 COMMENT '未开始编辑和动作乐观锁版本',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_hyperlink_task_tenant (tenant_id, id),
    KEY idx_hyperlink_task_created (tenant_id, created_at, id),
    KEY idx_hyperlink_task_name (tenant_id, task_name, id),
    KEY idx_hyperlink_task_package (tenant_id, data_package_id, id),
    KEY idx_hyperlink_task_planned_end (tenant_id, task_type, task_planned_end_at, id),
    CONSTRAINT ck_hyperlink_task_type CHECK (task_type IN (1,2,3)),
    CONSTRAINT ck_hyperlink_task_start_mode CHECK (start_mode IN (1,2)),
    CONSTRAINT ck_hyperlink_task_limits CHECK (
        task_delay_minutes >= 0 AND task_interval_minutes >= 0
        AND max_use_account >= 0 AND concurrent_num > 0
        AND account_max_send_num >= 0 AND account_send_concurrency BETWEEN 1 AND 100
        AND msg_interval_min_ms BETWEEN 0 AND 10000
        AND msg_interval_max_ms BETWEEN msg_interval_min_ms AND 10000
        AND version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务低频配置与冻结快照';

CREATE TABLE IF NOT EXISTS hyperlink_task_content (
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID;同时为主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    message_schema_version INT NOT NULL DEFAULT 1 COMMENT '消息内容契约版本',
    message_type TINYINT NOT NULL COMMENT '消息类型:1单图文 2历史双图文 3普通按钮 4卡片按钮',
    title VARCHAR(1024) NOT NULL COMMENT '消息标题;最多1024字符',
    content TEXT DEFAULT NULL COMMENT '正文或按钮副标题',
    link_description VARCHAR(512) DEFAULT NULL COMMENT '单/双图文链接描述',
    promotion_link VARCHAR(2048) DEFAULT NULL COMMENT '原始推广链接',
    buttons JSON NOT NULL COMMENT '按钮数组;新任务恰好一个CTA_URL',
    card_text VARCHAR(500) DEFAULT NULL COMMENT '卡片正文/底部文字',
    link_preview_asset_id BIGINT DEFAULT NULL COMMENT '链接预览图稳定素材ID',
    body_main_asset_id BIGINT DEFAULT NULL COMMENT '正文主图稳定素材ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (hyperlink_task_id),
    KEY idx_hyperlink_task_content_link_asset
        (tenant_id, link_preview_asset_id, hyperlink_task_id),
    KEY idx_hyperlink_task_content_body_asset
        (tenant_id, body_main_asset_id, hyperlink_task_id),
    CONSTRAINT ck_hyperlink_task_content_schema CHECK (message_schema_version > 0),
    CONSTRAINT ck_hyperlink_task_content_type CHECK (message_type IN (1,2,3,4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务消息内容冻结快照';

CREATE TABLE IF NOT EXISTS hyperlink_task_runtime (
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID;同时为主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    is_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0停用/仅保存 1启用',
    run_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始 1进行中 2完成 3暂停 4停止',
    provision_status TINYINT NOT NULL DEFAULT 0 COMMENT '0无需 1准备中 2就绪 3失败待恢复',
    current_round_id BIGINT DEFAULT NULL COMMENT '当前轮次ID',
    current_round_no BIGINT NOT NULL DEFAULT 0 COMMENT '当前轮次号',
    started_at BIGINT DEFAULT NULL COMMENT '首次开始时间(epoch毫秒)',
    last_send_at BIGINT DEFAULT NULL COMMENT '最近发送时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '完成/停止时间(epoch毫秒)',
    first_visit_at BIGINT DEFAULT NULL COMMENT '任务首次短链访问时间',
    last_visit_at BIGINT DEFAULT NULL COMMENT '任务最近短链访问时间',
    recipient_total INT NOT NULL DEFAULT 0 COMMENT '冻结去重受众数',
    send_total BIGINT NOT NULL DEFAULT 0 COMMENT '协议接受唯一command的recipient数',
    success_num BIGINT NOT NULL DEFAULT 0 COMMENT '至少单钩recipient数',
    delivered_num BIGINT NOT NULL DEFAULT 0 COMMENT '至少双钩recipient数',
    read_num BIGINT NOT NULL DEFAULT 0 COMMENT '已读recipient数',
    fail_num BIGINT NOT NULL DEFAULT 0 COMMENT '最终失败recipient数',
    fail_404_num BIGINT NOT NULL DEFAULT 0 COMMENT '未注册recipient数',
    invalid_account_count INT NOT NULL DEFAULT 0 COMMENT '任务内首次封号/失效账号去重数',
    click_uv_num INT NOT NULL DEFAULT 0 COMMENT '点击recipient去重数',
    click_total BIGINT NOT NULL DEFAULT 0 COMMENT '累计访问次数',
    used_account_count INT NOT NULL DEFAULT 0 COMMENT '实际分配recipient的账号去重数',
    actual_concurrency INT NOT NULL DEFAULT 0 COMMENT '当前或最近轮实际并发账号数',
    execution_duration_sec BIGINT NOT NULL DEFAULT 0 COMMENT '累计有效运行秒数',
    active_since_at BIGINT DEFAULT NULL COMMENT '当前连续运行段起点',
    metrics_updated_at BIGINT DEFAULT NULL COMMENT '发送指标最近投影完成时间',
    failure_code INT DEFAULT NULL COMMENT '准备失败稳定业务码',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '准备失败脱敏摘要',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '状态或投影更新时间(epoch毫秒)',
    PRIMARY KEY (hyperlink_task_id),
    KEY idx_hyperlink_runtime_status
        (tenant_id, provision_status, is_enabled, run_status, last_send_at, hyperlink_task_id),
    KEY idx_hyperlink_runtime_completion_global
        (is_enabled, run_status, provision_status, updated_at, hyperlink_task_id, tenant_id),
    CONSTRAINT ck_hyperlink_runtime_enabled CHECK (is_enabled IN (0,1)),
    CONSTRAINT ck_hyperlink_runtime_run CHECK (run_status IN (0,1,2,3,4)),
    CONSTRAINT ck_hyperlink_runtime_provision CHECK (provision_status IN (0,1,2,3)),
    CONSTRAINT ck_hyperlink_runtime_counts CHECK (
        current_round_no >= 0 AND recipient_total >= 0 AND send_total >= 0
        AND success_num >= 0 AND delivered_num >= 0 AND read_num >= 0
        AND fail_num >= 0 AND fail_404_num >= 0 AND invalid_account_count >= 0
        AND click_uv_num >= 0 AND click_total >= 0 AND used_account_count >= 0
        AND actual_concurrency >= 0 AND execution_duration_sec >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务状态与列表指标投影';

CREATE TABLE IF NOT EXISTS hyperlink_task_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '唯一recipient主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    data_package_id BIGINT DEFAULT NULL COMMENT '来源数据包ID',
    data_package_generation INT DEFAULT NULL COMMENT '来源数据包代次',
    source_import_id BIGINT NOT NULL COMMENT '来源导入批次ID',
    recipient_phone_snapshot VARCHAR(32) NOT NULL COMMENT '收件号码快照',
    recipient_country_iso2_snapshot CHAR(2) DEFAULT NULL COMMENT '收件国家ISO2快照',
    hyperlink_task_round_id BIGINT DEFAULT NULL COMMENT '实际分配轮次ID',
    round_no BIGINT DEFAULT NULL COMMENT '实际分配轮次号',
    account_id BIGINT DEFAULT NULL COMMENT '实际发信账号ID',
    sender_phone_snapshot VARCHAR(32) DEFAULT NULL COMMENT '发信号码快照',
    sender_country_iso2_snapshot CHAR(2) DEFAULT NULL COMMENT '发信国家ISO2快照',
    sender_account_type_snapshot TINYINT DEFAULT NULL COMMENT '发信账号类型:1个人 2商业',
    protocol_id VARCHAR(32) DEFAULT NULL COMMENT '实际协议标识快照',
    protocol_backend TINYINT DEFAULT NULL COMMENT '协议后端:1WEB 2ANDROID',
    command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '唯一稳定协议命令ID',
    protocol_message_id VARCHAR(128) DEFAULT NULL COMMENT '协议消息ID;ACK关联键',
    short_code VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '全局大小写敏感短码',
    send_status TINYINT NOT NULL DEFAULT 1 COMMENT '1待发 2发送中 3单钩 4双钩 5已读 6失败 7未注册',
    next_dispatch_at BIGINT NOT NULL DEFAULT 0 COMMENT '最早派发或同command恢复检查时间',
    metrics_projected_status TINYINT NOT NULL DEFAULT 1 COMMENT '最近已投影发送状态',
    needs_metrics_projection TINYINT GENERATED ALWAYS AS (
        CASE WHEN send_status <> metrics_projected_status THEN 1 ELSE NULL END
    ) STORED COMMENT '发送状态待投影标记',
    fail_code VARCHAR(64) DEFAULT NULL COMMENT '稳定失败码',
    fail_reason VARCHAR(255) DEFAULT NULL COMMENT '脱敏失败摘要',
    submitted_at BIGINT DEFAULT NULL COMMENT '协议通道接受唯一命令时间',
    sent_at BIGINT DEFAULT NULL COMMENT '至少单钩时间',
    delivered_at BIGINT DEFAULT NULL COMMENT '至少双钩时间',
    read_at BIGINT DEFAULT NULL COMMENT '已读时间',
    failed_at BIGINT DEFAULT NULL COMMENT '最终失败时间',
    click_count INT NOT NULL DEFAULT 0 COMMENT '收件人累计访问次数',
    first_visit_at BIGINT DEFAULT NULL COMMENT '首次访问时间',
    last_visit_at BIGINT DEFAULT NULL COMMENT '最近访问时间',
    first_visit_ip_address VARBINARY(16) DEFAULT NULL COMMENT '首次访问IPv4/IPv6',
    first_visit_user_agent VARCHAR(512) DEFAULT NULL COMMENT '首次访问原始UA截断',
    first_visit_browser VARCHAR(64) DEFAULT NULL COMMENT '首次访问浏览器',
    first_visit_os VARCHAR(64) DEFAULT NULL COMMENT '首次访问操作系统',
    first_visit_device VARCHAR(64) DEFAULT NULL COMMENT '首次访问设备',
    first_visit_language VARCHAR(32) DEFAULT NULL COMMENT '首次访问首选语言',
    first_visit_country_iso2 CHAR(2) DEFAULT NULL COMMENT '首次访问国家ISO2',
    attribution_purged_at BIGINT DEFAULT NULL COMMENT '首触敏感环境清理时间',
    metrics_projected_at BIGINT DEFAULT NULL COMMENT '最近发送指标投影时间',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_recipient
        (tenant_id, hyperlink_task_id, recipient_phone_snapshot),
    UNIQUE KEY uq_hyperlink_recipient_command (tenant_id, command_id),
    UNIQUE KEY uq_hyperlink_recipient_ack
        (tenant_id, account_id, protocol_id, protocol_message_id),
    UNIQUE KEY uq_hyperlink_recipient_short_code (short_code),
    KEY idx_hyperlink_recipient_task (tenant_id, hyperlink_task_id, send_status, id),
    KEY idx_hyperlink_recipient_account_sending
        (tenant_id, account_id, send_status, id),
    KEY idx_hyperlink_recipient_unassigned
        (tenant_id, hyperlink_task_id, send_status, hyperlink_task_round_id, id),
    KEY idx_hyperlink_recipient_pick
        (tenant_id, hyperlink_task_round_id, send_status, next_dispatch_at, id),
    KEY idx_hyperlink_recipient_source
        (tenant_id, data_package_id, data_package_generation, id),
    KEY idx_hyperlink_recipient_click (tenant_id, hyperlink_task_id, click_count, id),
    KEY idx_hyperlink_recipient_visit_trend
        (tenant_id, hyperlink_task_id, first_visit_at, id),
    KEY idx_hyperlink_recipient_attribution_retention
        (first_visit_at, attribution_purged_at, id),
    KEY idx_hyperlink_recipient_country
        (tenant_id, hyperlink_task_id, recipient_country_iso2_snapshot, id),
    KEY idx_hyperlink_recipient_sender_filter
        (tenant_id, hyperlink_task_id, sender_country_iso2_snapshot, fail_code, id),
    KEY idx_hyperlink_recipient_task_time
        (tenant_id, hyperlink_task_id, submitted_at, account_id, send_status, id),
    KEY idx_hyperlink_recipient_sender_phone
        (tenant_id, hyperlink_task_id, sender_phone_snapshot, id),
    KEY idx_hyperlink_recipient_projection
        (tenant_id, needs_metrics_projection, updated_at, id),
    KEY idx_hyperlink_recipient_projection_global
        (needs_metrics_projection, tenant_id, hyperlink_task_id, updated_at, id),
    KEY idx_hyperlink_recipient_reconciliation_global
        (send_status, next_dispatch_at, id, tenant_id, hyperlink_task_id),
    KEY idx_hyperlink_recipient_stat
        (tenant_id, submitted_at, sender_country_iso2_snapshot,
         recipient_country_iso2_snapshot, sender_account_type_snapshot, protocol_backend),
    CONSTRAINT ck_hyperlink_recipient_status CHECK (send_status IN (1,2,3,4,5,6,7)),
    CONSTRAINT ck_hyperlink_recipient_projected CHECK (metrics_projected_status IN (1,2,3,4,5,6,7)),
    CONSTRAINT ck_hyperlink_recipient_click CHECK (click_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务内唯一受众、发送与首触归因事实';

CREATE TABLE IF NOT EXISTS hyperlink_billing_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务计费预约主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    billing_provider VARCHAR(64) NOT NULL COMMENT '外部钱包提供方编码',
    quote_id VARCHAR(128) NOT NULL COMMENT '服务端报价标识;不保存quoteToken',
    quote_expires_at BIGINT NOT NULL COMMENT '报价失效时间',
    price_code VARCHAR(64) NOT NULL COMMENT '运行价码',
    pricing_mode TINYINT NOT NULL COMMENT '1普通 2超级并发',
    currency_code VARCHAR(16) NOT NULL COMMENT '计价币种',
    unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '单一国家展示单价',
    pricing_breakdown JSON NOT NULL COMMENT '按国家数量/单价/金额冻结行',
    quoted_recipient_count INT NOT NULL COMMENT '冻结受众数',
    quoted_amount DECIMAL(20,8) NOT NULL COMMENT '预计冻结金额',
    reserved_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '已预约金额',
    settled_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '已结算金额',
    released_amount DECIMAL(20,8) NOT NULL DEFAULT 0 COMMENT '已释放金额',
    settled_send_count BIGINT NOT NULL DEFAULT 0 COMMENT '已结算唯一recipient数',
    reservation_status TINYINT NOT NULL COMMENT '1处理中 2冻结 3部分结算 4结清 5释放 6失败',
    pending_operation TINYINT NOT NULL DEFAULT 0 COMMENT '0无 1冻结 2调整 3结算 4释放',
    operation_idempotency_key VARCHAR(128) DEFAULT NULL COMMENT '当前外部操作稳定幂等键',
    next_retry_at BIGINT DEFAULT NULL COMMENT '外部操作下次恢复时间',
    external_reservation_no VARCHAR(128) DEFAULT NULL COMMENT '外部预约单号',
    failure_code VARCHAR(64) DEFAULT NULL COMMENT '计费失败码',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '计费失败脱敏摘要',
    reserved_at BIGINT DEFAULT NULL COMMENT '冻结完成时间',
    settled_at BIGINT DEFAULT NULL COMMENT '结算完成时间',
    released_at BIGINT DEFAULT NULL COMMENT '释放完成时间',
    version INT NOT NULL DEFAULT 1 COMMENT '计费状态乐观锁版本',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_billing_task (tenant_id, hyperlink_task_id),
    UNIQUE KEY uq_hyperlink_billing_external
        (tenant_id, billing_provider, external_reservation_no),
    KEY idx_hyperlink_billing_recovery
        (tenant_id, pending_operation, next_retry_at, reservation_status, id),
    CONSTRAINT ck_hyperlink_billing_pricing CHECK (pricing_mode IN (1,2)),
    CONSTRAINT ck_hyperlink_billing_status CHECK (reservation_status IN (1,2,3,4,5,6)),
    CONSTRAINT ck_hyperlink_billing_operation CHECK (pending_operation IN (0,1,2,3,4)),
    CONSTRAINT ck_hyperlink_billing_amounts CHECK (
        quoted_recipient_count >= 0 AND quoted_amount >= 0 AND reserved_amount >= 0
        AND settled_amount >= 0 AND released_amount >= 0 AND settled_send_count >= 0
        AND version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务级报价冻结、结算和释放Saga状态';

CREATE TABLE IF NOT EXISTS hyperlink_task_round (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务轮次主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    round_no BIGINT NOT NULL COMMENT '任务内轮次号;从1开始',
    round_status TINYINT NOT NULL COMMENT '1计划 2选号 3待派发 4派发 5等回执 6完成 7暂停 8取消 9失败 10无账号',
    scheduled_at BIGINT NOT NULL COMMENT '轮次原始计划时间',
    next_dispatch_at BIGINT NOT NULL COMMENT '下一次业务可执行时间',
    lease_owner VARCHAR(64) DEFAULT NULL COMMENT '当前worker租约持有者',
    lease_expires_at BIGINT DEFAULT NULL COMMENT 'worker租约到期时间',
    assigned_recipient_count INT NOT NULL DEFAULT 0 COMMENT '本轮已分配唯一recipient数',
    selected_account_count INT NOT NULL DEFAULT 0 COMMENT '本轮固化账号数',
    actual_concurrency INT NOT NULL DEFAULT 0 COMMENT '本轮实际并发账号数',
    send_total BIGINT NOT NULL DEFAULT 0 COMMENT '本轮协议接受数',
    success_num BIGINT NOT NULL DEFAULT 0 COMMENT '本轮至少单钩数',
    delivered_num BIGINT NOT NULL DEFAULT 0 COMMENT '本轮至少双钩数',
    read_num BIGINT NOT NULL DEFAULT 0 COMMENT '本轮已读数',
    fail_num BIGINT NOT NULL DEFAULT 0 COMMENT '本轮最终失败数',
    fail_404_num BIGINT NOT NULL DEFAULT 0 COMMENT '本轮未注册数',
    started_at BIGINT DEFAULT NULL COMMENT '轮次开始时间',
    dispatch_completed_at BIGINT DEFAULT NULL COMMENT '分配完成时间',
    last_send_at BIGINT DEFAULT NULL COMMENT '最近发送时间',
    finished_at BIGINT DEFAULT NULL COMMENT '轮次终态时间',
    failure_code VARCHAR(64) DEFAULT NULL COMMENT '轮次失败码',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '轮次失败脱敏摘要',
    version INT NOT NULL DEFAULT 1 COMMENT '轮次状态/租约乐观锁版本',
    active_task_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN round_status IN (1,2,3,4,5,7) THEN hyperlink_task_id ELSE NULL END
    ) STORED COMMENT '同任务活动轮次唯一键辅助',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_round_no (tenant_id, hyperlink_task_id, round_no),
    UNIQUE KEY uq_hyperlink_round_active (tenant_id, active_task_id),
    KEY idx_hyperlink_round_due (tenant_id, round_status, next_dispatch_at, id),
    KEY idx_hyperlink_round_start_global
        (round_status, scheduled_at, id, tenant_id, hyperlink_task_id),
    KEY idx_hyperlink_round_lifecycle_global
        (round_status, next_dispatch_at, scheduled_at, updated_at, id, tenant_id, hyperlink_task_id),
    KEY idx_hyperlink_round_dispatch_global
        (round_status, next_dispatch_at, id, tenant_id, hyperlink_task_id),
    KEY idx_hyperlink_round_recovery (tenant_id, round_status, lease_expires_at, id),
    KEY idx_hyperlink_round_task (tenant_id, hyperlink_task_id, round_no, id),
    CONSTRAINT ck_hyperlink_round_status CHECK (round_status BETWEEN 1 AND 10),
    CONSTRAINT ck_hyperlink_round_counts CHECK (
        round_no > 0 AND assigned_recipient_count >= 0 AND selected_account_count >= 0
        AND actual_concurrency >= 0 AND send_total >= 0 AND success_num >= 0
        AND delivered_num >= 0 AND read_num >= 0 AND fail_num >= 0
        AND fail_404_num >= 0 AND version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务每轮可恢复调度状态';

CREATE TABLE IF NOT EXISTS hyperlink_task_account_usage (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务账号执行状态主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    account_id BIGINT NOT NULL COMMENT '发信账号ID',
    account_phone_snapshot VARCHAR(32) NOT NULL COMMENT '首次选中账号号码快照',
    sender_country_iso2_snapshot CHAR(2) DEFAULT NULL COMMENT '发信国家ISO2快照',
    account_type_snapshot TINYINT NOT NULL COMMENT '账号类型:1个人 2商业',
    account_created_at_snapshot BIGINT NOT NULL COMMENT '账号入库时间快照',
    protocol_id_snapshot VARCHAR(32) NOT NULL COMMENT '协议标识快照',
    protocol_account_id_snapshot VARCHAR(128) NOT NULL COMMENT '协议账号句柄快照',
    protocol_backend TINYINT NOT NULL COMMENT '协议后端:1WEB 2ANDROID',
    success_limit INT NOT NULL DEFAULT 0 COMMENT '任务内成功上限;0不限',
    successful_send_count BIGINT NOT NULL DEFAULT 0 COMMENT '跨轮成功数',
    reserved_success_slot_count INT NOT NULL DEFAULT 0 COMMENT '在途预占成功槽数',
    in_flight_count INT NOT NULL DEFAULT 0 COMMENT '当前在途command数',
    usage_status TINYINT NOT NULL DEFAULT 1 COMMENT '1可用 2达上限 3封号 4失效 5人工停用',
    invalid_code VARCHAR(64) DEFAULT NULL COMMENT '首次封号/失效码',
    invalid_reason VARCHAR(255) DEFAULT NULL COMMENT '首次封号/失效原因',
    invalid_at BIGINT DEFAULT NULL COMMENT '任务内首次失效时间',
    last_selected_round_no BIGINT NOT NULL DEFAULT 0 COMMENT '最近选入轮次号',
    next_send_at BIGINT NOT NULL DEFAULT 0 COMMENT '本账号下一条消息最早派发时间',
    first_used_at BIGINT DEFAULT NULL COMMENT '首次实际分配recipient时间',
    last_used_at BIGINT DEFAULT NULL COMMENT '最近实际分配recipient时间',
    version INT NOT NULL DEFAULT 1 COMMENT '占槽和状态乐观锁版本',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_task_account_usage
        (tenant_id, hyperlink_task_id, account_id),
    KEY idx_hyperlink_task_account_select
        (tenant_id, hyperlink_task_id, usage_status, next_send_at, id),
    KEY idx_hyperlink_task_account_reverse
        (tenant_id, account_id, usage_status, hyperlink_task_id, id),
    KEY idx_hyperlink_task_account_invalid
        (tenant_id, hyperlink_task_id, invalid_at, invalid_code, id),
    CONSTRAINT ck_hyperlink_usage_type CHECK (account_type_snapshot IN (1,2)),
    CONSTRAINT ck_hyperlink_usage_backend CHECK (protocol_backend IN (1,2)),
    CONSTRAINT ck_hyperlink_usage_status CHECK (usage_status IN (1,2,3,4,5)),
    CONSTRAINT ck_hyperlink_usage_counts CHECK (
        success_limit >= 0 AND successful_send_count >= 0
        AND reserved_success_slot_count >= 0 AND in_flight_count >= 0
        AND last_selected_round_no >= 0 AND version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务账号跨轮同步容量与失效事实';

CREATE TABLE IF NOT EXISTS hyperlink_task_round_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '轮次账号分配主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    hyperlink_task_round_id BIGINT NOT NULL COMMENT '轮次ID',
    round_no BIGINT NOT NULL COMMENT '轮次号快照',
    task_account_usage_id BIGINT NOT NULL COMMENT '任务账号使用行ID',
    account_id BIGINT NOT NULL COMMENT '发信账号ID',
    selection_no INT NOT NULL COMMENT '本轮稳定选号顺序;从1开始',
    assignment_status TINYINT NOT NULL DEFAULT 1 COMMENT '1可派发 2额度耗尽 3封号 4离线等待 5释放',
    selected_at BIGINT DEFAULT NULL COMMENT '选中时间',
    last_dispatch_at BIGINT DEFAULT NULL COMMENT '最近派发时间',
    released_at BIGINT DEFAULT NULL COMMENT '释放时间',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_round_account
        (tenant_id, hyperlink_task_round_id, account_id),
    UNIQUE KEY uq_hyperlink_round_account_order
        (tenant_id, hyperlink_task_round_id, selection_no),
    KEY idx_hyperlink_round_account_pick
        (tenant_id, hyperlink_task_round_id, assignment_status, id),
    CONSTRAINT ck_hyperlink_round_account_status CHECK (assignment_status IN (1,2,3,4,5)),
    CONSTRAINT ck_hyperlink_round_account_order CHECK (selection_no > 0 AND round_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务轮次内固化发信账号集合';

CREATE TABLE IF NOT EXISTS hyperlink_task_recipient_claim (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '受众领取作业主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    data_package_id BIGINT NOT NULL COMMENT '来源数据包ID',
    data_package_generation INT NOT NULL COMMENT '冻结数据包代次',
    claim_upper_phone_id BIGINT NOT NULL COMMENT '领取开始时最大phone.id',
    scan_cursor_phone_id BIGINT NOT NULL DEFAULT 0 COMMENT '已扫描到的最大phone.id',
    quoted_phone_count INT NOT NULL COMMENT '最后核对的受众数',
    claimed_phone_count INT NOT NULL DEFAULT 0 COMMENT '已生成唯一recipient数',
    claim_status TINYINT NOT NULL COMMENT '1准备 2领取 3持有 4释放 5已释放 6失败恢复 7关闭',
    lease_owner VARCHAR(64) DEFAULT NULL COMMENT '当前批worker',
    lease_expires_at BIGINT DEFAULT NULL COMMENT 'worker租约到期时间',
    failure_code VARCHAR(64) DEFAULT NULL COMMENT '最近失败码',
    failure_reason VARCHAR(255) DEFAULT NULL COMMENT '最近失败脱敏摘要',
    active_claim_key TINYINT GENERATED ALWAYS AS (
        CASE WHEN claim_status IN (1,2,4,6) THEN 1 ELSE NULL END
    ) STORED COMMENT '同代领取/释放操作互斥键',
    version INT NOT NULL DEFAULT 1 COMMENT '游标/状态乐观锁版本',
    started_at BIGINT DEFAULT NULL COMMENT '开始时间',
    finished_at BIGINT DEFAULT NULL COMMENT '领取/释放完成时间',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_recipient_claim_task (tenant_id, hyperlink_task_id),
    UNIQUE KEY uq_hyperlink_recipient_claim_generation
        (tenant_id, data_package_id, data_package_generation, active_claim_key),
    KEY idx_hyperlink_recipient_claim_recovery
        (tenant_id, claim_status, lease_expires_at, id),
    KEY idx_hyperlink_recipient_claim_provision_global
        (claim_status, lease_expires_at, updated_at, id, tenant_id, hyperlink_task_id),
    KEY idx_hyperlink_recipient_claim_cleanup_global
        (claim_status, updated_at, id, tenant_id, hyperlink_task_id),
    CONSTRAINT ck_hyperlink_claim_status CHECK (claim_status IN (1,2,3,4,5,6,7)),
    CONSTRAINT ck_hyperlink_claim_counts CHECK (
        data_package_generation > 0 AND claim_upper_phone_id >= 0
        AND scan_cursor_phone_id >= 0 AND quoted_phone_count >= 0
        AND claimed_phone_count >= 0 AND version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务受众分批领取与释放作业';

CREATE TABLE IF NOT EXISTS hyperlink_task_account_stat (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务账号累计投影主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    account_id BIGINT DEFAULT NULL COMMENT '实际发信账号ID;NULL为未分配桶',
    account_bucket_key BIGINT GENERATED ALWAYS AS (COALESCE(account_id, 0)) STORED
        COMMENT '未分配桶唯一键辅助;跨行相加口径',
    send_total BIGINT NOT NULL DEFAULT 0 COMMENT '已提交协议的唯一recipient数',
    success_num BIGINT NOT NULL DEFAULT 0 COMMENT '至少单钩recipient数',
    delivered_num BIGINT NOT NULL DEFAULT 0 COMMENT '至少双钩recipient数',
    read_num BIGINT NOT NULL DEFAULT 0 COMMENT '已读recipient数',
    failed_num BIGINT NOT NULL DEFAULT 0 COMMENT '最终失败recipient数',
    fail_404_num BIGINT NOT NULL DEFAULT 0 COMMENT '未注册recipient数',
    first_send_at BIGINT DEFAULT NULL COMMENT '首次发送时间',
    last_send_at BIGINT DEFAULT NULL COMMENT '最近发送时间',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '最近投影时间(epoch毫秒)',
    reconciled_at BIGINT DEFAULT NULL COMMENT '最近事实校准时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_account_stat
        (tenant_id, hyperlink_task_id, account_bucket_key),
    KEY idx_hyperlink_account_stat_success
        (tenant_id, hyperlink_task_id, success_num, id),
    KEY idx_hyperlink_account_stat_delivered
        (tenant_id, hyperlink_task_id, delivered_num, id),
    KEY idx_hyperlink_account_stat_failed
        (tenant_id, hyperlink_task_id, failed_num, id),
    CONSTRAINT ck_hyperlink_account_stat_counts CHECK (
        (account_id IS NULL OR account_id > 0) AND send_total >= 0 AND success_num >= 0
        AND delivered_num >= 0 AND read_num >= 0 AND failed_num >= 0 AND fail_404_num >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务账号累计统计投影;行内去重后跨行相加';

-- outbox 已先于超链任务存在；用生成列把两类保留期变为等值索引扫描，重复部署安全跳过。
SET @hyperlink_outbox_old_retention_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND index_name = 'idx_protocol_outbox_retention_aggregate') > 0,
    'ALTER TABLE protocol_command_outbox DROP INDEX idx_protocol_outbox_retention_aggregate',
    'SELECT 1'
);
PREPARE hyperlink_outbox_old_retention_index_stmt
    FROM @hyperlink_outbox_old_retention_index_ddl;
EXECUTE hyperlink_outbox_old_retention_index_stmt;
DEALLOCATE PREPARE hyperlink_outbox_old_retention_index_stmt;

SET @hyperlink_outbox_retention_class_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND column_name = 'retention_class') = 0,
    'ALTER TABLE protocol_command_outbox ADD COLUMN retention_class TINYINT GENERATED ALWAYS AS (CASE WHEN aggregate_type = ''HYPERLINK_TASK_RECIPIENT'' THEN 1 ELSE 0 END) STORED',
    'SELECT 1'
);
PREPARE hyperlink_outbox_retention_class_stmt FROM @hyperlink_outbox_retention_class_ddl;
EXECUTE hyperlink_outbox_retention_class_stmt;
DEALLOCATE PREPARE hyperlink_outbox_retention_class_stmt;

SET @hyperlink_outbox_retention_index_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'protocol_command_outbox'
       AND index_name = 'idx_protocol_outbox_retention_class') = 0,
    'ALTER TABLE protocol_command_outbox ADD KEY idx_protocol_outbox_retention_class (status, retention_class, created_at, id)',
    'SELECT 1'
);
PREPARE hyperlink_outbox_retention_index_stmt FROM @hyperlink_outbox_retention_index_ddl;
EXECUTE hyperlink_outbox_retention_index_stmt;
DEALLOCATE PREPARE hyperlink_outbox_retention_index_stmt;
