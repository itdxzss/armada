-- 正式迁移以 Flyway V171__hyperlink_task_audit.sql 为准。
CREATE TABLE IF NOT EXISTS hyperlink_task_audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '超链任务审计事件主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    event_id VARCHAR(191) NOT NULL COMMENT '调用方生成的幂等事件键',
    action VARCHAR(32) NOT NULL COMMENT '任务、导出或计费动作',
    actor_user_id BIGINT DEFAULT NULL COMMENT '操作人用户ID;后台计费恢复为空',
    hyperlink_task_id BIGINT NOT NULL COMMENT '超链任务ID',
    occurred_at BIGINT NOT NULL COMMENT '动作确认时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '审计入库时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_task_audit_event (tenant_id, event_id),
    KEY idx_hyperlink_task_audit_task
        (tenant_id, hyperlink_task_id, occurred_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链任务变更、导出与计费动作的持久审计事实';
