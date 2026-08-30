-- 超链发送策略唯一事实源：模板与任务独占快照共表，任务只保留策略ID。

CREATE TABLE IF NOT EXISTS hyperlink_strategy (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '策略主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    strategy_scope TINYINT NOT NULL COMMENT '策略用途:1模板 2任务快照',
    owner_task_id BIGINT DEFAULT NULL COMMENT '任务快照所属任务ID;模板为空',
    source_strategy_id BIGINT DEFAULT NULL COMMENT '任务快照来源模板ID;仅追溯',
    strategy_name VARCHAR(128) DEFAULT NULL COMMENT '模板名称;任务快照为空',
    task_type TINYINT NOT NULL COMMENT '任务模式:1即时 2预发布 3周期',
    account_filter JSON NOT NULL COMMENT '账号筛选快照;HyperlinkAccountFilterDTO schemaVersion=1',
    concurrent_num INT NOT NULL DEFAULT 10 COMMENT '最大执行账号数;0自动均分 1到100固定',
    max_use_account INT NOT NULL DEFAULT 0 COMMENT '最大使用账号数;0不限;周期为每轮上限',
    account_max_send_num INT NOT NULL DEFAULT 0 COMMENT '单账号最大发送数;0不限',
    task_interval_minutes INT NOT NULL DEFAULT 0 COMMENT '周期间隔分钟;模板周期至少30,任务周期至少1',
    is_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '模板是否可选;任务快照恒为1',
    version INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by BIGINT DEFAULT NULL COMMENT '创建人用户ID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '模板软删除时间(epoch毫秒);任务快照为空',
    template_active TINYINT GENERATED ALWAYS AS
        (CASE WHEN strategy_scope = 1 AND deleted_at IS NULL THEN 1 ELSE NULL END)
        STORED COMMENT '有效模板名称唯一辅助列',
    PRIMARY KEY (id),
    UNIQUE KEY uq_hyperlink_strategy_template_name
        (tenant_id, strategy_name, template_active),
    UNIQUE KEY uq_hyperlink_strategy_owner_task (tenant_id, owner_task_id),
    KEY idx_hyperlink_strategy_template_list
        (tenant_id, strategy_scope, deleted_at, updated_at, id),
    KEY idx_hyperlink_strategy_template_enabled
        (tenant_id, strategy_scope, is_enabled, deleted_at, updated_at, id),
    KEY idx_hyperlink_strategy_source (tenant_id, source_strategy_id, id),
    CONSTRAINT chk_hyperlink_strategy_scope CHECK (strategy_scope IN (1, 2)),
    CONSTRAINT chk_hyperlink_strategy_scope_fields CHECK (
        (strategy_scope = 1 AND strategy_name IS NOT NULL
            AND owner_task_id IS NULL AND source_strategy_id IS NULL)
        OR (strategy_scope = 2 AND strategy_name IS NULL AND is_enabled = 1)
    ),
    CONSTRAINT chk_hyperlink_strategy_task_type CHECK (task_type IN (1, 2, 3)),
    CONSTRAINT chk_hyperlink_strategy_concurrent CHECK (concurrent_num BETWEEN 0 AND 100),
    CONSTRAINT chk_hyperlink_strategy_max_use CHECK (max_use_account >= 0),
    CONSTRAINT chk_hyperlink_strategy_account_max_send CHECK (account_max_send_num >= 0),
    CONSTRAINT chk_hyperlink_strategy_enabled CHECK (is_enabled IN (0, 1)),
    CONSTRAINT chk_hyperlink_strategy_version CHECK (version > 0),
    CONSTRAINT chk_hyperlink_strategy_account_bounds CHECK (
        max_use_account = 0 OR concurrent_num = 0 OR max_use_account >= concurrent_num
    ),
    CONSTRAINT chk_hyperlink_strategy_mode_interval CHECK (
        (task_type IN (1, 2) AND task_interval_minutes = 0)
        OR (task_type = 3 AND max_use_account >= 1
            AND ((strategy_scope = 1 AND task_interval_minutes >= 30)
                OR (strategy_scope = 2 AND task_interval_minutes >= 1)))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链发送策略唯一事实源;模板与任务快照共表';

-- 竞品默认勾选公共组与超链组。用稳定编码取 ID，避免业务逻辑依赖可展示中文名称。
SET @hyperlink_group_schema_sql := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND column_name = 'system_code') = 0,
    'ALTER TABLE account_group ADD COLUMN system_code VARCHAR(32) DEFAULT NULL COMMENT ''系统业务分组稳定编码'' AFTER system_builtin',
    'SELECT 1'
);
PREPARE hyperlink_group_schema_stmt FROM @hyperlink_group_schema_sql;
EXECUTE hyperlink_group_schema_stmt;
DEALLOCATE PREPARE hyperlink_group_schema_stmt;

