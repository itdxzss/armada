-- 接码注册占用代理使用独立归属列：pairing_session_id 与 bound_account_id 各自只接受自己的主键，
-- 把接码订单ID写进别的列会与真实配对会话/账号撞号。状态仍复用 4（占用），沿用删除保护与检测不覆盖。
-- 本文件只包含一条原子 ALTER，避免 MySQL DDL 部分提交后无法重跑。
ALTER TABLE ip_proxy
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
        COMMENT '状态:1=空闲 2=使用中 3=不可用 4=占用(配对或接码注册临时预留)',
    ADD COLUMN registration_activation_id BIGINT DEFAULT NULL
        COMMENT '接码注册临时占用的订单ID(sms_activation.id),非注册占用为NULL' AFTER pairing_session_id,
    ADD UNIQUE KEY uq_ip_proxy_registration_activation (registration_activation_id);
