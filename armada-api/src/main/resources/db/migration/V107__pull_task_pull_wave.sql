-- 普通群链接拉人波次、粘性拉手及调用代际关联。
-- 仅新增结构，不回填历史调用；历史未完成调用由运行时代码兼容接管。

CREATE TABLE IF NOT EXISTS pull_task_pull_wave (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '拉人波次主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID',
    wave_no INT NOT NULL COMMENT '执行行内单调递增波次号',
    wave_type TINYINT NOT NULL COMMENT '波次类型:1=初始波次 2=重试波次',
    wave_status TINYINT NOT NULL COMMENT '波次状态:1=派发中 2=收集中 3=已结算 4=已取消',
    planned_call_count INT NOT NULL COMMENT '波次冻结调用数',
    next_call_seq INT NOT NULL DEFAULT 1 COMMENT '下一待派发波次内调用序号',
    next_dispatch_at BIGINT NOT NULL DEFAULT 0 COMMENT '下一调用可派发时间(epoch毫秒)',
    dispatch_completed_at BIGINT DEFAULT NULL COMMENT '全部调用派发完成时间(epoch毫秒)',
    settled_at BIGINT DEFAULT NULL COMMENT '全部参与者结果结算完成时间(epoch毫秒)',
    version INT NOT NULL DEFAULT 1 COMMENT '波次状态更新乐观锁版本号',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    active_execution_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN wave_status IN (1, 2) THEN group_execution_id ELSE NULL END
    ) STORED COMMENT '活动波次唯一键辅助列;终态为NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_wave_no (tenant_id, group_execution_id, wave_no),
    UNIQUE KEY uq_pull_task_wave_active (tenant_id, active_execution_id),
    KEY idx_pull_task_wave_due (tenant_id, wave_status, next_dispatch_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接拉人的完整计划与统一结算波次';

SET @pull_wave_execution_active_wave_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND column_name = 'active_pull_wave_id') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN active_pull_wave_id BIGINT DEFAULT NULL COMMENT ''当前活动拉人波次ID'' AFTER next_puller_index',
    'SELECT 1'
);
PREPARE pull_wave_execution_active_wave_stmt FROM @pull_wave_execution_active_wave_sql;
EXECUTE pull_wave_execution_active_wave_stmt;
DEALLOCATE PREPARE pull_wave_execution_active_wave_stmt;

SET @pull_wave_execution_active_puller_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND column_name = 'active_puller_group_account_id') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN active_puller_group_account_id BIGINT DEFAULT NULL COMMENT ''当前粘性拉手角色行ID'' AFTER active_pull_wave_id',
    'SELECT 1'
);
PREPARE pull_wave_execution_active_puller_stmt FROM @pull_wave_execution_active_puller_sql;
EXECUTE pull_wave_execution_active_puller_stmt;
DEALLOCATE PREPARE pull_wave_execution_active_puller_stmt;

SET @pull_wave_execution_assignment_seq_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_group_execution'
       AND column_name = 'puller_assignment_seq') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN puller_assignment_seq BIGINT NOT NULL DEFAULT 0 COMMENT ''粘性拉手分配代际;每次有效换号递增'' AFTER active_puller_group_account_id',
    'SELECT 1'
);
PREPARE pull_wave_execution_assignment_seq_stmt FROM @pull_wave_execution_assignment_seq_sql;
EXECUTE pull_wave_execution_assignment_seq_stmt;
DEALLOCATE PREPARE pull_wave_execution_assignment_seq_stmt;

SET @pull_wave_call_wave_id_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_pull_call'
       AND column_name = 'pull_wave_id') = 0,
    'ALTER TABLE pull_task_pull_call ADD COLUMN pull_wave_id BIGINT DEFAULT NULL COMMENT ''所属拉人波次ID;历史完成调用可为空'' AFTER group_execution_id',
    'SELECT 1'
);
PREPARE pull_wave_call_wave_id_stmt FROM @pull_wave_call_wave_id_sql;
EXECUTE pull_wave_call_wave_id_stmt;
DEALLOCATE PREPARE pull_wave_call_wave_id_stmt;

