-- 仅在确认该账号仍为 V072 初始化数据且未被实际使用时执行。
DELETE relation
FROM sys_user_role relation
JOIN sys_user account_user ON account_user.id = relation.user_id
WHERE account_user.tenant_id = 1 AND account_user.username = 'admin';

DELETE FROM sys_user
WHERE tenant_id = 1 AND username = 'admin';
