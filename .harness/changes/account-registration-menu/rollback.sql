-- 回滚方案模板：确认目标环境后放入新的 Flyway 版本执行，不直接操作共享库。
-- 保留菜单和授权记录，便于后续恢复；不影响注册订单及账号导入入口。
UPDATE sys_menu
SET status = 0,
    updated_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
WHERE menu_key = 'AccountRegistration'
  AND route_path = '/account/registration'
  AND component_path = 'account/registration/index';
