-- 账号导入自动上线跟踪:
-- account_import_detail 记录每行导入后的上线派发阶段与首次登录终态。
-- login_result 继续沿用既有 TINYINT: NULL=未冻结/不参与 1成功 2失败 3密钥异常 4封号。

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'online_phase') = 0,
    'ALTER TABLE account_import_detail ADD COLUMN online_phase TINYINT NOT NULL DEFAULT 0 COMMENT ''导入上线阶段:0跳过/不参与 1待派发 2已派发待回写 3已冻结终态'' AFTER login_result',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'online_dispatched_at') = 0,
    'ALTER TABLE account_import_detail ADD COLUMN online_dispatched_at BIGINT DEFAULT NULL COMMENT ''上线命令派发时间(epoch毫秒);settle 只认此时间后的协议状态事件'' AFTER online_phase',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'login_settled_at') = 0,
    'ALTER TABLE account_import_detail ADD COLUMN login_settled_at BIGINT DEFAULT NULL COMMENT ''本次导入登录结果冻结时间(epoch毫秒)'' AFTER online_dispatched_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'dispatch_attempts') = 0,
    'ALTER TABLE account_import_detail ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0 COMMENT ''上线派发重试次数'' AFTER login_settled_at',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND column_name = 'login_reason') = 0,
    'ALTER TABLE account_import_detail ADD COLUMN login_reason VARCHAR(255) DEFAULT NULL COMMENT ''登录失败/异常原因或派发错误'' AFTER dispatch_attempts',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND index_name = 'idx_import_detail_online_phase') = 0,
    'ALTER TABLE account_import_detail ADD KEY idx_import_detail_online_phase (online_phase, id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND index_name = 'idx_tenant_batch_login_result') = 0,
    'ALTER TABLE account_import_detail ADD KEY idx_tenant_batch_login_result (tenant_id, batch_id, login_result)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'account_import_detail' AND index_name = 'idx_tenant_batch_online_phase') = 0,
    'ALTER TABLE account_import_detail ADD KEY idx_tenant_batch_online_phase (tenant_id, batch_id, online_phase)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
