-- 租户内系统管理：用户、角色、菜单及其关联关系。

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id     BIGINT       NOT NULL COMMENT '所属租户ID',
    username      VARCHAR(64)  NOT NULL COMMENT '登录用户名，租户内唯一，创建后不可修改',
    nickname      VARCHAR(64)  DEFAULT NULL COMMENT '用户昵称，为空时展示用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT 'DelegatingPasswordEncoder单向密码哈希',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1启用 0禁用',
    created_at    BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒)',
    created_by    BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    updated_at    BIGINT       NOT NULL COMMENT '更新时间(epoch毫秒)',
    updated_by    BIGINT       DEFAULT NULL COMMENT '最后修改人用户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uq_sys_user_tenant_username (tenant_id, username),
    KEY idx_sys_user_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户';

CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id   BIGINT       NOT NULL COMMENT '所属租户ID',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称，租户内唯一',
    role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码，租户内唯一，创建后不可修改',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1启用 0禁用',
    is_system   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否系统内置角色:1是 0否',
    remark      VARCHAR(255) DEFAULT NULL COMMENT '角色说明',
    created_at  BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒)',
    created_by  BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    updated_at  BIGINT       NOT NULL COMMENT '更新时间(epoch毫秒)',
    updated_by  BIGINT       DEFAULT NULL COMMENT '最后修改人用户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uq_sys_role_tenant_name (tenant_id, role_name),
    UNIQUE KEY uq_sys_role_tenant_code (tenant_id, role_code),
    KEY idx_sys_role_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统角色';

