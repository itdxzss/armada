-- V051 累计成功群组功能独立回滚。
-- 仅恢复旧的创建时固定群目标计数口径，不执行 marketing-task/rollback.sql 的全模块删除。
UPDATE marketing_task task
LEFT JOIN (
    SELECT tenant_id,
           marketing_task_id,
           COUNT(DISTINCT group_link_id) AS target_group_count
    FROM marketing_task_target
    WHERE target_scope = 1
      AND group_link_id IS NOT NULL
    GROUP BY tenant_id, marketing_task_id
) target ON target.tenant_id = task.tenant_id
        AND target.marketing_task_id = task.id
SET task.target_group_count = COALESCE(target.target_group_count, 0);

ALTER TABLE marketing_task
    MODIFY COLUMN target_group_count INT NOT NULL DEFAULT 0
    COMMENT '选中去重目标群数';

DROP TABLE IF EXISTS marketing_task_success_group;
