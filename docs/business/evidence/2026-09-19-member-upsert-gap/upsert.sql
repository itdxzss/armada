
    INSERT INTO wa_group_participant (tenant_id,
      group_id, pn_jid, lid_jid, phone,
      presence_status, presence_source, presence_observed_at, presence_event_id,
      last_joined_at, last_join_event_at, last_join_source_event_id,
      last_exited_at, last_exit_type, last_exit_event_at,
      last_exit_source_event_id, last_exit_source_type,
      role, role_source, role_observed_at, role_event_id, last_snapshot_version,
      created_at, updated_at
    ) VALUES (1,
        20, NULL, '213@lid', '10001',
        1, 'ADD_EVENT', 100, 'event-1',
        100,
        CASE WHEN 100 IS NULL THEN NULL ELSE 100 END,
        CASE WHEN 100 IS NULL THEN NULL ELSE 'event-1' END,
        NULL, NULL,
        CASE WHEN NULL IS NULL THEN NULL ELSE 100 END,
        CASE WHEN NULL IS NULL THEN NULL ELSE 'event-1' END,
        NULL,
        COALESCE(NULL, 0), NULL, NULL,
        NULL, NULL,
        110, 110
      )
    
    ON DUPLICATE KEY UPDATE
      
      pn_jid = COALESCE(pn_jid, VALUES(pn_jid)),
      lid_jid = COALESCE(lid_jid, VALUES(lid_jid)),
      phone = COALESCE(VALUES(phone), phone),
      presence_status = CASE
        WHEN 
    (
      VALUES(presence_observed_at) > COALESCE(wa_group_participant.presence_observed_at, -1)
      OR (
        VALUES(presence_observed_at) = wa_group_participant.presence_observed_at
        AND (
          CASE VALUES(presence_source)
              WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
              WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
              WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
              WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
              WHEN 'GROUP_MEMBER_QUERY' THEN 2
              WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
              WHEN 'GROUP_SNAPSHOT' THEN 1
              ELSE 0
            END
            > CASE wa_group_participant.presence_source
              WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
              WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
              WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
              WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
              WHEN 'GROUP_MEMBER_QUERY' THEN 2
              WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
              WHEN 'GROUP_SNAPSHOT' THEN 1
              ELSE 0
            END
          OR (
            VALUES(presence_source) IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND wa_group_participant.presence_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND CAST(COALESCE(VALUES(last_snapshot_version), '') AS BINARY)
                > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
          )
        )
      )
    )
   THEN VALUES(presence_status)
        ELSE presence_status
      END,
      presence_source = CASE
        WHEN 
    (
      VALUES(presence_observed_at) > COALESCE(wa_group_participant.presence_observed_at, -1)
      OR (
        VALUES(presence_observed_at) = wa_group_participant.presence_observed_at
        AND (
          CASE VALUES(presence_source)
              WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
              WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
              WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
              WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
              WHEN 'GROUP_MEMBER_QUERY' THEN 2
              WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
              WHEN 'GROUP_SNAPSHOT' THEN 1
              ELSE 0
            END
            > CASE wa_group_participant.presence_source
              WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
              WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
              WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
              WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
              WHEN 'GROUP_MEMBER_QUERY' THEN 2
              WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
              WHEN 'GROUP_SNAPSHOT' THEN 1
              ELSE 0
            END
          OR (
            VALUES(presence_source) IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND wa_group_participant.presence_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND CAST(COALESCE(VALUES(last_snapshot_version), '') AS BINARY)
                > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
          )
        )
      )
    )
   THEN VALUES(presence_source)
        ELSE presence_source
      END,
      presence_event_id = CASE
        WHEN 
    (
      VALUES(presence_observed_at) > COALESCE(wa_group_participant.presence_observed_at, -1)
      OR (
        VALUES(presence_observed_at) = wa_group_participant.presence_observed_at
        AND (
          CASE VALUES(presence_source)
              WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
              WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
              WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
              WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
              WHEN 'GROUP_MEMBER_QUERY' THEN 2
              WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
              WHEN 'GROUP_SNAPSHOT' THEN 1
              ELSE 0
            END
            > CASE wa_group_participant.presence_source
              WHEN 'WGP2_REMOVE' THEN 5 WHEN 'WGP2_LEAVE' THEN 5
              WHEN 'REMOVE_EVENT' THEN 5 WHEN 'LEAVE_EVENT' THEN 5
              WHEN 'UNKNOWN_EXIT_EVENT' THEN 5
              WHEN 'GROUP_SNAPSHOT_NOT_JOINED' THEN 5
              WHEN 'WGP2_PROMOTE' THEN 4 WHEN 'WGP2_DEMOTE' THEN 4
              WHEN 'WGP2_ADD' THEN 3 WHEN 'ADD_EVENT' THEN 3
              WHEN 'GROUP_MEMBER_QUERY' THEN 2
              WHEN 'FULL_SNAPSHOT' THEN 2 WHEN 'SNAPSHOT_ABSENT' THEN 2
              WHEN 'GROUP_SNAPSHOT' THEN 1
              ELSE 0
            END
          OR (
            VALUES(presence_source) IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND wa_group_participant.presence_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND CAST(COALESCE(VALUES(last_snapshot_version), '') AS BINARY)
                > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
          )
        )
      )
    )
   THEN VALUES(presence_event_id)
        ELSE presence_event_id
      END,
      presence_observed_at = GREATEST(
        COALESCE(presence_observed_at, VALUES(presence_observed_at)),
        VALUES(presence_observed_at)
      ),
      last_joined_at = CASE
        WHEN VALUES(last_join_event_at) IS NOT NULL
         AND (last_join_event_at IS NULL OR VALUES(last_join_event_at) >= last_join_event_at)
          THEN VALUES(last_joined_at)
        ELSE last_joined_at
      END,
      last_join_source_event_id = CASE
        WHEN VALUES(last_join_event_at) IS NOT NULL
         AND (last_join_event_at IS NULL OR VALUES(last_join_event_at) >= last_join_event_at)
          THEN VALUES(last_join_source_event_id)
        ELSE last_join_source_event_id
      END,
      last_join_event_at = CASE
        WHEN VALUES(last_join_event_at) IS NOT NULL
         AND (last_join_event_at IS NULL OR VALUES(last_join_event_at) >= last_join_event_at)
          THEN VALUES(last_join_event_at)
        ELSE last_join_event_at
      END,
      last_exited_at = CASE
        WHEN VALUES(last_exit_event_at) IS NOT NULL
         AND (last_exit_event_at IS NULL OR VALUES(last_exit_event_at) >= last_exit_event_at)
          THEN VALUES(last_exited_at)
        ELSE last_exited_at
      END,
      last_exit_type = CASE
        WHEN VALUES(last_exit_event_at) IS NOT NULL
         AND (last_exit_event_at IS NULL OR VALUES(last_exit_event_at) >= last_exit_event_at)
          THEN VALUES(last_exit_type)
        ELSE last_exit_type
      END,
      last_exit_source_event_id = CASE
        WHEN VALUES(last_exit_event_at) IS NOT NULL
         AND (last_exit_event_at IS NULL OR VALUES(last_exit_event_at) >= last_exit_event_at)
          THEN VALUES(last_exit_source_event_id)
        ELSE last_exit_source_event_id
      END,
      last_exit_source_type = CASE
        WHEN VALUES(last_exit_event_at) IS NOT NULL
         AND (last_exit_event_at IS NULL OR VALUES(last_exit_event_at) >= last_exit_event_at)
          THEN VALUES(last_exit_source_type)
        ELSE last_exit_source_type
      END,
      last_exit_event_at = CASE
        WHEN VALUES(last_exit_event_at) IS NOT NULL
         AND (last_exit_event_at IS NULL OR VALUES(last_exit_event_at) >= last_exit_event_at)
          THEN VALUES(last_exit_event_at)
        ELSE last_exit_event_at
      END,
      role = CASE
        WHEN VALUES(role) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   < 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
      )
      AND (
        VALUES(role_observed_at) > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          VALUES(role_observed_at) = wa_group_participant.role_observed_at
          AND (
            
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   > 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
            OR (
              VALUES(role_source) = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(VALUES(last_snapshot_version), '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
   THEN VALUES(role)
        ELSE role
      END,
      role_source = CASE
        WHEN VALUES(role) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   < 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
      )
      AND (
        VALUES(role_observed_at) > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          VALUES(role_observed_at) = wa_group_participant.role_observed_at
          AND (
            
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   > 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
            OR (
              VALUES(role_source) = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(VALUES(last_snapshot_version), '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
   THEN VALUES(role_source)
        ELSE role_source
      END,
      role_observed_at = CASE
        WHEN VALUES(role) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   < 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
      )
      AND (
        VALUES(role_observed_at) > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          VALUES(role_observed_at) = wa_group_participant.role_observed_at
          AND (
            
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   > 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
            OR (
              VALUES(role_source) = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(VALUES(last_snapshot_version), '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
  
          THEN VALUES(role_observed_at)
        ELSE role_observed_at
      END,
      role_event_id = CASE
        WHEN VALUES(role) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   < 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
      )
      AND (
        VALUES(role_observed_at) > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          VALUES(role_observed_at) = wa_group_participant.role_observed_at
          AND (
            
    CASE VALUES(role_source)
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
   > 
    CASE wa_group_participant.role_source
      WHEN 'WGP2_PROMOTE' THEN 4
      WHEN 'WGP2_DEMOTE' THEN 4
      WHEN 'WGP2_ADD' THEN 3
      WHEN 'GROUP_MEMBER_QUERY' THEN 2
      WHEN 'FULL_SNAPSHOT' THEN 2
      WHEN 'LEGACY_MEMBER_SNAPSHOT' THEN 2
      WHEN 'LEGACY_METADATA_OWNER' THEN 2
      WHEN 'GROUP_SNAPSHOT' THEN 1
      ELSE 0
    END
  
            OR (
              VALUES(role_source) = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(VALUES(last_snapshot_version), '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
   THEN VALUES(role_event_id)
        ELSE role_event_id
      END,
      last_snapshot_version = COALESCE(
        VALUES(last_snapshot_version), last_snapshot_version
      ),
      updated_at = GREATEST(updated_at, VALUES(updated_at))
  ;
