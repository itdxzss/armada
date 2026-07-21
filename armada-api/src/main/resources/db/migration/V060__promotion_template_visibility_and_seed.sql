-- 先检查固定模板的主键和租户内编码是否已被其他数据占用。
-- 临时表预置 guard_key=1；发现冲突时再次写入 1，让迁移明确失败，避免静默覆盖存量数据。
DROP TEMPORARY TABLE IF EXISTS v060_template_seed_guard;
CREATE TEMPORARY TABLE v060_template_seed_guard (
    guard_key TINYINT NOT NULL PRIMARY KEY
) ENGINE = MEMORY;

INSERT INTO v060_template_seed_guard (guard_key) VALUES (1);

INSERT INTO v060_template_seed_guard (guard_key)
SELECT 1
FROM promotion_landing_template
WHERE (
        id IN (130, 40, 39, 38, 37)
        OR (tenant_id = 1 AND template_code IN
            ('base_sex2', 'basic_earn', 'basic_party_man', 'basic_party_female', 'base_sex'))
    )
  AND NOT (
        (id = 130 AND tenant_id = 1 AND template_code = 'base_sex2')
        OR (id = 40 AND tenant_id = 1 AND template_code = 'basic_earn')
        OR (id = 39 AND tenant_id = 1 AND template_code = 'basic_party_man')
        OR (id = 38 AND tenant_id = 1 AND template_code = 'basic_party_female')
        OR (id = 37 AND tenant_id = 1 AND template_code = 'base_sex')
    )
LIMIT 1;

DROP TEMPORARY TABLE v060_template_seed_guard;

-- 为后续“子账号可见”功能预留字段。本期不提供修改接口，默认全部可见。
ALTER TABLE promotion_landing_template
    ADD COLUMN is_subaccount_visible TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '是否对子账号可见:0=不可见 1=可见,例如 1'
        AFTER supported_params;

-- 截图中的固定模板先初始化到 tenant_id=1；其他租户后续按租户复制或配置自己的模板。
-- 每条数据仅在其固定“主键 + 租户 + 编码”不存在时插入，避免覆盖已经存在的同一模板。
-- 参数保存稳定程序代码，接口同时返回对应中文标签。
INSERT INTO promotion_landing_template
    (id, tenant_id, template_code, template_name, preview_uri, supported_params,
     is_subaccount_visible, status, remark, created_by, updated_by,
     created_at, updated_at, deleted_at)
SELECT 130, 1, 'base_sex2', '约会二代', '/preview/base_sex2.png',
       '["themeColor"]', 1, 1, NULL, NULL, NULL, 1782310803000, 1784254511000, NULL
WHERE NOT EXISTS (
    SELECT 1 FROM promotion_landing_template
    WHERE id = 130 AND tenant_id = 1 AND template_code = 'base_sex2'
);

INSERT INTO promotion_landing_template
    (id, tenant_id, template_code, template_name, preview_uri, supported_params,
     is_subaccount_visible, status, remark, created_by, updated_by,
     created_at, updated_at, deleted_at)
SELECT 40, 1, 'basic_earn', '基础领奖', '/preview/basic_earn.png',
       '["themeColor"]', 1, 1, '1231', NULL, NULL, 1779719349000, 1779719349000, NULL
WHERE NOT EXISTS (
    SELECT 1 FROM promotion_landing_template
    WHERE id = 40 AND tenant_id = 1 AND template_code = 'basic_earn'
);

INSERT INTO promotion_landing_template
    (id, tenant_id, template_code, template_name, preview_uri, supported_params,
     is_subaccount_visible, status, remark, created_by, updated_by,
     created_at, updated_at, deleted_at)
SELECT 39, 1, 'basic_party_man', '基础约会-投男粉', '/preview/basic_party_man.png',
       '["themeColor", "showAppDownload"]', 1, 1, NULL, NULL, NULL, 1779719349000, 1779719349000, NULL
WHERE NOT EXISTS (
    SELECT 1 FROM promotion_landing_template
    WHERE id = 39 AND tenant_id = 1 AND template_code = 'basic_party_man'
);

INSERT INTO promotion_landing_template
    (id, tenant_id, template_code, template_name, preview_uri, supported_params,
     is_subaccount_visible, status, remark, created_by, updated_by,
     created_at, updated_at, deleted_at)
SELECT 38, 1, 'basic_party_female', '基础约会-投女粉', '/preview/basic_party_female.png',
       '["themeColor", "showAppDownload"]', 1, 1, NULL, NULL, NULL, 1779719349000, 1779719349000, NULL
WHERE NOT EXISTS (
    SELECT 1 FROM promotion_landing_template
    WHERE id = 38 AND tenant_id = 1 AND template_code = 'basic_party_female'
);

INSERT INTO promotion_landing_template
    (id, tenant_id, template_code, template_name, preview_uri, supported_params,
     is_subaccount_visible, status, remark, created_by, updated_by,
     created_at, updated_at, deleted_at)
SELECT 37, 1, 'base_sex', '约会二代', '/preview/base_sex.png',
       '["themeColor"]', 1, 1, NULL, NULL, NULL, 1779719349000, 1779719349000, NULL
WHERE NOT EXISTS (
    SELECT 1 FROM promotion_landing_template
    WHERE id = 37 AND tenant_id = 1 AND template_code = 'base_sex'
);
