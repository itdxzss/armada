-- 群组列表批量刷新群链接 / 批量获取最新群信息的任务聚合与逐项结果。
-- 仅新增结构，不回填历史数据；已有的 group_metadata_sync_task 保持每租户每群一行的语义不变。

CREATE TABLE IF NOT EXISTS group_batch_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '批量任务主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_type TINYINT NOT NULL COMMENT '批量操作类型:1=刷新群链接 2=获取最新群信息',
    status TINYINT NOT NULL COMMENT '任务主状态:1=待执行 2=运行中 3=已完成 4=任务失败 5=已取消',
    total_count INT NOT NULL DEFAULT 0 COMMENT '提交校验去重后的有效处理项数;完成态等于成功数加失败数',
    success_count INT NOT NULL DEFAULT 0 COMMENT '成功项数;逐项原子递增，保证进度可实时读取',
    failed_count INT NOT NULL DEFAULT 0 COMMENT '失败项数;逐项原子递增',
    request_id VARCHAR(64) NOT NULL COMMENT '前端幂等键;同租户重复提交返回已有任务',
    created_by BIGINT NOT NULL COMMENT '发起操作的用户ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    completed_at BIGINT DEFAULT NULL COMMENT '进入终态时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_batch_task_request (tenant_id, request_id),
    KEY idx_group_batch_task_pending (tenant_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='群组列表批量刷新任务主表';

CREATE TABLE IF NOT EXISTS group_batch_task_item (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '批量任务明细主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '所属批量任务ID',
    group_link_id BIGINT NOT NULL COMMENT '目标群入口ID',
    group_jid VARCHAR(128) DEFAULT NULL COMMENT '目标群JID;提交时未知则为NULL',
    account_id BIGINT DEFAULT NULL COMMENT '实际执行账号ID;未选出账号时为NULL',
    status TINYINT NOT NULL COMMENT '明细状态:1=待执行 2=成功 3=失败 4=已取消',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '失败稳定错误码',
    description VARCHAR(512) DEFAULT NULL COMMENT '成功说明或脱敏失败原因',
    operated_at BIGINT DEFAULT NULL COMMENT '该项结束时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_batch_task_item_group (task_id, group_link_id),
    KEY idx_group_batch_task_item_pending (tenant_id, task_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='群组列表批量刷新任务逐群结果明细';
