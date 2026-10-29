SELECT UTC_TIMESTAMP() observed_utc;
SELECT a.id,FROM_UNIXTIME(a.deleted_at/1000) deleted_utc,s.login_state,s.desired_login_state,s.state_source FROM account a JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id WHERE a.tenant_id=1 AND a.id=2755;
SELECT COUNT(*) visible_account_count FROM account WHERE tenant_id=1 AND id=2755 AND deleted_at IS NULL;
SELECT COUNT(*) pending_online_count FROM protocol_command_outbox WHERE tenant_id=1 AND aggregate_id=2755 AND command_type='account.online.requested' AND deleted_at IS NULL AND status IN (0,1,5,6);
SELECT COUNT(*) unreleased_task_roles FROM pull_task_group_account WHERE tenant_id=1 AND account_id=2755 AND released_at IS NULL;
SELECT COUNT(*) bound_proxy_count FROM ip_proxy WHERE tenant_id=1 AND bound_account_id=2755 AND deleted_at IS NULL;
SELECT id,status,bound_account_id FROM ip_proxy WHERE tenant_id=1 AND id=13019;
SELECT COUNT(*) retained_lifecycle_audit FROM account_creator_deletion WHERE tenant_id=1 AND account_id=2755;
