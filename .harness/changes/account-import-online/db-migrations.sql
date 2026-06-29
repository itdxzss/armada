-- 对应 Flyway: armada-api/src/main/resources/db/migration/V016__account_import_online_tracking.sql
-- account_import_detail 新增导入自动上线跟踪字段与查询索引。

ALTER TABLE account_import_detail
    ADD COLUMN online_phase TINYINT NOT NULL DEFAULT 0
        COMMENT '导入上线阶段:0跳过/不参与 1待派发 2已派发待回写 3已冻结终态'
        AFTER login_result,
    ADD COLUMN online_dispatched_at BIGINT DEFAULT NULL
        COMMENT '上线命令派发时间(epoch毫秒);settle 只认此时间后的协议状态事件'
        AFTER online_phase,
    ADD COLUMN login_settled_at BIGINT DEFAULT NULL
        COMMENT '本次导入登录结果冻结时间(epoch毫秒)'
        AFTER online_dispatched_at,
    ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0
        COMMENT '上线派发重试次数'
        AFTER login_settled_at,
    ADD COLUMN login_reason VARCHAR(255) DEFAULT NULL
        COMMENT '登录失败/异常原因或派发错误'
        AFTER dispatch_attempts,
    ADD KEY idx_import_detail_online_phase (online_phase, id),
    ADD KEY idx_tenant_batch_login_result (tenant_id, batch_id, login_result),
    ADD KEY idx_tenant_batch_online_phase (tenant_id, batch_id, online_phase);
