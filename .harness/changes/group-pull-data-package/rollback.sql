-- 只在明确目标环境、应用已停写且确认回滚窗口后执行；本轮未执行。
-- 任一包、号码、导入审计、统计或任务来源存在真实数据，都拒绝删除结构。
-- 拒绝分支下每条DDL再次检查同一safe标志，即使客户端继续执行也不删表。
-- 保留Flyway schema history，不自动删除迁移记录；重新上线前需按发布流程处理版本。
SET @gdp_rollback_safe := 0;
SELECT (
 (SELECT COUNT(*) FROM group_data_package) = 0
 AND (SELECT COUNT(*) FROM group_data_package_phone) = 0
 AND (SELECT COUNT(*) FROM group_data_package_import) = 0
 AND (SELECT COUNT(*) FROM group_data_package_stat) = 0
 AND (SELECT COUNT(*) FROM pull_task_group_execution WHERE source_package_id IS NOT NULL OR source_package_generation IS NOT NULL) = 0
 AND (SELECT COUNT(*) FROM pull_task_material_member WHERE source_package_phone_id IS NOT NULL OR source_allocation_version IS NOT NULL) = 0
) INTO @gdp_rollback_safe;
SELECT CASE WHEN @gdp_rollback_safe = 1 THEN 'EMPTY_DATA_CONFIRMED: structure rollback allowed'
 ELSE 'REFUSED: package data or task source exists; retain all schema and roll back application only' END AS rollback_decision;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1,
 'DELETE grant_row FROM sys_role_menu grant_row JOIN sys_menu menu_row ON menu_row.id=grant_row.menu_id AND menu_row.tenant_id=grant_row.tenant_id WHERE menu_row.menu_key IN (''GroupDataPackage'',''GroupDataPackageCreate'',''GroupDataPackageImport'',''GroupDataPackageEdit'',''GroupDataPackageExport'',''GroupDataPackageDelete'')', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1,
 'DELETE FROM sys_menu WHERE menu_key IN (''GroupDataPackageCreate'',''GroupDataPackageImport'',''GroupDataPackageEdit'',''GroupDataPackageExport'',''GroupDataPackageDelete'',''GroupDataPackage'')', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1 AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='pull_task_group_execution' AND index_name='idx_execution_package_source') > 0,
 'ALTER TABLE pull_task_group_execution DROP INDEX idx_execution_package_source', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1 AND (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='pull_task_material_member' AND index_name='idx_material_package_source') > 0,
 'ALTER TABLE pull_task_material_member DROP INDEX idx_material_package_source', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pull_task_material_member' AND column_name='source_allocation_version') > 0,
 'ALTER TABLE pull_task_material_member DROP COLUMN source_allocation_version', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pull_task_material_member' AND column_name='source_package_phone_id') > 0,
 'ALTER TABLE pull_task_material_member DROP COLUMN source_package_phone_id', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pull_task_group_execution' AND column_name='source_package_generation') > 0,
 'ALTER TABLE pull_task_group_execution DROP COLUMN source_package_generation', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pull_task_group_execution' AND column_name='source_package_id') > 0,
 'ALTER TABLE pull_task_group_execution DROP COLUMN source_package_id', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1,
 'DROP TABLE IF EXISTS group_data_package_stat', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1,
 'DROP TABLE IF EXISTS group_data_package_phone', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1,
 'DROP TABLE IF EXISTS group_data_package_import', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

SET @gdp_rollback_sql := IF(@gdp_rollback_safe = 1,
 'DROP TABLE IF EXISTS group_data_package', 'SELECT ''SKIPPED: data exists or structure absent'' AS rollback_decision');
PREPARE gdp_rollback_stmt FROM @gdp_rollback_sql;
EXECUTE gdp_rollback_stmt;
DEALLOCATE PREPARE gdp_rollback_stmt;

