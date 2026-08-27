-- V140 canonical 群唯一分类只读 dry-run。
-- 仅执行 SELECT；共享环境运行前仍须确认目标环境并使用只读账号。
-- resolved_classification:0未分类 1历史群 2上控后群。
-- resolution_rule:0无证据 1最早可靠事实 2仅历史 3仅上控后 4双分类歧义保守归历史。

-- V140 会补齐的确定性 wa://group/{jid} 兼容句柄数量；已有 group_id 不改写。
SELECT COUNT(*) AS deterministic_handle_bindings_to_backfill
FROM group_link handle
JOIN wa_group current_group
  ON current_group.tenant_id = handle.tenant_id
 AND handle.link_url = CONCAT('wa://group/', current_group.group_jid)
WHERE handle.group_id IS NULL;

WITH evidence AS (
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
  GROUP BY current_group.tenant_id, current_group.id
), flags AS (
  SELECT evidence.*,
         CASE WHEN legacy_historical = 1 OR historical_evidence_at IS NOT NULL
           THEN 1 ELSE 0 END AS has_historical_evidence,
         CASE WHEN legacy_post_control = 1 OR post_control_evidence_at IS NOT NULL
           THEN 1 ELSE 0 END AS has_post_control_evidence
  FROM evidence
), resolution AS (
  SELECT flags.*,
         CASE
           WHEN has_historical_evidence = 1 AND has_post_control_evidence = 1 THEN
             CASE WHEN historical_evidence_at IS NOT NULL
                    AND post_control_evidence_at IS NOT NULL
                    AND post_control_evidence_at < historical_evidence_at
               THEN 2 ELSE 1 END
           WHEN has_historical_evidence = 1 THEN 1
           WHEN has_post_control_evidence = 1 THEN 2
           ELSE 0
         END AS resolved_classification,
         CASE
           WHEN has_historical_evidence = 1 AND has_post_control_evidence = 1
            AND historical_evidence_at IS NOT NULL
            AND post_control_evidence_at IS NOT NULL THEN 1
           WHEN has_historical_evidence = 1 AND has_post_control_evidence = 1 THEN 4
           WHEN has_historical_evidence = 1 THEN 2
           WHEN has_post_control_evidence = 1 THEN 3
           ELSE 0
         END AS resolution_rule
  FROM flags
)
SELECT resolution_rule,
       resolved_classification,
       COUNT(*) AS group_count
FROM resolution
GROUP BY resolution_rule, resolved_classification
ORDER BY resolution_rule, resolved_classification;

-- AMBIGUOUS_BOTH_HISTORICAL:无两端可比较时间的双证据群，正式迁移保守归历史群。
WITH evidence AS (
  SELECT current_group.tenant_id,
         current_group.id AS group_id,
         MAX(CASE WHEN handle.is_historical = 1 THEN 1 ELSE 0 END) AS legacy_historical,
         MAX(CASE WHEN handle.is_post_control = 1 THEN 1 ELSE 0 END) AS legacy_post_control,
         MIN(CASE
           WHEN binding.was_in_initial_baseline = 1
            AND sync_state.baseline_state = 2
            AND sync_state.baseline_completeness = 1
             THEN sync_state.baseline_captured_at
           ELSE NULL
         END) AS historical_evidence_at,
         MIN(CASE
           WHEN binding.was_in_initial_baseline = 0
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
  GROUP BY current_group.tenant_id, current_group.id
)
SELECT tenant_id,
       group_id,
       legacy_historical,
       legacy_post_control,
       historical_evidence_at,
       post_control_evidence_at,
       1 AS resolved_classification,
       4 AS resolution_rule
FROM evidence
WHERE (legacy_historical = 1 OR historical_evidence_at IS NOT NULL)
  AND (legacy_post_control = 1 OR post_control_evidence_at IS NOT NULL)
  AND (historical_evidence_at IS NULL OR post_control_evidence_at IS NULL)
ORDER BY tenant_id, group_id;
