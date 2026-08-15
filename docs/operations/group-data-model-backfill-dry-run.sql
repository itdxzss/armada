-- 群组六表回填只读门禁。violation_count 必须为 0；observed_count 只记录现状。
-- 本文件不由 Flyway 执行；共享环境执行前仍需确认目标环境和只读账号。

SELECT 'duplicate_group_jid' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM (
    SELECT preview.tenant_id, LOWER(TRIM(preview.group_jid)) AS group_jid
    FROM group_link_preview preview
    INNER JOIN group_link link
      ON preview.tenant_id = link.tenant_id
     AND preview.group_link_id = link.id
    WHERE preview.group_jid IS NOT NULL
      AND TRIM(preview.group_jid) <> ''
    GROUP BY preview.tenant_id, LOWER(TRIM(preview.group_jid))
    HAVING COUNT(*) > 1
) duplicate_groups;

SELECT 'duplicate_invite_code' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM (
    SELECT preview.tenant_id, TRIM(preview.invite_code) AS invite_code
    FROM group_link_preview preview
    INNER JOIN group_link link
      ON preview.tenant_id = link.tenant_id
     AND preview.group_link_id = link.id
    WHERE preview.invite_code IS NOT NULL
      AND TRIM(preview.invite_code) <> ''
    GROUP BY preview.tenant_id, TRIM(preview.invite_code)
    HAVING COUNT(*) > 1
) duplicate_invites;

-- wa_group 和 wa_group_invite 已有双写数据时执行；同一 code 不得指向另一个群。
SELECT 'invite_group_conflict' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM group_link_preview preview
INNER JOIN group_link link
  ON preview.tenant_id = link.tenant_id
 AND preview.group_link_id = link.id
INNER JOIN wa_group resolved_group
  ON resolved_group.tenant_id = preview.tenant_id
 AND resolved_group.group_jid = LOWER(TRIM(preview.group_jid))
INNER JOIN wa_group_invite invite
  ON invite.tenant_id = preview.tenant_id
 AND invite.invite_code = TRIM(preview.invite_code)
WHERE preview.invite_code IS NOT NULL
  AND TRIM(preview.invite_code) <> ''
  AND invite.group_id IS NOT NULL
  AND invite.group_id <> resolved_group.id;

SELECT 'invalid_group_jid' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM group_link_preview preview
INNER JOIN group_link link
  ON preview.tenant_id = link.tenant_id
 AND preview.group_link_id = link.id
WHERE preview.group_jid IS NOT NULL
  AND (
      TRIM(preview.group_jid) = ''
      OR LOWER(TRIM(preview.group_jid)) NOT LIKE '%@g.us'
  );

SELECT 'orphan_membership' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account_group_membership membership
LEFT JOIN account account
  ON account.tenant_id = membership.tenant_id
 AND account.id = membership.account_id
LEFT JOIN group_link link
  ON link.tenant_id = membership.tenant_id
 AND link.id = membership.group_link_id
WHERE account.id IS NULL
   OR link.id IS NULL;

-- wa_group 回填完成后执行；这里只检查后续账号-群绑定是否能解析目标群。
SELECT 'unresolved_binding_target' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account_group_membership membership
LEFT JOIN wa_group wa_group
  ON wa_group.tenant_id = membership.tenant_id
 AND wa_group.group_jid = LOWER(TRIM(membership.group_jid))
WHERE wa_group.id IS NULL;

-- 没有旧群入口的孤立成员缓存不属于列表可达数据，回填会跳过，只记录审计数量。
SELECT 'unresolved_member_group' AS gate_name,
       COUNT(*) AS observed_count,
       'information_only' AS expected_count
FROM (
    SELECT state.tenant_id, state.group_jid
    FROM whatsapp_group_member_state state
    UNION ALL
    SELECT join_fact.tenant_id, join_fact.group_jid
    FROM whatsapp_group_member_join_fact join_fact
    UNION ALL
    SELECT exit_fact.tenant_id, exit_fact.group_jid
    FROM whatsapp_group_departed_member exit_fact
) member_source
LEFT JOIN wa_group wa_group
  ON wa_group.tenant_id = member_source.tenant_id
 AND wa_group.group_jid = LOWER(TRIM(member_source.group_jid))
WHERE wa_group.id IS NULL;

SELECT 'invalid_participant_jid' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM (
    SELECT participant_jid FROM whatsapp_group_member_state
    UNION ALL
    SELECT participant_jid FROM whatsapp_group_member_join_fact
    UNION ALL
    SELECT participant_jid FROM whatsapp_group_departed_member
) member_source
WHERE LOWER(TRIM(member_source.participant_jid)) NOT LIKE '%@s.whatsapp.net'
  AND LOWER(TRIM(member_source.participant_jid)) NOT LIKE '%@lid';

