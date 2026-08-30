-- V170 超链市场分析：发送时设备快照、日/小时聚合投影及菜单权限。

SET @hyperlink_analysis_schema := DATABASE();

SET @hyperlink_analysis_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@hyperlink_analysis_schema
             AND TABLE_NAME='hyperlink_task_account_usage'
             AND COLUMN_NAME='sender_device_os_snapshot'),
    'SELECT 1',
    'ALTER TABLE hyperlink_task_account_usage ADD COLUMN sender_device_os_snapshot TINYINT DEFAULT NULL COMMENT ''发送时设备OS:1安卓 2苹果;NULL未知'' AFTER account_type_snapshot');
PREPARE hyperlink_analysis_stmt FROM @hyperlink_analysis_sql;
EXECUTE hyperlink_analysis_stmt;
DEALLOCATE PREPARE hyperlink_analysis_stmt;

SET @hyperlink_analysis_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=@hyperlink_analysis_schema
             AND TABLE_NAME='hyperlink_task_recipient'
             AND COLUMN_NAME='sender_device_os_snapshot'),
    'SELECT 1',
    'ALTER TABLE hyperlink_task_recipient ADD COLUMN sender_device_os_snapshot TINYINT DEFAULT NULL COMMENT ''发送时设备OS:1安卓 2苹果;NULL未知'' AFTER sender_account_type_snapshot');
PREPARE hyperlink_analysis_stmt FROM @hyperlink_analysis_sql;
EXECUTE hyperlink_analysis_stmt;
DEALLOCATE PREPARE hyperlink_analysis_stmt;

-- 历史 usage 尽力按当前账号事实补齐；未来发送一律冻结选号时快照。
UPDATE hyperlink_task_account_usage usage_row
INNER JOIN account account_row
    ON account_row.tenant_id=usage_row.tenant_id AND account_row.id=usage_row.account_id
SET usage_row.sender_device_os_snapshot=account_row.device_os
WHERE usage_row.sender_device_os_snapshot IS NULL;

UPDATE hyperlink_task_recipient recipient
INNER JOIN hyperlink_task_account_usage usage_row
    ON usage_row.tenant_id=recipient.tenant_id
   AND usage_row.hyperlink_task_id=recipient.hyperlink_task_id
   AND usage_row.account_id=recipient.account_id
SET recipient.sender_device_os_snapshot=usage_row.sender_device_os_snapshot
WHERE recipient.sender_device_os_snapshot IS NULL;

SET @hyperlink_analysis_sql := IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA=@hyperlink_analysis_schema
             AND TABLE_NAME='hyperlink_task_recipient'
             AND INDEX_NAME='idx_hyperlink_recipient_market_stat'),
    'SELECT 1',
    'ALTER TABLE hyperlink_task_recipient ADD KEY idx_hyperlink_recipient_market_stat (tenant_id, submitted_at, sender_country_iso2_snapshot, recipient_country_iso2_snapshot, sender_account_type_snapshot, sender_device_os_snapshot, account_id, send_status)');
PREPARE hyperlink_analysis_stmt FROM @hyperlink_analysis_sql;
EXECUTE hyperlink_analysis_stmt;
DEALLOCATE PREPARE hyperlink_analysis_stmt;

CREATE TABLE IF NOT EXISTS hyperlink_stat_daily (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '日聚合主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    stat_date INT NOT NULL COMMENT 'Asia/Shanghai自然日yyyyMMdd',
    sender_country_iso2 CHAR(2) NOT NULL COMMENT '发送国家ISO2;ZZ未知',
    recipient_country_iso2 CHAR(2) NOT NULL COMMENT '被营销国家ISO2;ZZ未知',
    account_type TINYINT NOT NULL COMMENT '发送账号类型:0未知 1个人 2商业',
    task_type TINYINT NOT NULL COMMENT '任务模式:1即时 2预发布 3周期',
    sender_device_os TINYINT NOT NULL COMMENT '发送时设备OS:0未知 1安卓 2苹果',
    is_short_link_enabled TINYINT(1) NOT NULL COMMENT '是否开启短链:0否 1是',
    send_total BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行已提交recipient数',
    success_num BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行至少单钩recipient数',
    delivered_num BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行至少双钩recipient数',
    used_account_count BIGINT NOT NULL DEFAULT 0 COMMENT '本时间桶维度行账号去重数;顶部overview另行全局去重',
    banned_account_count BIGINT NOT NULL DEFAULT 0 COMMENT '本时间桶维度行封号账号去重数;顶部overview另行全局去重',
    click_uv_num BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行点击recipient数;唯一发送事实可跨行加总',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '最近回填时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_stat_daily
        (tenant_id, stat_date, sender_country_iso2, recipient_country_iso2,
         account_type, task_type, sender_device_os, is_short_link_enabled),
    KEY idx_hyperlink_stat_daily_range (tenant_id, stat_date, id),
    KEY idx_hyperlink_stat_daily_retention (stat_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链市场分析日投影;Asia/Shanghai;滚动保留90天';

CREATE TABLE IF NOT EXISTS hyperlink_stat_hourly (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '小时聚合主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    stat_hour_start_at BIGINT NOT NULL COMMENT 'Asia/Shanghai整点epoch毫秒',
    sender_country_iso2 CHAR(2) NOT NULL COMMENT '发送国家ISO2;ZZ未知',
    recipient_country_iso2 CHAR(2) NOT NULL COMMENT '被营销国家ISO2;ZZ未知',
    account_type TINYINT NOT NULL COMMENT '发送账号类型:0未知 1个人 2商业',
    task_type TINYINT NOT NULL COMMENT '任务模式:1即时 2预发布 3周期',
    sender_device_os TINYINT NOT NULL COMMENT '发送时设备OS:0未知 1安卓 2苹果',
    is_short_link_enabled TINYINT(1) NOT NULL COMMENT '是否开启短链:0否 1是',
    send_total BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行已提交recipient数',
    success_num BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行至少单钩recipient数',
    delivered_num BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行至少双钩recipient数',
    used_account_count BIGINT NOT NULL DEFAULT 0 COMMENT '本时间桶维度行账号去重数;顶部overview另行全局去重',
    banned_account_count BIGINT NOT NULL DEFAULT 0 COMMENT '本时间桶维度行封号账号去重数;顶部overview另行全局去重',
    click_uv_num BIGINT NOT NULL DEFAULT 0 COMMENT '本维度行点击recipient数;唯一发送事实可跨行加总',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '最近回填时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_stat_hourly
        (tenant_id, stat_hour_start_at, sender_country_iso2, recipient_country_iso2,
         account_type, task_type, sender_device_os, is_short_link_enabled),
    KEY idx_hyperlink_stat_hourly_range (tenant_id, stat_hour_start_at, id),
    KEY idx_hyperlink_stat_hourly_retention (stat_hour_start_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链市场分析小时投影;Asia/Shanghai;滚动保留8天';

SET @hyperlink_analysis_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '超链市场分析', 'HyperlinkAnalysis', 'M',
       '/hyperlink/analysis', 'hyperlink/analysis/index',
       'tenant:hyperlink_analysis:view', 'solar:chart-2-bold-duotone', 60, 1,
       @hyperlink_analysis_menu_now, NULL, @hyperlink_analysis_menu_now, NULL
FROM sys_menu parent
WHERE parent.menu_key='HyperlinkMarketing';
