-- 可复用剧本是定义聚合，任务仍保存自己的 steps_json 快照。
CREATE TABLE script_marketing_definition (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '剧本定义 ID',
 tenant_id BIGINT NOT NULL COMMENT '租户 ID',
 created_by BIGINT NOT NULL COMMENT '创建用户，仅本人操作',
 name VARCHAR(100) NOT NULL COMMENT '剧本名称',
 steps_json JSON NOT NULL COMMENT '有序角色、消息和间隔；不绑定具体账号',
 enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0停用 1启用',
 created_at BIGINT NOT NULL COMMENT '创建时间毫秒',
 updated_at BIGINT NOT NULL COMMENT '更新时间毫秒',
 deleted_at BIGINT NULL COMMENT '软删除时间毫秒',
 PRIMARY KEY (id), KEY idx_script_definition_owner (tenant_id, created_by, deleted_at, id)
) COMMENT='可复用养群剧本';

-- 保留既有任务菜单 ID、路由和权限，普通角色的原授权仍有效。
INSERT IGNORE INTO sys_menu
 (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
  perm_key, icon, sort_no, status, created_at, updated_at)
SELECT tenant_id, 0, '养群管理', 'GroupMaintenance', 'D', '/group-maintenance', NULL,
 NULL, 'ep:chat-line-round', 37, 1, created_at, updated_at
FROM sys_menu WHERE menu_key='TaskScriptMarketing';

INSERT IGNORE INTO sys_menu
 (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
  perm_key, icon, sort_no, status, created_at, updated_at)
SELECT m.tenant_id, m.id, p.label, p.menu_key, 'M', p.route_path, p.component_path,
 'tenant:script_marketing:view', NULL, p.sort_no, 1, m.created_at, m.updated_at
FROM sys_menu m CROSS JOIN (
 SELECT '剧本素材库' label, 'ScriptMaterialLibrary' menu_key, '/group-maintenance/materials' route_path,
        'material/script-material/index' component_path, 10 sort_no
 UNION ALL SELECT '养群剧本', 'ScriptDefinitionLibrary', '/group-maintenance/scripts',
        'task/script-definition/index', 20
) p WHERE m.menu_key='GroupMaintenance';

UPDATE sys_menu task_menu
JOIN sys_menu directory ON directory.tenant_id=task_menu.tenant_id AND directory.menu_key='GroupMaintenance'
SET task_menu.parent_id=directory.id, task_menu.menu_name='养群任务', task_menu.sort_no=30
WHERE task_menu.menu_key='TaskScriptMarketing';
