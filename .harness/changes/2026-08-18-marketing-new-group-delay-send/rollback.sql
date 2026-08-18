-- 本文件是生产回滚操作手册，不是可直接执行的 down SQL。
-- Flyway 已记录 V130 后，手工删除列不会清理 flyway_schema_history；未来重新上线时也不会自动重建。
-- 首选回滚方式：只回退应用版本，保留 V130 的向后兼容新增列和索引。

-- 物理回滚前必须依次完成：
-- 1. 停止新版本入口和延迟扫描，确保不再产生 WAITING。
-- 2. 按租户、任务盘点下列记录，由业务选择“当前版本排空”或“统一标记 SKIPPED”。
SELECT tenant_id, marketing_task_id, COUNT(*) AS waiting_count
FROM marketing_task_send_attempt
WHERE status = 4
GROUP BY tenant_id, marketing_task_id
ORDER BY tenant_id, marketing_task_id;

-- 3. 如果业务选择取消等待，必须通过经过评审的正式数据迁移将 status=4 改为 SKIPPED，
--    reason_code=FEATURE_ROLLBACK，并补齐 reason_message/result_at/attempted_at。
-- 4. 确认 SELECT COUNT(*) FROM marketing_task_send_attempt WHERE status = 4; 返回 0。
-- 5. 先部署完全不引用新字段的旧应用。
-- 6. 如仍要求删除结构，另建新的正式 Flyway 前滚迁移删除索引和列；不要手工执行 ALTER TABLE。
