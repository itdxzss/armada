-- 群组数据模型重建第一步：只新增六张当前事实表。
-- 本迁移不回填、不切流、不修改旧表，也不新增协议或任务设施。

CREATE TABLE IF NOT EXISTS wa_group (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '内部群主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_jid VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '规范化WhatsApp群JID',
    folder_id BIGINT DEFAULT NULL COMMENT '运营群组分组ID',
    display_name VARCHAR(128) DEFAULT NULL COMMENT '当前本地展示名',
    avatar_url VARCHAR(1024) DEFAULT NULL COMMENT '当前展示头像',
    remark VARCHAR(255) DEFAULT NULL COMMENT '本地运营备注',
    origin TINYINT NOT NULL COMMENT '当前来源:1导入 2进群 3拉群 4自建 5账号同步',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '本地软删除时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_wa_group_jid (tenant_id, group_jid),
    KEY idx_wa_group_list (tenant_id, deleted_at, created_at, id),
    KEY idx_wa_group_folder (tenant_id, folder_id, deleted_at, created_at, id),
    KEY idx_wa_group_origin (tenant_id, origin, deleted_at, created_at, id),
    CONSTRAINT ck_wa_group_origin CHECK (origin IN (1, 2, 3, 4, 5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp群身份和当前本地属性';

CREATE TABLE IF NOT EXISTS wa_group_profile (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群资料主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_id BIGINT NOT NULL COMMENT '关联wa_group.id',
    subject VARCHAR(255) DEFAULT NULL COMMENT 'WhatsApp当前群名',
    description VARCHAR(1024) DEFAULT NULL COMMENT 'WhatsApp群描述',
    member_count INT DEFAULT NULL COMMENT 'metadata观察到的成员数',
    wa_created_at BIGINT DEFAULT NULL COMMENT 'WhatsApp建群时间(epoch毫秒)',
    announce_only TINYINT DEFAULT NULL COMMENT '发言模式:NULL未知 0所有成员 1仅管理员',
    admin_only_edit_info TINYINT DEFAULT NULL
        COMMENT '资料编辑:NULL未知 0所有成员 1仅管理员',
    member_add_mode TINYINT DEFAULT NULL COMMENT '成员添加:NULL未知 0仅管理员 1所有成员',
    join_approval_mode TINYINT DEFAULT NULL COMMENT '入群审批:NULL未知 0关闭 1开启',
    ephemeral_duration_seconds INT DEFAULT NULL COMMENT '限时消息秒数，NULL未知',
    metadata_observed_at BIGINT DEFAULT NULL COMMENT '最近接受metadata时间(epoch毫秒)',
    current_invite_id BIGINT DEFAULT NULL COMMENT '当前wa_group_invite.id',
    current_invite_observed_at BIGINT DEFAULT NULL COMMENT '当前邀请关系事实时间(epoch毫秒)',
    member_snapshot_at BIGINT DEFAULT NULL COMMENT '最近完整成员快照时间(epoch毫秒)',
    member_snapshot_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '当前Java服务生成的完整成员快照UUID',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_wa_group_profile_group (tenant_id, group_id),
    KEY idx_wa_group_profile_count (tenant_id, member_count, group_id),
    CONSTRAINT ck_wa_group_profile_member_count CHECK (member_count IS NULL OR member_count >= 0),
    CONSTRAINT ck_wa_group_profile_announce CHECK (announce_only IS NULL OR announce_only IN (0, 1)),
    CONSTRAINT ck_wa_group_profile_edit CHECK (
        admin_only_edit_info IS NULL OR admin_only_edit_info IN (0, 1)
    ),
    CONSTRAINT ck_wa_group_profile_member_add CHECK (member_add_mode IS NULL OR member_add_mode IN (0, 1)),
    CONSTRAINT ck_wa_group_profile_join_approval CHECK (
        join_approval_mode IS NULL OR join_approval_mode IN (0, 1)
    ),
    CONSTRAINT ck_wa_group_profile_ephemeral CHECK (
        ephemeral_duration_seconds IS NULL OR ephemeral_duration_seconds >= 0
    ),
    CONSTRAINT ck_wa_group_profile_invite CHECK (current_invite_id IS NULL OR current_invite_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp群当前资料、邀请指针和成员快照头';

CREATE TABLE IF NOT EXISTS wa_group_invite (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '邀请主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_id BIGINT DEFAULT NULL COMMENT '已解析时关联wa_group.id',
    invite_code VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'WhatsApp邀请code',
    label_id BIGINT DEFAULT NULL COMMENT '导入链接分组ID',
    display_name VARCHAR(128) DEFAULT NULL COMMENT '未解析邀请当前展示名',
    avatar_url VARCHAR(1024) DEFAULT NULL COMMENT '未解析邀请当前展示头像',
    remark VARCHAR(255) DEFAULT NULL COMMENT '邀请级运营备注',
    origin TINYINT NOT NULL COMMENT '当前来源:1导入 2进群 3拉群 4自建 5账号同步',
    preview_subject VARCHAR(255) DEFAULT NULL COMMENT '公开预览群名',
    preview_observed_at BIGINT DEFAULT NULL COMMENT '最近预览成功时间(epoch毫秒)',
    health_status TINYINT DEFAULT NULL COMMENT '当前状态:NULL未检测 1可用 2链接失效 3不可用',
    banned TINYINT DEFAULT NULL COMMENT '封禁标记:NULL未知 0否 1是',
    checked_member_count INT DEFAULT NULL COMMENT '链接检测观察到的成员数',
    last_checked_at BIGINT DEFAULT NULL COMMENT '最近链接检测时间(epoch毫秒)',
    last_error_code VARCHAR(64) DEFAULT NULL COMMENT '最近稳定错误码',
    failure_count INT NOT NULL DEFAULT 0 COMMENT '连续检测失败次数',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_wa_group_invite_code (tenant_id, invite_code),
    KEY idx_wa_group_invite_label (tenant_id, label_id, deleted_at, created_at, id),
    KEY idx_wa_group_invite_health
        (tenant_id, health_status, banned, last_checked_at, id),
    CONSTRAINT ck_wa_group_invite_code CHECK (invite_code <> ''),
    CONSTRAINT ck_wa_group_invite_origin CHECK (origin IN (1, 2, 3, 4, 5)),
    CONSTRAINT ck_wa_group_invite_health CHECK (health_status IS NULL OR health_status IN (1, 2, 3)),
    CONSTRAINT ck_wa_group_invite_banned CHECK (banned IS NULL OR banned IN (0, 1)),
    CONSTRAINT ck_wa_group_invite_member_count CHECK (
        checked_member_count IS NULL OR checked_member_count >= 0
    ),
    CONSTRAINT ck_wa_group_invite_failure_count CHECK (failure_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp邀请code、当前预览和v1检测结果';

CREATE TABLE IF NOT EXISTS wa_group_participant (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群成员主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    group_id BIGINT NOT NULL COMMENT '关联wa_group.id',
    pn_jid VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '已确认PN JID',
    lid_jid VARCHAR(191) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '已确认LID JID',
    phone VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '可信规范化手机号',
    phone_country_iso2 CHAR(2) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '手机号国家ISO2投影',
    presence_status TINYINT NOT NULL DEFAULT 0 COMMENT '在群态:0未知 1在群 2不在群',
    presence_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT 'presence来源',
    presence_observed_at BIGINT DEFAULT NULL COMMENT 'presence事实时间(epoch毫秒)',
    presence_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT 'presence同时间决胜事件ID',
    role TINYINT NOT NULL DEFAULT 0 COMMENT '成员角色:0未知 1成员 2管理员 3群主',
    role_source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'role来源',
    role_observed_at BIGINT DEFAULT NULL COMMENT 'role事实时间(epoch毫秒)',
    role_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT 'role同时间决胜事件ID',
    last_snapshot_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '最近完整成员快照UUID',
    last_joined_at BIGINT DEFAULT NULL COMMENT '最近可靠进群时间(epoch毫秒)',
    last_join_event_at BIGINT DEFAULT NULL COMMENT '最近进群排序时间(epoch毫秒)',
    last_join_source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '最近进群同时间决胜事件ID',
    last_exited_at BIGINT DEFAULT NULL COMMENT '最近可靠退出时间(epoch毫秒)',
    last_exit_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '最近退出方式:LEFT REMOVED UNKNOWN',
    last_exit_event_at BIGINT DEFAULT NULL COMMENT '最近退出排序时间(epoch毫秒)',
    last_exit_source_event_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '最近退出同时间决胜事件ID',
    last_exit_source_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '最近退出来源:HISTORY_SYNC或WGP2_NOTIFICATION',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_wa_group_participant_pn (tenant_id, group_id, pn_jid),
    UNIQUE KEY uq_wa_group_participant_lid (tenant_id, group_id, lid_jid),
    KEY idx_wa_group_participant_list
        (tenant_id, group_id, presence_status, role, id),
    KEY idx_wa_group_participant_country
        (tenant_id, phone_country_iso2, presence_status, role, group_id),
    KEY idx_wa_group_participant_exit (tenant_id, group_id, last_exited_at),
    CONSTRAINT ck_wa_group_participant_identity CHECK (pn_jid IS NOT NULL OR lid_jid IS NOT NULL),
    CONSTRAINT ck_wa_group_participant_presence CHECK (presence_status IN (0, 1, 2)),
    CONSTRAINT ck_wa_group_participant_role CHECK (role IN (0, 1, 2, 3)),
    CONSTRAINT ck_wa_group_participant_exit CHECK (
        last_exit_type IS NULL OR last_exit_type IN ('LEFT', 'REMOVED', 'UNKNOWN')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp群成员当前身份、presence、role和最近进退事实';

CREATE TABLE IF NOT EXISTS wa_account_group_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号群关系主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT 'Armada账号ID',
    group_id BIGINT NOT NULL COMMENT '关联wa_group.id',
    participant_id BIGINT NOT NULL COMMENT '账号在群内的self participant ID',
    was_in_initial_baseline TINYINT DEFAULT NULL
        COMMENT '初始baseline关系:NULL未知 0上控后群 1初始baseline内',
    baseline_subject_snapshot VARCHAR(255) DEFAULT NULL COMMENT 'baseline当时群名快照',
    membership_active_since_at BIGINT DEFAULT NULL
        COMMENT '兼容旧joined_at:当前在群周期首次建行或恢复在群的观察时间',
    last_observed_at BIGINT DEFAULT NULL COMMENT '最近观察关系时间(epoch毫秒)',
    first_post_control_observed_at BIGINT DEFAULT NULL
        COMMENT '实时业务首次判为上控后群的观察时间，legacy迁移恒为NULL',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_wa_account_group_binding (tenant_id, account_id, group_id),
    KEY idx_wa_binding_group_participant
        (tenant_id, group_id, participant_id, account_id),
    KEY idx_wa_binding_account_active_since
        (tenant_id, account_id, membership_active_since_at, group_id),
    KEY idx_wa_binding_group_baseline
        (tenant_id, group_id, was_in_initial_baseline, account_id),
    CHECK (was_in_initial_baseline IS NULL OR was_in_initial_baseline IN (0, 1)),
    CONSTRAINT ck_wa_binding_first_post CHECK (
        first_post_control_observed_at IS NULL
        OR (was_in_initial_baseline = 0 AND first_post_control_observed_at >= 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Armada账号与WhatsApp群self participant关系';

CREATE TABLE IF NOT EXISTS account_group_sync_state (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '账号群同步状态主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT 'Armada账号ID',
    baseline_state TINYINT NOT NULL DEFAULT 1 COMMENT 'baseline状态:1PENDING 2CAPTURED 3DISABLED',
    baseline_completeness TINYINT NOT NULL DEFAULT 0
        COMMENT 'baseline完整性:0NONE 1CURRENT_COMPAT 2LEGACY_UNKNOWN',
    baseline_captured_at BIGINT DEFAULT NULL COMMENT 'baseline形成时间(epoch毫秒)',
    baseline_group_count INT DEFAULT NULL COMMENT 'baseline群数，legacy未知可空',
    last_sync_requested_at BIGINT DEFAULT NULL COMMENT '最近同步命令入队时间(epoch毫秒)',
    last_reported_at BIGINT DEFAULT NULL COMMENT '最近收到账号群报告时间(epoch毫秒)',
    last_snapshot_complete TINYINT NOT NULL DEFAULT 0 COMMENT '最近报告有效完整标记:0否 1是',
    last_complete_at BIGINT DEFAULT NULL COMMENT '最近有效完整报告时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_group_sync_state (tenant_id, account_id),
    KEY idx_account_group_sync_requested (tenant_id, last_sync_requested_at, account_id),
    CONSTRAINT ck_account_group_sync_state CHECK (baseline_state IN (1, 2, 3)),
    CONSTRAINT ck_account_group_sync_completeness CHECK (baseline_completeness IN (0, 1, 2)),
    CONSTRAINT ck_account_group_sync_complete CHECK (last_snapshot_complete IN (0, 1)),
    CONSTRAINT ck_account_group_sync_count CHECK (
        baseline_group_count IS NULL OR baseline_group_count >= 0
    ),
    CONSTRAINT ck_account_group_sync_baseline_header CHECK (
        (baseline_state IN (1, 3)
            AND baseline_completeness = 0
            AND baseline_captured_at IS NULL
            AND baseline_group_count IS NULL)
        OR (baseline_state = 2
            AND baseline_completeness IN (1, 2)
            AND baseline_captured_at IS NOT NULL)
    ),
    CONSTRAINT ck_account_group_sync_current_count CHECK (
        baseline_completeness <> 1
        OR (baseline_group_count IS NOT NULL AND baseline_group_count >= 0)
    ),
    CONSTRAINT ck_account_group_sync_complete_time CHECK (
        last_snapshot_complete = 0 OR last_complete_at IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Armada账号群报告、baseline和同步水位';
