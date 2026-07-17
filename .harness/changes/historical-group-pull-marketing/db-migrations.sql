SET @baseline_group_subjects_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_group_baseline'
      AND column_name = 'baseline_group_subjects'
);

SET @baseline_group_subjects_sql := IF(
    @baseline_group_subjects_exists = 0,
    'ALTER TABLE account_group_baseline ADD COLUMN baseline_group_subjects JSON NULL COMMENT ''首次拍基线时轻量载荷已有的JID到静态群名映射;不表示当前成员关系'' AFTER baseline_group_jids',
    'SELECT 1'
);

PREPARE baseline_group_subjects_stmt FROM @baseline_group_subjects_sql;
EXECUTE baseline_group_subjects_stmt;
DEALLOCATE PREPARE baseline_group_subjects_stmt;

-- Task 8：历史群拉人是一次性独立执行域，失败后不在表内自动重试。
CREATE TABLE IF NOT EXISTS historical_group_pull_execution (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id               BIGINT       NOT NULL COMMENT '租户ID',
    created_by              BIGINT       NULL COMMENT '创建人user_id',
    idempotency_key         VARCHAR(128) NOT NULL COMMENT '创建幂等键;同租户唯一',
    operation_account_id    BIGINT       NOT NULL COMMENT '历史群当前操作账号ID',
    group_jid               VARCHAR(128) NOT NULL COMMENT '目标历史群JID',
    group_subject_snapshot  VARCHAR(255) NULL COMMENT '执行创建时群名快照',
    invite_link             VARCHAR(512) NULL COMMENT '执行创建时群邀请链接快照',
    puller_account_group_id BIGINT       NOT NULL COMMENT '拉手账号分组ID',
    puller_account_id       BIGINT       NULL COMMENT '实际领取执行的拉手账号ID',
    single_add_count        INT          NOT NULL COMMENT '每次participants/add批处理的普通与营销料子合计人数',
    marketing_template_id   BIGINT       NULL COMMENT '营销料子使用的营销模板ID',
    normal_count            INT          NOT NULL DEFAULT 0 COMMENT '普通料子数',
    marketing_count         INT          NOT NULL DEFAULT 0 COMMENT '营销料子数',
    invalid_count           INT          NOT NULL DEFAULT 0 COMMENT '无效号码数',
    duplicate_count         INT          NOT NULL DEFAULT 0 COMMENT '批次内重复号码数',
    pull_success_count      INT          NOT NULL DEFAULT 0 COMMENT '拉人成功数',
    pull_failure_count      INT          NOT NULL DEFAULT 0 COMMENT '拉人失败数',
    send_success_count      INT          NOT NULL DEFAULT 0 COMMENT '营销发送成功数',
    send_failure_count      INT          NOT NULL DEFAULT 0 COMMENT '营销发送失败数',
    pull_status             TINYINT      NOT NULL DEFAULT 0 COMMENT '拉人状态:0待执行 1执行中 2成功 3部分成功 4失败',
    marketing_status        TINYINT      NOT NULL DEFAULT 0 COMMENT '营销状态:0不适用 1未开始 2发送中 3成功 4部分成功 5失败',
    failure_stage           VARCHAR(64)  NULL COMMENT '失败阶段码',
    error_code              VARCHAR(64)  NULL COMMENT '执行错误码',
    error_message           TEXT         NULL COMMENT '执行原始错误详情;仅持久化不写日志',
    started_at              BIGINT       NULL COMMENT '首次开始时间(epoch毫秒)',
    finished_at             BIGINT       NULL COMMENT '终态完成时间(epoch毫秒)',
    created_at              BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at              BIGINT       NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hgpe_tenant_idempotency (tenant_id, idempotency_key),
    KEY idx_hgpe_tenant_account_group_time (tenant_id, operation_account_id, group_jid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史群一次性拉人营销执行';

CREATE TABLE IF NOT EXISTS historical_group_pull_member (
    id                           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id                    BIGINT       NOT NULL COMMENT '租户ID',
    execution_id                 BIGINT       NOT NULL COMMENT '所属历史群拉人执行ID',
    line_no                      INT          NOT NULL COMMENT '规范化料子原始行号',
    phone                        VARCHAR(32)  NOT NULL COMMENT '完整WA号码',
    material_type                TINYINT      NOT NULL COMMENT '料子类型:1普通 2营销',
    account_id                   BIGINT       NULL COMMENT '营销料子匹配的Armada营销账号ID;普通料子为NULL',
    protocol_account_id_snapshot VARCHAR(128) NULL COMMENT '营销料子匹配的协议账号ID快照;普通料子为NULL',
    contact_status               TINYINT      NOT NULL DEFAULT 0 COMMENT '联系人预存状态:0待处理 1成功 2失败',
    contact_error_code           VARCHAR(64)  NULL COMMENT '联系人预存错误码',
    contact_error_message        TEXT         NULL COMMENT '联系人预存原始错误详情;仅持久化不写日志',
    add_status                   TINYINT      NOT NULL DEFAULT 0 COMMENT '拉人状态:0待处理 1成功 2失败',
    add_error_code               VARCHAR(64)  NULL COMMENT '拉人错误码',
    add_error_message            TEXT         NULL COMMENT '拉人原始错误详情;仅持久化不写日志',
    send_status                  TINYINT      NOT NULL DEFAULT 0 COMMENT '成员发送状态:0不适用 1待发送 2发送中 3成功 4失败',
    send_command_id              VARCHAR(64)  NULL COMMENT '营销发送命令ID;用于幂等回写',
    send_result_event_id         VARCHAR(64)  NULL COMMENT '首次消费的发送结果事件ID;用于幂等去重',
    send_error_code              VARCHAR(64)  NULL COMMENT '发送错误码',
    send_error_message           TEXT         NULL COMMENT '发送原始错误详情;仅持久化不写日志',
    created_at                   BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at                   BIGINT       NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hgpm_tenant_execution_phone (tenant_id, execution_id, phone),
    UNIQUE KEY uq_hgpm_tenant_send_command (tenant_id, send_command_id),
    UNIQUE KEY uq_hgpm_tenant_send_event (tenant_id, send_result_event_id),
    KEY idx_hgpm_tenant_execution_material (tenant_id, execution_id, material_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史群拉人逐成员执行事实';
