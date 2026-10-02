-- 新开关只影响新建/编辑时显式开启的任务，历史任务默认关闭。
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='join_task' AND column_name='is_clear_admins_and_leave_enabled')=0,
  'ALTER TABLE join_task ADD COLUMN is_clear_admins_and_leave_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''提权成功后清空其他管理员并由原号退群''', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 清理是进群明细的执行子记录；不复制进群/提权状态或群成员当前事实。
CREATE TABLE IF NOT EXISTS join_task_cleanup (
  result_id BIGINT NOT NULL COMMENT '关联进群明细主键，一条明细最多一次清理',
  tenant_id BIGINT NOT NULL COMMENT '租户ID',
  join_task_id BIGINT NOT NULL COMMENT '所属进群任务',
  group_jid VARCHAR(64) NOT NULL COMMENT '目标群真实JID',
  status TINYINT NOT NULL COMMENT '1待处理 2读取名单中 3待踢人 4踢人中 5待退群 6退群中 7成功 8失败',
  context_json MEDIUMTEXT NOT NULL COMMENT '固定清理目标及完成游标，仅保存本次操作计划',
  reason VARCHAR(255) NOT NULL DEFAULT '' COMMENT '失败阶段目标及原因',
  next_execute_at BIGINT NULL COMMENT '下次执行或在途操作截止时间epoch毫秒',
  active_group_jid VARCHAR(64) NULL COMMENT '执行中群互斥键，终态清空',
  updated_at BIGINT NOT NULL COMMENT '更新时间epoch毫秒',
  PRIMARY KEY (result_id),
  UNIQUE KEY uq_join_cleanup_active_group (tenant_id,active_group_jid),
  KEY idx_join_cleanup_due (status,next_execute_at,result_id),
  KEY idx_join_cleanup_task (tenant_id,join_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='进群后清理管理员并退群的执行子记录';
