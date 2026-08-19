-- V130：普通营销账号动态目标的新群首次发送增加可持久化等待阶段。
-- 配置属于 marketing_task 聚合；检测和计划时间属于第 0 轮 attempt 审计事实。

SET @new_group_delay_enabled_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'is_new_group_delay_enabled'
);
SET @new_group_delay_enabled_sql = IF(
    @new_group_delay_enabled_exists = 0,
    'ALTER TABLE marketing_task ADD COLUMN is_new_group_delay_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''账号动态目标检测到新群后是否延迟首次发送:0=否 1=是''',
    'SELECT 1'
);
PREPARE new_group_delay_enabled_stmt FROM @new_group_delay_enabled_sql;
EXECUTE new_group_delay_enabled_stmt;
DEALLOCATE PREPARE new_group_delay_enabled_stmt;

SET @new_group_delay_value_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'new_group_delay_value'
);
SET @new_group_delay_value_sql = IF(
    @new_group_delay_value_exists = 0,
    'ALTER TABLE marketing_task ADD COLUMN new_group_delay_value INT NOT NULL DEFAULT 30 COMMENT ''新群首次发送延迟数值''',
    'SELECT 1'
);
PREPARE new_group_delay_value_stmt FROM @new_group_delay_value_sql;
EXECUTE new_group_delay_value_stmt;
DEALLOCATE PREPARE new_group_delay_value_stmt;

SET @new_group_delay_unit_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task'
      AND column_name = 'new_group_delay_unit'
);
SET @new_group_delay_unit_sql = IF(
    @new_group_delay_unit_exists = 0,
    'ALTER TABLE marketing_task ADD COLUMN new_group_delay_unit TINYINT NOT NULL DEFAULT 1 COMMENT ''新群首次发送延迟单位:1=分钟 2=小时''',
    'SELECT 1'
);
PREPARE new_group_delay_unit_stmt FROM @new_group_delay_unit_sql;
EXECUTE new_group_delay_unit_stmt;
DEALLOCATE PREPARE new_group_delay_unit_stmt;

SET @marketing_attempt_detected_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task_send_attempt'
      AND column_name = 'detected_at'
);
SET @marketing_attempt_detected_at_sql = IF(
    @marketing_attempt_detected_at_exists = 0,
    'ALTER TABLE marketing_task_send_attempt ADD COLUMN detected_at BIGINT DEFAULT NULL COMMENT ''Armada确认检测到新群时间(epoch毫秒)''',
    'SELECT 1'
);
PREPARE marketing_attempt_detected_at_stmt FROM @marketing_attempt_detected_at_sql;
EXECUTE marketing_attempt_detected_at_stmt;
DEALLOCATE PREPARE marketing_attempt_detected_at_stmt;

SET @marketing_attempt_scheduled_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task_send_attempt'
      AND column_name = 'scheduled_send_at'
);
SET @marketing_attempt_scheduled_at_sql = IF(
    @marketing_attempt_scheduled_at_exists = 0,
    'ALTER TABLE marketing_task_send_attempt ADD COLUMN scheduled_send_at BIGINT DEFAULT NULL COMMENT ''新群首次业务计划发送时间(epoch毫秒)''',
    'SELECT 1'
);
PREPARE marketing_attempt_scheduled_at_stmt FROM @marketing_attempt_scheduled_at_sql;
EXECUTE marketing_attempt_scheduled_at_stmt;
DEALLOCATE PREPARE marketing_attempt_scheduled_at_stmt;

-- 不可变地记录命令确实被 Outbox 接受的时间，避免协议最终失败后丢失“曾提交”事实。
SET @marketing_attempt_outbox_accepted_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task_send_attempt'
      AND column_name = 'outbox_accepted_at'
);
SET @marketing_attempt_outbox_accepted_at_sql = IF(
    @marketing_attempt_outbox_accepted_at_exists = 0,
    'ALTER TABLE marketing_task_send_attempt ADD COLUMN outbox_accepted_at BIGINT DEFAULT NULL COMMENT ''Outbox确认接受命令时间(epoch毫秒)''',
    'SELECT 1'
);
PREPARE marketing_attempt_outbox_accepted_at_stmt FROM @marketing_attempt_outbox_accepted_at_sql;
EXECUTE marketing_attempt_outbox_accepted_at_stmt;
DEALLOCATE PREPARE marketing_attempt_outbox_accepted_at_stmt;

-- Outbox 行是迁移前“确实被发送端口接受”的可验证历史事实；回填后即使 attempt 已是失败/跳过也不会重复首发。
UPDATE marketing_task_send_attempt attempt
JOIN protocol_command_outbox outbox
  ON outbox.tenant_id = attempt.tenant_id
 AND outbox.command_id = attempt.command_id
SET attempt.outbox_accepted_at = COALESCE(outbox.created_at, attempt.submitted_at, attempt.created_at)
WHERE attempt.round_no > 0
  AND attempt.outbox_accepted_at IS NULL;

ALTER TABLE marketing_task_send_attempt
    MODIFY COLUMN status TINYINT NOT NULL
        COMMENT '尝试状态:0=已提交 1=成功 2=失败 3=跳过 4=等待发送';

SET @marketing_attempt_wait_due_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'marketing_task_send_attempt'
      AND index_name = 'idx_marketing_attempt_wait_due'
);
SET @marketing_attempt_wait_due_index_sql = IF(
    @marketing_attempt_wait_due_index_exists = 0,
    'ALTER TABLE marketing_task_send_attempt ADD KEY idx_marketing_attempt_wait_due (status, scheduled_send_at, id)',
    'SELECT 1'
);
PREPARE marketing_attempt_wait_due_index_stmt FROM @marketing_attempt_wait_due_index_sql;
EXECUTE marketing_attempt_wait_due_index_stmt;
DEALLOCATE PREPARE marketing_attempt_wait_due_index_stmt;
