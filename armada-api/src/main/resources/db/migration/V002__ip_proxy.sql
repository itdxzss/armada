-- IP 代理池（resource 域）。一表 = 一聚合：仅承载「代理线路本身 + 池管理」。
-- 分配/绑定相关列（bound_account_id / release_eligible_at / country-ISO 匹配）属 account 上线流程，
-- 该流程尚未建，按「禁死列」此处不预留，待建该流程时再加（并随附数据迁移）。
CREATE TABLE ip_proxy (
    id          BIGINT       NOT NULL AUTO_INCREMENT                    COMMENT '主键',
    tenant_id   BIGINT                DEFAULT NULL                      COMMENT '租户 ID；NULL=平台共享池',
    host        VARCHAR(64)  NOT NULL                                   COMMENT '代理网关地址(如 geo.iproyal.com)，非真实出口IP；真实出口由出口探测得到',
    port        INT          NOT NULL                                   COMMENT '代理端口',
    protocol    TINYINT      NOT NULL DEFAULT 2                         COMMENT '协议:1=HTTP 2=SOCKS5',
    username    VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin  DEFAULT NULL COMMENT '鉴权用户名(按字节精确,用于去重)',
    password    VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin  DEFAULT NULL COMMENT '鉴权密码(含 _session/_lifetime 段;按字节精确,用于去重)',
    region      VARCHAR(64)           DEFAULT NULL                      COMMENT '国家/分组中文展示名(「印度」「混合（不限国家）」)',
    status      TINYINT      NOT NULL DEFAULT 1                         COMMENT '状态:1=空闲 2=使用中 3=不可用',
    source      VARCHAR(64)           DEFAULT NULL                      COMMENT '来源(服务商/批次,自由文本)',
    ownership   TINYINT      NOT NULL DEFAULT 1                         COMMENT '归属:1=租户自有 2=平台池 3=租借',
    remark      VARCHAR(255)          DEFAULT NULL                      COMMENT '备注',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                            COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by  BIGINT                DEFAULT NULL                      COMMENT '创建人 user_id',
    deleted_at  DATETIME              DEFAULT NULL                      COMMENT '软删时间；NULL=未删',
    -- 软删唯一键辅助列：活行=1，软删=NULL。MySQL 唯一键里多个 NULL 互不冲突，
    -- 故活行同元组唯一拦重复、软删行不冲突（允许将来重导）；不污染 deleted_at 的 NULL=未删 语义。
    is_active   TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL      COMMENT '软删唯一键辅助:活行=1 软删=NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_active_dedup (tenant_id, host, port, username, password, is_active),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IP 代理池';
