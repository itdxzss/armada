-- 账号块六表迁移：account / account_state / account_group / account_credential /
-- account_import_batch / account_import_detail
-- 时间列一律 BIGINT(epoch 毫秒)；软删 deleted_at + is_active 虚拟列；列逐列 COMMENT。

CREATE TABLE account (
    id                  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id           BIGINT       NOT NULL                 COMMENT '租户ID(拦截器注入)',
    ws_phone            VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'WA号(按字节精确去重)',
    account_type        TINYINT      NOT NULL                 COMMENT '账号类型:1个人 2商业(导入即冻结,不得改写)',
    device_os           TINYINT               DEFAULT NULL    COMMENT '机型:1安卓 2苹果',
    number_source       TINYINT               DEFAULT NULL    COMMENT '来源:1买量 2裂变 3自购',
    channel_name        VARCHAR(64)           DEFAULT NULL    COMMENT '推广渠道名',
    ownership           TINYINT      NOT NULL DEFAULT 1       COMMENT '归属:1自有 2平台 3租借',
    lease_until         BIGINT                DEFAULT NULL    COMMENT '租借到期(epoch毫秒;ownership=3)',
    account_group_id    BIGINT                DEFAULT NULL    COMMENT '★归一单分组(→account_group.id)',
    protocol_id         VARCHAR(32)           DEFAULT NULL    COMMENT '接入协议标识(系统分配)',
    protocol_account_id VARCHAR(64)           DEFAULT NULL    COMMENT '协议账号句柄 acc_<wsPhone>',
    protocol_address    VARCHAR(128)          DEFAULT NULL    COMMENT '协议地址',
    priority            INT          NOT NULL DEFAULT 0       COMMENT '选号优先级',
    dispatched_at       BIGINT                DEFAULT NULL    COMMENT '首次派单时间(epoch毫秒;step1恒NULL=未分配)',
    remark              VARCHAR(255)          DEFAULT NULL    COMMENT '备注',
    created_at          BIGINT       NOT NULL                 COMMENT '入库时间(epoch毫秒,应用层写;账号列表「入库时间」列)',
    updated_at          BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,应用层写)',
    created_by          BIGINT                DEFAULT NULL    COMMENT '创建人user_id',
    deleted_at          BIGINT                DEFAULT NULL    COMMENT '软删时间(epoch毫秒);NULL=未删',
    is_active           TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL COMMENT '软删唯一键辅助:活=1 删=NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_phone (tenant_id, ws_phone, is_active),
    KEY idx_tenant_group (tenant_id, account_group_id),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号身份主表';

