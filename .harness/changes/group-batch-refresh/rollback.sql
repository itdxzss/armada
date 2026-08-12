-- 群组列表批量刷新任务回滚：撤销 V112 新增结构。
-- 共享库或生产执行前必须单独确认目标环境；Flyway 历史行的处理另走审批后的部署方案。

-- 1) 批量任务明细与主表。仅本期新增，无其他业务读写，可直接删除。
--    执行前确认没有运行中的批量任务（status IN (1, 2)），否则前端轮询会拿到 404。
DROP TABLE IF EXISTS group_batch_task_item;
DROP TABLE IF EXISTS group_batch_task;

-- 2) 以下改动不在 Flyway 内（属代码变更），回滚代码即可，此处仅记录以免遗漏：
--    - AccountGroupMembershipMapper.xml 的 selectGroupAdminExecutionAccounts
--    - GroupMetadataSnapshotService.refresh(GroupMetadataSnapshotRequest, ...) 的读取入口
--    两个批量按钮都由执行器实时直调协议，不写 group_metadata_sync_task，
--    因此回滚不需要清理任何耐久队列残留行。
