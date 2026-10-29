SET @repair_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED);
SELECT 'backup_account' record_type,id,tenant_id,deleted_at,dispatched_at FROM account WHERE tenant_id=1 AND id IN (1038,1736) FOR UPDATE;
SELECT 'backup_state' record_type,account_id,account_state,login_state,desired_login_state,state_source,invalidated_at,last_state_sync_time,updated_at FROM account_state WHERE tenant_id=1 AND account_id IN (1038,1736) FOR UPDATE;
SET @repair_allowed=(SELECT COUNT(*)=2 FROM account a JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id WHERE a.tenant_id=1 AND a.id IN (1038,1736) AND a.deleted_at IS NULL AND a.dispatched_at IS NULL AND s.login_state=2 AND s.account_state IN (2,5));
SELECT 'repair_guard' result,@repair_allowed allowed,@repair_at repaired_at;
SELECT 'backup_outbox' record_type,id,status,last_error,updated_at FROM protocol_command_outbox WHERE tenant_id=1 AND aggregate_id IN (1038,1736) AND command_type='account.online.requested' AND status IN (0,1) FOR UPDATE;
UPDATE account_state SET account_state=5,login_state=2,desired_login_state=2,state_source='ACCOUNT_DELETED',invalidated_at=@repair_at,last_state_sync_time=@repair_at,updated_at=@repair_at WHERE tenant_id=1 AND account_id IN (1038,1736) AND @repair_allowed;
SELECT 'repair_accounts_stopped' result,ROW_COUNT() affected;
UPDATE protocol_command_outbox SET status=4,last_error='ACCOUNT_DELETED: confirmed protocol deletion; incident 01a11165',updated_at=@repair_at WHERE tenant_id=1 AND aggregate_id IN (1038,1736) AND command_type='account.online.requested' AND status IN (0,1) AND @repair_allowed;
SELECT 'repair_pending_commands_canceled' result,ROW_COUNT() affected;
UPDATE account SET deleted_at=@repair_at WHERE tenant_id=1 AND id IN (1038,1736) AND deleted_at IS NULL AND @repair_allowed;
SELECT 'repair_accounts_soft_deleted' result,ROW_COUNT() affected;
SELECT 'backup_proxy' record_type,p.id,p.status,p.bound_account_id,p.bound_at,p.last_sample_check_at,p.check_fail_count,p.last_check_error,p.check_status,p.whatsapp_check_status,p.whatsapp_http_status,p.whatsapp_check_error,p.updated_at
FROM ip_proxy p JOIN (SELECT proxy_id,MAX(id) id FROM account_online_attempt_log WHERE tenant_id=1 AND occurred_at>='2026-10-06 13:00:00' AND proxy_id IS NOT NULL AND diagnosis_code='PROXY_FAILED' GROUP BY proxy_id) latest ON latest.proxy_id=p.id JOIN account_online_attempt_log l ON l.id=latest.id
WHERE p.tenant_id=1 AND p.deleted_at IS NULL AND p.status=3 AND p.bound_account_id IS NULL AND p.pairing_session_id IS NULL AND p.last_check_error='协议层上报当前代理不可用: PROXY_FAILED' AND l.account_id IN (1038,1736) AND l.raw_code=1001 AND @repair_allowed FOR UPDATE;
UPDATE ip_proxy p JOIN (SELECT proxy_id,MAX(id) id FROM account_online_attempt_log WHERE tenant_id=1 AND occurred_at>='2026-10-06 13:00:00' AND proxy_id IS NOT NULL AND diagnosis_code='PROXY_FAILED' GROUP BY proxy_id) latest ON latest.proxy_id=p.id JOIN account_online_attempt_log l ON l.id=latest.id
SET p.status=1,p.last_sample_check_at=NULL,p.check_fail_count=GREATEST(p.check_fail_count-1,0),p.last_check_error=NULL,p.check_status=NULL,p.whatsapp_check_status=NULL,p.whatsapp_http_status=NULL,p.whatsapp_check_error=NULL,p.updated_at=@repair_at
WHERE p.tenant_id=1 AND p.deleted_at IS NULL AND p.status=3 AND p.bound_account_id IS NULL AND p.pairing_session_id IS NULL AND p.last_check_error='协议层上报当前代理不可用: PROXY_FAILED' AND l.account_id IN (1038,1736) AND l.raw_code=1001 AND @repair_allowed;
SELECT 'repair_proxies_restored' result,ROW_COUNT() affected;
SELECT 'final_accounts' result,a.id,a.deleted_at,s.account_state,s.login_state,s.desired_login_state,s.state_source FROM account a JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id WHERE a.tenant_id=1 AND a.id IN (1038,1736);
SELECT 'final_proxy_inventory' result,status,COUNT(*) n FROM ip_proxy WHERE tenant_id=1 AND deleted_at IS NULL GROUP BY status;
