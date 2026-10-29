SELECT 'observation_context' evidence_section,NOW(3) observed_at,
       CAST(UNIX_TIMESTAMP(NOW(3))*1000 AS UNSIGNED) observed_at_epoch_ms,DATABASE() database_schema;

SELECT 'task_execution_settings' evidence_section,t.tenant_id,t.id task_id,t.status task_status,
       t.created_by,t.started_at task_started_at,t.finished_at task_finished_at,
       t.is_creator_delete_after_takeover,s.is_creator_leave_after_pull,s.auto_start,
       s.creator_group_id,s.manager_group_id,s.puller_group_id,s.station_group_id,
       s.initial_station_count,s.station_count_per_call,s.puller_count_per_group,
       s.pull_count_min,s.pull_count_max,s.pull_interval_seconds,s.pull_interval_max_seconds,
       e.id execution_id,e.execution_status,e.stage,
       CASE e.stage WHEN 2 THEN 'MANAGER_JOIN' WHEN 3 THEN 'MANAGER_ADMIN'
         WHEN 4 THEN 'MANAGER_PULLER_CONTACT' WHEN 5 THEN 'PULLER_INVITE'
         WHEN 6 THEN 'PULL_EXECUTION' WHEN 7 THEN 'MATERIAL_ADMIN' WHEN 8 THEN 'CLOSING'
         WHEN 9 THEN 'GROUP_CREATE' WHEN 11 THEN 'CREATOR_DELETE'
         WHEN 12 THEN 'CREATOR_DELETE_VERIFY' ELSE 'OTHER' END stage_name,
       e.create_step,e.create_operation_id,e.create_attempt_count,e.manual_paused,
       e.reason_code,e.next_run_at,e.version,e.lock_expires_at,
       SHA2(e.group_jid,256) group_jid_hash,
       (e.normalized_link IS NOT NULL AND TRIM(e.normalized_link)<>'') invitation_persisted,
       SHA2(e.normalized_link,256) invitation_hash,
       e.profile_verified_at,e.profile_verified_command_id,
       e.valid_member_count,e.creator_leave_result,
       e.created_at execution_created_at,e.started_at execution_started_at,
       e.updated_at execution_updated_at,e.finished_at execution_finished_at
FROM pull_task t
LEFT JOIN pull_task_standard_setting s ON s.task_id=t.id AND s.tenant_id=t.tenant_id
LEFT JOIN pull_task_group_execution e ON e.task_id=t.id AND e.tenant_id=t.tenant_id
WHERE t.tenant_id=1 AND t.id IN (__TASK_IDS__) ORDER BY t.id,e.id;

SELECT 'deletion_ledger_binding' evidence_section,d.id,d.tenant_id,d.task_id,d.group_execution_id,
       d.creator_account_id,d.creator_identity_hash,d.create_operation_id,d.operation_id,
       d.status deletion_status,
       CASE d.status WHEN 0 THEN 'RESERVED' WHEN 1 THEN 'SUBMITTED' WHEN 2 THEN 'ACCEPTED'
         WHEN 3 THEN 'UNKNOWN' WHEN 4 THEN 'FAILED' WHEN 5 THEN 'COMPLETE' END deletion_state,
       d.manager_account_id,SHA2(d.group_jid,256) group_jid_hash,d.creation_before,d.attempts,
       d.deletion_result_status,d.result_operation_id,d.result_identity_hash,
       d.reason_code,d.created_at,d.submitted_at,d.observed_at,d.deadline_at,d.completed_at,d.updated_at,
       (d.creator_identity_hash=SHA2(a.creator_deletion_identity_phone,256)) current_identity_matches_frozen,
       (d.creator_protocol_account_id=a.protocol_account_id) current_route_matches_frozen,
       (d.create_operation_id=e.create_operation_id) creation_operation_matches_execution,
       (d.operation_id=l.operation_id) operation_matches_lifecycle,
       (d.creator_identity_hash=l.identity_hash) identity_matches_lifecycle,
       (d.creator_account_id=l.account_id) account_matches_lifecycle,
       l.lifecycle
