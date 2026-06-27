-- 对齐群链接导入统计/明细列注释到 V010 后的新语义。
-- 只修改 COMMENT,不改数据与字段类型。

ALTER TABLE group_link_import_batch
    MODIFY COLUMN inserted_rows INT NOT NULL DEFAULT 0 COMMENT '新增成功数量',
    MODIFY COLUMN adopted_rows INT NOT NULL DEFAULT 0 COMMENT '收编已有群入口的成功数量',
    MODIFY COLUMN failed_rows INT NOT NULL DEFAULT 0 COMMENT '失败总数(重复 + 格式错误)';

ALTER TABLE group_link_import_detail
    MODIFY COLUMN group_name VARCHAR(128) DEFAULT NULL COMMENT '群名称(保留旧列,导入链接不再写)',
    MODIFY COLUMN result TINYINT NOT NULL COMMENT '导入结果:1=成功 2=失败',
    MODIFY COLUMN fail_reason VARCHAR(255) DEFAULT NULL COMMENT '失败原因:重复/格式错误;成功时为空',
    MODIFY COLUMN group_link_id BIGINT DEFAULT NULL COMMENT '成功时关联group_link.id;失败时为空';
