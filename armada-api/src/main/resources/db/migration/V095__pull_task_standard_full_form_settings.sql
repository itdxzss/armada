-- 普通群链接拉群任务完整表单持久化：扩充冻结执行配置，并新增任务级群资料设置。
-- V090 已在 test1 执行，group_folder 名称长度只能在本增量迁移中调整，禁止改写 V090。

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'source_group_folder_id') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN source_group_folder_id BIGINT DEFAULT NULL COMMENT ''群组运营分组ID(→group_folder.id)'' AFTER auto_start',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'source_group_folder_name') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN source_group_folder_name VARCHAR(100) DEFAULT NULL COMMENT ''群组运营分组名称快照'' AFTER source_group_folder_id',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'puller_sync_mode') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN puller_sync_mode TINYINT NOT NULL DEFAULT 1 COMMENT ''拉手同步模式:1=单个 2=批量'' AFTER material_admin_timing',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'is_clear_existing_members') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN is_clear_existing_members TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否先清空群原成员:0=否 1=是'' AFTER puller_sync_mode',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'manager_finish_group_id') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN manager_finish_group_id BIGINT DEFAULT NULL COMMENT ''任务完成后管理员账号移入分组ID(→account_group.id)'' AFTER station_group_id',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'manager_finish_group_name') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN manager_finish_group_name VARCHAR(100) DEFAULT NULL COMMENT ''管理员完成分组名称快照'' AFTER station_group_name',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'puller_finish_group_id') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN puller_finish_group_id BIGINT DEFAULT NULL COMMENT ''任务完成后拉手账号移入分组ID(→account_group.id)'' AFTER manager_finish_group_id',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'puller_finish_group_name') = 0,
    'ALTER TABLE pull_task_standard_setting ADD COLUMN puller_finish_group_name VARCHAR(100) DEFAULT NULL COMMENT ''拉手完成分组名称快照'' AFTER manager_finish_group_name',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

-- 无站台需求时站台分组允许为空；正数场景由 Service 强制校验。
SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'station_group_id'
       AND is_nullable = 'NO') = 1,
    'ALTER TABLE pull_task_standard_setting MODIFY COLUMN station_group_id BIGINT DEFAULT NULL COMMENT ''站台账号分组ID(→account_group.id);站台数为0时可空''',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

SET @pull_task_standard_setting_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'pull_task_standard_setting'
       AND column_name = 'station_group_name'
       AND is_nullable = 'NO') = 1,
    'ALTER TABLE pull_task_standard_setting MODIFY COLUMN station_group_name VARCHAR(100) DEFAULT NULL COMMENT ''站台分组名称快照;站台数为0时可空''',
    'SELECT 1'
);
PREPARE pull_task_standard_setting_stmt FROM @pull_task_standard_setting_ddl;
EXECUTE pull_task_standard_setting_stmt;
DEALLOCATE PREPARE pull_task_standard_setting_stmt;

-- 原型和账号分组统一使用 100 字符；V090 已执行，只能在新迁移中增量放宽。
SET @group_folder_name_ddl := IF(
    (SELECT character_maximum_length
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_folder'
       AND column_name = 'name') < 100,
    'ALTER TABLE group_folder MODIFY COLUMN name VARCHAR(100) NOT NULL COMMENT ''群组运营分组名称''',
    'SELECT 1'
);
PREPARE group_folder_name_stmt FROM @group_folder_name_ddl;
EXECUTE group_folder_name_stmt;
DEALLOCATE PREPARE group_folder_name_stmt;

-- 一条普通群链接任务一行，保存任务期望应用的群资料与权限，不表示 WhatsApp 实时资料。
CREATE TABLE IF NOT EXISTS pull_task_standard_group_setting (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群资料设置主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    task_id BIGINT NOT NULL COMMENT '拉群任务ID(→pull_task.id)',
    setting_timing TINYINT NOT NULL DEFAULT 2 COMMENT '设置顺序:1=拉人前 2=拉完后',
    group_name VARCHAR(128) DEFAULT NULL COMMENT '手工群名;使用TXT文件名为群名时为空',
    is_material_filename_as_group_name TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否使用对应TXT文件名为群名:0=否 1=是',
    avatar_file_key VARCHAR(512) DEFAULT NULL COMMENT '当前租户头像目录内的安全文件Key',
    group_description VARCHAR(1024) DEFAULT NULL COMMENT '群描述',
    is_auto_unmute_after_task TINYINT(1) NOT NULL DEFAULT 0 COMMENT '任务完成后是否自动解除禁言:0=否 1=是',
    is_auto_close_invite_after_task TINYINT(1) NOT NULL DEFAULT 0 COMMENT '任务完成后是否关闭拉人权限:0=否 1=是',
    edit_permission_mode TINYINT NOT NULL DEFAULT 0 COMMENT '编辑群设置权限:0=不操作 1=允许 2=不允许',
    mute_mode TINYINT NOT NULL DEFAULT 0 COMMENT '群禁言:0=不操作 1=禁言 2=不禁言',
    link_permission_mode TINYINT NOT NULL DEFAULT 2 COMMENT '获取群链接权限:1=所有人 2=仅管理员',
    disappearing_message_mode TINYINT NOT NULL DEFAULT 0 COMMENT '限时消息:0=不操作 1=24小时 2=7天 3=90天 4=关闭',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_standard_group_setting_task (tenant_id, task_id),
    UNIQUE KEY uq_pull_task_standard_group_setting_avatar (tenant_id, avatar_file_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='普通群链接任务期望应用的群资料与权限设置';
