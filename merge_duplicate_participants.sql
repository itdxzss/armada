-- 归并 wa_group_participant 表中的 PN/LID 双行
-- 策略：保留 LID 行（canonical），将 PN 补入，删除纯 PN 行

-- 临时表：找出所有双行
DROP TEMPORARY TABLE IF EXISTS duplicate_participants;
CREATE TEMPORARY TABLE duplicate_participants AS
SELECT
    tenant_id,
    group_id,
    phone,
    COUNT(*) as row_count,
    MIN(id) as keep_id,  -- 保留较早的 ID
    MAX(id) as delete_id  -- 删除较晚的 ID
FROM wa_group_participant
WHERE tenant_id = 1
  AND phone IS NOT NULL
GROUP BY tenant_id, group_id, phone
HAVING COUNT(*) > 1;

-- 查看将要处理的数据量
SELECT
    COUNT(*) as duplicate_groups,
    SUM(row_count) as total_rows
FROM duplicate_participants;

-- 开始归并事务
START TRANSACTION;

-- 更新 keep_id 行：补全缺失的身份字段
UPDATE wa_group_participant target
INNER JOIN duplicate_participants dp ON target.id = dp.keep_id
INNER JOIN wa_group_participant other ON other.tenant_id = dp.tenant_id
    AND other.group_id = dp.group_id
    AND other.phone = dp.phone
    AND other.id = dp.delete_id
SET
    target.pn_jid = COALESCE(target.pn_jid, other.pn_jid),
    target.lid_jid = COALESCE(target.lid_jid, other.lid_jid),
    target.phone = COALESCE(target.phone, other.phone),
    target.phone_country_iso2 = COALESCE(target.phone_country_iso2, other.phone_country_iso2),
    -- 状态取最新的
    target.presence_status = CASE
        WHEN other.presence_observed_at > target.presence_observed_at THEN other.presence_status
        ELSE target.presence_status
    END,
    target.presence_source = CASE
        WHEN other.presence_observed_at > target.presence_observed_at THEN other.presence_source
        ELSE target.presence_source
    END,
    target.presence_observed_at = GREATEST(
        COALESCE(target.presence_observed_at, 0),
        COALESCE(other.presence_observed_at, 0)
    ),
    -- 角色取最新的
    target.role = CASE
        WHEN other.role_observed_at > target.role_observed_at THEN other.role
        ELSE target.role
    END,
    target.role_source = CASE
        WHEN other.role_observed_at > target.role_observed_at THEN other.role_source
        ELSE target.role_source
    END,
    target.role_observed_at = GREATEST(
        COALESCE(target.role_observed_at, 0),
        COALESCE(other.role_observed_at, 0)
    ),
    -- 进群时间取最早的
    target.last_joined_at = LEAST(
        COALESCE(target.last_joined_at, 9999999999999),
        COALESCE(other.last_joined_at, 9999999999999)
    ),
    target.last_join_event_at = LEAST(
        COALESCE(target.last_join_event_at, 9999999999999),
        COALESCE(other.last_join_event_at, 9999999999999)
    ),
    target.updated_at = UNIX_TIMESTAMP(NOW()) * 1000;

-- 删除重复行
DELETE target
FROM wa_group_participant target
INNER JOIN duplicate_participants dp ON target.id = dp.delete_id;

-- 检查结果
SELECT
    '归并后检查' as step,
    COUNT(*) as remaining_duplicates
FROM wa_group_participant
WHERE tenant_id = 1 AND phone IS NOT NULL
GROUP BY tenant_id, group_id, phone
HAVING COUNT(*) > 1;

-- 如果上面查询返回 0，说明成功
COMMIT;
-- ROLLBACK;  -- 如果有问题就回滚
