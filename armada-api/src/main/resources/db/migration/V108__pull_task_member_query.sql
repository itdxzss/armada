-- 普通拉群异步群成员查询；一行代表一次查询尝试，不拆明细或 attempt 子表。

CREATE TABLE IF NOT EXISTS pull_task_member_query (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '成员查询主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '普通拉群任务ID',
    group_execution_id BIGINT NOT NULL COMMENT '群执行行ID',
    business_key VARCHAR(191) NOT NULL COMMENT '调用方稳定业务键',
    purpose VARCHAR(64) NOT NULL COMMENT '查询用途',
    command_id VARCHAR(64) DEFAULT NULL COMMENT '协议Outbox命令ID',
    account_id BIGINT NOT NULL COMMENT 'Armada执行账号ID',
    protocol_account_id VARCHAR(128) NOT NULL COMMENT '协议账号ID',
    protocol_backend VARCHAR(16) NOT NULL COMMENT '协议后端:WEB/ANDROID',
    ws_phone VARCHAR(32) NOT NULL COMMENT 'Android执行账号手机号',
    group_jid VARCHAR(191) NOT NULL COMMENT '目标群JID',
    target_jids_json MEDIUMTEXT NOT NULL COMMENT '请求目标JID数组JSON',
    result_json MEDIUMTEXT DEFAULT NULL COMMENT '过滤后的成员事实JSON',
    query_status TINYINT NOT NULL COMMENT '状态:1=待结果 2=成功 3=失败 4=超时 5=取消',
    attempt_no INT NOT NULL COMMENT '同一业务键查询尝试号',
    requested_at BIGINT NOT NULL COMMENT '请求创建时间(epoch毫秒)',
    deadline_at BIGINT NOT NULL COMMENT '本次结果截止时间(epoch毫秒)',
    completed_at BIGINT DEFAULT NULL COMMENT '结果完成时间(epoch毫秒)',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '失败或终止原因码',
    error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败或终止原因',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    active_business_key VARCHAR(191) GENERATED ALWAYS AS (
        CASE WHEN query_status = 1 THEN business_key ELSE NULL END
    ) STORED COMMENT '同一执行行活动查询唯一键辅助列',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_member_query_open (
        tenant_id, group_execution_id, active_business_key
    ),
    UNIQUE KEY uq_pull_task_member_query_command (command_id),
    KEY idx_pull_task_member_query_execution (
        tenant_id, group_execution_id, query_status, purpose, id
    ),
    KEY idx_pull_task_member_query_task (tenant_id, task_id, query_status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通拉群异步群成员查询';
