-- 配对占用使用独立归属列，禁止把临时会话 ID 写入只允许正式 account.id 的 bound_account_id。
-- 状态注释同步新增枚举值；本文件只包含一条原子 ALTER，避免 MySQL DDL 部分提交后无法重跑。
ALTER TABLE ip_proxy
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
        COMMENT '状态:1=空闲 2=使用中 3=不可用 4=配对占用',
    ADD COLUMN pairing_session_id BIGINT DEFAULT NULL
        COMMENT '推广配对临时占用会话ID,非配对状态为NULL,例如 7001' AFTER bound_account_id,
    ADD UNIQUE KEY uq_ip_proxy_pairing_session (pairing_session_id);
