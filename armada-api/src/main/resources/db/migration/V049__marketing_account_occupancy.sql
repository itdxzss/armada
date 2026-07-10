CREATE TABLE IF NOT EXISTS marketing_account_occupancy (
    id                 BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id          BIGINT NOT NULL                COMMENT '租户ID',
    account_id         BIGINT NOT NULL                COMMENT '当前被占用账号ID(→account.id)',
    marketing_task_id  BIGINT NOT NULL                COMMENT '当前占用账号的普通营销任务ID(→marketing_task.id)',
    occupied_at        BIGINT NOT NULL                COMMENT '账号被当前任务占用时间(epoch毫秒)',
    created_at         BIGINT NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at         BIGINT NOT NULL                COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_marketing_account_occupancy_account (tenant_id, account_id),
    KEY idx_marketing_account_occupancy_task (tenant_id, marketing_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='普通群组营销账号当前占用关系';
