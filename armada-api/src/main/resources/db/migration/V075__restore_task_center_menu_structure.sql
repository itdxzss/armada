-- 恢复原任务中心菜单层级：不把已有任务功能拆到新的业务目录。
SET @task_menu_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

-- V070 已上线拉群营销页面，但旧 RBAC 初始化遗漏了对应菜单，在这里补齐。
INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT task_center.tenant_id, task_center.id, '拉群营销', 'TaskGroupPullMarketing', 'M',
       '/task/group-pull-marketing', 'task/group-pull-marketing/index',
       'tenant:group_pull_marketing:view', NULL, 80, 1,
       @task_menu_now, NULL, @task_menu_now, NULL
FROM sys_menu task_center
WHERE task_center.menu_key = 'TaskCenter';

-- 九个原有功能统一保持在任务中心，排序与原前端菜单一致。
UPDATE sys_menu child
JOIN sys_menu task_center
  ON task_center.tenant_id = child.tenant_id
 AND task_center.menu_key = 'TaskCenter'
SET child.parent_id = task_center.id,
    child.sort_no = CASE child.menu_key
        WHEN 'AccountImport' THEN 10
        WHEN 'TaskGroupLinkImports' THEN 20
        WHEN 'GroupList' THEN 30
        WHEN 'HistoricalGroupManagement' THEN 40
        WHEN 'TaskPull' THEN 50
        WHEN 'TaskJoin' THEN 60
        WHEN 'TaskGroupMarketing' THEN 70
        WHEN 'TaskGroupPullMarketing' THEN 80
        WHEN 'TaskGroupCreationMarketing' THEN 90
        ELSE child.sort_no
    END,
    child.updated_at = @task_menu_now
WHERE child.menu_key IN (
    'AccountImport',
    'TaskGroupLinkImports',
    'GroupList',
    'HistoricalGroupManagement',
    'TaskPull',
    'TaskJoin',
    'TaskGroupMarketing',
    'TaskGroupPullMarketing',
    'TaskGroupCreationMarketing'
);

-- 子菜单已迁回任务中心，停用后来拆出的空“群组管理”目录，不物理删除。
UPDATE sys_menu
SET status = 0,
    updated_at = @task_menu_now
WHERE menu_key = 'GroupManagement';
