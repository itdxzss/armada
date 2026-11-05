-- 仅供另行授权后执行。先停止任务，确认在途命令/回执已收敛并备份审计事实。
-- 回滚不能撤销已发消息。同步处理 Flyway 历史应遵循部署流程，禁止运行时自动执行。
DROP TABLE IF EXISTS account_mutual_chat_item;
DROP TABLE IF EXISTS account_mutual_chat_task;
