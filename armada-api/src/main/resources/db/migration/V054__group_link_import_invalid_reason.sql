-- 群链接公开邀请页无法识别群名时，导入明细新增“链接失效”失败原因。
-- 只修改 COMMENT，不修改历史数据与字段类型。

ALTER TABLE group_link_import_batch
    MODIFY COLUMN failed_rows INT NOT NULL DEFAULT 0
        COMMENT '失败总数(重复 + 格式错误 + 链接失效)';

ALTER TABLE group_link_import_detail
    MODIFY COLUMN fail_reason VARCHAR(255) DEFAULT NULL
        COMMENT '失败原因:重复/格式错误/链接失效;成功时为空';
