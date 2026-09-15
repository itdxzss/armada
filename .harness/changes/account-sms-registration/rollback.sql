-- 仅允许在确认从未产生真实采购且任务表为空后执行；有真实订单时保留审计表。
-- 首先关闭 armada.account.registration.enabled 和 scheduler.enabled。
DROP TABLE IF EXISTS account_registration_item;
DROP TABLE IF EXISTS account_registration_task;
