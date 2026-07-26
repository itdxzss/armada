-- V066：渠道落地页运行时展示配置；字段属于渠道聚合，由创建/编辑接口维护。

SET @promotion_channel_theme_color_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_channel'
      AND column_name = 'theme_color'
);
SET @promotion_channel_theme_color_sql := IF(
    @promotion_channel_theme_color_exists = 0,
    'ALTER TABLE promotion_channel ADD COLUMN theme_color VARCHAR(7) NOT NULL DEFAULT ''#e11d48'' COMMENT ''落地页主题色,六位十六进制颜色,例如 #e11d48'' AFTER promotion_domain_id',
    'SELECT 1'
);
PREPARE promotion_channel_theme_color_stmt FROM @promotion_channel_theme_color_sql;
EXECUTE promotion_channel_theme_color_stmt;
DEALLOCATE PREPARE promotion_channel_theme_color_stmt;

SET @promotion_channel_app_download_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'promotion_channel'
      AND column_name = 'is_app_download_shown'
);
SET @promotion_channel_app_download_sql := IF(
    @promotion_channel_app_download_exists = 0,
    'ALTER TABLE promotion_channel ADD COLUMN is_app_download_shown TINYINT(1) NOT NULL DEFAULT 1 COMMENT ''是否展示落地页底部应用下载区域:0=否 1=是,例如 1'' AFTER theme_color',
    'SELECT 1'
);
PREPARE promotion_channel_app_download_stmt FROM @promotion_channel_app_download_sql;
EXECUTE promotion_channel_app_download_stmt;
DEALLOCATE PREPARE promotion_channel_app_download_stmt;
