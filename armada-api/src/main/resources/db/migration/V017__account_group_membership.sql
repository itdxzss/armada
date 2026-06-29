-- 账号当前在群关系:
-- 1) account_group_membership 记录账号维度当前参与的群,用于营销树等按账号展示;
-- 2) group_link.origin 注释补充 5=账号同步,表示该群入口首次由账号当前群同步发现。

ALTER TABLE group_link
    MODIFY COLUMN origin TINYINT NOT NULL DEFAULT 1
    COMMENT '首次进入群组池来源:1=导入链接 2=进群任务 3=拉群任务 4=自建群 5=账号同步';

CREATE TABLE IF NOT EXISTS account_group_membership (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT       NOT NULL                COMMENT '租户ID',
    account_id    BIGINT       NOT NULL                COMMENT '→account.id',
    group_link_id BIGINT       NOT NULL                COMMENT '→group_link.id',
    group_jid     VARCHAR(128) NOT NULL                COMMENT 'WhatsApp群JID',
    is_admin      TINYINT(1)            DEFAULT NULL   COMMENT '该账号在群内是否管理员:NULL=未知 0=否 1=是',
    last_seen_at  BIGINT       NOT NULL                COMMENT '最近一次账号群同步看到该关系的时间(epoch毫秒)',
    created_at    BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at    BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    deleted_at    BIGINT                DEFAULT NULL   COMMENT '软删时间(epoch毫秒);NULL=当前仍在群内',
    is_active     TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL
        COMMENT '软删唯一键辅助',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_group_membership (tenant_id, account_id, group_jid, is_active),
    KEY idx_account_group_membership_account (tenant_id, account_id, deleted_at),
    KEY idx_account_group_membership_group_link (tenant_id, group_link_id, deleted_at),
    KEY idx_account_group_membership_jid (tenant_id, group_jid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号当前在群关系';
