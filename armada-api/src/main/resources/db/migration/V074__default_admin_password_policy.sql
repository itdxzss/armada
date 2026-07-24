-- V073 已在测试环境执行，不能修改其校验和；仅修正仍保留 admin 初始密码的默认管理员。
SET @admin_password_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

UPDATE sys_user
SET password_hash = '{bcrypt}$2y$10$i1EGxsMaUUxIdn2QINEGIupKWI9Tl3Vuelovv4/zs6qR7AWc6Mt2W',
    updated_at = @admin_password_now,
    updated_by = NULL
WHERE tenant_id = 1
  AND username = 'admin'
  AND password_hash = '{bcrypt}$2y$10$NkTkmZM.1O2DzgUrxes4/uuXUIL3dIhdijb1tvfaeu7wM9EM4YkKG';
