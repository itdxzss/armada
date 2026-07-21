-- 渠道国家引用改为保存 CountryOptionVO.value：真实国家使用 ISO2，混合选项使用 MIXED。
-- 使用后续版本迁移而不是修改 V058，避免已执行环境出现 Flyway checksum 不一致。

ALTER TABLE promotion_channel
    ADD COLUMN target_country_value VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '目标国家下拉值:ISO2或MIXED,例如 IN',
    ADD COLUMN preselected_country_value VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '落地页预选区号国家ISO2,不允许MIXED,例如 IN';

-- 先回填存量数据：原目标国家 NULL 的业务语义就是“混合（不限国家）”。
-- 若历史 ID 已失效，目标列会保持 NULL，后续 NOT NULL 会让迁移失败并阻止静默丢失国家语义。
UPDATE promotion_channel AS pc
LEFT JOIN country AS tc ON tc.id = pc.target_country_id
LEFT JOIN country AS pc_country ON pc_country.id = pc.preselected_country_id
SET pc.target_country_value = CASE
        WHEN pc.target_country_id IS NULL THEN 'MIXED'
        ELSE UPPER(tc.iso2)
    END,
    pc.preselected_country_value = UPPER(pc_country.iso2);

ALTER TABLE promotion_channel
    MODIFY COLUMN target_country_value VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '目标国家下拉值:ISO2或MIXED,例如 IN',
    MODIFY COLUMN preselected_country_value VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '落地页预选区号国家ISO2,不允许MIXED,例如 IN',
    DROP COLUMN target_country_id,
    DROP COLUMN preselected_country_id;
