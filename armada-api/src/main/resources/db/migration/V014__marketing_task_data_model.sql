-- 营销任务数据模型第一刀:
-- 1) account 增加营销树群基线状态;
-- 2) account_group_baseline 以一账号一行 JSON 数组保存登录前群基线;
-- 3) marketing_task / marketing_task_target / marketing_task_send_attempt 承载任务配置、账号×群执行目标、发送尝试历史。
-- 时间列全 BIGINT epoch 毫秒,应用层写;状态列统一 TINYINT,逐值 COMMENT 说明。

SET @baseline_col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'account' AND column_name = 'group_baseline_state'
);
SET @sql := IF(
    @baseline_col_exists = 0,
    'ALTER TABLE account ADD COLUMN group_baseline_state TINYINT NOT NULL DEFAULT 1 COMMENT ''营销树群基线状态:1=待拍 2=已拍 3=不启用过滤'' AFTER dispatched_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量账号无法还原“登录平台前群组”,默认不启用过滤;新导入账号仍按 DEFAULT 1 待拍。
SET @sql := IF(
    @baseline_col_exists = 0,
    'UPDATE account SET group_baseline_state = 3 WHERE group_baseline_state = 1',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS account_group_baseline (
    id                  BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id           BIGINT  NOT NULL                COMMENT '租户ID',
    account_id          BIGINT  NOT NULL                COMMENT '→account.id',
    baseline_group_jids JSON    NOT NULL                COMMENT '首次拍基线时该账号已在群JID数组(JSON);空数组表示已拍但无历史群',
    group_count         INT     NOT NULL DEFAULT 0      COMMENT 'baseline_group_jids数组长度',
    captured_at         BIGINT  NOT NULL                COMMENT '拍基线时间(epoch毫秒)',
    created_at          BIGINT  NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at          BIGINT  NOT NULL                COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_baseline (tenant_id, account_id),
    KEY idx_account_baseline_captured (tenant_id, captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号登录前群基线快照(营销树排除用)';

CREATE TABLE IF NOT EXISTS marketing_task (
    id                         BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id                  BIGINT       NOT NULL                 COMMENT '租户ID',
    task_name                  VARCHAR(128) NOT NULL                 COMMENT '任务名称',
    account_group_id           BIGINT       NOT NULL                 COMMENT '选择的账号分组ID(→account_group.id)',
    account_group_name         VARCHAR(100) NOT NULL                 COMMENT '账号分组名称快照',
    marketing_template_id      BIGINT       NOT NULL                 COMMENT '营销模板ID(→marketing_template.id)',
    marketing_template_name    VARCHAR(128) NOT NULL                 COMMENT '营销模板名称快照',
    status                     TINYINT      NOT NULL DEFAULT 1       COMMENT '任务状态:1=待启动/未发送 2=发送中 3=发送成功 4=发送失败 5=已停止 6=部分失败',
    selected_account_count     INT          NOT NULL DEFAULT 0       COMMENT '选中去重发送账号数',
    target_group_count         INT          NOT NULL DEFAULT 0       COMMENT '选中去重目标群数',
    target_pair_count          INT          NOT NULL DEFAULT 0       COMMENT '账号+群组执行目标行数',
    sent_message_count         INT          NOT NULL DEFAULT 0       COMMENT '任务累计发送成功条数',
    failed_message_count       INT          NOT NULL DEFAULT 0       COMMENT '任务累计发送失败条数',
    send_per_round             INT          NOT NULL DEFAULT 1       COMMENT '单次发送数量(条)',
    send_interval_seconds      INT          NOT NULL DEFAULT 30      COMMENT '发送间隔(秒)',
    is_online_check_enabled    TINYINT(1)   NOT NULL DEFAULT 1       COMMENT '发送前是否检测账号在线:0=否 1=是',
    is_abnormal_group_skipped  TINYINT(1)   NOT NULL DEFAULT 1       COMMENT '是否跳过异常群组:0=否 1=是',
    is_auto_retry_enabled      TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '失败后是否自动重试:0=否 1=是',
    retry_limit                INT          NOT NULL DEFAULT 0       COMMENT '自动重试次数上限;一期勾选时为1',
    remark                     VARCHAR(512)          DEFAULT NULL    COMMENT '任务备注',
    started_at                 BIGINT                DEFAULT NULL    COMMENT '首次启动时间(epoch毫秒)',
    last_sent_at               BIGINT                DEFAULT NULL    COMMENT '最后一次成功发送时间(epoch毫秒)',
    finished_at                BIGINT                DEFAULT NULL    COMMENT '进入成功/失败终态时间(epoch毫秒)',
    created_by                 BIGINT                DEFAULT NULL    COMMENT '创建人user_id',
    created_at                 BIGINT       NOT NULL                 COMMENT '创建时间(epoch毫秒)',
    updated_at                 BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒)',
    deleted_at                 BIGINT                DEFAULT NULL    COMMENT '软删时间(epoch毫秒);NULL=未删',
    PRIMARY KEY (id),
    KEY idx_marketing_task_tenant (tenant_id, deleted_at, id),
    KEY idx_marketing_task_status_time (tenant_id, status, last_sent_at),
    KEY idx_marketing_task_template (tenant_id, marketing_template_id),
    KEY idx_marketing_task_account_group (tenant_id, account_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群组营销任务';

CREATE TABLE IF NOT EXISTS marketing_task_target (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id             BIGINT       NOT NULL                COMMENT '租户ID',
    marketing_task_id     BIGINT       NOT NULL                COMMENT '→marketing_task.id',
    account_id            BIGINT       NOT NULL                COMMENT '发言账号ID(→account.id)',
    account_phone         VARCHAR(32)  NOT NULL                COMMENT '发言账号号码快照',
    group_link_id         BIGINT       NOT NULL                COMMENT '目标群入口ID(→group_link.id)',
    group_jid             VARCHAR(128) NOT NULL                COMMENT 'WhatsApp群JID,协议发送寻址用',
    group_link_url        VARCHAR(255) NOT NULL                COMMENT '群链接URL快照',
    group_name            VARCHAR(128)          DEFAULT NULL   COMMENT '群名称快照',
    status                TINYINT      NOT NULL DEFAULT 1      COMMENT '目标状态:1=待发送 2=发送中 3=成功 4=失败 5=部分失败 6=已跳过 7=已停止',
    sent_message_count    INT          NOT NULL DEFAULT 0      COMMENT '该目标成功发送次数',
    failed_message_count  INT          NOT NULL DEFAULT 0      COMMENT '该目标失败次数',
    retry_count           INT          NOT NULL DEFAULT 0      COMMENT '该目标已自动重试次数',
    last_attempt_at       BIGINT                DEFAULT NULL   COMMENT '最近一次执行/跳过时间(epoch毫秒)',
    last_sent_at          BIGINT                DEFAULT NULL   COMMENT '最近一次成功发送时间(epoch毫秒)',
    last_reason           VARCHAR(255)          DEFAULT NULL   COMMENT '最近一次失败/跳过原因',
    created_at            BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at            BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_marketing_task_target_pair (tenant_id, marketing_task_id, account_id, group_link_id),
    KEY idx_marketing_task_target_task (tenant_id, marketing_task_id, id),
    KEY idx_marketing_task_target_status_time (tenant_id, status, last_sent_at),
    KEY idx_marketing_task_target_account (tenant_id, account_id),
    KEY idx_marketing_task_target_group_jid (tenant_id, group_jid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群组营销任务执行目标(账号×群组)';

CREATE TABLE IF NOT EXISTS marketing_task_send_attempt (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id          BIGINT       NOT NULL                COMMENT '租户ID',
    marketing_task_id  BIGINT       NOT NULL                COMMENT '→marketing_task.id',
    target_id          BIGINT       NOT NULL                COMMENT '→marketing_task_target.id',
    attempt_no         INT          NOT NULL                COMMENT '同一目标下第几次尝试,从1开始',
    is_retry           TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否自动重试:0=否 1=是',
    status             TINYINT      NOT NULL                COMMENT '尝试结果:1=成功 2=失败 3=跳过',
    reason_code        VARCHAR(64)           DEFAULT NULL   COMMENT '机器可识别原因码,如ACCOUNT_OFFLINE/GROUP_BANNED',
    reason_message     VARCHAR(255)          DEFAULT NULL   COMMENT '页面展示原因',
    attempted_at       BIGINT       NOT NULL                COMMENT '执行时间(epoch毫秒)',
    created_at         BIGINT       NOT NULL                COMMENT '记录创建时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_marketing_task_attempt_no (tenant_id, target_id, attempt_no),
    KEY idx_marketing_task_attempt_task (tenant_id, marketing_task_id, id),
    KEY idx_marketing_task_attempt_target (tenant_id, target_id, attempt_no),
    KEY idx_marketing_task_attempt_status_time (tenant_id, status, attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='群组营销任务发送尝试记录';
