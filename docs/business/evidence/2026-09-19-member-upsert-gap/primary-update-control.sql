UPDATE wa_group_participant SET pn_jid = COALESCE(pn_jid, NULL),
      lid_jid = COALESCE(lid_jid, '213@lid'),
      phone = COALESCE('10001', phone),
      presence_status = CASE
        WHEN 
    (
      100 > COALESCE(wa_group_participant.presence_observed_at, -1)
      OR (
        100 = wa_group_participant.presence_observed_at
        AND (
          CASE 'ADD_EVENT'
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
            'ADD_EVENT' IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND wa_group_participant.presence_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND CAST(COALESCE(NULL, '') AS BINARY)
                > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
          )
        )
      )
    )
   THEN 1
        ELSE presence_status
      END,
      presence_source = CASE
        WHEN 
    (
      100 > COALESCE(wa_group_participant.presence_observed_at, -1)
      OR (
        100 = wa_group_participant.presence_observed_at
        AND (
          CASE 'ADD_EVENT'
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
            'ADD_EVENT' IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND wa_group_participant.presence_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND CAST(COALESCE(NULL, '') AS BINARY)
                > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
          )
        )
      )
    )
   THEN 'ADD_EVENT'
        ELSE presence_source
      END,
      presence_event_id = CASE
        WHEN 
    (
      100 > COALESCE(wa_group_participant.presence_observed_at, -1)
      OR (
        100 = wa_group_participant.presence_observed_at
        AND (
          CASE 'ADD_EVENT'
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
            'ADD_EVENT' IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND wa_group_participant.presence_source IN ('FULL_SNAPSHOT', 'SNAPSHOT_ABSENT')
            AND CAST(COALESCE(NULL, '') AS BINARY)
                > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
          )
        )
      )
    )
   THEN 'event-1'
        ELSE presence_event_id
      END,
      presence_observed_at = GREATEST(
        COALESCE(presence_observed_at, 100),
        100
      ),
      last_joined_at = CASE
        WHEN CASE WHEN 100 IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_join_event_at IS NULL OR CASE WHEN 100 IS NULL THEN NULL ELSE 100 END >= last_join_event_at)
          THEN 100
        ELSE last_joined_at
      END,
      last_join_source_event_id = CASE
        WHEN CASE WHEN 100 IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_join_event_at IS NULL OR CASE WHEN 100 IS NULL THEN NULL ELSE 100 END >= last_join_event_at)
          THEN CASE WHEN 100 IS NULL THEN NULL ELSE 'event-1' END
        ELSE last_join_source_event_id
      END,
      last_join_event_at = CASE
        WHEN CASE WHEN 100 IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_join_event_at IS NULL OR CASE WHEN 100 IS NULL THEN NULL ELSE 100 END >= last_join_event_at)
          THEN CASE WHEN 100 IS NULL THEN NULL ELSE 100 END
        ELSE last_join_event_at
      END,
      last_exited_at = CASE
        WHEN CASE WHEN NULL IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_exit_event_at IS NULL OR CASE WHEN NULL IS NULL THEN NULL ELSE 100 END >= last_exit_event_at)
          THEN NULL
        ELSE last_exited_at
      END,
      last_exit_type = CASE
        WHEN CASE WHEN NULL IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_exit_event_at IS NULL OR CASE WHEN NULL IS NULL THEN NULL ELSE 100 END >= last_exit_event_at)
          THEN NULL
        ELSE last_exit_type
      END,
      last_exit_source_event_id = CASE
        WHEN CASE WHEN NULL IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_exit_event_at IS NULL OR CASE WHEN NULL IS NULL THEN NULL ELSE 100 END >= last_exit_event_at)
          THEN CASE WHEN NULL IS NULL THEN NULL ELSE 'event-1' END
        ELSE last_exit_source_event_id
      END,
      last_exit_source_type = CASE
        WHEN CASE WHEN NULL IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_exit_event_at IS NULL OR CASE WHEN NULL IS NULL THEN NULL ELSE 100 END >= last_exit_event_at)
          THEN NULL
        ELSE last_exit_source_type
      END,
      last_exit_event_at = CASE
        WHEN CASE WHEN NULL IS NULL THEN NULL ELSE 100 END IS NOT NULL
         AND (last_exit_event_at IS NULL OR CASE WHEN NULL IS NULL THEN NULL ELSE 100 END >= last_exit_event_at)
          THEN CASE WHEN NULL IS NULL THEN NULL ELSE 100 END
        ELSE last_exit_event_at
      END,
      role = CASE
        WHEN COALESCE(NULL, 0) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE NULL
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
        NULL > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          NULL = wa_group_participant.role_observed_at
          AND (
            
    CASE NULL
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
              NULL = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(NULL, '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
   THEN COALESCE(NULL, 0)
        ELSE role
      END,
      role_source = CASE
        WHEN COALESCE(NULL, 0) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE NULL
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
        NULL > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          NULL = wa_group_participant.role_observed_at
          AND (
            
    CASE NULL
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
              NULL = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(NULL, '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
   THEN NULL
        ELSE role_source
      END,
      role_observed_at = CASE
        WHEN COALESCE(NULL, 0) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE NULL
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
        NULL > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          NULL = wa_group_participant.role_observed_at
          AND (
            
    CASE NULL
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
              NULL = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(NULL, '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
  
          THEN NULL
        ELSE role_observed_at
      END,
      role_event_id = CASE
        WHEN COALESCE(NULL, 0) <> 0 AND 
    (
      
      NOT (
        wa_group_participant.role <> 0
        AND 
    CASE NULL
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
        NULL > COALESCE(wa_group_participant.role_observed_at, -1)
        OR (
          NULL = wa_group_participant.role_observed_at
          AND (
            
    CASE NULL
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
              NULL = 'FULL_SNAPSHOT'
              AND wa_group_participant.role_source = 'FULL_SNAPSHOT'
              AND CAST(COALESCE(NULL, '') AS BINARY)
                  > CAST(COALESCE(wa_group_participant.last_snapshot_version, '') AS BINARY)
            )
          )
        )
      )
    )
   THEN NULL
        ELSE role_event_id
      END,
      last_snapshot_version = COALESCE(
        NULL, last_snapshot_version
      ),
      updated_at = GREATEST(updated_at, 110)
   WHERE tenant_id=1 AND id=11;