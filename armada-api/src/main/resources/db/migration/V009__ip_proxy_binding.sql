ALTER TABLE ip_proxy
    ADD COLUMN bound_account_id BIGINT DEFAULT NULL COMMENT '当前绑定账号ID;NULL=未绑定' AFTER status,
    ADD COLUMN bound_at BIGINT DEFAULT NULL COMMENT '绑定时间(epoch毫秒);NULL=未绑定' AFTER bound_account_id,
    ADD KEY idx_ip_proxy_bound_account (tenant_id, bound_account_id, status, deleted_at),
    ADD KEY idx_ip_proxy_idle (tenant_id, status, deleted_at, id);
