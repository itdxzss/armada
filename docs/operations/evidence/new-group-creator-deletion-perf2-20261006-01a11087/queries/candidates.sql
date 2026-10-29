SELECT 'candidate_query_context' evidence_section,NOW(3) observed_at,DATABASE() database_schema;

SELECT 'candidate_summary' evidence_section,a.tenant_id,a.id account_id,a.owner_user_id,
       a.protocol_id,a.device_os,a.account_group_id,
       REGEXP_REPLACE(g.name,'[+]?[0-9]([[:space:]().-]*[0-9]){6,}','[redacted]') group_name,
       g.marketing_occupancy_type,g.marketing_occupancy_task_id,
       a.deleted_at,s.login_state,s.desired_login_state,s.account_state,REGEXP_REPLACE(s.state_source,'[+]?[0-9]([[:space:]().-]*[0-9]){6,}','[redacted]') state_source,
       CONCAT('***',RIGHT(a.creator_deletion_identity_phone,4)) phone_tail,
       SHA2(a.creator_deletion_identity_phone,256) identity_hash,
       (a.protocol_account_id IS NOT NULL AND TRIM(a.protocol_account_id)<>'') protocol_route_present,
       (SELECT GROUP_CONCAT(c.cred_format ORDER BY c.id) FROM account_credential c
        WHERE c.tenant_id=a.tenant_id AND c.account_id=a.id AND c.deleted_at IS NULL) cred_formats,
       (SELECT COUNT(*) FROM account aa
        WHERE aa.creator_deletion_identity_phone=a.creator_deletion_identity_phone) identity_alias_count,
       d.lifecycle deletion_lifecycle,d.task_id deletion_task_id,d.group_execution_id deletion_execution_id,
       (a.deleted_at IS NULL AND UPPER(TRIM(a.protocol_id))='ANDROID' AND a.device_os=1
        AND TRIM(a.ws_phone)<>'' AND TRIM(a.protocol_account_id)<>''
        AND EXISTS(SELECT 1 FROM account_credential c WHERE c.tenant_id=a.tenant_id
          AND c.account_id=a.id AND c.deleted_at IS NULL AND c.cred_format IN(1,3))) backend_primary_android_shape
FROM account a
LEFT JOIN account_state s ON s.tenant_id=a.tenant_id AND s.account_id=a.id
LEFT JOIN account_group g ON g.id=a.account_group_id AND g.tenant_id=a.tenant_id
LEFT JOIN account_creator_deletion d ON d.identity_hash=SHA2(a.creator_deletion_identity_phone,256)
WHERE a.id IN (1720,1729,1737,1709,2774,2,3,4)
ORDER BY a.tenant_id,a.id;

