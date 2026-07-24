-- 仅供人工回滚评审；Flyway 不会自动执行本文件。
-- 执行前必须停止配对写入、确认目标环境并完成备份。过程兼容 V067-V069 只执行了部分版本的状态。
DELIMITER $$

DROP PROCEDURE IF EXISTS rollback_promotion_pairing$$
CREATE PROCEDURE rollback_promotion_pairing()
BEGIN
    -- V068 已执行时，先把仍处于配对占用或残留会话归属的代理恢复为空闲，避免旧应用无法识别状态 4。
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'ip_proxy'
          AND column_name = 'pairing_session_id'
    ) THEN
        UPDATE ip_proxy
        SET status = 1,
            pairing_session_id = NULL,
            bound_account_id = NULL,
            bound_at = NULL,
            updated_at = CURRENT_TIMESTAMP
        WHERE status = 4 OR pairing_session_id IS NOT NULL;

        ALTER TABLE ip_proxy
            DROP INDEX uq_ip_proxy_pairing_session,
            DROP COLUMN pairing_session_id,
            MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
                COMMENT '状态:1=空闲 2=使用中 3=不可用';
    END IF;

    -- V069 可能尚未执行；DROP TABLE IF EXISTS 可安全处理完整或部分迁移状态。
    DROP TABLE IF EXISTS promotion_pairing_session;

    -- V067 已执行但后续版本失败时，仍需单独移除已成功记录的账号索引。
    IF EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'account'
          AND index_name = 'idx_account_ws_phone_active'
    ) THEN
        DROP INDEX idx_account_ws_phone_active ON account;
    END IF;
END$$

CALL rollback_promotion_pairing()$$
DROP PROCEDURE rollback_promotion_pairing$$

DELIMITER ;
