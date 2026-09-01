-- 超链账号遇到可恢复的触达限制后退出当前任务，usage_status=6。
-- V158 的约束只允许 1..5；V173 已在测试环境执行，必须用后续迁移扩展约束。
ALTER TABLE hyperlink_task_account_usage
    DROP CHECK ck_hyperlink_usage_status,
    MODIFY COLUMN usage_status TINYINT NOT NULL DEFAULT 1
        COMMENT '1可用 2达上限 3封号 4失效 5人工停用 6操作受限',
    ADD CONSTRAINT ck_hyperlink_usage_status
        CHECK (usage_status IN (1,2,3,4,5,6));
