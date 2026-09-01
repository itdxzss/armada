-- 账号操作限制统一模型：复用 mute_status + cooldown_until，状态区分限制能力。
-- mute_status: NULL=未受限 1=消息发送受限 2=拉人受限 3=消息发送和拉人受限。

ALTER TABLE account_state
    MODIFY COLUMN mute_status TINYINT DEFAULT NULL
        COMMENT '操作限制:1消息发送 2拉人 3消息发送和拉人;NULL=未受限',
    MODIFY COLUMN cooldown_until BIGINT DEFAULT NULL
        COMMENT '账号操作限制统一截止时间(epoch毫秒)',
    ADD COLUMN restriction_reason_code VARCHAR(64) DEFAULT NULL
        COMMENT '最近一次操作限制原因码' AFTER mute_status,
    ADD COLUMN restriction_reported_at BIGINT DEFAULT NULL
        COMMENT '最近一次操作限制事实时间(epoch毫秒)' AFTER restriction_reason_code,
    ADD KEY idx_account_state_restriction_due (cooldown_until, id);

-- 存量 1/2 原本表示禁言时长，统一归为“消息发送受限”；截止时间仍沿用原 cooldown_until。
UPDATE account_state
SET mute_status = 1
WHERE mute_status IN (1, 2);

SET @operation_restriction_now := CAST(
    UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

CREATE TEMPORARY TABLE tmp_account_puller_restriction (
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    restriction_until BIGINT NOT NULL,
    restriction_reported_at BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, account_id)
) ENGINE=InnoDB;

-- V106 之后的逐号码执行事实。
INSERT INTO tmp_account_puller_restriction (
    tenant_id, account_id, restriction_until, restriction_reported_at)
SELECT attempt.tenant_id,
       puller.account_id,
       MAX(COALESCE(attempt.result_at, attempt.updated_at) + 86400000),
       MAX(COALESCE(attempt.result_at, attempt.updated_at))
FROM pull_task_pull_call_member_attempt attempt
JOIN pull_task_group_account puller
  ON puller.tenant_id = attempt.tenant_id
 AND puller.id = attempt.puller_group_account_id
 AND puller.role_type = 2
WHERE UPPER(TRIM(attempt.reason_code)) IN (
    'RATE_LIMITED', 'ACCOUNT_REACHOUT_RESTRICTED')
GROUP BY attempt.tenant_id, puller.account_id
ON DUPLICATE KEY UPDATE
    restriction_until = GREATEST(restriction_until, VALUES(restriction_until)),
    restriction_reported_at = GREATEST(
        restriction_reported_at, VALUES(restriction_reported_at));

-- V106 之前料子结果上的历史事实，通过拉人调用定位实际拉手。
INSERT INTO tmp_account_puller_restriction (
    tenant_id, account_id, restriction_until, restriction_reported_at)
SELECT material.tenant_id,
       call_row.puller_account_id,
       MAX(COALESCE(material.pull_result_at, material.updated_at) + 86400000),
       MAX(COALESCE(material.pull_result_at, material.updated_at))
FROM pull_task_material_member material
JOIN pull_task_pull_call call_row
  ON call_row.tenant_id = material.tenant_id
 AND call_row.id = material.pull_call_id
WHERE call_row.puller_account_id IS NOT NULL
  AND UPPER(TRIM(material.pull_reason_code)) IN (
      'RATE_LIMITED', 'ACCOUNT_REACHOUT_RESTRICTED')
GROUP BY material.tenant_id, call_row.puller_account_id
ON DUPLICATE KEY UPDATE
    restriction_until = GREATEST(restriction_until, VALUES(restriction_until)),
    restriction_reported_at = GREATEST(
        restriction_reported_at, VALUES(restriction_reported_at));

-- 站台入群结果上的历史事实，通过拉人调用定位实际拉手。
INSERT INTO tmp_account_puller_restriction (
    tenant_id, account_id, restriction_until, restriction_reported_at)
