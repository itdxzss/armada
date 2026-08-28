-- 通讯录营销任务。一个任务 = 一组账号筛选条件 + 一条 WhatsApp 消息，
-- 命中的每个账号向自己通讯录里的联系人群发同一条消息。
-- 不复用 marketing_task：营销任务的账号占用是分组级的，通讯录任务按筛选跨分组圈号，套不上那把锁。

CREATE TABLE IF NOT EXISTS contact_friend_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    name VARCHAR(128) NOT NULL COMMENT '任务名称;仅后台展示',
    message_type TINYINT NOT NULL COMMENT '消息类型:0链接消息 1图文消息;创建后不可改',
    title VARCHAR(512) DEFAULT NULL COMMENT '消息标题;仅链接消息',
    description VARCHAR(2048) DEFAULT NULL COMMENT '链接描述;仅链接消息',
    promotion_link VARCHAR(2048) DEFAULT NULL COMMENT '推广链接;仅链接消息',
    content VARCHAR(2000) NOT NULL COMMENT '正文内容或图文文案',
    preview_image_file_id BIGINT DEFAULT NULL COMMENT '预览图或配图;引用marketing_template_file.id',
    account_filter JSON DEFAULT NULL COMMENT '账号筛选条件;白名单归一化后落库,空对象表示不限定',
    msg_interval_min_sec DECIMAL(4,1) NOT NULL DEFAULT 0.5 COMMENT '单号发送最小间隔秒;带一位小数',
    msg_interval_max_sec DECIMAL(4,1) NOT NULL DEFAULT 1.0 COMMENT '单号发送最大间隔秒;带一位小数',
    concurrency INT NOT NULL DEFAULT 10 COMMENT '最大执行账号数',
    max_sends_per_account INT NOT NULL DEFAULT 50 COMMENT '每号最大发送数;0表示全部联系人',
    retry_max INT NOT NULL DEFAULT 3 COMMENT '单条消息失败最大重试次数;0不重试',
    start_mode VARCHAR(16) NOT NULL DEFAULT 'now' COMMENT '启动方式:now立即 scheduled延后',
    task_delay_minutes INT NOT NULL DEFAULT 0 COMMENT '延后执行分钟数;start_mode=now时为0',
    task_start_at BIGINT DEFAULT NULL COMMENT '计划开始时间(epoch毫秒)',
    is_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '任务开关:0已停用仅保存 1启用',
    run_status TINYINT NOT NULL DEFAULT 0 COMMENT '运行状态:0未开始 1进行中 2已完成 3已暂停 4已停止',
    next_round_at BIGINT DEFAULT NULL COMMENT '下一轮调度时间(epoch毫秒)',
    total_send_num INT NOT NULL DEFAULT 0 COMMENT '计划发送总条数',
    success_message_num INT NOT NULL DEFAULT 0 COMMENT '成功送达条数',
    used_account_count INT NOT NULL DEFAULT 0 COMMENT '实际参与发送的账号数',
    invalid_account_num INT NOT NULL DEFAULT 0 COMMENT '发送期间被封禁的账号数',
    avg_send_per_account DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '号均发量',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL为未删',
    PRIMARY KEY (id),
    KEY idx_contact_task_tenant (tenant_id, deleted_at, id),
    KEY idx_contact_task_run (tenant_id, run_status, next_round_at),
    KEY idx_contact_task_created (tenant_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通讯录营销任务主表';

CREATE TABLE IF NOT EXISTS contact_friend_task_account (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务账号主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '所属任务ID',
    account_id BIGINT NOT NULL COMMENT '发送账号ID',
    account_phone_snapshot VARCHAR(32) DEFAULT NULL COMMENT '账号号码快照;账号改号不影响历史',
    account_status_snapshot VARCHAR(16) DEFAULT NULL COMMENT '账号状态快照:valid有效 invalid无效',
    need_send_num INT NOT NULL DEFAULT 0 COMMENT '该账号计划发送条数',
    sent_num INT NOT NULL DEFAULT 0 COMMENT '该账号已成功条数',
    fail_num INT NOT NULL DEFAULT 0 COMMENT '该账号失败条数',
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '账号执行态:PENDING RUNNING DONE FAILED SKIPPED',
    contact_synced_at BIGINT DEFAULT NULL COMMENT '本任务使用的通讯录快照时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_contact_task_account (task_id, account_id),
    KEY idx_contact_task_account_need (task_id, need_send_num),
    KEY idx_contact_task_account_sent (task_id, sent_num),
    KEY idx_contact_task_account_fail (task_id, fail_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通讯录营销任务账号维度读模型';

CREATE TABLE IF NOT EXISTS contact_friend_task_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '收件人主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    task_id BIGINT NOT NULL COMMENT '所属任务ID',
    task_account_id BIGINT NOT NULL COMMENT '所属任务账号行ID',
    contact_phone VARCHAR(32) NOT NULL COMMENT '联系人号码快照;不带加号的纯数字',
    contact_jid VARCHAR(64) NOT NULL COMMENT '联系人JID快照',
    contact_named TINYINT NOT NULL DEFAULT 0 COMMENT '展开时该联系人是否有名字',
    send_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '发送状态:PENDING SENDING SUCCESS FAILED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    protocol_message_id VARCHAR(128) DEFAULT NULL COMMENT '协议返回的消息ID',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '失败错误码',
    error_desc VARCHAR(255) DEFAULT NULL COMMENT '失败描述',
    first_sent_at BIGINT DEFAULT NULL COMMENT '首次发出时间(epoch毫秒)',
    last_attempt_at BIGINT DEFAULT NULL COMMENT '最近一次尝试时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_contact_task_recipient (task_id, task_account_id, contact_phone),
    KEY idx_contact_task_recipient_pick (task_id, send_status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='通讯录营销任务收件人明细';
