-- 独立剧本营销：旧 marketing_task 聚合不变。
CREATE TABLE script_marketing_task (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务 ID',
 tenant_id BIGINT NOT NULL COMMENT '租户 ID',
 created_by BIGINT NOT NULL COMMENT '创建用户，仅本人可操作',
 task_name VARCHAR(100) NOT NULL COMMENT '任务名称',
 steps_json JSON NOT NULL COMMENT '启动后固定的有序角色账号消息配置',
 status TINYINT NOT NULL DEFAULT 0 COMMENT '0草稿 1运行 2暂停 3完成 4关闭',
 interval_seconds INT NOT NULL COMMENT '统一发送间隔秒',
 start_at BIGINT NOT NULL COMMENT '计划开始时间毫秒',
 end_at BIGINT NULL COMMENT '可选截止时间毫秒',
 created_at BIGINT NOT NULL COMMENT '创建时间毫秒',
 updated_at BIGINT NOT NULL COMMENT '更新时间毫秒',
 PRIMARY KEY (id), KEY idx_script_owner (tenant_id, created_by, id)
) COMMENT='剧本营销任务';
CREATE TABLE script_marketing_group (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群执行 ID',
 tenant_id BIGINT NOT NULL COMMENT '租户 ID',
 task_id BIGINT NOT NULL COMMENT '任务 ID',
 group_link_id BIGINT NOT NULL COMMENT '已授权群链接 ID',
 group_jid VARCHAR(128) NOT NULL COMMENT '固定目标群 JID',
 group_name VARCHAR(255) NULL COMMENT '群名快照',
 next_step INT NOT NULL DEFAULT 0 COMMENT '下一发送项下标，从零开始',
 next_at BIGINT NOT NULL COMMENT '下一项最早执行时间毫秒',
 remaining_wait_ms BIGINT NOT NULL DEFAULT 0 COMMENT '暂停剩余等待毫秒',
 PRIMARY KEY (id), UNIQUE KEY uk_script_task_group (tenant_id, task_id, group_jid),
 KEY idx_script_due (next_at, task_id)
) COMMENT='剧本营销每群进度';
CREATE TABLE script_marketing_send_record (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '发送事实 ID',
 tenant_id BIGINT NOT NULL COMMENT '租户 ID',
 task_id BIGINT NOT NULL COMMENT '任务 ID',
 group_id BIGINT NOT NULL COMMENT '群执行 ID',
 step_index INT NOT NULL COMMENT '发送项下标，从零开始',
 account_id BIGINT NOT NULL COMMENT '实际发送账号 ID',
 command_id VARCHAR(64) NOT NULL COMMENT '始终跟踪同一发送命令',
 status TINYINT NOT NULL COMMENT '1等待结果 2成功 3失败 4未知 5暂停未投递',
 reason VARCHAR(500) NULL COMMENT '结果说明',
 message_id VARCHAR(255) NULL COMMENT '协议消息 ID',
 submitted_at BIGINT NOT NULL COMMENT '提交或恢复时间毫秒',
 finished_at BIGINT NULL COMMENT '结束等待时间毫秒',
 PRIMARY KEY (id), UNIQUE KEY uk_script_group_step (tenant_id, group_id, step_index),
 UNIQUE KEY uk_script_command (command_id), KEY idx_script_record_task (tenant_id, task_id, id)
) COMMENT='剧本营销单项发送事实';

-- 新菜单挂到既有任务中心，不改旧页面或旧菜单权限。
INSERT IGNORE INTO sys_menu
 (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path, perm_key,
  icon, sort_no, status, created_at, updated_at)
SELECT tenant_id, id, '剧本营销任务', 'TaskScriptMarketing', 'M', '/task/script-marketing',
 'task/script-marketing/index', 'tenant:script_marketing:view', NULL, 85, 1, created_at, updated_at
FROM sys_menu WHERE menu_key='TaskCenter';
INSERT IGNORE INTO sys_menu
 (tenant_id, parent_id, menu_name, menu_key, menu_type, perm_key, sort_no, status, created_at, updated_at)
SELECT m.tenant_id, m.id, p.label, p.menu_key, 'B', p.perm_key, p.sort_no, 1, m.created_at, m.updated_at
FROM sys_menu m CROSS JOIN (
 SELECT '创建剧本任务' label, 'TaskScriptMarketingCreate' menu_key, 'tenant:script_marketing:create' perm_key, 10 sort_no
 UNION ALL SELECT '修改剧本任务', 'TaskScriptMarketingEdit', 'tenant:script_marketing:edit', 20
 UNION ALL SELECT '操作剧本任务', 'TaskScriptMarketingOperate', 'tenant:script_marketing:operate', 30
) p WHERE m.menu_key='TaskScriptMarketing';
