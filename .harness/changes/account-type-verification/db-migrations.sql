-- 正式迁移以 Flyway V167__account_type_verification.sql 为准。
ALTER TABLE account
    ADD COLUMN declared_account_type TINYINT NULL COMMENT '导入申报账号类型:1个人 2商业' AFTER account_type,
    ADD COLUMN account_type_verify_status TINYINT NOT NULL DEFAULT 4 COMMENT '账号类型校验状态:0待校验 1已匹配 2已纠正 3无法确认 4存量未校验' AFTER declared_account_type,
    ADD COLUMN account_type_verify_source TINYINT NULL COMMENT '账号类型校验来源:1凭据元数据 2配对结果 3商业资料查询' AFTER account_type_verify_status,
    ADD COLUMN account_type_verified_at BIGINT NULL COMMENT '账号类型最后校验时间(epoch毫秒)' AFTER account_type_verify_source,
    ADD COLUMN business_verification_level TINYINT NULL COMMENT '商业认证级别:1蓝标高认证 2明确非高认证;NULL未确认' AFTER account_type_verified_at,
    ADD COLUMN business_verification_source TINYINT NULL COMMENT '商业认证识别来源:1凭据元数据 2配对结果 3商业资料查询' AFTER business_verification_level,
    ADD COLUMN business_verification_verified_at BIGINT NULL COMMENT '商业认证级别最后确认时间(epoch毫秒)' AFTER business_verification_source;

UPDATE account
SET declared_account_type = account_type
WHERE declared_account_type IS NULL;

ALTER TABLE account
    MODIFY COLUMN declared_account_type TINYINT NOT NULL COMMENT '导入申报账号类型:1个人 2商业';