SELECT 'mapper_dependencies_excluding_future_script_bindings' evidence_section,ca.tenant_id,ca.id account_id,
(
      EXISTS(SELECT 1 FROM pull_task_group_account r
        INNER JOIN pull_task_group_execution e ON e.id=r.group_execution_id AND e.tenant_id=r.tenant_id
        WHERE r.account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) AND e.execution_status IN (0,1,2,3)
          AND r.released_at IS NULL
          AND NOT (r.tenant_id=ca.tenant_id AND r.group_execution_id=-1
            AND r.task_id=-1 AND r.account_id=ca.id AND r.role_type=4) )
      OR EXISTS(SELECT 1 FROM marketing_account_occupancy o
        INNER JOIN marketing_task mt ON mt.id=o.marketing_task_id AND mt.tenant_id=o.tenant_id
        LEFT JOIN group_pull_marketing_task gp ON gp.marketing_task_id=mt.id AND gp.tenant_id=mt.tenant_id
        WHERE o.account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) AND mt.deleted_at IS NULL
          AND ((mt.business_type=1 AND mt.status IN (1,2,5))
            OR (mt.business_type=2 AND gp.resource_status IN (2,3))) )
      OR EXISTS(SELECT 1 FROM join_task_result r INNER JOIN join_task t ON t.id=r.join_task_id AND t.tenant_id=r.tenant_id
        WHERE r.account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) AND t.deleted_at IS NULL
          AND t.status NOT IN ('STOPPED','DONE','FAILED') )
      OR EXISTS(SELECT 1 FROM hyperlink_task_account_usage u INNER JOIN hyperlink_task_runtime t
        ON t.hyperlink_task_id=u.hyperlink_task_id AND t.tenant_id=u.tenant_id
        WHERE u.account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) AND (t.run_status IN (0,1,3) OR u.in_flight_count>0) )
      OR EXISTS(SELECT 1 FROM feed_task_account a INNER JOIN feed_task t ON t.id=a.task_id AND t.tenant_id=a.tenant_id
        WHERE a.account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) AND t.deleted_at IS NULL AND t.task_status IN (0,1,3) )
      OR EXISTS(SELECT 1 FROM contact_friend_task_account a INNER JOIN contact_friend_task t
        ON t.id=a.task_id AND t.tenant_id=a.tenant_id
        WHERE a.account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) AND t.deleted_at IS NULL AND t.run_status IN (0,1,3) )
      OR EXISTS(SELECT 1 FROM group_pull_marketing_execution e WHERE
        (e.builder_account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) OR e.marketing_account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ))
        AND e.execution_status IN (1,2,7) )
      OR EXISTS(SELECT 1 FROM historical_group_pull_execution e WHERE
        (e.operation_account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) OR e.puller_account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ))
        AND (e.pull_status IN (0,1) OR e.marketing_status IN (1,2)) )
      OR EXISTS(SELECT 1 FROM script_marketing_send_record r WHERE r.account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  )
        AND r.status IN (1,4,5) )
      OR EXISTS(SELECT 1 FROM account_mutual_contact_item i WHERE
        (i.actor_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) OR i.target_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ))
        AND i.status IN (1,2,5) )
      OR EXISTS(SELECT 1 FROM normal_group_creation_item i WHERE i.creator_account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  )
        AND i.deleted_at IS NULL AND i.status IN ('PENDING','RUNNING','RESULT_UNKNOWN')
        AND i.current_step<>'DONE' )
      OR EXISTS(SELECT 1 FROM normal_group_creation_item_member m INNER JOIN normal_group_creation_item i
        ON i.id=m.item_id AND i.tenant_id=m.tenant_id
        WHERE m.member_account_id IN (
    SELECT id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone
  ) AND i.deleted_at IS NULL AND i.status IN ('PENDING','RUNNING','RESULT_UNKNOWN')
        AND i.current_step<>'DONE' )
      OR EXISTS(SELECT 1 FROM protocol_command_outbox o WHERE o.protocol_account_id IN
        (SELECT protocol_account_id FROM account WHERE creator_deletion_identity_phone=ca.creator_deletion_identity_phone) AND o.deleted_at IS NULL AND o.status IN (0,1,5,6) )
    ) AS has_active_dependency
FROM account ca WHERE ca.id IN (1720,1729,1737,1709,2774,2,3,4) ORDER BY ca.tenant_id,ca.id;

SELECT 'identity_aliases' evidence_section,ca.id candidate_account_id,
       aa.tenant_id alias_tenant_id,aa.id alias_account_id,aa.protocol_id,aa.device_os,
       aa.deleted_at,aa.account_group_id,
       REGEXP_REPLACE(g.name,'[+]?[0-9]([[:space:]().-]*[0-9]){6,}','[redacted]') group_name,
       s.login_state,s.desired_login_state,d.lifecycle deletion_lifecycle,
       d.task_id deletion_task_id,d.group_execution_id deletion_execution_id
FROM account ca
JOIN account aa ON aa.creator_deletion_identity_phone=ca.creator_deletion_identity_phone
LEFT JOIN account_state s ON s.account_id=aa.id AND s.tenant_id=aa.tenant_id
LEFT JOIN account_group g ON g.id=aa.account_group_id AND g.tenant_id=aa.tenant_id
LEFT JOIN account_creator_deletion d ON d.identity_hash=SHA2(aa.creator_deletion_identity_phone,256)
WHERE ca.id IN (1720,1729,1737,1709,2774,2,3,4) ORDER BY ca.id,aa.tenant_id,aa.id;

SELECT 'active_pull_roles' evidence_section,ca.id candidate_account_id,
       r.tenant_id,r.task_id,r.group_execution_id,r.account_id,r.role_type,
       r.membership_status,r.admin_status,r.availability_status,r.released_at,
       e.execution_status,e.stage,e.manual_paused,t.status task_status,
       aa.account_group_id,REGEXP_REPLACE(g.name,'[+]?[0-9]([[:space:]().-]*[0-9]){6,}','[redacted]') group_name