CREATE TABLE account_state (
    id                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id             BIGINT      NOT NULL                COMMENT '租户ID',
    account_id            BIGINT      NOT NULL                COMMENT '→account.id',
    account_state         TINYINT              DEFAULT NULL   COMMENT '1新增 2正常 3封禁 4导出 5解绑;NULL=未上报',
    login_state           TINYINT              DEFAULT NULL   COMMENT '1在线 2离线;NULL=未上报',
    risk_status           TINYINT              DEFAULT NULL   COMMENT '1未风控 2风控中 3待解除;NULL=未上报',
    risk_end_time         BIGINT               DEFAULT NULL   COMMENT '风控倒计时终点(epoch毫秒)',
    cooldown_until        BIGINT               DEFAULT NULL   COMMENT '冷却到期(epoch毫秒)',
    mute_status           TINYINT              DEFAULT NULL   COMMENT '1禁言6h 2禁言24h;NULL=未上报',
    block_error_code      VARCHAR(32)          DEFAULT NULL   COMMENT '封号错误码(401/403/440)',
    block_reason          VARCHAR(255)         DEFAULT NULL   COMMENT '封号原因(落库前按列宽截断)',
    state_source          VARCHAR(64)          DEFAULT NULL   COMMENT '状态来源前缀 NEED_REAUTH/PROXY_FAILED(截断)',
    last_state_sync_time  BIGINT               DEFAULT NULL   COMMENT '最后对账时间(epoch毫秒)',
    invalidated_at        BIGINT               DEFAULT NULL   COMMENT '失效时间(epoch毫秒;导出/解绑)',
    truth_ip              VARCHAR(45)          DEFAULT NULL   COMMENT '真实出口公网IP(上线探测;≠ip_proxy.host网关)',
    proxy_country         VARCHAR(64)          DEFAULT NULL   COMMENT '出口国家',
    proxy_failure_count   INT         NOT NULL DEFAULT 0      COMMENT '代理失败计数',
    pull_into_group_count INT         NOT NULL DEFAULT 0      COMMENT '拉人数量',
    created_at            BIGINT      NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at            BIGINT      NOT NULL                COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_account (tenant_id, account_id),
    KEY idx_tenant_login (tenant_id, login_state),
    KEY idx_tenant_state (tenant_id, account_state),
    KEY idx_tenant_risk (tenant_id, risk_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号生命周期状态(高频Kafka回写)';

CREATE TABLE account_group (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT       NOT NULL                COMMENT '租户ID',
    name           VARCHAR(100) NOT NULL                COMMENT '分组名(租户内不重复)',
    remark         VARCHAR(255)          DEFAULT NULL   COMMENT '备注',
    system_builtin TINYINT      NOT NULL DEFAULT 0      COMMENT '系统内置默认组:1=不可删',
    created_at     BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at     BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    created_by     BIGINT                DEFAULT NULL   COMMENT '创建人user_id',
    deleted_at     BIGINT                DEFAULT NULL   COMMENT '软删时间(epoch毫秒);NULL=未删',
    is_active      TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL,1,NULL)) VIRTUAL COMMENT '软删唯一键辅助',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_name (tenant_id, name, is_active),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号分组';

CREATE TABLE account_credential (
    id                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id          BIGINT      NOT NULL                COMMENT '租户ID',
    account_id         BIGINT      NOT NULL                COMMENT '→account.id',
    ws_phone           VARCHAR(32)          DEFAULT NULL   COMMENT 'WA号(冗余便反查)',
    cred_format        TINYINT     NOT NULL                COMMENT '凭据格式:1六段 2JSON 3全参',
    creds_json         TEXT                 DEFAULT NULL   COMMENT '完整凭据blob(六段/全参也解析组装进这里;敏感,日志只打maskPhone+长度)',
    proxy_session_id   VARCHAR(64)          DEFAULT NULL   COMMENT 'sticky代理session(同IP复用键;上线时填)',
    proxy_retain_until BIGINT               DEFAULT NULL   COMMENT '代理session保留到期(epoch毫秒;下线时填)',
    created_at         BIGINT      NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at         BIGINT      NOT NULL                COMMENT '更新时间(epoch毫秒)',
    deleted_at         BIGINT               DEFAULT NULL   COMMENT '软删时间(epoch毫秒)',
    is_active          TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL,1,NULL)) VIRTUAL COMMENT '软删唯一键辅助',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_account (tenant_id, account_id, is_active),
    KEY idx_tenant_phone (tenant_id, ws_phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号自托管凭据';

CREATE TABLE account_import_batch (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id         BIGINT       NOT NULL                COMMENT '租户ID',
    account_group_id  BIGINT       NOT NULL                COMMENT '导入目标分组(→account_group.id)',
    source_file_name  VARCHAR(255)          DEFAULT NULL   COMMENT '上传文件原名;纯文本导入时为「导入」兜底串',
    import_format     TINYINT      NOT NULL                COMMENT '导入格式:1六段 2JSON 3全参',
    device_os         TINYINT               DEFAULT NULL   COMMENT '机型:1安卓 2苹果',
    account_type      TINYINT               DEFAULT NULL   COMMENT '账号类型:1个人 2商业',
    ip_region         VARCHAR(64)           DEFAULT NULL   COMMENT '导入时选的IP国家',
    total_rows        INT          NOT NULL DEFAULT 0      COMMENT '解析总行数',
    imported_rows     INT          NOT NULL DEFAULT 0      COMMENT '成功入库行数',
    duplicate_rows    INT          NOT NULL DEFAULT 0      COMMENT '重复行数(批内/库内)',
    format_error_rows INT          NOT NULL DEFAULT 0      COMMENT '格式/凭据不全行数',
    login_success     INT                   DEFAULT NULL   COMMENT '登录成功(step1 NULL=未登录)',
    login_failed      INT                   DEFAULT NULL   COMMENT '登录失败(step1 NULL)',
    login_abnormal    INT                   DEFAULT NULL   COMMENT '登录异常密钥/封号(step1 NULL)',
    status            TINYINT      NOT NULL DEFAULT 2      COMMENT '批次状态:1进行中 2已完成(step1同步导入即2)',
    created_at        BIGINT       NOT NULL                COMMENT '导入时间(epoch毫秒)',
    created_by        BIGINT                DEFAULT NULL   COMMENT '创建人user_id',
    deleted_at        BIGINT                DEFAULT NULL   COMMENT '软删时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_tenant_group (tenant_id, account_group_id),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号导入批次';

CREATE TABLE account_import_detail (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT       NOT NULL                COMMENT '租户ID',
    batch_id     BIGINT       NOT NULL                COMMENT '所属批次(→account_import_batch.id)',
    line_no      INT          NOT NULL                COMMENT '行号',
    ws_phone     VARCHAR(32)           DEFAULT NULL   COMMENT '该行WA号',
    account_id   BIGINT                DEFAULT NULL   COMMENT '成功入库时回填(→account.id)',
    parse_result TINYINT      NOT NULL                COMMENT '1成功入库 2重复(批内或库内已存在) 3格式错误 4凭据不全',
    fail_reason  VARCHAR(255)          DEFAULT NULL   COMMENT '失败原因',
    login_result TINYINT               DEFAULT NULL   COMMENT 'NULL=未登录/跳过(step1);step3:1成功 2失败 3密钥异常 4封号',
    created_at   BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_tenant_batch (tenant_id, batch_id),
    KEY idx_tenant_batch_result (tenant_id, batch_id, parse_result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号导入明细';
