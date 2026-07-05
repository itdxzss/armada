ALTER TABLE marketing_task_target
    ADD COLUMN target_scope TINYINT NOT NULL DEFAULT 1
        COMMENT '目标维度:1=固定群组 2=账号动态群组' AFTER account_phone,
    MODIFY COLUMN group_link_id BIGINT DEFAULT NULL
        COMMENT '目标群入口ID;固定群组维度必填,账号动态维度为空',
    MODIFY COLUMN group_jid VARCHAR(128) DEFAULT NULL
        COMMENT 'WhatsApp群JID;固定群组维度快照,账号动态维度发送前解析',
    MODIFY COLUMN group_link_url VARCHAR(255) DEFAULT NULL
        COMMENT '群链接URL快照;固定群组维度保存,账号动态维度为空',
    ADD COLUMN target_unique_group_key BIGINT GENERATED ALWAYS AS (
        CASE WHEN target_scope = 2 THEN 0 ELSE COALESCE(group_link_id, -1) END
    ) STORED
        COMMENT '目标唯一约束辅助列:账号动态维度同任务同账号只允许一行' AFTER group_name;

ALTER TABLE marketing_task_target
    DROP INDEX uq_marketing_task_target_pair,
    ADD UNIQUE KEY uq_marketing_task_target_scope
        (tenant_id, marketing_task_id, account_id, target_scope, target_unique_group_key),
    ADD KEY idx_marketing_task_target_scope (tenant_id, marketing_task_id, target_scope, account_id);

ALTER TABLE marketing_task_send_attempt
    ADD COLUMN group_link_id BIGINT DEFAULT NULL
        COMMENT '本次实际发送的群入口ID快照;账号动态维度每轮解析后写入' AFTER target_id,
    ADD COLUMN group_jid VARCHAR(128) DEFAULT NULL
        COMMENT '本次实际发送的WhatsApp群JID快照' AFTER group_link_id,
    ADD COLUMN group_name VARCHAR(128) DEFAULT NULL
        COMMENT '本次实际发送的群名称快照' AFTER group_jid,
    ADD COLUMN attempt_group_key VARCHAR(160) GENERATED ALWAYS AS (
        COALESCE(group_jid, CONCAT('target:', target_id))
    ) STORED
        COMMENT '同一target同一轮的实际群唯一约束辅助列' AFTER group_name;

ALTER TABLE marketing_task_send_attempt
    DROP INDEX uq_marketing_task_attempt_round,
    ADD UNIQUE KEY uq_marketing_task_attempt_group_round
        (tenant_id, target_id, round_no, attempt_group_key),
    ADD KEY idx_marketing_task_attempt_group_jid (tenant_id, group_jid);
