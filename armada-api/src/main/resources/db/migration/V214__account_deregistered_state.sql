ALTER TABLE account_state
  MODIFY COLUMN account_state TINYINT DEFAULT NULL
  COMMENT '账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限 9注销;NULL=未上报';

-- 只回填已有确认完成证据的建群人注销，不以普通离线或注销请求已发送推断完成。
UPDATE account_state
SET account_state = 9
WHERE (account_state IS NULL OR account_state <> 9)
  AND EXISTS (
    SELECT 1
    FROM account_creator_deletion acd
    WHERE acd.tenant_id = account_state.tenant_id
      AND acd.account_id = account_state.account_id
      AND acd.lifecycle = 'DELETED'
      AND acd.completed_at IS NOT NULL
  );
