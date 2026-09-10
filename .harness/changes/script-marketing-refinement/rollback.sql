-- 回退检查清单，仅只读查询；未执行。先确认目标环境并停止新任务创建。
-- 不直接删除绑定列、结果截止列、定义表或发送记录，不手工修改 Flyway 历史。
SELECT COUNT(*) AS grouped_tasks FROM script_marketing_task WHERE account_group_id IS NOT NULL;
SELECT COUNT(*) AS active_grouped_tasks FROM script_marketing_task WHERE account_group_id IS NOT NULL AND status IN (0,1,2);
SELECT COUNT(*) AS unresolved_commands FROM script_marketing_send_record r
JOIN script_marketing_task t ON t.id=r.task_id AND t.tenant_id=r.tenant_id
WHERE t.account_group_id IS NOT NULL AND r.status IN (1,5);
SELECT COUNT(*) AS saved_definitions FROM script_marketing_definition;
SELECT id, tenant_id, parent_id, menu_key, route_path FROM sys_menu
WHERE menu_key IN ('GroupMaintenance','TaskScriptMarketing','ScriptMaterialLibrary','ScriptDefinitionLibrary');
-- 有新配置或发送事实：保留结构与数据，优先前向修复；在新执行器下收敛原命令。
-- 回退旧应用前必须停掉其剧本扫描，不能让旧执行器读取并发送新配置。
-- 菜单回退需保留旧任务菜单 ID/授权，恢复原 TaskCenter 父菜单；新菜单只停用，不直接删角色授权。
-- 仅在全部未使用且另行确认回退范围时，才设计新的前向 Flyway 撤销迁移。
