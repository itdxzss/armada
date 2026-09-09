-- 动态发布任务。调度器按账号生成 status.publish.requested 协议命令，
-- 复用 protocol_command_outbox / Kafka / 协议层串行发送 / 统一结果回写链路。

CREATE TABLE IF NOT EXISTS feed_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    name VARCHAR(128) NOT NULL COMMENT '任务名称;仅后台展示',
    account_filter JSON DEFAULT NULL COMMENT '账号筛选条件;白名单归一化后落库,空对象表示不限定',
    title VARCHAR(512) DEFAULT NULL COMMENT '状态链接标题',
    description VARCHAR(2048) DEFAULT NULL COMMENT '状态链接描述',
    content VARCHAR(2000) NOT NULL COMMENT '动态正文内容',
    promotion_link VARCHAR(2048) DEFAULT NULL COMMENT '推广链接;可为空',
    link_preview_image_file_id BIGINT DEFAULT NULL COMMENT '链接预览图文件ID;引用marketing_template_file.id',
    text_color VARCHAR(32) DEFAULT NULL COMMENT '动态文本颜色',
    background_color VARCHAR(32) DEFAULT NULL COMMENT '动态背景颜色',
    concurrency INT NOT NULL DEFAULT 10 COMMENT '最大并发账号数',
    retry_max INT NOT NULL DEFAULT 3 COMMENT '单账号失败最大重试次数;0不重试',
    start_mode VARCHAR(16) NOT NULL DEFAULT 'now' COMMENT '启动方式:now立即 scheduled延后',
    task_delay_minutes INT NOT NULL DEFAULT 0 COMMENT '延后执行分钟数;start_mode=now时为0',
    task_start_at BIGINT DEFAULT NULL COMMENT '计划开始时间(epoch毫秒)',
    task_mode VARCHAR(16) NOT NULL DEFAULT 'instant' COMMENT '任务模式:instant即时 rolling滚动',
    task_planned_end_at BIGINT DEFAULT NULL COMMENT '滚动任务计划结束时间(epoch毫秒)',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '任务开关:0停用 1启用',
    task_status TINYINT NOT NULL DEFAULT 0 COMMENT '运行状态:0未开始 1进行中 2已完成 3已暂停 4已停止',
    current_round_no BIGINT NOT NULL DEFAULT 0 COMMENT '当前调度轮次',
    next_run_at BIGINT DEFAULT NULL COMMENT '下一次调度时间(epoch毫秒)',
    total_account_num INT NOT NULL DEFAULT 0 COMMENT '计划发送账号数',
    success_account_num INT NOT NULL DEFAULT 0 COMMENT '成功账号数',
    failed_account_num INT NOT NULL DEFAULT 0 COMMENT '失败账号数',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL为未删',
    PRIMARY KEY (id),
    KEY idx_feed_task_tenant (tenant_id, deleted_at, id),
    KEY idx_feed_task_run (tenant_id, task_status, next_run_at),
    KEY idx_feed_task_status_start (tenant_id, status, task_status, task_start_at),
    KEY idx_feed_task_created (tenant_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='动态发布任务主表';

CREATE TABLE IF NOT EXISTS feed_task_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务账号主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '所属任务ID',
    account_id BIGINT NOT NULL COMMENT '发送账号ID',
    account_phone_snapshot VARCHAR(32) DEFAULT NULL COMMENT '账号号码快照;账号改号不影响历史',
    send_status VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '发送状态:pending sending success failed retrying',
    retry_num INT NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    retry_max INT NOT NULL DEFAULT 3 COMMENT '最大重试次数;从任务配置快照写入',
    command_id VARCHAR(64) DEFAULT NULL COMMENT '协议命令ID;对应protocol_command_outbox.command_id',
    protocol_message_id VARCHAR(128) DEFAULT NULL COMMENT '协议返回的消息ID',
    send_at BIGINT DEFAULT NULL COMMENT '最近一次发出时间(epoch毫秒)',
    success_at BIGINT DEFAULT NULL COMMENT '成功时间(epoch毫秒)',
    failed_at BIGINT DEFAULT NULL COMMENT '最终失败时间(epoch毫秒)',
    fail_code VARCHAR(64) DEFAULT NULL COMMENT '失败错误码',
    fail_reason VARCHAR(255) DEFAULT NULL COMMENT '失败描述',
    round_no BIGINT NOT NULL DEFAULT 0 COMMENT '调度轮次',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_feed_task_account (task_id, account_id),
    KEY idx_feed_task_account_pick (task_id, send_status, id),
    KEY idx_feed_task_account_command (tenant_id, command_id),
    KEY idx_feed_task_account_phone (task_id, account_phone_snapshot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='动态发布任务账号明细';

SET @feed_task_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '动态营销', 'TaskFeedMarketing', 'D',
       '/task/feed', NULL, NULL, 'ep:picture', 60, 1,
       @feed_task_menu_now, NULL, @feed_task_menu_now, NULL
FROM sys_menu parent
WHERE parent.menu_key = 'TaskCenter';

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '动态任务', 'TaskFeed', 'M',
       '/task/feed/task', 'task/feed-task/index', 'tenant:feed_task:view',
       NULL, 10, 1, @feed_task_menu_now, NULL, @feed_task_menu_now, NULL
FROM sys_menu parent
WHERE parent.menu_key = 'TaskFeedMarketing';

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL,
       NULL, permission.perm_key, NULL, permission.sort_no, 1,
       @feed_task_menu_now, NULL, @feed_task_menu_now, NULL
FROM sys_menu parent
CROSS JOIN (
    SELECT '新建动态任务' AS menu_name,
           'TaskFeedCreate' AS menu_key,
           'tenant:feed_task:create' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT '编辑动态任务', 'TaskFeedEdit', 'tenant:feed_task:edit', 20
    UNION ALL
    SELECT '操作动态任务', 'TaskFeedOperate', 'tenant:feed_task:operate', 30
) permission
WHERE parent.menu_key = 'TaskFeed';
