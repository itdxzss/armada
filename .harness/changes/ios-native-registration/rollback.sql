-- 回滚第一步：关闭 armada.account.registration.device-enabled，并移除新许可配置。
-- 保留识别 IOS_DEVICE 的代码直至手机在途订单结算；不得让旧 worker 接管手机任务。
-- 保留新增列和审计记录；本次不自动删除订单或恢复 NOT NULL。
-- 以下只读检查用于判断是否仍有在途手机任务，不含号码或验证码。
SELECT t.tenant_id, t.id, i.state
FROM account_registration_task t JOIN account_registration_item i ON i.task_id=t.id AND i.tenant_id=t.tenant_id
WHERE t.execution_mode=2 AND i.state IN (1,2,3,4,11);

-- r7 回退：先停止手机和控端采购，确认上述无在途任务；恢复备份 JAR 与完整旧 env。
-- V198 许可表和 Flyway 记录保留用于审计，不手工 DROP 或删除迁移记录。
