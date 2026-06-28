-- 协议命令 Outbox 第一刀:
-- 只建立 Armada 本地命令缓冲表,不引入 Kafka producer/consumer,不改变上线入口行为。
-- 一行代表一个账号命令;批量 500 个账号时写 500 行,用 batch_id 归组。

CREATE TABLE protocol_command_outbox (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id           BIGINT       NOT NULL                COMMENT '租户ID',
    command_id          VARCHAR(64)  NOT NULL                COMMENT '全局唯一命令ID;Kafka幂等与排查使用',
    batch_id            VARCHAR(64)           DEFAULT NULL   COMMENT '批量命令ID;单条命令可为空',
    command_type        VARCHAR(64)  NOT NULL                COMMENT '命令类型,如account.online.requested',
    aggregate_type      VARCHAR(32)  NOT NULL                COMMENT '聚合类型,初始固定ACCOUNT',
    aggregate_id        BIGINT       NOT NULL                COMMENT '聚合ID;账号命令对应account.id',
    kafka_topic         VARCHAR(128) NOT NULL                COMMENT '目标Kafka topic',
    kafka_key           VARCHAR(128) NOT NULL                COMMENT '目标Kafka key;同账号命令按key保序',
    protocol_account_id VARCHAR(128) NOT NULL                COMMENT '协议层账号ID',
    payload_json        JSON         NOT NULL                COMMENT '轻量命令payload;只存引用信息,不复制凭据和代理密码',
    status              TINYINT      NOT NULL DEFAULT 0      COMMENT '状态:0=PENDING 1=LOCKED 2=SENT 3=DEAD 4=CANCELED',
    retry_count         INT          NOT NULL DEFAULT 0      COMMENT '发布重试次数',
    next_retry_at       BIGINT       NOT NULL DEFAULT 0      COMMENT '下次可重试时间(epoch毫秒);0=立即可发',
    locked_by           VARCHAR(64)           DEFAULT NULL   COMMENT '抢占发送的publisher实例',
    locked_at           BIGINT                DEFAULT NULL   COMMENT '抢占发送时间(epoch毫秒)',
    sent_at             BIGINT                DEFAULT NULL   COMMENT 'Kafka producer ack成功时间(epoch毫秒)',
    last_error          VARCHAR(1024)         DEFAULT NULL   COMMENT '最近一次发布失败原因(截断)',
    created_at          BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at          BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    deleted_at          BIGINT                DEFAULT NULL   COMMENT '软删时间(epoch毫秒);NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_command_id (command_id),
    KEY idx_dispatch (status, next_retry_at, id),
    KEY idx_batch (tenant_id, batch_id),
    KEY idx_account (tenant_id, aggregate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='协议命令Outbox(Armada到协议层Kafka命令)';
