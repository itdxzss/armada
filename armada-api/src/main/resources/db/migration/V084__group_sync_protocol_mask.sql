ALTER TABLE group_link
    ADD COLUMN sync_protocol_mask TINYINT NOT NULL DEFAULT 0
        COMMENT '曾被账号群同步观察到的协议位:1=WEB(JSON号),2=ANDROID(六段号),3=两者';

UPDATE group_link group_link
INNER JOIN (
    SELECT membership.tenant_id,
           membership.group_link_id,
           BIT_OR(
               CASE
                   WHEN UPPER(TRIM(account.protocol_id)) = 'ANDROID' THEN 2
                   ELSE 1
               END
           ) AS sync_protocol_mask
    FROM account_group_membership membership
    INNER JOIN account account
      ON account.tenant_id = membership.tenant_id
     AND account.id = membership.account_id
    GROUP BY membership.tenant_id, membership.group_link_id
) observed
  ON observed.tenant_id = group_link.tenant_id
 AND observed.group_link_id = group_link.id
SET group_link.sync_protocol_mask = observed.sync_protocol_mask;
