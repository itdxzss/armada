-- 连续离线起点属于登录态事实，不用会被待上线/重连事件刷新的同步水位代替。
-- information_schema 守卫沿用 V077，允许已有列的环境安全执行本迁移。
SET @offline_since_col_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'account_state'
      AND column_name = 'offline_since'
);
SET @sql := IF(
    @offline_since_col_exists = 0,
    'ALTER TABLE account_state
       ADD COLUMN offline_since BIGINT NULL
       COMMENT ''本次连续离线起点(epoch毫秒);在线时为NULL;供任务角色离线等待计时''
       AFTER login_state',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 只补非在线且尚无起点的存量账号；重跑不刷新既有离线宽限期。
UPDATE account_state
SET offline_since = COALESCE(last_state_sync_time, updated_at)
WHERE (login_state IS NULL OR login_state <> 1)
  AND offline_since IS NULL;

-- 抢登熔断是自动恢复的运行态，与账号生命周期分开持久化。
CREATE TABLE IF NOT EXISTS account_takeover_breaker (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT '→account.id',
    window_started_at BIGINT NULL COMMENT '当前计数窗口起点(epoch毫秒,窗口内首次被挤时间)',
    kick_count INT NOT NULL DEFAULT 0 COMMENT '当前窗口内被挤(LOGIN_REPLACED)次数',
    tripped_at BIGINT NULL COMMENT '熔断时间(epoch毫秒);非空=熔断中,不再自动抢登,人工一键抢登清空',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_account (tenant_id, account_id),
    KEY idx_tenant_tripped (tenant_id, tripped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号自动抢登熔断计数';
