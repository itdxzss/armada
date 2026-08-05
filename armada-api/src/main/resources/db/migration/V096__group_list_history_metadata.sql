-- 群组列表固化历史/上控后分类，并保存协议群详情与最后一次完整成员快照。

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link'
       AND column_name = 'is_historical') = 0,
    'ALTER TABLE group_link ADD COLUMN is_historical TINYINT NOT NULL DEFAULT 0 COMMENT ''是否曾属于首次上线历史群基线:0=否 1=是;只升不降'' AFTER membership_state',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link'
       AND column_name = 'is_post_control') = 0,
    'ALTER TABLE group_link ADD COLUMN is_post_control TINYINT NOT NULL DEFAULT 0 COMMENT ''是否曾在账号上控后被发现加入:0=否 1=是;只升不降'' AFTER is_historical',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link'
       AND index_name = 'idx_group_link_historical') = 0,
    'ALTER TABLE group_link ADD KEY idx_group_link_historical (tenant_id, deleted_at, is_historical, created_at, id)',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link'
       AND index_name = 'idx_group_link_post_control') = 0,
    'ALTER TABLE group_link ADD KEY idx_group_link_post_control (tenant_id, deleted_at, is_post_control, created_at, id)',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'country'
       AND column_name = 'continent_code') = 0,
    'ALTER TABLE country ADD COLUMN continent_code VARCHAR(24) DEFAULT NULL COMMENT ''所属大洲:ASIA/AFRICA/EUROPE/NORTH_AMERICA/SOUTH_AMERICA/OCEANIA;特殊地区可空'' AFTER flag',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'country'
       AND index_name = 'idx_country_continent_sort') = 0,
    'ALTER TABLE country ADD KEY idx_country_continent_sort (continent_code, is_enabled, sort_order, id)',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

-- 使用显式 ISO2/CLDR 列表固化六大洲；AQ/BV/HM/TF 属于特殊或南极地区，保留 NULL。
UPDATE country
SET continent_code = CASE
    WHEN iso2 IN (
        'AF','AM','AZ','BH','BD','BT','BN','KH','CN','CY','GE','HK','IN','ID','IR','IQ','IL',
        'JP','JO','KZ','KP','KR','KW','KG','LA','LB','MO','MY','MV','MN','MM','NP','OM','PK','PS',
        'PH','QA','SA','SG','LK','SY','TW','TJ','TH','TL','TR','TM','AE','UZ','VN','YE'
    ) THEN 'ASIA'
    WHEN iso2 IN (
        'DZ','AO','AC','BJ','BW','BF','BI','CV','CM','CF','TD','KM','CG','CD','CI','DJ','EG','GQ',
        'ER','SZ','ET','GA','GM','GH','GN','GW','IO','KE','LS','LR','LY','MG','MW','ML','MR','MU',
        'YT','MA','MZ','NA','NE','NG','RE','RW','SH','ST','SN','SC','SL','SO','ZA','SS','SD','TZ',
        'TG','TA','TN','UG','EH','ZM','ZW'
    ) THEN 'AFRICA'
    WHEN iso2 IN (
        'AX','AL','AD','AT','BY','BE','BA','BG','HR','CZ','DK','EE','FO','FI','FR','DE','GI','GR',
        'GG','VA','HU','IS','IE','IM','IT','JE','XK','LV','LI','LT','LU','MT','MD','MC','ME','NL',
        'MK','NO','PL','PT','RO','RU','SM','RS','SK','SI','ES','SJ','SE','CH','UA','GB'
    ) THEN 'EUROPE'
    WHEN iso2 IN (
        'AI','AG','AW','BS','BB','BZ','BM','BQ','VG','CA','KY','CR','CU','CW','DM','DO','SV','GL',
        'GD','GP','GT','HT','HN','JM','MQ','MX','MS','NI','PA','PR','BL','KN','LC','MF','PM','VC',
        'SX','TC','TT','US','VI'
    ) THEN 'NORTH_AMERICA'
    WHEN iso2 IN (
        'AR','BO','BR','CL','CO','EC','FK','GF','GY','PY','PE','GS','SR','UY','VE'
    ) THEN 'SOUTH_AMERICA'
    WHEN iso2 IN (
        'AS','AU','CX','CC','CK','FJ','PF','GU','KI','MH','FM','NR','NC','NZ','NU','NF','MP','PW',
        'PG','PN','WS','SB','TK','TO','TV','VU','WF'
    ) THEN 'OCEANIA'
    ELSE continent_code
END
WHERE deleted_at IS NULL
  AND iso2 NOT IN ('AQ','BV','HM','TF');