SET @hyperlink_group_schema_sql := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_group'
       AND index_name = 'uq_account_group_system_code') = 0,
    'ALTER TABLE account_group ADD UNIQUE KEY uq_account_group_system_code (tenant_id, system_code)',
    'SELECT 1'
);
PREPARE hyperlink_group_schema_stmt FROM @hyperlink_group_schema_sql;
EXECUTE hyperlink_group_schema_stmt;
DEALLOCATE PREPARE hyperlink_group_schema_stmt;

SET @hyperlink_group_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO account_group
    (tenant_id, name, remark, system_builtin, system_code,
     created_at, updated_at, created_by, deleted_at)
SELECT tenant_row.id, defaults.name, defaults.remark, 1, defaults.system_code,
       @hyperlink_group_now, @hyperlink_group_now, NULL, NULL
FROM tenant tenant_row
CROSS JOIN (
    SELECT '公共组' AS name, '超链营销系统公共账号组' AS remark,
           'HYPERLINK_PUBLIC' AS system_code
    UNION ALL
    SELECT '超链组', '超链营销系统专用账号组', 'HYPERLINK_MARKETING'
) defaults;

-- 若租户已有同名活跃分组，复用其 ID 并升级为不可删除的系统业务组。
UPDATE account_group AS account_group_row
INNER JOIN (
    SELECT '公共组' AS name, 'HYPERLINK_PUBLIC' AS system_code
    UNION ALL
    SELECT '超链组', 'HYPERLINK_MARKETING'
) defaults ON defaults.name = account_group_row.name
SET account_group_row.system_builtin = 1,
    account_group_row.system_code = defaults.system_code,
    account_group_row.updated_at = @hyperlink_group_now
WHERE account_group_row.deleted_at IS NULL
  AND account_group_row.owner_user_id IS NULL
  AND account_group_row.system_code IS NULL;

-- 每个存量任务生成独占快照；owner_task_id 是幂等回填键。
INSERT IGNORE INTO hyperlink_strategy
    (tenant_id, strategy_scope, owner_task_id, source_strategy_id, strategy_name,
     task_type, account_filter, concurrent_num, max_use_account,
     account_max_send_num, task_interval_minutes, is_enabled, version,
     created_by, created_at, updated_at)
SELECT tenant_id, 2, id, NULL, NULL,
       task_type, account_filter, concurrent_num, max_use_account,
       account_max_send_num, task_interval_minutes, 1, version,
       created_by, created_at, updated_at
FROM hyperlink_task;

UPDATE hyperlink_task task
INNER JOIN hyperlink_strategy strategy
   ON strategy.tenant_id = task.tenant_id
  AND strategy.strategy_scope = 2
  AND strategy.owner_task_id = task.id
SET task.hyperlink_strategy_id = strategy.id;

-- 收敛为单一事实源：任务生命周期字段留在 task，六个策略字段只留在 strategy。
ALTER TABLE hyperlink_task
    DROP CHECK ck_hyperlink_task_type,
    DROP CHECK ck_hyperlink_task_limits,
    DROP INDEX idx_hyperlink_task_planned_end,
    MODIFY COLUMN hyperlink_strategy_id BIGINT NOT NULL COMMENT '任务独占策略快照ID',
    ADD UNIQUE KEY uq_hyperlink_task_strategy (tenant_id, hyperlink_strategy_id),
    ADD KEY idx_hyperlink_task_planned_end (tenant_id, task_planned_end_at, id);

ALTER TABLE hyperlink_task
    DROP COLUMN task_type,
    DROP COLUMN task_interval_minutes,
    DROP COLUMN account_filter,
    DROP COLUMN max_use_account,
    DROP COLUMN concurrent_num,
    DROP COLUMN account_max_send_num;

SET @hyperlink_strategy_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, '超链策略', 'HyperlinkStrategy', 'M',
       '/hyperlink/strategy', 'hyperlink/strategy/index',
       'tenant:hyperlink_strategy:view', 'solar:tuning-2-bold-duotone', 40, 1,
       @hyperlink_strategy_menu_now, NULL, @hyperlink_strategy_menu_now, NULL
FROM sys_menu parent
WHERE parent.menu_key = 'HyperlinkMarketing';

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT parent.tenant_id, parent.id, permission.menu_name, permission.menu_key, 'B', NULL,
       NULL, permission.perm_key, NULL, permission.sort_no, 1,
       @hyperlink_strategy_menu_now, NULL, @hyperlink_strategy_menu_now, NULL
FROM sys_menu parent
CROSS JOIN (
    SELECT '创建超链策略' AS menu_name,
           'HyperlinkStrategyCreate' AS menu_key,
           'tenant:hyperlink_strategy:create' AS perm_key,
           10 AS sort_no
    UNION ALL
    SELECT '编辑超链策略', 'HyperlinkStrategyEdit',
           'tenant:hyperlink_strategy:edit', 20
    UNION ALL
    SELECT '删除超链策略', 'HyperlinkStrategyDelete',
           'tenant:hyperlink_strategy:delete', 30
) permission
WHERE parent.menu_key = 'HyperlinkStrategy';