SET @pull_wave_call_seq_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_pull_call'
       AND column_name = 'wave_call_seq') = 0,
    'ALTER TABLE pull_task_pull_call ADD COLUMN wave_call_seq INT DEFAULT NULL COMMENT ''波次内稳定调用序号'' AFTER call_seq',
    'SELECT 1'
);
PREPARE pull_wave_call_seq_stmt FROM @pull_wave_call_seq_sql;
EXECUTE pull_wave_call_seq_stmt;
DEALLOCATE PREPARE pull_wave_call_seq_stmt;

SET @pull_wave_call_assignment_seq_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_pull_call'
       AND column_name = 'puller_assignment_seq') = 0,
    'ALTER TABLE pull_task_pull_call ADD COLUMN puller_assignment_seq BIGINT DEFAULT NULL COMMENT ''本次调用绑定的拉手分配代际'' AFTER puller_account_id',
    'SELECT 1'
);
PREPARE pull_wave_call_assignment_seq_stmt FROM @pull_wave_call_assignment_seq_sql;
EXECUTE pull_wave_call_assignment_seq_stmt;
DEALLOCATE PREPARE pull_wave_call_assignment_seq_stmt;

ALTER TABLE pull_task_pull_call
    MODIFY puller_group_account_id BIGINT DEFAULT NULL
        COMMENT '执行本次调用的拉手角色行ID;计划态可为空',
    MODIFY puller_account_id BIGINT DEFAULT NULL
        COMMENT '执行本次调用的拉手账号ID;计划态可为空';

SET @pull_wave_call_index_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_pull_call'
       AND index_name = 'uq_pull_task_call_wave_seq') = 0,
    'ALTER TABLE pull_task_pull_call ADD UNIQUE KEY uq_pull_task_call_wave_seq (tenant_id, pull_wave_id, wave_call_seq)',
    'SELECT 1'
);
PREPARE pull_wave_call_index_stmt FROM @pull_wave_call_index_sql;
EXECUTE pull_wave_call_index_stmt;
DEALLOCATE PREPARE pull_wave_call_index_stmt;

SET @pull_wave_attempt_wave_id_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_pull_call_member_attempt'
       AND column_name = 'pull_wave_id') = 0,
    'ALTER TABLE pull_task_pull_call_member_attempt ADD COLUMN pull_wave_id BIGINT DEFAULT NULL COMMENT ''所属拉人波次ID'' AFTER pull_call_id',
    'SELECT 1'
);
PREPARE pull_wave_attempt_wave_id_stmt FROM @pull_wave_attempt_wave_id_sql;
EXECUTE pull_wave_attempt_wave_id_stmt;
DEALLOCATE PREPARE pull_wave_attempt_wave_id_stmt;

SET @pull_wave_attempt_assignment_seq_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_pull_call_member_attempt'
       AND column_name = 'puller_assignment_seq') = 0,
    'ALTER TABLE pull_task_pull_call_member_attempt ADD COLUMN puller_assignment_seq BIGINT DEFAULT NULL COMMENT ''本次执行绑定的拉手分配代际'' AFTER puller_group_account_id',
    'SELECT 1'
);
PREPARE pull_wave_attempt_assignment_seq_stmt FROM @pull_wave_attempt_assignment_seq_sql;
EXECUTE pull_wave_attempt_assignment_seq_stmt;
DEALLOCATE PREPARE pull_wave_attempt_assignment_seq_stmt;

ALTER TABLE pull_task_pull_call_member_attempt
    MODIFY puller_group_account_id BIGINT DEFAULT NULL
        COMMENT '本次真实执行拉手角色行ID;计划态可为空';

SET @pull_wave_attempt_index_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_pull_call_member_attempt'
       AND index_name = 'idx_pull_task_attempt_wave') = 0,
    'ALTER TABLE pull_task_pull_call_member_attempt ADD KEY idx_pull_task_attempt_wave (tenant_id, pull_wave_id, lifecycle_status, id)',
    'SELECT 1'
);
PREPARE pull_wave_attempt_index_stmt FROM @pull_wave_attempt_index_sql;
EXECUTE pull_wave_attempt_index_stmt;
DEALLOCATE PREPARE pull_wave_attempt_index_stmt;
