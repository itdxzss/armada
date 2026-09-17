-- 仅在回退使用新增列的后端并停止新客户端后执行；删除列会丢失失败分类与详情。
ALTER TABLE account_registration_item DROP COLUMN failure_detail;
ALTER TABLE account_registration_item DROP COLUMN failure_kind;
