-- 释放审计属于账号生命周期聚合；同一身份可多次预留和释放，不设业务唯一键。
CREATE TABLE IF NOT EXISTS account_creator_deletion_release (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '释放历史主键',
 account_id BIGINT NOT NULL COMMENT '原预留账号',
 tenant_id BIGINT NOT NULL COMMENT '原冻结租户',
 task_id BIGINT NOT NULL COMMENT '原冻结任务',
 group_execution_id BIGINT NOT NULL COMMENT '原冻结执行行',
 identity_hash CHAR(64) NOT NULL COMMENT '原规范化号码 SHA256',
 creator_phone VARCHAR(32) NOT NULL COMMENT '原冻结规范化身份',
 protocol_account_id VARCHAR(128) NOT NULL COMMENT '原冻结协议路由',
 create_operation_id VARCHAR(128) NOT NULL COMMENT '原冻结建群操作',
 operation_id VARCHAR(128) DEFAULT NULL COMMENT '原注销操作，安全释放时为空',
 lifecycle VARCHAR(16) NOT NULL COMMENT '释放前生命周期 RESERVED',
 created_at BIGINT NOT NULL COMMENT '原预留时间',
 updated_at BIGINT NOT NULL COMMENT '原状态时间',
 completed_at BIGINT DEFAULT NULL COMMENT '原注销验证完成时间',
 released_at BIGINT NOT NULL COMMENT '安全释放时间',
 release_reason VARCHAR(64) NOT NULL COMMENT '触发释放的原因',
 execution_status_at_release TINYINT COMMENT '释放时执行状态:4完成 5失败 6放弃',
 KEY idx_creator_release_account (account_id),
 KEY idx_creator_release_execution (tenant_id, group_execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='终态且注销从未提交的建群账号预留释放历史';

ALTER TABLE pull_task_creator_deletion MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
 COMMENT '0预留 1已提交 2接受待清理 3未知 4失败 5完成 6预留已释放（未注销）';
