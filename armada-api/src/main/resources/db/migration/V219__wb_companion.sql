-- 安卓号登录到 WB 分身（WB 切片 46）。
-- wb_companion_task：一次"登录到 WB"操作，按条串行推进；进度只记当前这一条，已成功条数累加，失败即停。
-- wb_companion_binding：账号当前挂在哪个 WB 环境、哪个会员、哪台设备，以及最近一次同步的连接数，列表列只读这张表。
CREATE TABLE wb_companion_task (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务主键,例如 3001',
    tenant_id BIGINT NOT NULL COMMENT '账号所属租户ID,例如 1',
    account_id BIGINT NOT NULL COMMENT '安卓协议账号ID(→account.id),例如 9001',
    ws_phone VARCHAR(32) NOT NULL COMMENT '账号号码快照,只含数字,例如 6281234567801',
    wb_env_id VARCHAR(32) NOT NULL COMMENT 'WB环境标识,与WB的WBPRO_ENV_ID一致,例如 wb-prod',
    wb_member_id BIGINT NOT NULL COMMENT 'WB会员ID,例如 120',
    wb_username VARCHAR(64) NOT NULL COMMENT 'WB会员用户名快照,例如 wb_jack01',
    base_count TINYINT NOT NULL COMMENT '开始时WB上已有的连接数(主连接+影子),例如 0',
    target_count TINYINT NOT NULL COMMENT '目标连接数1-4(含已有),例如 4',
    succeeded_count TINYINT NOT NULL DEFAULT 0 COMMENT '本任务已成功挂上的连接数,例如 2',
    status TINYINT NOT NULL COMMENT '状态:1进行中 2成功 3失败 4已停止,例如 1',
    step TINYINT NOT NULL DEFAULT 0 COMMENT '当前这一条的步骤:0待发起 1WB已发起配对 2安卓已输入配对码,例如 1',
    wb_pairing_id VARCHAR(64) DEFAULT NULL COMMENT '当前这一条的WB配对ID,例如 32位十六进制',
    wb_role VARCHAR(16) DEFAULT NULL COMMENT '当前这一条在WB的角色:MAIN新主设备 REPAIR重新配对 SHADOW影子,例如 SHADOW',
    pair_code VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '当前这一条的配对码,这一条结束即清空,例如 ABCDEFGH',
    step_deadline BIGINT DEFAULT NULL COMMENT '当前步骤截止时间(epoch毫秒),例如 1800000000000',
    next_run_at BIGINT NOT NULL COMMENT '下次推进时间(epoch毫秒),例如 1800000000000',
    error_code VARCHAR(64) DEFAULT NULL COMMENT '失败码,例如 SHADOW_LIMIT_REACHED',
    created_by BIGINT DEFAULT NULL COMMENT '发起人用户ID,例如 81',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒),例如 1800000000000',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒),例如 1800000000000',
    finished_at BIGINT DEFAULT NULL COMMENT '结束时间(epoch毫秒),例如 1800000000000',
    active_account_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 1 THEN account_id ELSE NULL END) VIRTUAL
        COMMENT '进行中任务的账号唯一键辅助列:同一账号同时只有一个进行中任务',
    PRIMARY KEY (id),
    UNIQUE KEY uq_wb_companion_task_active (active_account_id),
    KEY idx_wb_companion_task_account (tenant_id, account_id, id),
    KEY idx_wb_companion_task_due (status, next_run_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安卓号登录到WB分身的任务';

CREATE TABLE wb_companion_binding (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键,例如 501',
    tenant_id BIGINT NOT NULL COMMENT '账号所属租户ID,例如 1',
    account_id BIGINT NOT NULL COMMENT '安卓协议账号ID(→account.id),例如 9001',
    wb_env_id VARCHAR(32) NOT NULL COMMENT 'WB环境标识,例如 wb-prod',
    wb_member_id BIGINT NOT NULL COMMENT 'WB会员ID,例如 120',
    wb_username VARCHAR(64) NOT NULL COMMENT 'WB会员用户名快照,例如 wb_jack01',
    wb_device_id BIGINT DEFAULT NULL COMMENT 'WB主设备ID,例如 10482',
    connection_count TINYINT NOT NULL DEFAULT 0 COMMENT '最近同步时的连接数(主连接+影子),例如 4',
    online_count TINYINT NOT NULL DEFAULT 0 COMMENT '最近同步时在线的连接数,例如 3',
    synced_at BIGINT NOT NULL COMMENT '最近同步时间(epoch毫秒),例如 1800000000000',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒),例如 1800000000000',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒),例如 1800000000000',
    PRIMARY KEY (id),
    UNIQUE KEY uq_wb_companion_binding_account (account_id),
    KEY idx_wb_companion_binding_tenant (tenant_id, account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安卓号当前挂在WB的分身';
