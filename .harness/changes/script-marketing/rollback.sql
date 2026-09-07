-- 仅在已停用剧本调度且已确认环境后执行；保留三张业务表作为审计记录。
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE menu_key IN ('TaskScriptMarketingCreate','TaskScriptMarketingEdit','TaskScriptMarketingOperate','TaskScriptMarketing'));
DELETE FROM sys_menu WHERE menu_key IN ('TaskScriptMarketingCreate','TaskScriptMarketingEdit','TaskScriptMarketingOperate','TaskScriptMarketing');
