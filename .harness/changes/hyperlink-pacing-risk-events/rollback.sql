-- 仅供人工评审，不自动执行。回滚前应先导出 protocol_risk_event，避免丢失风控审计历史。

DROP TABLE IF EXISTS protocol_risk_event;

ALTER TABLE account_state
    DROP INDEX idx_account_state_message_restriction_due,
    DROP INDEX idx_account_state_pulling_restriction_due,
    DROP COLUMN pulling_restriction_until,
    DROP COLUMN platform_message_restriction_reported_at,
    DROP COLUMN platform_message_restriction_active,
    DROP COLUMN platform_message_restriction_until,
    DROP COLUMN fallback_message_restriction_until;

