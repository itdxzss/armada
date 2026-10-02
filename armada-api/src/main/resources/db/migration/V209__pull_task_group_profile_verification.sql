-- 执行行记录必填群名/简介的真实回读核验证据，不把资料动作的 UNKNOWN/可选项失败改成 SUCCESS。
-- 新列保持 NULL；禁止根据历史步骤或命令已入队回填为已核验。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND column_name = 'profile_verified_at') = 0, 'ALTER TABLE pull_task_group_execution ADD COLUMN profile_verified_at BIGINT NULL DEFAULT NULL COMMENT ''必填群名和简介最近回读核验通过时间(epoch毫秒);NULL未核验''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND column_name = 'profile_verified_command_id') = 0, 'ALTER TABLE pull_task_group_execution ADD COLUMN profile_verified_command_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT ''必填群资料核验对应命令ID;不表示可选资料项全部成功''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
