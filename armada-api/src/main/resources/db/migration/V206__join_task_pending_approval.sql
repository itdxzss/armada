-- JoinTaskResult 的恢复子记录，仅实际返回待审核时创建；提权字段保持原语义。
CREATE TABLE IF NOT EXISTS join_task_approval (
  result_id BIGINT NOT NULL COMMENT '进群明细主键，每条明细最多一次审核恢复',
  tenant_id BIGINT NOT NULL COMMENT '所属租户',
  join_task_id BIGINT NOT NULL COMMENT '所属进群任务',
  stage TINYINT NOT NULL COMMENT '1解析 2关闭审核 3查成员申请 4批准 5续进群 6核实 7成功 8失败',
  in_flight BOOLEAN NOT NULL DEFAULT FALSE COMMENT '当前阶段是否已领取执行',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '领取版本，拒绝重复迟到结果',
  group_jid VARCHAR(64) NOT NULL DEFAULT '' COMMENT '本条邀请解析出的群身份',
  actor_account_id BIGINT NULL COMMENT '关闭审核的原管理员账号，不替代提权执行者',
  actor_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '原管理员号码身份快照',
  target_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '当前入群账号号码身份快照',
  target_protocol_account_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '当前入群账号协议身份快照',
  pending_jid VARCHAR(128) NOT NULL DEFAULT '' COMMENT '本条待审申请成员标识',
  reason VARCHAR(255) NOT NULL DEFAULT '' COMMENT '处理阶段说明或明确失败原因',
  next_execute_at BIGINT NULL COMMENT '下次处理或当前执行租约截止时间',
  deadline_at BIGINT NOT NULL COMMENT '本条自动处理总截止时间',
  updated_at BIGINT NOT NULL COMMENT '更新时间epoch毫秒',
  PRIMARY KEY (result_id),
  KEY idx_join_approval_due (stage,next_execute_at,result_id),
  KEY idx_join_approval_task (tenant_id,join_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='进群待审核自动处理进度';