SELECT station.tenant_id,
       call_row.puller_account_id,
       MAX(COALESCE(station.membership_result_at, station.updated_at) + 86400000),
       MAX(COALESCE(station.membership_result_at, station.updated_at))
FROM pull_task_group_account station
JOIN pull_task_pull_call call_row
  ON call_row.tenant_id = station.tenant_id
 AND call_row.id = station.pull_call_id
WHERE station.role_type = 3
  AND call_row.puller_account_id IS NOT NULL
  AND UPPER(TRIM(station.membership_reason_code)) IN (
      'RATE_LIMITED', 'ACCOUNT_REACHOUT_RESTRICTED')
GROUP BY station.tenant_id, call_row.puller_account_id
ON DUPLICATE KEY UPDATE
    restriction_until = GREATEST(restriction_until, VALUES(restriction_until)),
    restriction_reported_at = GREATEST(
        restriction_reported_at, VALUES(restriction_reported_at));

-- 调用级结果事实。
INSERT INTO tmp_account_puller_restriction (
    tenant_id, account_id, restriction_until, restriction_reported_at)
SELECT call_row.tenant_id,
       call_row.puller_account_id,
       MAX(COALESCE(call_row.result_at, call_row.updated_at) + 86400000),
       MAX(COALESCE(call_row.result_at, call_row.updated_at))
FROM pull_task_pull_call call_row
WHERE call_row.puller_account_id IS NOT NULL
  AND UPPER(TRIM(call_row.reason_code)) IN (
      'RATE_LIMITED', 'ACCOUNT_REACHOUT_RESTRICTED')
GROUP BY call_row.tenant_id, call_row.puller_account_id
ON DUPLICATE KEY UPDATE
    restriction_until = GREATEST(restriction_until, VALUES(restriction_until)),
    restriction_reported_at = GREATEST(
        restriction_reported_at, VALUES(restriction_reported_at));

-- 兼容旧角色行上尚未恢复的 RISK_COOLDOWN；迁移后不再把角色表作为权威状态。
INSERT INTO tmp_account_puller_restriction (
    tenant_id, account_id, restriction_until, restriction_reported_at)
SELECT role_row.tenant_id,
       role_row.account_id,
       MAX(COALESCE(role_row.cooldown_until, role_row.updated_at + 86400000)),
       MAX(role_row.updated_at)
FROM pull_task_group_account role_row
WHERE role_row.role_type = 2
  AND role_row.availability_status = 2
GROUP BY role_row.tenant_id, role_row.account_id
ON DUPLICATE KEY UPDATE
    restriction_until = GREATEST(restriction_until, VALUES(restriction_until)),
    restriction_reported_at = GREATEST(
        restriction_reported_at, VALUES(restriction_reported_at));

UPDATE account_state state_row
JOIN tmp_account_puller_restriction restriction_row
  ON restriction_row.tenant_id = state_row.tenant_id
 AND restriction_row.account_id = state_row.account_id
SET state_row.mute_status = CASE
      WHEN state_row.mute_status IS NULL THEN 2
      WHEN state_row.mute_status = 1 THEN 3
      ELSE 3
    END,
    state_row.cooldown_until = GREATEST(
        COALESCE(state_row.cooldown_until, 0),
        restriction_row.restriction_until),
    state_row.restriction_reason_code = 'PULLER_HISTORY_RESTRICTION',
    state_row.restriction_reported_at = GREATEST(
        COALESCE(state_row.restriction_reported_at, 0),
        restriction_row.restriction_reported_at),
    state_row.updated_at = GREATEST(
        state_row.updated_at, @operation_restriction_now)
WHERE restriction_row.restriction_until > @operation_restriction_now;

UPDATE pull_task_group_account
SET availability_status = 1,
    unavailable_reason_code = NULL,
    cooldown_until = NULL,
    updated_at = GREATEST(updated_at, @operation_restriction_now)
WHERE role_type = 2
  AND availability_status = 2;

DROP TEMPORARY TABLE tmp_account_puller_restriction;
