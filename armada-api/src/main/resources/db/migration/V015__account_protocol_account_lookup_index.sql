-- account.state_changed Kafka 回写按 protocol_account_id 反查 account.id。
-- 高频状态事件不能走租户内全表扫描,因此补充 tenant_id + protocol_account_id 组合索引。
CREATE INDEX idx_tenant_protocol_account
    ON account (tenant_id, protocol_account_id);
