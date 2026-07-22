-- 回滚前必须确认落地页已停止读取这两个运行时配置字段。
ALTER TABLE promotion_channel
    DROP COLUMN is_app_download_shown,
    DROP COLUMN theme_color;
