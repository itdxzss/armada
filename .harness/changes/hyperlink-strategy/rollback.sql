-- 超链策略模板专项回滚。
-- 仅在应用代码已回退、策略数据已备份且确认无需保留后执行。
-- 共享库或生产执行前必须单独确认目标环境；任务策略会回填到旧列，模板会永久删除。

ALTER TABLE hyperlink_task
    ADD COLUMN task_type TINYINT NOT NULL DEFAULT 1 COMMENT '任务模式:1即时 2预发布 3周期',
    ADD COLUMN task_interval_minutes INT NOT NULL DEFAULT 0 COMMENT '周期间隔分钟;非周期为0',
    ADD COLUMN account_filter JSON DEFAULT NULL COMMENT '账号筛选快照',
    ADD COLUMN max_use_account INT NOT NULL DEFAULT 0 COMMENT '最大使用账号数',
    ADD COLUMN concurrent_num INT NOT NULL DEFAULT 10 COMMENT '最大执行账号数',
    ADD COLUMN account_max_send_num INT NOT NULL DEFAULT 0 COMMENT '单账号任务内成功上限';

UPDATE hyperlink_task task
INNER JOIN hyperlink_strategy strategy
   ON strategy.tenant_id = task.tenant_id
  AND strategy.id = task.hyperlink_strategy_id
  AND strategy.strategy_scope = 2
SET task.task_type = strategy.task_type,
    task.task_interval_minutes = strategy.task_interval_minutes,
    task.account_filter = strategy.account_filter,
    task.max_use_account = strategy.max_use_account,
    task.concurrent_num = GREATEST(strategy.concurrent_num, 1),
    task.account_max_send_num = strategy.account_max_send_num;

ALTER TABLE hyperlink_task
    MODIFY COLUMN account_filter JSON NOT NULL COMMENT '账号筛选快照',
    DROP INDEX uq_hyperlink_task_strategy,
    DROP INDEX idx_hyperlink_task_planned_end,
    MODIFY COLUMN hyperlink_strategy_id BIGINT DEFAULT NULL COMMENT '引用策略ID;仅追溯',
    ADD KEY idx_hyperlink_task_planned_end
        (tenant_id, task_type, task_planned_end_at, id),
    ADD CONSTRAINT ck_hyperlink_task_type CHECK (task_type IN (1,2,3)),
    ADD CONSTRAINT ck_hyperlink_task_limits CHECK (
        task_delay_minutes >= 0 AND task_interval_minutes >= 0
        AND max_use_account >= 0 AND concurrent_num > 0
        AND account_max_send_num >= 0 AND account_send_concurrency BETWEEN 1 AND 100
        AND msg_interval_min_ms BETWEEN 0 AND 10000
        AND msg_interval_max_ms BETWEEN msg_interval_min_ms AND 10000
        AND version > 0);

DELETE role_menu
FROM sys_role_menu role_menu
JOIN sys_menu menu ON menu.id = role_menu.menu_id
WHERE menu.menu_key IN (
    'HyperlinkStrategyCreate',
    'HyperlinkStrategyEdit',
    'HyperlinkStrategyDelete',
    'HyperlinkStrategy'
);

DELETE FROM sys_menu
WHERE menu_key IN (
    'HyperlinkStrategyCreate',
    'HyperlinkStrategyEdit',
    'HyperlinkStrategyDelete'
);

DELETE FROM sys_menu
WHERE menu_key = 'HyperlinkStrategy';

DROP TABLE IF EXISTS hyperlink_strategy;

ALTER TABLE account_group
    DROP INDEX uq_account_group_system_code,
    DROP COLUMN system_code;

-- Flyway 历史记录必须由部署流程在确认 V168 回滚后处理，不在此脚本自动删除。
