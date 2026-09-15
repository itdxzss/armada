-- 本次Flyway迁移的审计副本；共享库仅由应用Flyway执行，勿手工运行本文件。

-- BEGIN V191__group_data_package.sql
-- 拉群数据包独立资源聚合；原任务成员继续保存执行历史。
CREATE TABLE IF NOT EXISTS group_data_package (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据包ID',
 tenant_id BIGINT NOT NULL COMMENT '租户ID',
 name VARCHAR(128) NOT NULL COMMENT '数据包名称',
 remark VARCHAR(512) NULL COMMENT '备注',
 generation INT NOT NULL DEFAULT 1 COMMENT '当前号码代次，覆盖导入递增',
 version INT NOT NULL DEFAULT 1 COMMENT '名称备注乐观锁版本',
 last_used_at BIGINT NULL COMMENT '最近实际任务领取时间',
 created_by BIGINT NOT NULL COMMENT '创建人审计',
 created_at BIGINT NOT NULL COMMENT '创建时间毫秒',
 updated_at BIGINT NOT NULL COMMENT '更新时间毫秒',
 deleted_at BIGINT NULL COMMENT '软删时间毫秒',
 PRIMARY KEY (id), KEY idx_group_package_tenant (tenant_id, deleted_at, id),
 KEY idx_group_package_created (tenant_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拉群数据包';

CREATE TABLE IF NOT EXISTS group_data_package_import (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '导入ID',
 tenant_id BIGINT NOT NULL COMMENT '租户ID',
 package_id BIGINT NOT NULL COMMENT '数据包ID',
 generation INT NULL COMMENT '本次写入的号码代次',
 mode TINYINT NOT NULL COMMENT '1追加 2覆盖',
 file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
 status TINYINT NOT NULL COMMENT '1处理中 2成功 3失败',
 total_rows INT NOT NULL DEFAULT 0 COMMENT '物理行数',
 accepted_rows INT NOT NULL DEFAULT 0 COMMENT '实际新增有效号码数',
 invalid_rows INT NOT NULL DEFAULT 0 COMMENT '非法行数',
 duplicated_rows INT NOT NULL DEFAULT 0 COMMENT '文件内及包内重复行数',
 privacy_filtered_rows INT NOT NULL DEFAULT 0 COMMENT '可靠隐私拒绝历史命中数',
 failure_reason VARCHAR(512) NULL COMMENT '脱敏导入失败原因',
 created_by BIGINT NOT NULL COMMENT '导入人审计',
 created_at BIGINT NOT NULL COMMENT '开始时间毫秒',
 finished_at BIGINT NULL COMMENT '完成时间毫秒',
 PRIMARY KEY (id), KEY idx_group_package_import (tenant_id, package_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拉群数据包导入审计';

CREATE TABLE IF NOT EXISTS group_data_package_phone (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '号码ID',
 tenant_id BIGINT NOT NULL COMMENT '租户ID',
 package_id BIGINT NOT NULL COMMENT '数据包ID',
 generation INT NOT NULL COMMENT '号码代次',
 source_import_id BIGINT NOT NULL COMMENT '来源导入ID',
 phone VARCHAR(15) NOT NULL COMMENT '7至15位国际号码',
 country_iso2 CHAR(2) NULL COMMENT '导入时识别国家',
 member_seq INT NOT NULL COMMENT '代次内稳定顺序',
 source_line_no INT NOT NULL COMMENT '首次有效出现物理行号',
 admin_required TINYINT NOT NULL DEFAULT 0 COMMENT '0普通成员 1入群后设管理员',
 status TINYINT NOT NULL DEFAULT 1 COMMENT '1未用 2占用 3入群成功 4可重试失败 5隐私拒绝 6未注册 7待确认',
 claimed_task_id BIGINT NULL COMMENT '当前分配的拉群任务ID',
 claimed_execution_seq INT NULL COMMENT '当前任务内稳定逻辑执行序号，换群不变',
 allocation_version BIGINT NOT NULL DEFAULT 0 COMMENT '分配代次，防止旧结果回写新分配',
 created_at BIGINT NOT NULL COMMENT '创建时间毫秒',
 updated_at BIGINT NOT NULL COMMENT '状态更新时间毫秒',
 PRIMARY KEY (id), UNIQUE KEY uq_group_package_phone (tenant_id, package_id, generation, phone),
 UNIQUE KEY uq_group_package_seq (tenant_id, package_id, generation, member_seq),
 KEY idx_group_package_pick (tenant_id, package_id, generation, status, member_seq),
 KEY idx_group_package_history (tenant_id, phone, status, updated_at),
 KEY idx_group_package_claim (tenant_id, claimed_task_id, claimed_execution_seq, id),
 CONSTRAINT ck_group_package_status CHECK (status IN (1,2,3,4,5,6,7))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拉群数据包号码与当前分配';

CREATE TABLE IF NOT EXISTS group_data_package_stat (
 package_id BIGINT NOT NULL COMMENT '包ID，同时为主键',
 tenant_id BIGINT NOT NULL COMMENT '租户ID',
 generation INT NOT NULL COMMENT '当前统计代次',
 primary_country_iso2 CHAR(2) NULL COMMENT '号码最多国家；数量相同时按ISO2排序',
 continent VARCHAR(24) NULL COMMENT '主要国家大洲',
 total_count INT NOT NULL DEFAULT 0 COMMENT '当前代总量',
 unused_count INT NOT NULL DEFAULT 0 COMMENT '当前未用数',
 claimed_count INT NOT NULL DEFAULT 0 COMMENT '当前占用数',
 success_count INT NOT NULL DEFAULT 0 COMMENT '当前入群成功数',
 failed_count INT NOT NULL DEFAULT 0 COMMENT '明确失败总数，含隐私和未注册',
 privacy_rejected_count INT NOT NULL DEFAULT 0 COMMENT '隐私拒绝失败子集',
 unregistered_count INT NOT NULL DEFAULT 0 COMMENT '真实未注册失败子集',
 unknown_count INT NOT NULL DEFAULT 0 COMMENT '结果待确认数',
 PRIMARY KEY (package_id), KEY idx_group_package_country (tenant_id, primary_country_iso2, package_id),
 CONSTRAINT ck_group_package_stat CHECK (total_count >= 0 AND unused_count >= 0
 AND claimed_count >= 0 AND success_count >= 0 AND failed_count >= 0
 AND privacy_rejected_count >= 0 AND unregistered_count >= 0 AND unknown_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拉群数据包当前代统计读模型';

-- 页面在任务中心、拉群任务相邻，普通角色沿用显式授权规则。
INSERT IGNORE INTO sys_menu
 (tenant_id,parent_id,menu_name,menu_key,menu_type,route_path,component_path,
  perm_key,icon,sort_no,status,created_at,updated_at)
SELECT tenant_id,parent_id,'拉群数据包','GroupDataPackage','M','/resource/group-data-package',
 'resource/group-data-package/index','tenant:group_data_package:view','ep:files',sort_no + 1,1,created_at,updated_at
FROM sys_menu WHERE menu_key='TaskPull';

INSERT IGNORE INTO sys_menu
 (tenant_id,parent_id,menu_name,menu_key,menu_type,route_path,component_path,
  perm_key,icon,sort_no,status,created_at,updated_at)
SELECT m.tenant_id,m.id,p.label,p.menu_key,'B',NULL,NULL,p.perm,NULL,p.sort_no,1,m.created_at,m.updated_at
FROM sys_menu m CROSS JOIN (
 SELECT '创建数据包' label,'GroupDataPackageCreate' menu_key,'tenant:group_data_package:create' perm,10 sort_no
 UNION ALL SELECT '导入号码','GroupDataPackageImport','tenant:group_data_package:import',20
 UNION ALL SELECT '编辑及重置','GroupDataPackageEdit','tenant:group_data_package:edit',30
 UNION ALL SELECT '导出号码','GroupDataPackageExport','tenant:group_data_package:export',40
 UNION ALL SELECT '删除数据包','GroupDataPackageDelete','tenant:group_data_package:delete',50
) p WHERE m.menu_key='GroupDataPackage';
-- END V191__group_data_package.sql

-- BEGIN V192__pull_task_group_data_package_source.sql
-- 拉群保留原执行模型，仅记录独立资源的可追溯来源。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND column_name = 'source_package_id') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN source_package_id BIGINT NULL COMMENT ''独立数据包来源；原文件上传为空''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND column_name = 'source_package_generation') = 0,
    'ALTER TABLE pull_task_group_execution ADD COLUMN source_package_generation INT NULL COMMENT ''草稿冻结的数据包代次；防覆盖后的陈旧提交''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_material_member' AND column_name = 'source_package_phone_id') = 0,
    'ALTER TABLE pull_task_material_member ADD COLUMN source_package_phone_id BIGINT NULL COMMENT ''数据包号码来源；换群重试原样保留''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'pull_task_material_member' AND column_name = 'source_allocation_version') = 0,
    'ALTER TABLE pull_task_material_member ADD COLUMN source_allocation_version BIGINT NULL COMMENT ''任务领取资源的分配版本；拒绝旧分配回写''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pull_task_group_execution' AND index_name = 'idx_execution_package_source') = 0,
    'CREATE INDEX idx_execution_package_source ON pull_task_group_execution (tenant_id, source_package_id, task_id, seq, attempt_no)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'pull_task_material_member' AND index_name = 'idx_material_package_source') = 0,
    'CREATE INDEX idx_material_package_source ON pull_task_material_member (tenant_id, group_execution_id, source_package_phone_id, source_allocation_version)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- END V192__pull_task_group_data_package_source.sql

