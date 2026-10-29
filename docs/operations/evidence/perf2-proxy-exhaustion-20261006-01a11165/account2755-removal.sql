SET @repair_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED);
SELECT 'backup_account' record_type,id,tenant_id,deleted_at,dispatched_at FROM account WHERE tenant_id=1 AND id=2755 FOR UPDATE;
SELECT 'backup_state' record_type,account_id,account_state,login_state,desired_login_state,state_source,invalidated_at,updated_at FROM account_state WHERE tenant_id=1 AND account_id=2755 FOR UPDATE;
SELECT 'backup_creator_lock' record_type,account_id,task_id,group_execution_id,lifecycle,operation_id FROM account_creator_deletion WHERE tenant_id=1 AND account_id=2755 FOR UPDATE;
SELECT 'backup_task' record_type,id,status FROM pull_task WHERE tenant_id=1 AND id=64 FOR UPDATE;
SELECT 'backup_execution' record_type,id,task_id,execution_status FROM pull_task_group_execution WHERE tenant_id=1 AND id=303 FOR UPDATE;
SELECT 'backup_role' record_type,id,task_id,group_execution_id,account_id,role_type,availability_status,unavailable_reason_code,released_at,updated_at FROM pull_task_group_account WHERE tenant_id=1 AND account_id=2755 AND released_at IS NULL FOR UPDATE;
SELECT 'backup_outbox' record_type,id,command_type,status,last_error,updated_at FROM protocol_command_outbox WHERE tenant_id=1 AND aggregate_id=2755 AND deleted_at IS NULL AND status IN (0,1,5,6) FOR UPDATE;
SELECT 'backup_proxy' record_type,id,status,bound_account_id,bound_at,pairing_session_id,updated_at FROM ip_proxy WHERE tenant_id=1 AND bound_account_id=2755 AND deleted_at IS NULL FOR UPDATE;
SET @repair_allowed=(SELECT COUNT(*)=1 FROM account a JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id JOIN account_creator_deletion d ON d.account_id=a.id AND d.tenant_id=a.tenant_id JOIN pull_task t ON t.id=d.task_id AND t.tenant_id=d.tenant_id JOIN pull_task_group_execution e ON e.id=d.group_execution_id AND e.tenant_id=d.tenant_id WHERE a.tenant_id=1 AND a.id=2755 AND RIGHT(a.ws_phone,4)='6580' AND a.deleted_at IS NULL AND a.dispatched_at IS NULL AND COALESCE(s.state_source,'')<>'ACCOUNT_EXPORT' AND d.task_id=64 AND d.group_execution_id=303 AND d.lifecycle='RESERVED' AND d.operation_id IS NULL AND t.status='ENDED' AND e.execution_status=6)
 AND NOT EXISTS(SELECT 1 FROM protocol_command_outbox WHERE tenant_id=1 AND aggregate_id=2755 AND deleted_at IS NULL AND status IN (1,5,6))
 AND NOT EXISTS(SELECT 1 FROM pull_task_group_account r JOIN pull_task_group_execution e ON e.id=r.group_execution_id AND e.tenant_id=r.tenant_id WHERE r.tenant_id=1 AND r.account_id=2755 AND r.released_at IS NULL AND e.execution_status IN (0,1,2,3));
SELECT 'repair_guard' result,@repair_allowed allowed,FROM_UNIXTIME(@repair_at/1000) repair_utc;
UPDATE account SET deleted_at=@repair_at WHERE tenant_id=1 AND id=2755 AND deleted_at IS NULL AND @repair_allowed;
SELECT 'repair_account_soft_deleted' result,ROW_COUNT() affected;
UPDATE account_state SET login_state=2,desired_login_state=2,state_source='ACCOUNT_DELETED',invalidated_at=@repair_at,updated_at=@repair_at WHERE tenant_id=1 AND account_id=2755 AND @repair_allowed;
SELECT 'repair_auto_login_stopped' result,ROW_COUNT() affected;
UPDATE protocol_command_outbox SET status=4,last_error='ACCOUNT_DELETED: user requested removal of account 2755; incident 01a11165',updated_at=@repair_at WHERE tenant_id=1 AND aggregate_id=2755 AND command_type='account.online.requested' AND deleted_at IS NULL AND status=0 AND @repair_allowed;
SELECT 'repair_pending_online_canceled' result,ROW_COUNT() affected;
UPDATE pull_task_group_account SET released_at=@repair_at,availability_status=4,unavailable_reason_code='ACCOUNT_DELETED',updated_at=@repair_at WHERE tenant_id=1 AND id=4680 AND account_id=2755 AND task_id=64 AND group_execution_id=303 AND role_type=4 AND released_at IS NULL AND @repair_allowed;
SELECT 'repair_ended_task_role_released' result,ROW_COUNT() affected;
UPDATE ip_proxy SET status=1,bound_account_id=NULL,bound_at=NULL,updated_at=@repair_at WHERE tenant_id=1 AND bound_account_id=2755 AND deleted_at IS NULL AND status=2 AND pairing_session_id IS NULL AND @repair_allowed;
SELECT 'repair_bound_proxy_released' result,ROW_COUNT() affected;
SELECT 'final_account' result,a.id,FROM_UNIXTIME(a.deleted_at/1000) deleted_utc,s.login_state,s.desired_login_state,s.state_source FROM account a JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id WHERE a.tenant_id=1 AND a.id=2755;
SELECT 'final_active_account_count' result,COUNT(*) n FROM account WHERE tenant_id=1 AND id=2755 AND deleted_at IS NULL;
SELECT 'final_pending_online_count' result,COUNT(*) n FROM protocol_command_outbox WHERE tenant_id=1 AND aggregate_id=2755 AND command_type='account.online.requested' AND deleted_at IS NULL AND status IN (0,1,5,6);
SELECT 'final_proxy_binding_count' result,COUNT(*) n FROM ip_proxy WHERE tenant_id=1 AND bound_account_id=2755 AND deleted_at IS NULL;
