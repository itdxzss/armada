-- 实际上线迁移由 Flyway 执行，禁止手工修改共享库。
-- 对应文件：
--   armada-api/src/main/resources/db/migration/V082__marketing_export_country_and_joined_at.sql
--   armada-api/src/main/resources/db/migration/V083__marketing_task_export_job.sql

-- V082：补充营销导出国家主数据、共享区号唯一映射，并为成功进群事实增加 joined_at。
-- V083：新增普通营销任务异步导出作业表、同用户活动作业唯一约束和独立按钮权限；不自动授权普通角色。
