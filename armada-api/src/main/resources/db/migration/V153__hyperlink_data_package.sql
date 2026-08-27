-- 超链营销一期数据包资源池。只创建数据包主表、号码成员、统计读模型和导入审计四张表。

CREATE TABLE IF NOT EXISTS data_package (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据包主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    package_name VARCHAR(128) NOT NULL COMMENT '数据包名称',
    remark VARCHAR(255) DEFAULT NULL COMMENT '数据包备注',
    current_generation INT NOT NULL DEFAULT 1 COMMENT '当前可见号码代次;覆盖成功后原子递增',
    phone_count INT NOT NULL DEFAULT 0 COMMENT '当前代号码总数',
    version INT NOT NULL DEFAULT 1 COMMENT '名称和备注乐观锁版本;统计更新不修改',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_by BIGINT DEFAULT NULL COMMENT '删除人user_id',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删时间(epoch毫秒);NULL为未删',
    is_active TINYINT GENERATED ALWAYS AS (
        CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END
    ) STORED COMMENT '软删唯一键辅助;有效行为1,已删行为NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_data_package_name (tenant_id, package_name, is_active),
    KEY idx_data_package_tenant (tenant_id, deleted_at, id),
    KEY idx_data_package_created (tenant_id, created_at, id),
    CONSTRAINT ck_data_package_generation CHECK (current_generation > 0),
    CONSTRAINT ck_data_package_phone_count CHECK (phone_count >= 0),
    CONSTRAINT ck_data_package_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链营销数据包主表';

CREATE TABLE IF NOT EXISTS data_package_phone (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '号码成员主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    data_package_id BIGINT NOT NULL COMMENT '数据包ID',
    generation INT NOT NULL COMMENT '所属号码代次',
    source_import_id BIGINT NOT NULL COMMENT '产生该成员的导入批次ID',
    phone VARCHAR(32) NOT NULL COMMENT '完整国际号码;只含数字',
    country_iso2 CHAR(2) DEFAULT NULL COMMENT '导入时快照的国家ISO2;无法识别为NULL',
    pool_status TINYINT NOT NULL DEFAULT 1
        COMMENT '号码池状态:1未使用 2已领取 3当前单钩 4已送达 5可重试失败 6未注册',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_data_package_phone (tenant_id, data_package_id, generation, phone),
    KEY idx_data_package_phone_pick
        (tenant_id, data_package_id, generation, pool_status, id),
    KEY idx_data_package_phone_country
        (tenant_id, data_package_id, generation, country_iso2, id),
    KEY idx_data_package_phone_import (tenant_id, source_import_id, id),
    CONSTRAINT ck_data_package_phone_generation CHECK (generation > 0),
    CONSTRAINT ck_data_package_phone_status CHECK (pool_status IN (1, 2, 3, 4, 5, 6))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链营销数据包号码成员';

CREATE TABLE IF NOT EXISTS data_package_stat (
    data_package_id BIGINT NOT NULL COMMENT '数据包ID;同时为主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    generation INT NOT NULL COMMENT '本行统计对应的数据包代次',
    unused_count INT NOT NULL DEFAULT 0 COMMENT '未使用号码数',
    claimed_count INT NOT NULL DEFAULT 0 COMMENT '已领取号码数',
    sent_count INT NOT NULL DEFAULT 0 COMMENT '当前停留在单钩的号码数',
    delivered_count INT NOT NULL DEFAULT 0 COMMENT '当前已送达号码数',
    retryable_failed_count INT NOT NULL DEFAULT 0 COMMENT '当前可重试失败号码数',
    unregistered_count INT NOT NULL DEFAULT 0 COMMENT '当前确认未注册号码数',
    updated_at BIGINT NOT NULL COMMENT '最近投影更新时间(epoch毫秒)',
    reconciled_at BIGINT DEFAULT NULL COMMENT '最近全量校准时间(epoch毫秒)',
    PRIMARY KEY (data_package_id),
    UNIQUE KEY uq_data_package_stat (tenant_id, data_package_id),
    CONSTRAINT ck_data_package_stat_generation CHECK (generation > 0),
    CONSTRAINT ck_data_package_stat_counts CHECK (
        unused_count >= 0 AND claimed_count >= 0 AND sent_count >= 0
        AND delivered_count >= 0 AND retryable_failed_count >= 0
        AND unregistered_count >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链营销数据包当前代状态统计读模型';

CREATE TABLE IF NOT EXISTS data_package_import (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '导入批次主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    data_package_id BIGINT NOT NULL COMMENT '数据包ID',
    generation INT DEFAULT NULL COMMENT '本次导入写入的代次;锁定数据包后确定',
    import_mode TINYINT NOT NULL COMMENT '导入模式:1追加 2覆盖',
    status TINYINT NOT NULL COMMENT '导入状态:1处理中 2成功 3失败',
    source_file_name VARCHAR(255) NOT NULL COMMENT '原始TXT文件名;不保存文件内容',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '非空行数',
    accepted_rows INT NOT NULL DEFAULT 0 COMMENT '实际生效号码数',
    invalid_rows INT NOT NULL DEFAULT 0 COMMENT '格式非法行数',
    duplicated_rows INT NOT NULL DEFAULT 0 COMMENT '文件内或当前代重复行数',
    failure_reason VARCHAR(512) DEFAULT NULL COMMENT '脱敏失败摘要;不记录号码明文',
    created_by BIGINT DEFAULT NULL COMMENT '上传人user_id',
    created_at BIGINT NOT NULL COMMENT '开始时间(epoch毫秒)',
    finished_at BIGINT DEFAULT NULL COMMENT '完成时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_data_package_import_pkg (tenant_id, data_package_id, created_at, id),
    KEY idx_data_package_import_generation
        (tenant_id, data_package_id, generation, status, finished_at),
    KEY idx_data_package_import_status (tenant_id, status, created_at, id),
    CONSTRAINT ck_data_package_import_generation CHECK (generation IS NULL OR generation > 0),
    CONSTRAINT ck_data_package_import_mode CHECK (import_mode IN (1, 2)),
    CONSTRAINT ck_data_package_import_status CHECK (status IN (1, 2, 3)),
    CONSTRAINT ck_data_package_import_counts CHECK (
        total_rows >= 0 AND accepted_rows >= 0 AND invalid_rows >= 0
        AND duplicated_rows >= 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='超链营销数据包TXT导入审计';
