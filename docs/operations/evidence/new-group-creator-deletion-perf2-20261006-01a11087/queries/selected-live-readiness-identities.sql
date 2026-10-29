SELECT JSON_OBJECT(
 'accountId',a.id,'tenantId',a.tenant_id,'protocol',UPPER(TRIM(a.protocol_id)),
 'protocolAccountId',a.protocol_account_id,'phone',a.ws_phone,'deviceOs',a.device_os,
 'dbLoginState',s.login_state,'dbDesiredLoginState',s.desired_login_state,
 'backendPrimaryAndroidShape',(a.deleted_at IS NULL AND UPPER(TRIM(a.protocol_id))='ANDROID'
  AND a.device_os=1 AND TRIM(a.ws_phone)<>'' AND TRIM(a.protocol_account_id)<>''
  AND EXISTS(SELECT 1 FROM account_credential c WHERE c.tenant_id=a.tenant_id
   AND c.account_id=a.id AND c.deleted_at IS NULL AND c.cred_format IN(1,3)))
) selected_identity
FROM account a LEFT JOIN account_state s ON s.tenant_id=a.tenant_id AND s.account_id=a.id
WHERE a.tenant_id=1 AND a.deleted_at IS NULL AND a.id IN (1720,1737,2187,1729,1709,2774)
ORDER BY a.id;
