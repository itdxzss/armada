-- 租户内 canonical wa_group 首次唯一分类。
-- 0未分类 1历史群 2上控后群；新事实只允许从0原子写入一次，后续事件不可改写。

SET @classification_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'wa_group'
    AND column_name = 'group_classification'
);
SET @classification_column_ddl := IF(@classification_column_exists = 0,
  'ALTER TABLE wa_group
     ADD COLUMN group_classification TINYINT NOT NULL DEFAULT 0
       COMMENT ''群首次唯一分类:0未分类 1历史群 2上控后群'' AFTER origin',
  'SELECT 1');
PREPARE stmt FROM @classification_column_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @classified_at_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'wa_group'
    AND column_name = 'group_classified_at'
);
SET @classified_at_column_ddl := IF(@classified_at_column_exists = 0,
  'ALTER TABLE wa_group
     ADD COLUMN group_classified_at BIGINT DEFAULT NULL
       COMMENT ''首次分类事实时间;迁移无可靠事实时间时为迁移决策时间(epoch毫秒)''
       AFTER group_classification',
  'SELECT 1');
PREPARE stmt FROM @classified_at_column_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @classification_source_column_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'wa_group'
    AND column_name = 'group_classification_source'
);
SET @classification_source_column_ddl := IF(@classification_source_column_exists = 0,
  'ALTER TABLE wa_group
     ADD COLUMN group_classification_source TINYINT DEFAULT NULL
       COMMENT ''首次分类来源:1首次完整baseline 2上控后新增 3迁移可靠证据 4迁移兜底''
       AFTER group_classified_at',
  'SELECT 1');
