-- 运行态回退顺序：停止新建含引用的任务，暂停/收敛已存在的引用任务及 outbox，再回退程序。
-- 新列可空，旧代码不读取。保留列和发送事实，不 DROP 引用快照、不改 Flyway 已应用记录。
-- 不得让旧执行器接管含 replyToStepId 的任务，防止忽略关系而错误发送。
SELECT 'retain quote_context_json and reply_fallback_reason; drain quoted-reply work before binary rollback' AS rollback_policy;
