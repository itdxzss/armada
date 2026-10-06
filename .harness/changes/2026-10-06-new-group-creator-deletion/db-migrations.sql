-- 业务开关单一事实位于任务聚合，覆盖草稿、待启动与启动后冻结。
SET @creator_delete_sql = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 'pull_task'
     AND column_name = 'is_creator_delete_after_takeover') = 0,
  'ALTER TABLE pull_task ADD COLUMN is_creator_delete_after_takeover TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''管理员接管后永久注销建群账号:0关闭 1开启；启动后冻结''',
  'SELECT 1');
PREPARE creator_delete_statement FROM @creator_delete_sql;
EXECUTE creator_delete_statement;
DEALLOCATE PREPARE creator_delete_statement;

CREATE TABLE IF NOT EXISTS pull_task_creator_deletion (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  tenant_id BIGINT NOT NULL COMMENT '所属租户',
  task_id BIGINT NOT NULL COMMENT '任务',
  group_execution_id BIGINT NOT NULL COMMENT '冻结执行行',
  creator_account_id BIGINT NOT NULL COMMENT '建群时冻结的真实账号',
  creator_identity_hash VARCHAR(255) NOT NULL COMMENT '建群账号身份 SHA256',
  creator_protocol_account_id VARCHAR(255) NOT NULL COMMENT '冻结协议账号路由',
  creator_phone VARCHAR(255) NOT NULL COMMENT '冻结的原建群号码',
  create_operation_id VARCHAR(255) NOT NULL COMMENT '绑定的建群操作',
  operation_id VARCHAR(255) NOT NULL COMMENT '注销操作唯一键，全程不变',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0预留 1已提交 2接受待清理 3未知 4失败 5完成',
  manager_account_id BIGINT COMMENT '本次接管管理号',
  group_jid VARCHAR(255) COMMENT '核验目标群',
  creation_before BIGINT COMMENT '注销前协议 Creation 时间',
  attempts INT NOT NULL DEFAULT 0 COMMENT '观察次数；不代表删除发送次数',
  deletion_result_status VARCHAR(255) COMMENT '协议持久删除结果',
  result_operation_id VARCHAR(255) COMMENT '协议结果绑定操作',
  result_identity_hash VARCHAR(255) COMMENT '协议结果绑定账号',
  evidence_json TEXT COMMENT '最近一次新鲜协议查询证据',
  observed_at BIGINT COMMENT '证据采集时间',
  submitted_at BIGINT COMMENT '已持久化唯一发送意图时间',
  deadline_at BIGINT COMMENT '本轮验证等待截止时间',
  completed_at BIGINT COMMENT '全部放行条件满足时间',
  reason_code VARCHAR(255) COMMENT '阻断原因',
  reason_message VARCHAR(255) COMMENT '运营可读原因',
  created_at BIGINT COMMENT '创建时间',
  updated_at BIGINT COMMENT '更新时间',
  UNIQUE KEY uq_ptcd_execution (tenant_id, group_execution_id),
  UNIQUE KEY uq_ptcd_operation (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_creator_deletion (
 account_id BIGINT NOT NULL PRIMARY KEY COMMENT '真实账号，永久保留审计',
 tenant_id BIGINT NOT NULL COMMENT '冻结租户',
 task_id BIGINT NOT NULL COMMENT '冻结任务',
 group_execution_id BIGINT NOT NULL COMMENT '冻结执行行',
 identity_hash CHAR(64) NOT NULL COMMENT '规范化号码 SHA256，全局防止重复身份占用',
 creator_phone VARCHAR(32) NOT NULL COMMENT '冻结规范化身份',
 protocol_account_id VARCHAR(128) NOT NULL COMMENT '冻结协议路由',
 create_operation_id VARCHAR(128) NOT NULL COMMENT '冻结建群操作',
 operation_id VARCHAR(128) DEFAULT NULL COMMENT '注销操作，绑定后不可替换',
 lifecycle VARCHAR(16) NOT NULL COMMENT 'RESERVED/DELETING/DELETED',
 created_at BIGINT NOT NULL COMMENT '预留时间',
 updated_at BIGINT NOT NULL COMMENT '状态时间',
 completed_at BIGINT DEFAULT NULL COMMENT '注销验证完成时间',
 UNIQUE KEY uq_account_creator_identity(identity_hash),
 UNIQUE KEY uq_account_creator_execution(tenant_id,group_execution_id),
 UNIQUE KEY uq_account_creator_operation(operation_id),
 KEY idx_account_creator_phone(creator_phone),
 KEY idx_account_creator_protocol(protocol_account_id),
 KEY idx_account_creator_route(tenant_id,protocol_account_id,lifecycle)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次性建群账号独占及永久注销生命周期';

-- account.ws_phone 为 VARCHAR(32)，规范化仅删除字符，不会扩大字段。
-- 相同 WhatsApp 身份的多个导入行必须共用顺序一致的索引范围锁，禁止全表函数扫描锁住无关账号。
SET @creator_identity_column_sql := IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='account' AND column_name='creator_deletion_identity_phone')=0,
  'ALTER TABLE account ADD COLUMN creator_deletion_identity_phone VARCHAR(32) GENERATED ALWAYS AS (REPLACE(REPLACE(TRIM(ws_phone),''+'',''''),'' '','''')) STORED COMMENT ''永久注销使用的规范化身份索引，不包含授权材料''',
  'SELECT 1');
PREPARE creator_identity_column_stmt FROM @creator_identity_column_sql;
EXECUTE creator_identity_column_stmt;
DEALLOCATE PREPARE creator_identity_column_stmt;
SET @creator_identity_index_sql := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='account' AND index_name='idx_account_creator_deletion_identity')=0,
  'ALTER TABLE account ADD KEY idx_account_creator_deletion_identity (creator_deletion_identity_phone,id)',
  'SELECT 1');
PREPARE creator_identity_index_stmt FROM @creator_identity_index_sql;
EXECUTE creator_identity_index_stmt;
DEALLOCATE PREPARE creator_identity_index_stmt;
