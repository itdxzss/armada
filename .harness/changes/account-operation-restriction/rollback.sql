-- 仅供人工评审，不自动执行。
-- mute_status 的旧 6h/24h 码值已被归一化，无法无损还原，回滚前必须先备份 account_state。

ALTER TABLE hyperlink_task_recipient
    DROP COLUMN dispatch_attempt;

ALTER TABLE account_state
    DROP INDEX idx_account_state_restriction_due,
    DROP COLUMN restriction_reported_at,
    DROP COLUMN restriction_reason_code,
    MODIFY COLUMN cooldown_until BIGINT DEFAULT NULL COMMENT '冷却到期(epoch毫秒)',
    MODIFY COLUMN mute_status TINYINT DEFAULT NULL COMMENT '1禁言6h 2禁言24h;NULL=未上报';
