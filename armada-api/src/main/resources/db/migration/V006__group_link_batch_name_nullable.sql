-- 来源文件/批次名称(batch_name)非必填:导入群链接时用户可留空。
-- 与需求及 wheel 对齐(wheel 对应列 source_file 本就可空)。改为可空,留空时存 NULL。
ALTER TABLE group_link_import_batch
    MODIFY COLUMN batch_name VARCHAR(255) DEFAULT NULL COMMENT '来源文件/批次名称(用户填,可空)';
