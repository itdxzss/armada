-- Only restore the column comment after the application rollback.
-- Do not release phase 4 rows without proof of phone logout.
ALTER TABLE account_import_detail
    MODIFY COLUMN online_phase TINYINT NOT NULL DEFAULT 0
    COMMENT '导入上线阶段:0跳过/不参与 1待派发 2已派发待回写 3已冻结终态';
