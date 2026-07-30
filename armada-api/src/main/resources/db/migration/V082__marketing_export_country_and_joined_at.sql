-- 营销导出国家主数据、共享区号唯一映射与进群成功时间。
-- 只新增主数据/表/列，不修改既有迁移。

-- CLDR/ITU 均单列 Diego Garcia；原清单缺少该地区，补齐后营销导出为 249 项。
INSERT IGNORE INTO country
    (iso2, name_zh, name_en, phone_prefix, flag,
     is_enabled, is_ip_supported, sort_order, created_at, updated_at)
VALUES
    ('DG', '迪戈加西亚岛', 'Diego Garcia', '+246', '🇩🇬', 1, 0, 2490, 1785254400000, 1785254400000);

CREATE TABLE IF NOT EXISTS country_phone_prefix_mapping (
    normalized_prefix VARCHAR(16) NOT NULL COMMENT '只包含数字的共享国际区号',
    country_iso2      CHAR(2)     NOT NULL COMMENT '该共享区号唯一展示的国家/地区 ISO2',
    remark            VARCHAR(255)         DEFAULT NULL COMMENT '映射说明',
    created_at        BIGINT      NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at        BIGINT      NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (normalized_prefix),
    KEY idx_country_phone_prefix_mapping_iso2 (country_iso2)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '共享国际区号唯一国家展示配置';

INSERT INTO country_phone_prefix_mapping
    (normalized_prefix, country_iso2, remark, created_at, updated_at)
VALUES
    ('1',   'US', 'CA/US 共享 +1', 1785254400000, 1785254400000),
    ('7',   'RU', 'KZ/RU 共享 +7', 1785254400000, 1785254400000),
    ('47',  'NO', 'NO/SJ 共享 +47', 1785254400000, 1785254400000),
    ('61',  'AU', 'AU/CX/CC 共享 +61', 1785254400000, 1785254400000),
    ('64',  'NZ', 'NZ/PN 共享 +64', 1785254400000, 1785254400000),
    ('212', 'MA', 'MA/EH 共享 +212', 1785254400000, 1785254400000),
    ('246', 'DG', 'IO/DG 共享 +246', 1785254400000, 1785254400000),
    ('262', 'RE', 'YT/RE 共享 +262', 1785254400000, 1785254400000),
    ('290', 'SH', 'SH/TA 共享 +290', 1785254400000, 1785254400000),
    ('358', 'FI', 'AX/FI 共享 +358', 1785254400000, 1785254400000),
    ('500', 'FK', 'FK/GS 共享 +500', 1785254400000, 1785254400000),
    ('590', 'GP', 'GP/BL/MF 共享 +590', 1785254400000, 1785254400000),
    ('599', 'CW', 'BQ/CW 共享 +599', 1785254400000, 1785254400000),
    ('672', 'AQ', 'AQ/NF 共享 +672', 1785254400000, 1785254400000)
ON DUPLICATE KEY UPDATE
    country_iso2 = VALUES(country_iso2),
    remark = VALUES(remark),
    updated_at = VALUES(updated_at);

SET @joined_at_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'join_task_result'
      AND COLUMN_NAME = 'joined_at'
);
SET @joined_at_ddl := IF(
    @joined_at_exists = 0,
    'ALTER TABLE join_task_result ADD COLUMN joined_at BIGINT NULL COMMENT ''受控账号首次明确进群成功时间(epoch毫秒)'' AFTER promoted_at',
    'SELECT 1'
);
PREPARE joined_at_stmt FROM @joined_at_ddl;
EXECUTE joined_at_stmt;
DEALLOCATE PREPARE joined_at_stmt;
