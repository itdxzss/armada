ALTER TABLE account_online_attempt_log
    ADD KEY idx_tenant_occurred (tenant_id, occurred_at, id);
