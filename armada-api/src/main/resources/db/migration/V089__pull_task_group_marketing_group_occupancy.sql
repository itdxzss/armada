-- 拉群营销候选群组等待池与后续任务硬锁共用的单群占用表。
-- 群资料和账号关系继续以 group/account 聚合为事实源，本表只保存任务瞬时占用事实。
CREATE TABLE pull_task_group_marketing_group_occupancy (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '占用记录主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_link_id BIGINT DEFAULT NULL COMMENT '关联group_link.id；历史缺失时可空',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群唯一JID',
    group_source VARCHAR(16) NOT NULL COMMENT '群来源:HISTORICAL历史群 SELF_COLLECTED自收群',
    occupancy_type VARCHAR(16) NOT NULL COMMENT '占用类型:WAITING等待池软占用 HARD_LOCK任务硬占用',
    reservation_token VARCHAR(64) DEFAULT NULL COMMENT '建任务前等待池随机标识；硬锁后为空',
    task_id BIGINT DEFAULT NULL COMMENT '硬占用关联pull_task.id；软占用时为空',
    task_name_snapshot VARCHAR(128) DEFAULT NULL COMMENT '等待池或任务名称展示快照',
    planned_start_at BIGINT DEFAULT NULL COMMENT '计划启动时间(epoch毫秒)',
    last_validation_reason VARCHAR(255) DEFAULT NULL COMMENT '最近一次不可执行原因；可执行时为空',
    last_validated_at BIGINT DEFAULT NULL COMMENT '最近一次等待池校验时间(epoch毫秒)',
    created_by BIGINT NOT NULL COMMENT '创建等待池或取得硬锁的用户ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    expires_at BIGINT DEFAULT NULL COMMENT '等待池软占用过期时间(epoch毫秒)；硬锁为空',
    released_at BIGINT DEFAULT NULL COMMENT '释放时间(epoch毫秒)；NULL表示当前有效占用',
    active_key TINYINT GENERATED ALWAYS AS (
        CASE WHEN released_at IS NULL THEN 1 ELSE NULL END
    ) STORED COMMENT '有效占用唯一键辅助列',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_marketing_group_active (tenant_id, group_jid, active_key),
    KEY idx_group_marketing_pool (tenant_id, reservation_token, released_at, id),
    KEY idx_group_marketing_waiting_expiry (tenant_id, occupancy_type, released_at, expires_at),
    KEY idx_group_marketing_task_lock (tenant_id, task_id, occupancy_type, released_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群营销单群等待池软占用与任务硬锁';
