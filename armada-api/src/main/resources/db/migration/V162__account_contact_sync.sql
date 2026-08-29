-- 账号通讯录快照与同步状态。
-- 快照是账号资产，不属于通讯录营销一个业务：超链任务的「双向好友数」筛选也读这份数据。
-- 写入语义是整批替换：一次成功同步先 upsert 本批号码，再删除 synced_at 更早的残留行。
-- 同步失败时不动任何已有数据，只在 account_contact_sync 记 FAILED。

CREATE TABLE IF NOT EXISTS account_contact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '联系人快照主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT '所属账号ID',
    contact_phone VARCHAR(32) NOT NULL COMMENT '联系人号码;不带加号的纯数字',
    contact_jid VARCHAR(64) NOT NULL COMMENT '联系人JID;phone@s.whatsapp.net',
    full_name VARCHAR(128) DEFAULT NULL COMMENT '通讯录全名;Web协议无此概念时为NULL',
    first_name VARCHAR(128) DEFAULT NULL COMMENT '通讯录名;Web协议恒为NULL',
    push_name VARCHAR(128) DEFAULT NULL COMMENT '对方设置的展示名',
    business_name VARCHAR(128) DEFAULT NULL COMMENT '商业号认证名;Web侧取verifiedName',
    is_named TINYINT NOT NULL DEFAULT 0 COMMENT '通讯录里是否有名字:1有 0无;竞品好友数口径',
    is_mutual TINYINT NOT NULL DEFAULT 0 COMMENT '是否双向好友:1是 0否;两套协议暂不暴露该标记恒为0',
    synced_at BIGINT NOT NULL COMMENT '本行所属同步批次时间(epoch毫秒)',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_contact (tenant_id, account_id, contact_phone),
    KEY idx_account_contact_named (tenant_id, account_id, is_named),
    KEY idx_account_contact_sweep (tenant_id, account_id, synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号通讯录联系人快照';

CREATE TABLE IF NOT EXISTS account_contact_sync (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '同步状态主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    account_id BIGINT NOT NULL COMMENT '账号ID;一账号一行',
    last_synced_at BIGINT DEFAULT NULL COMMENT '最近一次成功同步时间(epoch毫秒);从未成功为NULL',
    last_sync_source VARCHAR(32) DEFAULT NULL COMMENT '最近一次触发来源:ONLINE_EVENT TASK_START MANUAL',
    contact_num INT NOT NULL DEFAULT 0 COMMENT '最近一次成功同步到的联系人总数',
    named_num INT NOT NULL DEFAULT 0 COMMENT '其中通讯录有名字的数量',
    mutual_num INT NOT NULL DEFAULT 0 COMMENT '其中双向好友数量;当前恒为0',
    sync_status VARCHAR(16) NOT NULL DEFAULT 'NEVER' COMMENT '同步状态:NEVER SYNCING SUCCESS FAILED',
    fail_reason VARCHAR(255) DEFAULT NULL COMMENT '最近一次失败原因;成功时置空',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_account_contact_sync (tenant_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='账号通讯录同步状态';

-- 通讯录计数落在上游 V159 建的 account_profile 上：那张表就是「账号营销筛选画像」，
-- 每个事实带自己的水位、NULL 表示未采集。这里按同样的约定补一列，不动 friend_count
-- 的双向好友语义（两套协议都不暴露互加关系，那一列至今没有采集源）。
-- 写入点唯一：AccountProfileService#updateContactNamedNum，由通讯录快照落库时调用。
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_profile'
       AND column_name = 'contact_named_num') = 0,
    'ALTER TABLE account_profile ADD COLUMN contact_named_num INT DEFAULT NULL COMMENT ''通讯录中有名字的联系人数;NULL=未采集'' AFTER friend_count_synced_at',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'account_profile'
       AND column_name = 'contact_named_synced_at') = 0,
    'ALTER TABLE account_profile ADD COLUMN contact_named_synced_at BIGINT DEFAULT NULL COMMENT ''通讯录计数最近同步时间(epoch毫秒)'' AFTER contact_named_num',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'account_profile'
       AND index_name = 'idx_account_profile_contact_named') = 0,
    'ALTER TABLE account_profile ADD KEY idx_account_profile_contact_named (tenant_id, contact_named_num, account_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
