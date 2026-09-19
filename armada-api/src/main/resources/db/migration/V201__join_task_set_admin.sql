-- 独立进群任务群管理配置；历史任务默认不执行管理员设置。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task' AND column_name = 'is_set_admin_enabled') = 0, 'ALTER TABLE join_task ADD COLUMN is_set_admin_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''进群成功后是否设置管理员''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'admin_status') = 0, 'ALTER TABLE join_task_result ADD COLUMN admin_status TINYINT NOT NULL DEFAULT 0 COMMENT ''管理员阶段:0无需设置 1待处理 2已提交 3成功 4失败 5结果待核实''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'admin_command_id') = 0, 'ALTER TABLE join_task_result ADD COLUMN admin_command_id VARCHAR(64) NULL COMMENT ''当前设置管理员命令ID''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'admin_attempt_no') = 0, 'ALTER TABLE join_task_result ADD COLUMN admin_attempt_no INT NOT NULL DEFAULT 0 COMMENT ''已提交设置管理员尝试次数''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'admin_actor_account_id') = 0, 'ALTER TABLE join_task_result ADD COLUMN admin_actor_account_id BIGINT NULL COMMENT ''本次设置管理员的原有管理员账号ID''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'admin_next_execute_at') = 0, 'ALTER TABLE join_task_result ADD COLUMN admin_next_execute_at BIGINT NULL COMMENT ''管理员阶段下次处理时间及抢占租约epoch毫秒''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'admin_deadline_at') = 0, 'ALTER TABLE join_task_result ADD COLUMN admin_deadline_at BIGINT NULL COMMENT ''管理员阶段截止时间epoch毫秒''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND column_name = 'admin_reason') = 0, 'ALTER TABLE join_task_result ADD COLUMN admin_reason VARCHAR(255) NOT NULL DEFAULT '''' COMMENT ''管理员阶段等待或失败原因''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'join_task_result' AND index_name = 'idx_jtr_admin_due') = 0, 'CREATE INDEX idx_jtr_admin_due ON join_task_result (admin_status, admin_next_execute_at, id)', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