SELECT 'invalid_binding_account_phone' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account account
WHERE account.deleted_at IS NULL
  AND TRIM(account.ws_phone) NOT REGEXP '^[0-9]+$'
  AND (
    account.group_baseline_state = 2
    OR EXISTS (
      SELECT 1
      FROM account_group_membership membership
      WHERE membership.tenant_id = account.tenant_id
        AND membership.account_id = account.id
        AND membership.deleted_at IS NULL
    )
  );

-- 空集合只有符合现有真实捕获路径（无同步水位，且协议时间不同于落库时间）才可影子回填。
-- 其他空集合继续阻断；即使通过，本项也不能作为最终 writer/read cut 的充分条件。
SELECT 'ambiguous_empty_baseline' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account account
LEFT JOIN account_group_baseline baseline
  ON baseline.tenant_id = account.tenant_id
 AND baseline.account_id = account.id
WHERE account.deleted_at IS NULL
  AND account.group_baseline_state = 2
  AND (
      baseline.id IS NULL
      OR (
        JSON_LENGTH(baseline.baseline_group_jids) = 0
        AND (
          baseline.last_group_sync_requested_at IS NOT NULL
          OR baseline.captured_at = baseline.created_at
        )
      )
  );

SELECT 'baseline_count_mismatch' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account account
INNER JOIN account_group_baseline baseline
  ON baseline.tenant_id = account.tenant_id
 AND baseline.account_id = account.id
WHERE account.group_baseline_state = 2
  AND (
      JSON_TYPE(baseline.baseline_group_jids) <> 'ARRAY'
      OR baseline.group_count <> JSON_LENGTH(baseline.baseline_group_jids)
      OR baseline.captured_at IS NULL
      OR baseline.captured_at < 0
  );

SELECT 'unresolved_baseline_target' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account account
INNER JOIN account_group_baseline baseline
  ON baseline.tenant_id = account.tenant_id
 AND baseline.account_id = account.id
INNER JOIN JSON_TABLE(
  baseline.baseline_group_jids,
  '$[*]' COLUMNS (group_jid VARCHAR(128) PATH '$')
) baseline_group
LEFT JOIN wa_group wa_group
  ON wa_group.tenant_id = account.tenant_id
 AND wa_group.group_jid = LOWER(TRIM(baseline_group.group_jid))
WHERE account.deleted_at IS NULL
  AND account.group_baseline_state = 2
  AND (
    LOWER(TRIM(baseline_group.group_jid)) NOT LIKE '%@g.us'
    OR wa_group.id IS NULL
  );

SELECT 'duplicate_baseline_group' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM (
    SELECT account.tenant_id,
           account.id AS account_id,
           LOWER(TRIM(baseline_group.group_jid)) AS group_jid
    FROM account account
    INNER JOIN account_group_baseline baseline
      ON baseline.tenant_id = account.tenant_id
     AND baseline.account_id = account.id
    INNER JOIN JSON_TABLE(
      baseline.baseline_group_jids,
      '$[*]' COLUMNS (group_jid VARCHAR(128) PATH '$')
    ) baseline_group
    WHERE account.deleted_at IS NULL
      AND account.group_baseline_state = 2
    GROUP BY account.tenant_id, account.id,
             LOWER(TRIM(baseline_group.group_jid))
    HAVING COUNT(*) > 1
) duplicate_baseline;

SELECT 'pending_with_baseline_data' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account account
INNER JOIN account_group_baseline baseline
  ON baseline.tenant_id = account.tenant_id
 AND baseline.account_id = account.id
WHERE account.group_baseline_state = 1
  AND JSON_LENGTH(baseline.baseline_group_jids) > 0;

SELECT 'disabled_with_baseline_data' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM account account
INNER JOIN account_group_baseline baseline
  ON baseline.tenant_id = account.tenant_id
 AND baseline.account_id = account.id
WHERE account.group_baseline_state = 3
  AND JSON_LENGTH(baseline.baseline_group_jids) > 0;

SELECT 'legacy_joined_at_non_null' AS gate_name,
       COUNT(*) AS observed_count,
       'information_only' AS expected_count
FROM account_group_membership
WHERE joined_at IS NOT NULL;

-- 双写已经运行，现存 first-post 可能来自合法实时事件，不能冒充“迁移产生”的违规数。
-- 迁移 SQL 不写该列由代码结构测试保证；这里仅记录现状，并对 baseline=1 做可归因硬门禁。
SELECT 'existing_first_post_non_null' AS gate_name,
       COUNT(*) AS observed_count,
       'information_only' AS expected_count
FROM wa_account_group_binding
WHERE first_post_control_observed_at IS NOT NULL;

SELECT 'baseline_first_post_non_null' AS gate_name,
       COUNT(*) AS violation_count,
       0 AS expected_count
FROM wa_account_group_binding
WHERE was_in_initial_baseline = 1
  AND first_post_control_observed_at IS NOT NULL;
