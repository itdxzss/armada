-- 破坏性回滚脚本：会删除系统管理 RBAC 的全部用户、角色、菜单和授权数据。
-- 仅允许在确认旧版本应用已回退、数据库已备份且 V071 确实需要整体撤销时执行。
-- Flyway 已登记 V071 的环境还需要按部署规范修复 schema history，不能只执行本文件。

DROP TABLE IF EXISTS sys_role_menu;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_menu;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_user;
