-- 未执行。仅在关闭创建、停止全部任务、确认 Outbox/Kafka 无在途并完成审计备份后，
-- 由明确授权的环境回滚执行。此操作不能撤销 WhatsApp 已完成的联系人保存。
-- 不删除 Flyway 历史记录；生产回滚须另建前向 Flyway 迁移，避免 checksum/历史漂移。
DROP TABLE IF EXISTS account_mutual_contact_item;
DROP TABLE IF EXISTS account_mutual_contact_task;
