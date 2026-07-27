-- 默认租户真实登录管理员；密码列仅保存 DelegatingPasswordEncoder BCrypt 哈希。
SET @auth_seed_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

INSERT IGNORE INTO sys_user
    (tenant_id, username, nickname, password_hash, status,
     created_at, created_by, updated_at, updated_by)
SELECT tenant.id, 'admin', '管理员',
       '{bcrypt}$2y$10$e5Bybu.dc4qwXpoEp.vb5utBYn/krqIUilAyvCfZY3L3Vc6sMTDmG',
       1, @auth_seed_now, NULL, @auth_seed_now, NULL
FROM tenant
WHERE tenant.id = 1
  AND tenant.status = 1;

INSERT IGNORE INTO sys_user_role (tenant_id, user_id, role_id)
SELECT account_user.tenant_id, account_user.id, role.id
FROM sys_user account_user
JOIN sys_role role
  ON role.tenant_id = account_user.tenant_id
 AND role.role_code = 'TENANT_ADMIN'
WHERE account_user.tenant_id = 1
  AND account_user.username = 'admin'
  AND account_user.password_hash = '{bcrypt}$2y$10$e5Bybu.dc4qwXpoEp.vb5utBYn/krqIUilAyvCfZY3L3Vc6sMTDmG';
