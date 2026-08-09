-- 普通群链接执行域回滚:先撤销 V107 增量，再按依赖逆序撤销初始结构。
-- 共享库或生产执行前必须单独确认目标环境。

-- V107 拉人波次增量。执行前必须确认不存在拉手为空的计划调用/attempt；
-- 生产回滚的数据处理与 Flyway 历史处理必须另走审批后的部署方案。
ALTER TABLE pull_task_pull_call_member_attempt
    DROP INDEX idx_pull_task_attempt_wave,
    DROP COLUMN puller_assignment_seq,
    DROP COLUMN pull_wave_id,
    MODIFY puller_group_account_id BIGINT NOT NULL
        COMMENT '本次真实执行拉手角色行ID';

ALTER TABLE pull_task_pull_call
    DROP INDEX uq_pull_task_call_wave_seq,
    DROP COLUMN puller_assignment_seq,
    DROP COLUMN wave_call_seq,
    DROP COLUMN pull_wave_id,
    MODIFY puller_group_account_id BIGINT NOT NULL COMMENT '执行本次调用的拉手角色行ID',
    MODIFY puller_account_id BIGINT NOT NULL COMMENT '执行本次调用的拉手账号ID';

ALTER TABLE pull_task_group_execution
    DROP COLUMN puller_assignment_seq,
    DROP COLUMN active_puller_group_account_id,
    DROP COLUMN active_pull_wave_id;

DROP TABLE IF EXISTS pull_task_pull_wave;

DROP TABLE IF EXISTS pull_task_pull_call;
DROP TABLE IF EXISTS pull_task_account_action;
DROP TABLE IF EXISTS pull_task_group_account;
DROP TABLE IF EXISTS pull_task_material_member;
DROP TABLE IF EXISTS pull_task_group_execution;
DROP TABLE IF EXISTS pull_task_standard_setting;

ALTER TABLE pull_task DROP COLUMN version;
ALTER TABLE pull_task DROP COLUMN finished_at;
ALTER TABLE pull_task DROP COLUMN started_at;

-- 回滚后必须手工删除 flyway_schema_history 中 version='090' 的记录,
-- 否则重新迁移会因 checksum 校验失败导致启动 crash-loop。
DELETE FROM flyway_schema_history WHERE version = '090';
