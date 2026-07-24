-- 仅把 V072 创建且尚未改过密码的默认管理员调整为 admin/admin，不覆盖后续人工修改的密码。
SET @admin_password_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

UPDATE sys_user
SET password_hash = '{bcrypt}$2y$10$NkTkmZM.1O2DzgUrxes4/uuXUIL3dIhdijb1tvfaeu7wM9EM4YkKG',
    updated_at = @admin_password_now,
    updated_by = NULL
WHERE tenant_id = 1
  AND username = 'admin'
  AND password_hash = '{bcrypt}$2y$10$e5Bybu.dc4qwXpoEp.vb5utBYn/krqIUilAyvCfZY3L3Vc6sMTDmG';
