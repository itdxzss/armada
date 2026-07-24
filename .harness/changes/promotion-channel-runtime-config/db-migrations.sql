-- 评审副本；实际执行入口为 Flyway V066__promotion_channel_runtime_config.sql。
ALTER TABLE promotion_channel
    ADD COLUMN theme_color VARCHAR(7) NOT NULL DEFAULT '#e11d48'
        COMMENT '落地页主题色,六位十六进制颜色,例如 #e11d48' AFTER promotion_domain_id,
    ADD COLUMN is_app_download_shown TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '是否展示落地页底部应用下载区域:0=否 1=是,例如 1' AFTER theme_color;