FROM account ca
JOIN account aa ON aa.creator_deletion_identity_phone=ca.creator_deletion_identity_phone
JOIN pull_task_group_account r ON r.account_id=aa.id
JOIN pull_task_group_execution e ON e.id=r.group_execution_id AND e.tenant_id=r.tenant_id
JOIN pull_task t ON t.id=r.task_id AND t.tenant_id=r.tenant_id
LEFT JOIN account_group g ON g.id=aa.account_group_id AND g.tenant_id=aa.tenant_id
WHERE ca.id IN (1720,1729,1737,1709,2774,2,3,4) AND e.execution_status IN(0,1,2,3) AND r.released_at IS NULL
ORDER BY ca.id,r.tenant_id,r.task_id,r.group_execution_id,r.id;

SELECT 'future_script_structure' evidence_section,ca.id candidate_account_id,
       a.tenant_id,a.id alias_account_id,t.id script_task_id,t.status,g.id script_group_id,
       JSON_VALID(t.steps_json) steps_json_valid,
       CASE WHEN JSON_VALID(t.steps_json) THEN JSON_TYPE(t.steps_json) END steps_type,
       CASE WHEN JSON_VALID(t.steps_json) THEN JSON_LENGTH(t.steps_json) END steps_count,
       (g.bindings_json IS NULL OR TRIM(g.bindings_json)='') bindings_absent,
       JSON_VALID(g.bindings_json) bindings_json_valid,
       CASE WHEN JSON_VALID(g.bindings_json) THEN JSON_TYPE(g.bindings_json) END bindings_type
FROM account ca
JOIN account a ON a.creator_deletion_identity_phone=ca.creator_deletion_identity_phone
JOIN script_marketing_task t ON t.tenant_id=a.tenant_id AND t.status IN(1,2)
LEFT JOIN script_marketing_group g ON g.task_id=t.id AND g.tenant_id=t.tenant_id
WHERE ca.id IN (1720,1729,1737,1709,2774,2,3,4) ORDER BY ca.id,a.id,t.id,g.id;

WITH script_steps AS (
 SELECT ca.id candidate_account_id,a.tenant_id,a.id alias_account_id,t.id script_task_id,
        st.step_no,
        JSON_TYPE(JSON_EXTRACT(t.steps_json,CONCAT('$[',st.step_no-1,']'))) step_type,
        JSON_EXTRACT(t.steps_json,CONCAT('$[',st.step_no-1,'].accountId')) reference_value
 FROM account ca
 JOIN account a ON a.creator_deletion_identity_phone=ca.creator_deletion_identity_phone
 JOIN script_marketing_task t ON t.tenant_id=a.tenant_id AND t.status IN(1,2)
 JOIN JSON_TABLE(CASE WHEN JSON_VALID(t.steps_json) AND JSON_TYPE(t.steps_json)='ARRAY'
                   THEN t.steps_json ELSE JSON_ARRAY() END,
                 '$[*]' COLUMNS(step_no FOR ORDINALITY)) st
 WHERE ca.id IN (1720,1729,1737,1709,2774,2,3,4)
)
SELECT 'future_script_step_safe_projection' evidence_section,candidate_account_id,tenant_id,
       alias_account_id,script_task_id,step_no,step_type,JSON_TYPE(reference_value) reference_type,
       (reference_value IS NULL OR JSON_TYPE(reference_value)='NULL') reference_absent,
       CASE WHEN JSON_TYPE(reference_value) IN('INTEGER','STRING')
              AND JSON_UNQUOTE(reference_value) REGEXP '^[+-]?[0-9]{1,18}$'
            THEN CAST(JSON_UNQUOTE(reference_value) AS SIGNED) END numeric_reference,
       CASE WHEN JSON_TYPE(reference_value) IN('INTEGER','STRING')
              AND JSON_UNQUOTE(reference_value) REGEXP '^[+-]?[0-9]{1,18}$'
            THEN CAST(JSON_UNQUOTE(reference_value) AS SIGNED)=alias_account_id END references_alias
FROM script_steps ORDER BY candidate_account_id,alias_account_id,script_task_id,step_no;

