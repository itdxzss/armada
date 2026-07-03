-- Clarify account invalidation time semantics.
-- The column is set when account_state becomes non-normal and cleared when it returns to normal.
ALTER TABLE account_state
    MODIFY COLUMN invalidated_at BIGINT DEFAULT NULL COMMENT '失效时间(epoch毫秒;账号状态非正常;恢复正常清空)';