CREATE TABLE IF NOT EXISTS sys_menu (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT       NOT NULL COMMENT '所属租户ID',
    parent_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '父节点ID，根目录为0',
    menu_name      VARCHAR(64)  NOT NULL COMMENT '节点名称',
    menu_key       VARCHAR(64)  NOT NULL COMMENT '节点稳定标识，租户内唯一',
    menu_type      CHAR(1)      NOT NULL COMMENT '节点类型:D目录 M菜单 B按钮',
    route_path     VARCHAR(128) DEFAULT NULL COMMENT '目录或菜单路由，仅D/M使用',
    component_path VARCHAR(128) DEFAULT NULL COMMENT '前端组件路径，仅M使用',
    perm_key       VARCHAR(128) DEFAULT NULL COMMENT '页面或按钮权限编码，仅M/B使用',
    icon           VARCHAR(64)  DEFAULT NULL COMMENT '菜单图标，仅D/M使用',
    sort_no        INT          NOT NULL DEFAULT 0 COMMENT '同级排序，数值越小越靠前',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:1启用 0禁用',
    created_at     BIGINT       NOT NULL COMMENT '创建时间(epoch毫秒)',
    created_by     BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    updated_at     BIGINT       NOT NULL COMMENT '更新时间(epoch毫秒)',
    updated_by     BIGINT       DEFAULT NULL COMMENT '最后修改人用户ID',
    PRIMARY KEY (id),
    UNIQUE KEY uq_sys_menu_tenant_key (tenant_id, menu_key),
    UNIQUE KEY uq_sys_menu_tenant_route (tenant_id, route_path),
    KEY idx_sys_menu_tenant_perm (tenant_id, perm_key),
    KEY idx_sys_menu_tenant_parent_sort (tenant_id, parent_id, sort_no),
    KEY idx_sys_menu_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统菜单与权限节点';

CREATE TABLE IF NOT EXISTS sys_user_role (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    user_id   BIGINT NOT NULL COMMENT '用户ID',
    role_id   BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (tenant_id, user_id, role_id),
    KEY idx_sys_user_role_role (tenant_id, role_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS sys_role_menu (
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    role_id   BIGINT NOT NULL COMMENT '角色ID',
    menu_id   BIGINT NOT NULL COMMENT '菜单或按钮节点ID，不保存目录节点',
    PRIMARY KEY (tenant_id, role_id, menu_id),
    KEY idx_sys_role_menu_menu (tenant_id, menu_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色菜单权限关联';

SET @rbac_seed_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

-- 每个已启用租户初始化一个内置管理员角色；该角色权限由服务端动态计算，不写角色菜单关联。
INSERT IGNORE INTO sys_role
    (tenant_id, role_name, role_code, status, is_system, remark,
     created_at, created_by, updated_at, updated_by)
SELECT id, '租户管理员', 'TENANT_ADMIN', 1, 1, '系统内置管理员角色',
       @rbac_seed_now, NULL, @rbac_seed_now, NULL
FROM tenant
WHERE status = 1;

-- 临时目录仅用于把统一菜单模板复制到当前已启用租户。
CREATE TEMPORARY TABLE tmp_system_menu_seed (
    parent_key     VARCHAR(64)  DEFAULT NULL,
    menu_name      VARCHAR(64)  NOT NULL,
    menu_key       VARCHAR(64)  NOT NULL,
    menu_type      CHAR(1)      NOT NULL,
    route_path     VARCHAR(128) DEFAULT NULL,
    component_path VARCHAR(128) DEFAULT NULL,
    perm_key       VARCHAR(128) DEFAULT NULL,
    icon           VARCHAR(64)  DEFAULT NULL,
    sort_no        INT          NOT NULL
);

INSERT INTO tmp_system_menu_seed
    (parent_key, menu_name, menu_key, menu_type, route_path, component_path, perm_key, icon, sort_no)
VALUES
    (NULL, '账号管理', 'AccountManagement', 'D', '/account', NULL, NULL, 'ep:user', 10),
    (NULL, '群组管理', 'GroupManagement', 'D', '/group', NULL, NULL, 'ep:chat-dot-round', 20),
    (NULL, '任务中心', 'TaskCenter', 'D', '/task', NULL, NULL, 'ep:list', 30),
    (NULL, '素材管理', 'MaterialManagement', 'D', '/material', NULL, NULL, 'ep:document', 40),
    (NULL, '资源管理', 'ResourceManagement', 'D', '/resource', NULL, NULL, 'ep:connection', 50),
    (NULL, '买号上量', 'BuyerGrowth', 'D', '/buyer', NULL, NULL, 'ep:trend-charts', 60),
    (NULL, '系统管理', 'SystemManagement', 'D', '/system', NULL, NULL, 'ep:setting', 70),

    ('AccountManagement', '账号列表', 'AccountIndex', 'M', '/account/index', 'account/index/index', 'tenant:account:view', NULL, 10),
    ('AccountManagement', '账号分组', 'AccountGroup', 'M', '/account/group/index', 'account/group/index', 'tenant:account-group:view', NULL, 20),
    ('AccountManagement', '账号导入', 'AccountImport', 'M', '/account/import', 'account/import/index', 'tenant:account:edit', NULL, 30),

    ('GroupManagement', '导入链接', 'TaskGroupLinkImports', 'M', '/task/group-link/imports', 'group/imports/index', 'tenant:group_link:view', NULL, 10),
    ('GroupManagement', '群组列表', 'GroupList', 'M', '/group/list', 'group/list/index', 'tenant:group_link:view', NULL, 20),
    ('GroupManagement', '历史群管理', 'HistoricalGroupManagement', 'M', '/group/history', 'group/history/index', 'tenant:historical_group:view', NULL, 30),

    ('TaskCenter', '拉群任务', 'TaskPull', 'M', '/task/pull', 'task/pull-task/index', 'tenant:pull_task:view', NULL, 10),
    ('TaskCenter', '进群任务', 'TaskJoin', 'M', '/task/join', 'task/join-task/index', 'tenant:join_task:view', NULL, 20),
    ('TaskCenter', '营销任务', 'TaskGroupMarketing', 'M', '/task/group-marketing', 'task/group-marketing/index', 'tenant:marketing_task:view', NULL, 30),
    ('TaskCenter', '建群营销', 'TaskGroupCreationMarketing', 'M', '/task/group-creation-marketing', 'task/group-creation-marketing/index', 'tenant:group_creation_marketing:view', NULL, 40),

    ('MaterialManagement', '营销模板', 'TaskMarketingTemplate', 'M', '/task/marketing', 'material/marketing-template/index', 'tenant:marketing_template:view', NULL, 10),

    ('ResourceManagement', 'IP管理', 'ResourceIp', 'M', '/resource/ip', 'resource/ip/index', 'tenant:resource:ips:list', NULL, 10),
    ('ResourceManagement', 'IP数据统计', 'ResourceIpStats', 'M', '/resource/ip-stats', 'resource/ip-stats/index', 'tenant:resource:ip-stats:list', NULL, 20),

    ('BuyerGrowth', '模板管理', 'BuyerTemplate', 'M', '/buyer/promotion/template', 'buyer/template/index', 'tenant:buyer-template:view', NULL, 10),
    ('BuyerGrowth', '渠道管理', 'BuyerChannel', 'M', '/buyer/promotion/channel', 'buyer/channel/index', 'tenant:buyer-channel:view', NULL, 20),
    ('BuyerGrowth', '渠道统计', 'BuyerChannelStats', 'M', '/buyer/data/channel-stats', 'buyer/channel-stats/index', 'tenant:buyer-channel-stats:view', NULL, 30),

    ('SystemManagement', '用户管理', 'SystemUser', 'M', '/system/user', 'system/user/index', 'tenant:system-user:view', NULL, 10),
    ('SystemManagement', '角色管理', 'SystemRole', 'M', '/system/role', 'system/role/index', 'tenant:system-role:view', NULL, 20),
    ('SystemManagement', '菜单管理', 'SystemMenu', 'M', '/system/menu', 'system/menu/index', 'tenant:system-menu:view', NULL, 30),

    ('BuyerTemplate', '修改可见性', 'BuyerTemplateVisibility', 'B', NULL, NULL, 'tenant:buyer-template:visibility', NULL, 10),
    ('BuyerTemplate', '修改备注', 'BuyerTemplateRemark', 'B', NULL, NULL, 'tenant:buyer-template:remark', NULL, 20),
    ('BuyerChannel', '新增渠道', 'BuyerChannelCreate', 'B', NULL, NULL, 'tenant:buyer-channel:create', NULL, 10),
    ('BuyerChannel', '编辑渠道', 'BuyerChannelEdit', 'B', NULL, NULL, 'tenant:buyer-channel:edit', NULL, 20),
    ('BuyerChannel', '检测渠道', 'BuyerChannelDetect', 'B', NULL, NULL, 'tenant:buyer-channel:detect', NULL, 30),
    ('BuyerChannel', '删除渠道', 'BuyerChannelDelete', 'B', NULL, NULL, 'tenant:buyer-channel:delete', NULL, 40),
    ('BuyerChannelStats', '编辑统计', 'BuyerChannelStatsEdit', 'B', NULL, NULL, 'tenant:buyer-channel-stats:edit', NULL, 10),
    ('BuyerChannelStats', '导出统计', 'BuyerChannelStatsExport', 'B', NULL, NULL, 'tenant:buyer-channel-stats:export', NULL, 20),
    ('SystemUser', '新增用户', 'SystemUserCreate', 'B', NULL, NULL, 'tenant:system-user:create', NULL, 10),
    ('SystemUser', '编辑用户', 'SystemUserEdit', 'B', NULL, NULL, 'tenant:system-user:edit', NULL, 20),
    ('SystemUser', '重置密码', 'SystemUserResetPassword', 'B', NULL, NULL, 'tenant:system-user:reset-password', NULL, 30),
    ('SystemUser', '变更状态', 'SystemUserStatus', 'B', NULL, NULL, 'tenant:system-user:status', NULL, 40),
    ('SystemRole', '新增角色', 'SystemRoleCreate', 'B', NULL, NULL, 'tenant:system-role:create', NULL, 10),
    ('SystemRole', '编辑角色', 'SystemRoleEdit', 'B', NULL, NULL, 'tenant:system-role:edit', NULL, 20),
    ('SystemRole', '分配权限', 'SystemRoleGrant', 'B', NULL, NULL, 'tenant:system-role:grant', NULL, 30),
    ('SystemRole', '变更状态', 'SystemRoleStatus', 'B', NULL, NULL, 'tenant:system-role:status', NULL, 40),
    ('SystemMenu', '新增菜单', 'SystemMenuCreate', 'B', NULL, NULL, 'tenant:system-menu:create', NULL, 10),
    ('SystemMenu', '编辑菜单', 'SystemMenuEdit', 'B', NULL, NULL, 'tenant:system-menu:edit', NULL, 20),
    ('SystemMenu', '变更状态', 'SystemMenuStatus', 'B', NULL, NULL, 'tenant:system-menu:status', NULL, 30);

-- 先写根目录，再按父节点稳定标识写页面和按钮，避免依赖固定自增 ID。
INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, 0, seed.menu_name, seed.menu_key, seed.menu_type, seed.route_path,
       seed.component_path, seed.perm_key, seed.icon, seed.sort_no, 1,
       @rbac_seed_now, NULL, @rbac_seed_now, NULL
FROM tenant
JOIN tmp_system_menu_seed seed ON seed.menu_type = 'D'
WHERE tenant.status = 1;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, seed.menu_name, seed.menu_key, seed.menu_type, seed.route_path,
       seed.component_path, seed.perm_key, seed.icon, seed.sort_no, 1,
       @rbac_seed_now, NULL, @rbac_seed_now, NULL
FROM tenant
JOIN tmp_system_menu_seed seed ON seed.menu_type = 'M'
JOIN sys_menu parent
  ON parent.tenant_id = tenant.id
 AND parent.menu_key = seed.parent_key
WHERE tenant.status = 1;

INSERT IGNORE INTO sys_menu
    (tenant_id, parent_id, menu_name, menu_key, menu_type, route_path, component_path,
     perm_key, icon, sort_no, status, created_at, created_by, updated_at, updated_by)
SELECT tenant.id, parent.id, seed.menu_name, seed.menu_key, seed.menu_type, seed.route_path,
       seed.component_path, seed.perm_key, seed.icon, seed.sort_no, 1,
       @rbac_seed_now, NULL, @rbac_seed_now, NULL
FROM tenant
JOIN tmp_system_menu_seed seed ON seed.menu_type = 'B'
JOIN sys_menu parent
  ON parent.tenant_id = tenant.id
 AND parent.menu_key = seed.parent_key
WHERE tenant.status = 1;

DROP TEMPORARY TABLE tmp_system_menu_seed;
