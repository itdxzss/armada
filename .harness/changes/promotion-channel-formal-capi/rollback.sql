-- 仅适用于已明确停止正式 CAPI、已备份并确认无需保留待投递事件的专项回滚窗口。
-- 执行前必须关闭 PROMOTION_CAPI_SCHEDULER_ENABLED，并确认应用版本已回滚。
DROP TABLE IF EXISTS promotion_capi_event_outbox;
