-- V201 的审阅用逆向 DDL，仅供生成经确认的新 Flyway 回退版本；本次未执行。
-- 紧急代码回滚应排空在途任务并保留新增列，不删除业务结果或 Flyway 历史。
-- 仅在功能从未使用时允许删除列；有历史使用记录时明确拒绝，避免丢失审计事实。
DELIMITER $$
CREATE PROCEDURE rollback_unused_join_task_admin()
BEGIN
    IF EXISTS (SELECT 1 FROM join_task WHERE is_set_admin_enabled = 1)
       OR EXISTS (SELECT 1 FROM join_task_result WHERE admin_status <> 0)
       OR EXISTS (SELECT 1 FROM protocol_command_outbox WHERE aggregate_type = 'JOIN_TASK_ADMIN') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Join admin has business history: retain columns and drain commands before code rollback';
    END IF;
    ALTER TABLE join_task_result
        DROP INDEX idx_jtr_admin_due,
        DROP COLUMN admin_status,
        DROP COLUMN admin_command_id,
        DROP COLUMN admin_attempt_no,
        DROP COLUMN admin_actor_account_id,
        DROP COLUMN admin_next_execute_at,
        DROP COLUMN admin_deadline_at,
        DROP COLUMN admin_reason;
    ALTER TABLE join_task DROP COLUMN is_set_admin_enabled;
END$$
DELIMITER ;
CALL rollback_unused_join_task_admin();
DROP PROCEDURE rollback_unused_join_task_admin;
