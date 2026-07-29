-- 本功能执行过 Flyway 后只回滚应用版本，不回退数据库结构或主数据。
-- V082/V083 均为向前兼容的增量结构；删除列、表、权限或国家数据会破坏审计、
-- 后续重新发布和其他可能复用这些数据的功能，因此本脚本不执行任何 DDL/DML。
-- 回滚步骤：停用导出入口和 Worker，回滚后端/前端应用，保留历史作业及导出文件。
SELECT 'marketing-task-data-export database artifacts retained' AS rollback_notice;