WITH script_roles AS (
 SELECT ca.id candidate_account_id,a.tenant_id,a.id alias_account_id,t.id script_task_id,
        g.id script_group_id,bk.binding_no,
        JSON_EXTRACT(g.bindings_json,CONCAT('$.',JSON_QUOTE(bk.binding_key))) reference_value
 FROM account ca
 JOIN account a ON a.creator_deletion_identity_phone=ca.creator_deletion_identity_phone
 JOIN script_marketing_task t ON t.tenant_id=a.tenant_id AND t.status IN(1,2)
 JOIN script_marketing_group g ON g.task_id=t.id AND g.tenant_id=t.tenant_id
 JOIN JSON_TABLE(JSON_KEYS(CASE WHEN JSON_VALID(g.bindings_json) AND JSON_TYPE(g.bindings_json)='OBJECT'
                              THEN g.bindings_json ELSE JSON_OBJECT() END),
                 '$[*]' COLUMNS(binding_no FOR ORDINALITY,binding_key VARCHAR(255) PATH '$')) bk
 WHERE ca.id IN (1720,1729,1737,1709,2774,2,3,4)
)
SELECT 'future_script_role_safe_projection' evidence_section,candidate_account_id,tenant_id,
       alias_account_id,script_task_id,script_group_id,binding_no,
       JSON_TYPE(reference_value) reference_type,
       (reference_value IS NULL OR JSON_TYPE(reference_value)='NULL') reference_absent,
       CASE WHEN JSON_TYPE(reference_value) IN('INTEGER','STRING')
              AND JSON_UNQUOTE(reference_value) REGEXP '^[+-]?[0-9]{1,18}$'
            THEN CAST(JSON_UNQUOTE(reference_value) AS SIGNED) END numeric_reference,
       CASE WHEN JSON_TYPE(reference_value) IN('INTEGER','STRING')
              AND JSON_UNQUOTE(reference_value) REGEXP '^[+-]?[0-9]{1,18}$'
            THEN CAST(JSON_UNQUOTE(reference_value) AS SIGNED)=alias_account_id END references_alias
FROM script_roles ORDER BY candidate_account_id,alias_account_id,script_task_id,script_group_id,binding_no;

SELECT 'all_undeleted_web_route_candidates' evidence_section,a.tenant_id,a.id account_id,a.owner_user_id,
       a.protocol_id,a.device_os,a.account_group_id,
       REGEXP_REPLACE(g.name,'[+]?[0-9]([[:space:]().-]*[0-9]){6,}','[redacted]') group_name,
       g.marketing_occupancy_type,g.marketing_occupancy_task_id,
       a.deleted_at,s.login_state,s.desired_login_state,s.account_state,REGEXP_REPLACE(s.state_source,'[+]?[0-9]([[:space:]().-]*[0-9]){6,}','[redacted]') state_source,
       CONCAT('***',RIGHT(a.creator_deletion_identity_phone,4)) phone_tail,
       SHA2(a.creator_deletion_identity_phone,256) identity_hash,
       (a.protocol_account_id IS NOT NULL AND TRIM(a.protocol_account_id)<>'') protocol_route_present,
       (SELECT GROUP_CONCAT(c.cred_format ORDER BY c.id) FROM account_credential c
        WHERE c.tenant_id=a.tenant_id AND c.account_id=a.id AND c.deleted_at IS NULL) cred_formats,
       (SELECT COUNT(*) FROM account aa
        WHERE aa.creator_deletion_identity_phone=a.creator_deletion_identity_phone) identity_alias_count,
       d.lifecycle deletion_lifecycle,d.task_id deletion_task_id,d.group_execution_id deletion_execution_id,
       (a.deleted_at IS NULL AND UPPER(TRIM(a.protocol_id))='ANDROID' AND a.device_os=1
        AND TRIM(a.ws_phone)<>'' AND TRIM(a.protocol_account_id)<>''
        AND EXISTS(SELECT 1 FROM account_credential c WHERE c.tenant_id=a.tenant_id
          AND c.account_id=a.id AND c.deleted_at IS NULL AND c.cred_format IN(1,3))) backend_primary_android_shape
FROM account a
LEFT JOIN account_state s ON s.tenant_id=a.tenant_id AND s.account_id=a.id
LEFT JOIN account_group g ON g.id=a.account_group_id AND g.tenant_id=a.tenant_id
LEFT JOIN account_creator_deletion d ON d.identity_hash=SHA2(a.creator_deletion_identity_phone,256)
WHERE a.deleted_at IS NULL AND COALESCE(UPPER(TRIM(a.protocol_id)),'')<>'ANDROID'
ORDER BY a.tenant_id,a.id;
