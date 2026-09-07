-- 普通链接任务事实汇总，由 pull-task-diagnose.sh 在独立只读连接执行。
-- 手动使用须先设置 @task_id、@execution_id（NULL 表示整个任务），
-- 并执行 SET SESSION MAX_EXECUTION_TIME = 5000 和 SET SESSION TRANSACTION READ ONLY。
-- 各明细先按任务独立聚合，避免多表联查放大行数；命令去重后等值查找 Outbox。
WITH
task_scope AS (
    SELECT id, tenant_id
    FROM pull_task
    WHERE id = @task_id
      AND task_type = 'STANDARD' AND mode = 'NORMAL_LINK' AND deleted_at IS NULL
),
execution_scope AS (
    SELECT e.id, e.tenant_id, e.task_id
    FROM task_scope t
    JOIN pull_task_group_execution e
      ON e.tenant_id = t.tenant_id AND e.task_id = t.id
    WHERE @execution_id IS NULL OR e.id = @execution_id
),
action_counts AS (
    SELECT e.tenant_id, e.task_id,
           COUNT(*) AS action_count,
           COUNT(DISTINCT a.command_id) AS action_command_count
    FROM execution_scope e
    JOIN pull_task_account_action a
      ON a.tenant_id = e.tenant_id AND a.group_execution_id = e.id
    GROUP BY e.tenant_id, e.task_id
),
call_counts AS (
    SELECT e.tenant_id, e.task_id,
           COUNT(*) AS pull_call_count,
           COUNT(DISTINCT c.command_id) AS pull_command_count
    FROM execution_scope e
    JOIN pull_task_pull_call c
      ON c.tenant_id = e.tenant_id AND c.group_execution_id = e.id
    GROUP BY e.tenant_id, e.task_id
),
material_counts AS (
    SELECT e.tenant_id, e.task_id, COUNT(*) AS material_member_count
    FROM execution_scope e
    JOIN pull_task_material_member m
      ON m.tenant_id = e.tenant_id AND m.group_execution_id = e.id
    GROUP BY e.tenant_id, e.task_id
),
puller_counts AS (
    SELECT e.tenant_id, e.task_id, COUNT(*) AS unreleased_puller_count
    FROM execution_scope e
    JOIN pull_task_group_account g
      ON g.tenant_id = e.tenant_id AND g.group_execution_id = e.id
    WHERE g.role_type = 2 AND g.released_at IS NULL
    GROUP BY e.tenant_id, e.task_id
),
command_refs AS (
    SELECT e.tenant_id, e.task_id, a.command_id
    FROM execution_scope e
    JOIN pull_task_account_action a
      ON a.tenant_id = e.tenant_id AND a.group_execution_id = e.id
    WHERE a.command_id IS NOT NULL
    UNION
    SELECT e.tenant_id, e.task_id, c.command_id
    FROM execution_scope e
    JOIN pull_task_pull_call c
      ON c.tenant_id = e.tenant_id AND c.group_execution_id = e.id
    WHERE c.command_id IS NOT NULL
    UNION
    SELECT e.tenant_id, e.task_id, m.admin_command_id AS command_id
    FROM execution_scope e
    JOIN pull_task_material_member m
      ON m.tenant_id = e.tenant_id AND m.group_execution_id = e.id
    WHERE m.admin_command_id IS NOT NULL
),
backend_counts AS (
    SELECT c.tenant_id, c.task_id,
           GROUP_CONCAT(DISTINCT o.protocol_backend ORDER BY o.protocol_backend)
               AS protocol_backends
    FROM command_refs c
    JOIN protocol_command_outbox o
      ON o.command_id = c.command_id AND o.tenant_id = c.tenant_id
    GROUP BY c.tenant_id, c.task_id
)
SELECT 'FACTS' AS record_type,
       COALESCE(a.action_count, 0) AS action_count,
       COALESCE(a.action_command_count, 0) AS action_command_count,
       COALESCE(c.pull_call_count, 0) AS pull_call_count,
       COALESCE(c.pull_command_count, 0) AS pull_command_count,
       COALESCE(m.material_member_count, 0) AS material_member_count,
       COALESCE(p.unreleased_puller_count, 0) AS unreleased_puller_count,
       COALESCE(b.protocol_backends, '-') AS protocol_backends
FROM task_scope t
LEFT JOIN action_counts a ON a.tenant_id = t.tenant_id AND a.task_id = t.id
LEFT JOIN call_counts c ON c.tenant_id = t.tenant_id AND c.task_id = t.id
LEFT JOIN material_counts m ON m.tenant_id = t.tenant_id AND m.task_id = t.id
LEFT JOIN puller_counts p ON p.tenant_id = t.tenant_id AND p.task_id = t.id
LEFT JOIN backend_counts b ON b.tenant_id = t.tenant_id AND b.task_id = t.id;
