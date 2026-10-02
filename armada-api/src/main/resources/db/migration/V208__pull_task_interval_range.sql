-- 冻结执行配置只保留一组上下限：已有 pull_interval_seconds 作为下限，新列为上限。
-- NULL 用于滚动升级期间旧版本写入，读侧按下限处理，保持历史固定间隔。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_standard_setting' AND column_name = 'pull_interval_max_seconds') = 0, 'ALTER TABLE pull_task_standard_setting ADD COLUMN pull_interval_max_seconds INT NULL DEFAULT NULL COMMENT ''同群相邻拉人命令提交的随机间隔上限(秒);NULL按原下限固定间隔''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE pull_task_standard_setting
SET pull_interval_max_seconds = pull_interval_seconds
WHERE pull_interval_max_seconds IS NULL;

ALTER TABLE pull_task_standard_setting
  MODIFY COLUMN pull_interval_seconds INT NOT NULL COMMENT '同群相邻拉人命令提交的随机间隔下限(秒)';
