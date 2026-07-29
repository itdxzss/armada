-- 普通营销任务异步导出：仅新增作业表和独立导出权限，不修改既有营销任务业务表。
CREATE TABLE IF NOT EXISTS marketing_task_export_job (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '导出作业主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    created_by BIGINT NOT NULL COMMENT '提交导出的用户ID',
    export_mode VARCHAR(32) NOT NULL COMMENT '导出模式:COUNTRY_ENTRY按国家进群明细/FULL全量',
    task_ids_json JSON NOT NULL COMMENT '所选普通营销任务ID有序去重快照',
    country_iso2s_json JSON NOT NULL COMMENT '按国家模式所选ISO2列表;全量模式为空数组',
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化请求SHA-256',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态:PENDING/PROCESSING/SUCCESS/FAILED',
    snapshot_at BIGINT NOT NULL COMMENT '用户确认导出时的数据统计截止时间(epoch毫秒)',
    lease_until BIGINT DEFAULT NULL COMMENT '后台处理租约到期时间(epoch毫秒)',
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '本次Worker领取令牌,防止过期Worker覆盖新结果',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '后台领取处理次数,最多3次',
    storage_key VARCHAR(255) DEFAULT NULL COMMENT '服务端存储目录内的相对文件键',
    file_name VARCHAR(255) DEFAULT NULL COMMENT '下载展示文件名',
    content_type VARCHAR(128) DEFAULT NULL COMMENT '下载Content-Type',
    file_size BIGINT DEFAULT NULL COMMENT '文件字节数',
    summary_row_count INT NOT NULL DEFAULT 0 COMMENT '任务汇总数据行数',
    detail_row_count INT NOT NULL DEFAULT 0 COMMENT '国家或群组明细数据行数',
    error_message VARCHAR(500) DEFAULT NULL COMMENT '失败时给运营展示的稳定错误说明',
    created_at BIGINT NOT NULL COMMENT '作业创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '作业更新时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '文件生成完成或失败时间(epoch毫秒)',
    expires_at BIGINT DEFAULT NULL COMMENT '成功文件过期时间(epoch毫秒)',
    active_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (
            IF(status IN ('PENDING', 'PROCESSING'), request_hash, NULL)
        ) STORED COMMENT '同用户同范围活动作业防重复键',
    active_created_by BIGINT
        GENERATED ALWAYS AS (
            IF(status IN ('PENDING', 'PROCESSING'), created_by, NULL)
        ) STORED COMMENT '同租户用户活动作业唯一键;非活动状态为空',
    PRIMARY KEY (id),
    UNIQUE KEY uq_marketing_export_active
        (tenant_id, created_by, active_request_hash),
    UNIQUE KEY uq_marketing_export_creator_active
        (tenant_id, active_created_by),
    KEY idx_marketing_export_process (status, lease_until, attempt_count, id),
    KEY idx_marketing_export_owner (tenant_id, created_by, created_at, id),
    KEY idx_marketing_export_expire (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通营销任务异步导出作业';

-- 导出是独立敏感权限；租户管理员按既有动态规则拥有，普通角色由运营显式授权。
SET @marketing_export_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '导出任务', 'TaskGroupMarketingExport', 'B', NULL, NULL,
       'tenant:marketing_task:export', NULL, 10, 1,
       @marketing_export_menu_now, NULL, @marketing_export_menu_now, NULL
FROM sys_menu parent
WHERE parent.menu_key = 'TaskGroupMarketing';
