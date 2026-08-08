-- 普通链接批量拉人逐号码执行台账、失败计数与批次名单核实状态。
-- 只新增结构，不复制、重算或解释历史批次；没有 attempt 行的历史调用继续走旧状态机。

ALTER TABLE pull_task_material_member
    ADD COLUMN pull_failure_count BIGINT NOT NULL DEFAULT 0
        COMMENT '累计对当前有效执行生效的明确失败次数' AFTER pull_status,
    ADD COLUMN active_pull_attempt_id BIGINT DEFAULT NULL
        COMMENT '当前活动逐号码执行记录ID' AFTER pull_failure_count,
    ADD COLUMN last_puller_group_account_id BIGINT DEFAULT NULL
        COMMENT '最近一次真实执行拉手角色行ID' AFTER active_pull_attempt_id;

ALTER TABLE pull_task_group_account
    ADD COLUMN membership_failure_count BIGINT NOT NULL DEFAULT 0
        COMMENT '站台累计明确入群失败次数' AFTER membership_status,
    ADD COLUMN active_pull_attempt_id BIGINT DEFAULT NULL
        COMMENT '站台当前活动逐号码执行记录ID' AFTER membership_failure_count,
    ADD COLUMN last_puller_group_account_id BIGINT DEFAULT NULL
        COMMENT '站台最近一次真实执行拉手角色行ID' AFTER active_pull_attempt_id;

ALTER TABLE pull_task_pull_call
    ADD COLUMN roster_check_status TINYINT NOT NULL DEFAULT 0
        COMMENT '名单核实:0未开始 1已认领 2成功 3失败 4跳过' AFTER call_status,
    ADD COLUMN roster_check_started_at BIGINT DEFAULT NULL
        COMMENT '名单核实认领时间(epoch毫秒)' AFTER roster_check_status,
    ADD COLUMN roster_check_finished_at BIGINT DEFAULT NULL
        COMMENT '名单核实完成时间(epoch毫秒)' AFTER roster_check_started_at;

CREATE TABLE pull_task_pull_call_member_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '逐号码执行记录主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID',
    group_execution_id BIGINT NOT NULL COMMENT '所属执行行ID',
    pull_call_id BIGINT NOT NULL COMMENT '所属批量拉人调用ID',
    participant_type TINYINT NOT NULL COMMENT '参与者:1=料子 2=站台',
    participant_ref_id BIGINT NOT NULL COMMENT '料子行或站台角色行ID',
    target_phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '归一化目标号码快照',
    target_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '冻结目标JID或协议补齐JID',
    puller_group_account_id BIGINT NOT NULL COMMENT '本次真实执行拉手角色行ID',
    attempt_no INT NOT NULL COMMENT '该参与者单调递增的计划执行序号',
    failure_count_before BIGINT NOT NULL DEFAULT 0 COMMENT '本次执行前累计明确失败次数',
    lifecycle_status TINYINT NOT NULL DEFAULT 1
        COMMENT '生命周期:1计划 2已提交 3关闭 4释放 5取消',
    active_slot TINYINT DEFAULT 1 COMMENT '计划或提交态固定为1，关闭后为NULL',
    protocol_outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '协议事实:SUCCESS/FAILED/UNKNOWN',
    execution_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '副作用阶段:NOT_STARTED/STARTED/UNCERTAIN',
    reason_code VARCHAR(64) DEFAULT NULL COMMENT '本次结果原因码',
    reason_message VARCHAR(255) DEFAULT NULL COMMENT '本次脱敏结果描述',
    submitted_at BIGINT DEFAULT NULL COMMENT '批次协议提交时间(epoch毫秒)',
    result_at BIGINT DEFAULT NULL COMMENT '协议事实时间(epoch毫秒)',
    released_at BIGINT DEFAULT NULL COMMENT '本次占用释放时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_attempt_call_participant
        (tenant_id, pull_call_id, participant_type, participant_ref_id),
    UNIQUE KEY uq_pull_task_attempt_active
        (tenant_id, group_execution_id, participant_type, participant_ref_id, active_slot),
    UNIQUE KEY uq_pull_task_attempt_sequence
        (tenant_id, group_execution_id, participant_type, participant_ref_id, attempt_no),
    KEY idx_pull_task_attempt_callback
        (tenant_id, pull_call_id, target_jid, target_phone, id),
    KEY idx_pull_task_attempt_schedule
        (tenant_id, group_execution_id, lifecycle_status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通链接批量拉人逐号码不可变执行台账';