PREPARE stmt FROM @classification_source_column_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 永久审计表保存正式回填前的输入证据与决策，便于按租户统计和复核歧义群。
CREATE TABLE IF NOT EXISTS wa_group_classification_migration_audit (
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_id BIGINT NOT NULL COMMENT 'wa_group.id',
    legacy_historical TINYINT NOT NULL COMMENT '旧句柄是否存在历史群true:0否 1是',
    legacy_post_control TINYINT NOT NULL COMMENT '旧句柄是否存在上控后群true:0否 1是',
    historical_evidence_at BIGINT DEFAULT NULL COMMENT '最早完整baseline事实时间(epoch毫秒)',
    post_control_evidence_at BIGINT DEFAULT NULL COMMENT '最早上控后事实时间(epoch毫秒)',
    resolved_classification TINYINT NOT NULL COMMENT '回填分类:0未分类 1历史群 2上控后群',
    classification_source TINYINT DEFAULT NULL
        COMMENT '回填来源:3 MIGRATION_EVIDENCE 4 MIGRATION_LEGACY_FALLBACK',
    classified_at BIGINT DEFAULT NULL COMMENT '回填采用的事实或迁移决策时间(epoch毫秒)',
    resolution_rule TINYINT NOT NULL
        COMMENT '规则:0无证据 1 EARLIEST_RELIABLE_FACT 2 HISTORICAL_ONLY 3 POST_CONTROL_ONLY 4 AMBIGUOUS_BOTH_HISTORICAL',
    created_at BIGINT NOT NULL COMMENT '审计生成时间(epoch毫秒)',
    PRIMARY KEY (tenant_id, group_id),
    KEY idx_wa_group_classification_audit_rule
        (tenant_id, resolution_rule, resolved_classification, group_id),
    CONSTRAINT ck_wa_group_classification_audit_legacy
        CHECK (legacy_historical IN (0, 1) AND legacy_post_control IN (0, 1)),
    CONSTRAINT ck_wa_group_classification_audit_result
        CHECK (resolved_classification IN (0, 1, 2)),
    CONSTRAINT ck_wa_group_classification_audit_source
        CHECK (classification_source IS NULL OR classification_source IN (3, 4)),
    CONSTRAINT ck_wa_group_classification_audit_rule
        CHECK (resolution_rule IN (0, 1, 2, 3, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='V140 canonical群首次唯一分类回填审计';

SET @classification_migration_at := CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED);

-- V123 只按旧 preview.group_jid 回填过 canonical 引用；账号同步生成的 wa://group/{jid}
-- 句柄可能仍未绑定。先补齐这类确定性引用，确保迁移后列表立即能读到唯一分类，而非等待
-- 下一次账号快照才恢复显示。已有 group_id 永不改写。
UPDATE group_link handle
JOIN wa_group current_group
  ON current_group.tenant_id = handle.tenant_id
 AND handle.link_url = CONCAT('wa://group/', current_group.group_jid)
SET handle.group_id = current_group.id
WHERE handle.group_id IS NULL;

DROP TEMPORARY TABLE IF EXISTS tmp_wa_group_classification_evidence;
CREATE TEMPORARY TABLE tmp_wa_group_classification_evidence AS
SELECT current_group.tenant_id,
       current_group.id AS group_id,
       MAX(CASE WHEN handle.is_historical = 1 THEN 1 ELSE 0 END) AS legacy_historical,
       MAX(CASE WHEN handle.is_post_control = 1 THEN 1 ELSE 0 END) AS legacy_post_control,
       MIN(CASE
         WHEN binding.was_in_initial_baseline = 1
          AND sync_state.baseline_state = 2
          AND sync_state.baseline_completeness = 1
          AND sync_state.baseline_captured_at IS NOT NULL
           THEN sync_state.baseline_captured_at
         ELSE NULL
       END) AS historical_evidence_at,
       MIN(CASE
         WHEN binding.was_in_initial_baseline = 0
          AND binding.first_post_control_observed_at IS NOT NULL
           THEN binding.first_post_control_observed_at
         ELSE NULL
       END) AS post_control_evidence_at
FROM wa_group current_group
LEFT JOIN group_link handle
  ON handle.tenant_id = current_group.tenant_id
 AND (
   handle.group_id = current_group.id
   OR (handle.group_id IS NULL
     AND handle.link_url = CONCAT('wa://group/', current_group.group_jid))
 )
LEFT JOIN wa_account_group_binding binding
  ON binding.tenant_id = current_group.tenant_id
 AND binding.group_id = current_group.id
LEFT JOIN account_group_sync_state sync_state
  ON sync_state.tenant_id = binding.tenant_id
 AND sync_state.account_id = binding.account_id
GROUP BY current_group.tenant_id, current_group.id;

DROP TEMPORARY TABLE IF EXISTS tmp_wa_group_classification_resolution;
CREATE TEMPORARY TABLE tmp_wa_group_classification_resolution AS
SELECT evidence.*,
       CASE
         WHEN evidence.legacy_historical = 1
           OR evidence.historical_evidence_at IS NOT NULL THEN 1
         ELSE 0
       END AS has_historical_evidence,
       CASE
         WHEN evidence.legacy_post_control = 1
           OR evidence.post_control_evidence_at IS NOT NULL THEN 1
         ELSE 0
       END AS has_post_control_evidence
FROM tmp_wa_group_classification_evidence evidence;

INSERT INTO wa_group_classification_migration_audit (
  tenant_id, group_id, legacy_historical, legacy_post_control,
  historical_evidence_at, post_control_evidence_at,
  resolved_classification, classification_source, classified_at,
  resolution_rule, created_at
)
SELECT resolution.tenant_id,
       resolution.group_id,
       resolution.legacy_historical,
       resolution.legacy_post_control,
       resolution.historical_evidence_at,
       resolution.post_control_evidence_at,
       CASE
         WHEN resolution.has_historical_evidence = 1
          AND resolution.has_post_control_evidence = 1 THEN
           CASE
             WHEN resolution.historical_evidence_at IS NOT NULL
              AND resolution.post_control_evidence_at IS NOT NULL
              AND resolution.post_control_evidence_at < resolution.historical_evidence_at
               THEN 2
             ELSE 1
           END
         WHEN resolution.has_historical_evidence = 1 THEN 1
         WHEN resolution.has_post_control_evidence = 1 THEN 2
         ELSE 0
       END AS resolved_classification,
       CASE
         WHEN resolution.has_historical_evidence = 0
          AND resolution.has_post_control_evidence = 0 THEN NULL
         WHEN resolution.historical_evidence_at IS NOT NULL
          AND resolution.post_control_evidence_at IS NOT NULL THEN 3
         WHEN resolution.has_historical_evidence = 1
          AND resolution.has_post_control_evidence = 0
          AND resolution.historical_evidence_at IS NOT NULL THEN 3
         WHEN resolution.has_historical_evidence = 0
          AND resolution.has_post_control_evidence = 1
          AND resolution.post_control_evidence_at IS NOT NULL THEN 3
         ELSE 4
       END AS classification_source,
       CASE
         WHEN resolution.has_historical_evidence = 0
          AND resolution.has_post_control_evidence = 0 THEN NULL
         WHEN resolution.historical_evidence_at IS NOT NULL
          AND resolution.post_control_evidence_at IS NOT NULL
           THEN LEAST(
             resolution.historical_evidence_at,
             resolution.post_control_evidence_at)
         WHEN resolution.has_historical_evidence = 1
          AND resolution.has_post_control_evidence = 0
          AND resolution.historical_evidence_at IS NOT NULL
           THEN resolution.historical_evidence_at
         WHEN resolution.has_historical_evidence = 0
          AND resolution.has_post_control_evidence = 1
          AND resolution.post_control_evidence_at IS NOT NULL
           THEN resolution.post_control_evidence_at
         ELSE @classification_migration_at
       END AS classified_at,
       CASE
         WHEN resolution.has_historical_evidence = 1
          AND resolution.has_post_control_evidence = 1
          AND resolution.historical_evidence_at IS NOT NULL
          AND resolution.post_control_evidence_at IS NOT NULL THEN 1
         WHEN resolution.has_historical_evidence = 1
          AND resolution.has_post_control_evidence = 1 THEN 4
         WHEN resolution.has_historical_evidence = 1 THEN 2
         WHEN resolution.has_post_control_evidence = 1 THEN 3
         ELSE 0
       END AS resolution_rule,
       @classification_migration_at
FROM tmp_wa_group_classification_resolution resolution
ON DUPLICATE KEY UPDATE
  legacy_historical = VALUES(legacy_historical),
  legacy_post_control = VALUES(legacy_post_control),
  historical_evidence_at = VALUES(historical_evidence_at),
  post_control_evidence_at = VALUES(post_control_evidence_at),
  resolved_classification = VALUES(resolved_classification),
  classification_source = VALUES(classification_source),
  classified_at = VALUES(classified_at),
  resolution_rule = VALUES(resolution_rule),
  created_at = VALUES(created_at);

UPDATE wa_group current_group
JOIN wa_group_classification_migration_audit audit
  ON audit.tenant_id = current_group.tenant_id
 AND audit.group_id = current_group.id
SET current_group.group_classification = audit.resolved_classification,
    current_group.group_classified_at = audit.classified_at,
    current_group.group_classification_source = audit.classification_source
WHERE current_group.group_classification = 0;

DROP TEMPORARY TABLE IF EXISTS tmp_wa_group_classification_resolution;
DROP TEMPORARY TABLE IF EXISTS tmp_wa_group_classification_evidence;

SET @classification_index_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'wa_group'
    AND index_name = 'idx_wa_group_classification'
);
SET @classification_index_ddl := IF(@classification_index_exists = 0,
  'ALTER TABLE wa_group
     ADD KEY idx_wa_group_classification
       (tenant_id, group_classification, deleted_at, created_at, id),
     ALGORITHM=INPLACE, LOCK=NONE',
  'SELECT 1');
PREPARE stmt FROM @classification_index_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @classification_check_exists := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'wa_group'
    AND constraint_name = 'ck_wa_group_classification'
);
SET @classification_check_ddl := IF(@classification_check_exists = 0,
  'ALTER TABLE wa_group
     ADD CONSTRAINT ck_wa_group_classification
       CHECK (group_classification IN (0, 1, 2))',
  'SELECT 1');
PREPARE stmt FROM @classification_check_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @classification_source_check_exists := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'wa_group'
    AND constraint_name = 'ck_wa_group_classification_source'
);
SET @classification_source_check_ddl := IF(@classification_source_check_exists = 0,
  'ALTER TABLE wa_group
     ADD CONSTRAINT ck_wa_group_classification_source
       CHECK (group_classification_source IS NULL
         OR group_classification_source IN (1, 2, 3, 4))',
  'SELECT 1');
PREPARE stmt FROM @classification_source_check_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @classification_header_check_exists := (
  SELECT COUNT(*)
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'wa_group'
    AND constraint_name = 'ck_wa_group_classification_header'
);
SET @classification_header_check_ddl := IF(@classification_header_check_exists = 0,
  'ALTER TABLE wa_group
     ADD CONSTRAINT ck_wa_group_classification_header CHECK (
       (group_classification = 0
         AND group_classified_at IS NULL
         AND group_classification_source IS NULL)
       OR (group_classification IN (1, 2)
         AND group_classified_at IS NOT NULL
         AND group_classification_source IS NOT NULL)
     )',
  'SELECT 1');
PREPARE stmt FROM @classification_header_check_ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
