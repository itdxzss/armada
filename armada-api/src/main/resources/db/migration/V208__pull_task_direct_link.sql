-- 群链接模式（新）不分配管理分组；存量配置值保持原样。
ALTER TABLE pull_task
  MODIFY COLUMN creation_mode VARCHAR(32) NOT NULL DEFAULT 'PASTED_LINK'
  COMMENT '创建模式:PASTED_LINK群链接 DIRECT_LINK群链接新 RESOURCE_POOL资源池 NEW_GROUP新群';
ALTER TABLE pull_task_group_execution
  MODIFY COLUMN stage TINYINT NOT NULL DEFAULT 1
  COMMENT '业务阶段:1链接校验 2管理入群 3管理员提权 4管理拉手联系人 5邀请拉手 6拉人 7料子提权 8收口 9建群 10普通拉手链接入群';
ALTER TABLE pull_task_standard_setting
  MODIFY COLUMN manager_group_id BIGINT NULL COMMENT '管理账号分组ID;DIRECT_LINK不需要',
  MODIFY COLUMN manager_group_name VARCHAR(100) NULL COMMENT '管理分组名称快照;DIRECT_LINK不需要';

SET @direct_request_missing = (
  SELECT COUNT(*) = 0 FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'pull_task'
    AND column_name = 'creation_request_id'
);
SET @direct_request_ddl = IF(@direct_request_missing,
  'ALTER TABLE pull_task ADD COLUMN creation_request_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT ''直接创建请求UUID;存量及草稿任务为空''',
  'SELECT 1');
PREPARE direct_request_stmt FROM @direct_request_ddl;
EXECUTE direct_request_stmt;
DEALLOCATE PREPARE direct_request_stmt;

SET @direct_request_index_missing = (
  SELECT COUNT(*) = 0 FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'pull_task'
    AND index_name = 'uq_pull_task_creation_request'
);
SET @direct_request_ddl = IF(@direct_request_index_missing,
  'ALTER TABLE pull_task ADD UNIQUE KEY uq_pull_task_creation_request (tenant_id, created_by, creation_request_id)',
  'SELECT 1');
PREPARE direct_request_stmt FROM @direct_request_ddl;
EXECUTE direct_request_stmt;
DEALLOCATE PREPARE direct_request_stmt;
