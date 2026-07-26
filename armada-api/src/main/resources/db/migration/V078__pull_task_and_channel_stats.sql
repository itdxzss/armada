-- 补齐“拉群任务”和“渠道统计”页面的最小持久化能力。
-- 两张表均为独立新增，不修改进群任务、拉群营销、渠道管理等现有业务表结构。

CREATE TABLE pull_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '拉群任务主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    mode VARCHAR(32) NOT NULL COMMENT '任务模式:OLD_LINK老群链接 CREATE_NEW自建群',
    status VARCHAR(32) NOT NULL DEFAULT 'WAIT_START' COMMENT '状态:WAIT_START/EXECUTING/PAUSED/COMPLETED/ENDED',
    group_name VARCHAR(128) DEFAULT NULL COMMENT '配置的群名称',
    group_count INT NOT NULL DEFAULT 0 COMMENT '任务群数量',
    expected_pull_count INT NOT NULL DEFAULT 0 COMMENT '预计拉人数量',
    config_json JSON NOT NULL COMMENT '页面任务配置快照',
    operator_name VARCHAR(64) DEFAULT NULL COMMENT '创建人展示名称快照',
    created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '逻辑删除时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_pull_task_page (tenant_id, deleted_at, created_at, id),
    KEY idx_pull_task_status (tenant_id, status, deleted_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拉群任务配置';

CREATE TABLE promotion_channel_daily_ad_metric (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '渠道日广告数据主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    channel_id BIGINT NOT NULL COMMENT '推广渠道ID',
    country_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '统计国家ISO2或MIXED',
    stat_date DATE NOT NULL COMMENT '统计日期',
    spend DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '广告消耗',
    impressions BIGINT NOT NULL DEFAULT 0 COMMENT '广告展示次数',
    clicks BIGINT NOT NULL DEFAULT 0 COMMENT '广告点击次数',
    service_rate DECIMAL(9,6) NOT NULL DEFAULT 0 COMMENT '手续费率小数',
    other_fee DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '其他费用',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    updated_by BIGINT DEFAULT NULL COMMENT '最后修改人用户ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_channel_daily_ad_metric (tenant_id, channel_id, country_code, stat_date),
    KEY idx_channel_daily_ad_metric_range (tenant_id, stat_date, channel_id, country_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='渠道每日人工广告数据';

-- 写操作必须拥有独立按钮权限，不能用页面查看权限代替。
SET @permission_seed_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);
INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL, NULL,
       permission.perm_key, NULL, permission.sort_no, 1,
       @permission_seed_now, NULL, @permission_seed_now, NULL
FROM tenant
INNER JOIN sys_menu parent
    ON parent.tenant_id = tenant.id AND parent.menu_key = 'TaskPull'
CROSS JOIN (
    SELECT '新增任务' AS menu_name, 'TaskPullCreate' AS menu_key,
           'tenant:pull_task:create' AS perm_key, 10 AS sort_no
    UNION ALL SELECT '操作任务', 'TaskPullOperate', 'tenant:pull_task:operate', 20
    UNION ALL SELECT '删除任务', 'TaskPullDelete', 'tenant:pull_task:delete', 30
    UNION ALL SELECT '导出任务', 'TaskPullExport', 'tenant:pull_task:export', 40
) permission
WHERE tenant.status = 1;
