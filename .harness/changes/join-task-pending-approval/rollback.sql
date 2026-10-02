-- 回滚前只读检查；不得直接给旧后端留下 APPROVAL 在途状态。
SELECT tenant_id, stage, COUNT(*) AS records
FROM join_task_approval
GROUP BY tenant_id, stage;
SELECT tenant_id, COUNT(*) AS processing
FROM join_task_result
WHERE status = 'PENDING' AND dispatch_state = 'APPROVAL'
GROUP BY tenant_id;
-- 有在途记录时先排空或使用新版业务流程明确终结，再回滚应用。
-- 保留子记录审计数据，不 DROP 表；代码回滚不自动打开真实群审核开关。