UPDATE country
SET continent_code = NULL
WHERE deleted_at IS NULL
  AND iso2 IN ('AQ','BV','HM','TF');

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'wa_description') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN wa_description VARCHAR(1024) DEFAULT NULL COMMENT ''WhatsApp群描述'' AFTER wa_subject',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'admin_only_edit_info') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN admin_only_edit_info TINYINT DEFAULT NULL COMMENT ''编辑群资料权限:NULL=未知 0=所有成员 1=仅管理员'' AFTER announce_only',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'member_add_mode') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN member_add_mode TINYINT DEFAULT NULL COMMENT ''添加成员权限:NULL=未知 0=仅管理员 1=所有成员'' AFTER admin_only_edit_info',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'join_approval_mode') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN join_approval_mode TINYINT DEFAULT NULL COMMENT ''新成员入群审批:NULL=未知 0=关闭 1=开启'' AFTER member_add_mode',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'ephemeral_duration_seconds') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN ephemeral_duration_seconds INT DEFAULT NULL COMMENT ''限时消息保留秒数;NULL=未知 0=关闭'' AFTER join_approval_mode',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'creator_country_iso2') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN creator_country_iso2 VARCHAR(2) DEFAULT NULL COMMENT ''按确认手机号解析的群主国家ISO2;未知为空'' AFTER group_created_at',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'creator_continent_code') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN creator_continent_code VARCHAR(24) DEFAULT NULL COMMENT ''按确认手机号解析的群主大洲代码;未知为空'' AFTER creator_country_iso2',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

SET @group_history_ddl := IF(
    (SELECT COUNT(*)
     FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'group_link_preview'
       AND column_name = 'metadata_observed_at') = 0,
    'ALTER TABLE group_link_preview ADD COLUMN metadata_observed_at BIGINT DEFAULT NULL COMMENT ''群详情请求开始观察时间(epoch毫秒);用于拒绝晚到旧快照'' AFTER last_preview_at',
    'SELECT 1'
);
PREPARE group_history_stmt FROM @group_history_ddl;
EXECUTE group_history_stmt;
DEALLOCATE PREPARE group_history_stmt;

CREATE TABLE IF NOT EXISTS whatsapp_group_member_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群成员快照主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_link_id BIGINT NOT NULL COMMENT '群入口ID(→group_link.id)',
    group_jid VARCHAR(128) NOT NULL COMMENT 'WhatsApp群JID',
    participant_jid VARCHAR(128) NOT NULL COMMENT '成员规范化WhatsApp JID',
    phone VARCHAR(32) DEFAULT NULL COMMENT '协议明确返回或可从PN JID确认的手机号',
    role VARCHAR(32) DEFAULT NULL COMMENT '协议成员角色:admin/superadmin/普通成员;未知为空',
    is_admin TINYINT NOT NULL DEFAULT 0 COMMENT '是否管理员或群主:0=否 1=是',
    is_owner TINYINT NOT NULL DEFAULT 0 COMMENT '是否群主:0=否 1=是',
    snapshot_at BIGINT NOT NULL COMMENT '本次完整成员快照观察时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_whatsapp_group_member (tenant_id, group_link_id, participant_jid),
    KEY idx_whatsapp_group_admin (tenant_id, group_link_id, is_admin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WhatsApp群最后一次完整成员快照';

CREATE TABLE IF NOT EXISTS group_metadata_sync_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '群详情同步任务主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    group_link_id BIGINT NOT NULL COMMENT '群入口ID(→group_link.id)',
    status TINYINT NOT NULL COMMENT '任务状态:1=PENDING 2=RUNNING 3=RETRY_WAIT 4=SUCCEEDED 5=DEFERRED 6=FAILED',
    trigger_source TINYINT NOT NULL COMMENT '触发来源:1=BASELINE_CAPTURED 2=POST_CONTROL_DISCOVERED 3=PARTICIPANT_CHANGED 4=METADATA_CHANGED 5=ACCOUNT_ONLINE 6=MANUAL_REFRESH 7=BACKFILL',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '已占用执行尝试次数;无可用账号DEFERRED不增加',
    next_run_at BIGINT DEFAULT NULL COMMENT '下一次可执行时间(epoch毫秒)',
    lease_until BIGINT DEFAULT NULL COMMENT '运行租约到期时间(epoch毫秒)',
    execution_account_id BIGINT DEFAULT NULL COMMENT '当前执行账号ID;用于租户及账号并发约束',
    rerun_requested TINYINT NOT NULL DEFAULT 0 COMMENT '运行期间收到新触发:0=否 1=完成后重跑',
    last_started_at BIGINT DEFAULT NULL COMMENT '最近一次开始执行时间(epoch毫秒)',
    last_success_at BIGINT DEFAULT NULL COMMENT '最近一次成功时间(epoch毫秒)',
    last_error_code VARCHAR(64) DEFAULT NULL COMMENT '最近一次错误码',
    last_error_message VARCHAR(512) DEFAULT NULL COMMENT '最近一次脱敏错误摘要',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_metadata_sync_task (tenant_id, group_link_id),
    KEY idx_group_metadata_due (status, next_run_at, lease_until),
    KEY idx_group_metadata_running (tenant_id, execution_account_id, status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='每租户每群一行的耐久群详情同步任务';