FROM pull_task_creator_deletion d
JOIN pull_task_group_execution e ON e.id=d.group_execution_id AND e.tenant_id=d.tenant_id
LEFT JOIN account a ON a.id=d.creator_account_id AND a.tenant_id=d.tenant_id
LEFT JOIN account_creator_deletion l ON l.group_execution_id=d.group_execution_id AND l.tenant_id=d.tenant_id
WHERE d.tenant_id=1 AND d.task_id IN (__TASK_IDS__) ORDER BY d.task_id,d.id;

WITH proof AS (
 SELECT d.*,CASE WHEN JSON_VALID(d.evidence_json) THEN
   CASE WHEN JSON_CONTAINS_PATH(d.evidence_json,'one','$.observation')
     THEN JSON_EXTRACT(d.evidence_json,'$.observation') ELSE CAST(d.evidence_json AS JSON) END
   ELSE NULL END observation
 FROM pull_task_creator_deletion d WHERE d.tenant_id=1 AND d.task_id IN (__TASK_IDS__)
)
SELECT 'deletion_proof_whitelist' evidence_section,id,tenant_id,task_id,group_execution_id,
       operation_id,JSON_VALID(evidence_json) evidence_json_valid,JSON_TYPE(observation) observation_type,
       JSON_TYPE(JSON_EXTRACT(observation,'$.creator')) creator_field_type,
       CASE WHEN JSON_TYPE(JSON_EXTRACT(observation,'$.creator'))='STRING'
         THEN JSON_UNQUOTE(JSON_EXTRACT(observation,'$.creator'))='' END creator_explicitly_empty,
       JSON_TYPE(JSON_EXTRACT(observation,'$.creatorPN')) creator_pn_field_type,
       CASE WHEN JSON_TYPE(JSON_EXTRACT(observation,'$.creatorPN'))='STRING'
         THEN JSON_UNQUOTE(JSON_EXTRACT(observation,'$.creatorPN'))='' END creator_pn_explicitly_empty,
       SHA2(JSON_UNQUOTE(JSON_EXTRACT(observation,'$.groupJid')),256) proof_group_jid_hash,
       JSON_EXTRACT(observation,'$.creation') proof_creation,
       JSON_EXTRACT(observation,'$.queriedAt') proof_queried_at,
       JSON_EXTRACT(observation,'$.managerPresent') manager_present,
       JSON_EXTRACT(observation,'$.managerAdmin') manager_admin,
       JSON_EXTRACT(observation,'$.creatorPresent') creator_present,
       JSON_EXTRACT(observation,'$.registrationKnown') registration_known,
       JSON_EXTRACT(observation,'$.registered') registered,
       JSON_EXTRACT(observation,'$.structureComplete') structure_complete,
       CASE WHEN JSON_VALID(evidence_json) THEN
         SHA2(JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.deletionResult.iqId')),256) END deletion_iq_id_hash,
       CASE WHEN JSON_VALID(evidence_json) THEN
         JSON_EXTRACT(evidence_json,'$.deletionResult.responseType') END deletion_response_type,
       observed_at,submitted_at,completed_at
FROM proof ORDER BY task_id,id;

SELECT 'roles_and_account_lifecycle' evidence_section,r.tenant_id,r.task_id,r.group_execution_id,
       r.id role_id,r.account_id,r.role_type,r.entry_mode,r.membership_status,r.admin_status,
       r.availability_status,r.unavailable_reason_code,r.joined_at,r.occupied_at,r.released_at,
       a.protocol_id,a.device_os,a.account_group_id,a.deleted_at account_deleted_at,
       s.login_state,s.desired_login_state,s.invalidated_at,
       CASE WHEN s.state_source IN('CREATOR_DELETION','CREATOR_DELETED','STATE_CHANGED')
         THEN s.state_source ELSE 'OTHER_OR_NULL' END state_source_category,
       l.lifecycle,l.operation_id,l.created_at lifecycle_created_at,l.updated_at lifecycle_updated_at,
       l.completed_at lifecycle_completed_at
FROM pull_task_group_account r
JOIN account a ON a.id=r.account_id AND a.tenant_id=r.tenant_id
LEFT JOIN account_state s ON s.account_id=a.id AND s.tenant_id=a.tenant_id
LEFT JOIN account_creator_deletion l ON l.account_id=a.id AND l.tenant_id=a.tenant_id
WHERE r.tenant_id=1 AND r.task_id IN (__TASK_IDS__) ORDER BY r.task_id,r.id;

SELECT 'account_actions' evidence_section,a.id,a.tenant_id,a.task_id,a.group_execution_id,
       a.action_type,a.action_status,a.command_id,a.attempt_no,
       actor.account_id actor_account_id,target.account_id target_account_id,
       a.reason_code,a.retryable,a.created_at,a.submitted_at,a.result_at,a.updated_at
FROM pull_task_account_action a
LEFT JOIN pull_task_group_account actor ON actor.id=a.actor_group_account_id AND actor.tenant_id=a.tenant_id
LEFT JOIN pull_task_group_account target ON target.id=a.target_group_account_id AND target.tenant_id=a.tenant_id
WHERE a.tenant_id=1 AND a.task_id IN (__TASK_IDS__) ORDER BY a.task_id,a.id;

SELECT 'pull_calls' evidence_section,id,tenant_id,task_id,group_execution_id,pull_wave_id,
       call_seq,puller_group_account_id,puller_account_id,puller_assignment_seq,
       planned_material_count,planned_station_count,call_status,roster_check_status,
       roster_check_started_at,roster_check_finished_at,command_id,reason_code,
       created_at,submitted_at,result_at,updated_at
FROM pull_task_pull_call WHERE tenant_id=1 AND task_id IN (__TASK_IDS__) ORDER BY task_id,id;

SELECT 'material_results' evidence_section,e.task_id,m.group_execution_id,m.id member_id,
       m.member_seq,m.admin_required,SHA2(m.normalized_phone,256) member_identity_hash,
       (m.normalized_phone=test_member.creator_deletion_identity_phone) is_designated_test_member_2774,
       m.pull_call_id,m.pull_status,m.pull_failure_count,m.active_pull_attempt_id,
       m.last_puller_group_account_id,m.pull_reason_code,m.pull_result_at,m.admin_status,
       m.admin_command_id,m.admin_reason_code,m.admin_result_at,m.created_at,m.updated_at
FROM pull_task_material_member m
JOIN pull_task_group_execution e ON e.id=m.group_execution_id AND e.tenant_id=m.tenant_id
LEFT JOIN account test_member ON test_member.id=2774 AND test_member.tenant_id=1
WHERE e.tenant_id=1 AND e.task_id IN (__TASK_IDS__) ORDER BY e.task_id,m.id;

SELECT 'participant_attempts' evidence_section,id,tenant_id,task_id,group_execution_id,
       pull_call_id,pull_wave_id,participant_type,participant_ref_id,
       SHA2(target_phone,256) target_identity_hash,puller_group_account_id,
       puller_assignment_seq,attempt_no,failure_count_before,lifecycle_status,
       active_slot,protocol_outcome,execution_state,reason_code,
       created_at,submitted_at,result_at,released_at,updated_at
FROM pull_task_pull_call_member_attempt
WHERE tenant_id=1 AND task_id IN (__TASK_IDS__) ORDER BY task_id,id;

SELECT 'member_query_records' evidence_section,id,tenant_id,task_id,group_execution_id,
       purpose,command_id,account_id,protocol_backend,SHA2(group_jid,256) group_jid_hash,
       query_status,attempt_no,requested_at,deadline_at,completed_at,error_code,
       JSON_VALID(target_jids_json) requested_targets_valid,
       CASE WHEN JSON_VALID(target_jids_json) THEN JSON_LENGTH(target_jids_json) END requested_targets_count,
       JSON_VALID(result_json) result_json_valid,
       CASE WHEN JSON_VALID(result_json) THEN JSON_TYPE(result_json) END result_json_type,
       CASE WHEN JSON_VALID(result_json) THEN JSON_LENGTH(result_json) END result_count,
       created_at,updated_at
FROM pull_task_member_query WHERE tenant_id=1 AND task_id IN (__TASK_IDS__) ORDER BY task_id,id;

WITH facts AS (
 SELECT q.id query_id,q.task_id,q.group_execution_id,q.query_status,q.completed_at,
        f.fact_no,JSON_EXTRACT(q.result_json,CONCAT('$[',f.fact_no-1,']')) fact
 FROM pull_task_member_query q
 JOIN JSON_TABLE(CASE WHEN JSON_VALID(q.result_json) AND JSON_TYPE(q.result_json)='ARRAY'
                   THEN q.result_json ELSE JSON_ARRAY() END,
                 '$[*]' COLUMNS(fact_no FOR ORDINALITY)) f
 WHERE q.tenant_id=1 AND q.task_id IN (__TASK_IDS__)
)
SELECT 'member_query_facts_whitelist' evidence_section,query_id,task_id,group_execution_id,
       query_status,completed_at,fact_no,JSON_TYPE(fact) fact_type,
       SHA2(JSON_UNQUOTE(JSON_EXTRACT(fact,'$.targetJid')),256) target_jid_hash,
       SHA2(JSON_UNQUOTE(JSON_EXTRACT(fact,'$.participantJid')),256) participant_jid_hash,
       SHA2(JSON_UNQUOTE(JSON_EXTRACT(fact,'$.phoneNumber')),256) phone_number_hash,
       JSON_EXTRACT(fact,'$.inGroup') in_group,JSON_EXTRACT(fact,'$.admin') is_admin,
       (JSON_UNQUOTE(JSON_EXTRACT(fact,'$.phoneNumber'))=a.creator_deletion_identity_phone
        OR JSON_UNQUOTE(JSON_EXTRACT(fact,'$.targetJid'))=CONCAT(a.creator_deletion_identity_phone,'@s.whatsapp.net'))
         is_designated_test_member_2774
FROM facts LEFT JOIN account a ON a.id=2774 AND a.tenant_id=1
ORDER BY task_id,query_id,fact_no;

SELECT 'business_outbox_metadata' evidence_section,o.id,o.tenant_id,o.command_id,o.command_type,
       o.aggregate_type,o.aggregate_id,o.protocol_backend,o.status,o.retry_count,
       o.created_at,o.locked_at,o.sent_at,o.updated_at,o.next_retry_at,o.deleted_at
FROM protocol_command_outbox o
WHERE o.tenant_id=1 AND (
  JSON_EXTRACT(o.payload_json,'$.pullTaskId') IN (__TASK_IDS__)
  OR o.command_id IN(SELECT a.command_id FROM pull_task_account_action a
                     WHERE a.tenant_id=1 AND a.task_id IN (__TASK_IDS__))
  OR o.command_id IN(SELECT c.command_id FROM pull_task_pull_call c
                     WHERE c.tenant_id=1 AND c.task_id IN (__TASK_IDS__))
  OR o.command_id IN(SELECT q.command_id FROM pull_task_member_query q
                     WHERE q.tenant_id=1 AND q.task_id IN (__TASK_IDS__)))
ORDER BY o.created_at,o.id;

SELECT 'creator_commands_since_task_creation' evidence_section,d.task_id,d.group_execution_id,
       d.creator_account_id,d.operation_id,d.status deletion_status,d.submitted_at deletion_submitted_at,
       o.id outbox_id,o.command_id,o.command_type,o.protocol_backend,o.status outbox_status,
       o.created_at,o.sent_at,o.updated_at,
       (d.submitted_at IS NOT NULL AND o.created_at>=d.submitted_at) created_after_deletion_intent,
       (d.completed_at IS NOT NULL AND o.created_at>=d.completed_at) created_after_deletion_complete
FROM pull_task_creator_deletion d
JOIN pull_task t ON t.id=d.task_id AND t.tenant_id=d.tenant_id
JOIN protocol_command_outbox o ON o.tenant_id=d.tenant_id
  AND o.protocol_account_id=d.creator_protocol_account_id AND o.created_at>=t.created_at
WHERE d.tenant_id=1 AND d.task_id IN (__TASK_IDS__) ORDER BY d.task_id,o.created_at,o.id;

WITH timeline AS (
 SELECT t.id task_id,CAST(NULL AS SIGNED) execution_id,'TASK_STARTED' event_type,t.started_at event_at,
        CAST(NULL AS CHAR(255)) command_id,CAST(NULL AS CHAR(255)) operation_id
 FROM pull_task t WHERE t.tenant_id=1 AND t.id IN (__TASK_IDS__)
 UNION ALL
 SELECT e.task_id,e.id,'PROFILE_VERIFIED',e.profile_verified_at,e.profile_verified_command_id,NULL
 FROM pull_task_group_execution e WHERE e.tenant_id=1 AND e.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT d.task_id,d.group_execution_id,'DELETION_RESERVED',d.created_at,NULL,d.operation_id
 FROM pull_task_creator_deletion d WHERE d.tenant_id=1 AND d.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT d.task_id,d.group_execution_id,'DELETION_INTENT_PERSISTED',d.submitted_at,NULL,d.operation_id
 FROM pull_task_creator_deletion d WHERE d.tenant_id=1 AND d.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT d.task_id,d.group_execution_id,'LATEST_DELETION_OBSERVATION',d.observed_at,NULL,d.operation_id
 FROM pull_task_creator_deletion d WHERE d.tenant_id=1 AND d.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT d.task_id,d.group_execution_id,'DELETION_COMPLETE',d.completed_at,NULL,d.operation_id
 FROM pull_task_creator_deletion d WHERE d.tenant_id=1 AND d.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT a.task_id,a.group_execution_id,CONCAT('ACTION_',a.action_type,'_SUBMITTED'),a.submitted_at,a.command_id,NULL
 FROM pull_task_account_action a WHERE a.tenant_id=1 AND a.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT a.task_id,a.group_execution_id,CONCAT('ACTION_',a.action_type,'_RESULT'),a.result_at,a.command_id,NULL
 FROM pull_task_account_action a WHERE a.tenant_id=1 AND a.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT c.task_id,c.group_execution_id,'PULL_CALL_SUBMITTED',c.submitted_at,c.command_id,NULL
 FROM pull_task_pull_call c WHERE c.tenant_id=1 AND c.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT c.task_id,c.group_execution_id,'PULL_CALL_RESULT',c.result_at,c.command_id,NULL
 FROM pull_task_pull_call c WHERE c.tenant_id=1 AND c.task_id IN (__TASK_IDS__)
 UNION ALL
 SELECT e.task_id,e.id,'EXECUTION_FINISHED',e.finished_at,NULL,NULL
 FROM pull_task_group_execution e WHERE e.tenant_id=1 AND e.task_id IN (__TASK_IDS__)
)
SELECT 'persisted_event_timeline' evidence_section,task_id,execution_id,event_type,event_at,
       FROM_UNIXTIME(event_at/1000) event_at_database_timezone,command_id,operation_id
FROM timeline WHERE event_at IS NOT NULL AND event_at>0 ORDER BY event_at,task_id,event_type;
