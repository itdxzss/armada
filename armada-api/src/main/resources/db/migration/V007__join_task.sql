-- 进群任务第一刀:join_task(配置)+ join_task_result(账号×链接计划行)。
-- 时间列全 BIGINT epoch 毫秒,应用层写;两表均含 tenant_id 走拦截器行隔离;无业务唯一键故不建 is_active。
-- 引擎/Kafka 列(group_jid/is_admin/promoted_at + idx_jtr_admin_lookup)一次建齐,CRUD 不写,引擎切片直接用。
CREATE TABLE join_task (
    id                     BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id              BIGINT       NOT NULL                 COMMENT '租户ID(拦截器注入)',
    name                   VARCHAR(128) NOT NULL DEFAULT ''      COMMENT '任务名称',
    account_group_ids      VARCHAR(512) NOT NULL DEFAULT ''      COMMENT '选中账号分组id(JSON数组快照)',
    account_group_names    VARCHAR(512) NOT NULL DEFAULT ''      COMMENT '账号分组名快照(展示用,/连接,免JOIN)',
    selected_account_ids   TEXT                  DEFAULT NULL    COMMENT '选中账号id(JSON数组快照,编辑回填)',
    links_text             TEXT                  DEFAULT NULL    COMMENT '进群链接输入框原始文本(编辑回填,不去重/拆行)',
    distribution_mode      VARCHAR(32)  NOT NULL DEFAULT 'FIXED_ACCOUNTS_PER_LINK'
                                                                 COMMENT '分配方式:FIXED_ACCOUNTS_PER_LINK每链接固定账号数 / FIXED_ACCOUNT_MULTI_LINK固定账号多链接',
    accounts_per_link      INT          NOT NULL DEFAULT 0       COMMENT '方式一:每条群链接分配账号数',
    executor_account_count INT          NOT NULL DEFAULT 0       COMMENT '方式二:参与执行账号数',
    links_per_account      INT          NOT NULL DEFAULT 0       COMMENT '方式二:每账号进群链接数',
    fixed_interval_min_sec INT          NOT NULL DEFAULT 0       COMMENT '方式一进群间隔下限(秒)',
    fixed_interval_max_sec INT          NOT NULL DEFAULT 0       COMMENT '方式一进群间隔上限(秒)',
    multi_interval_min_sec INT          NOT NULL DEFAULT 0       COMMENT '方式二进群间隔下限(秒)',
    multi_interval_max_sec INT          NOT NULL DEFAULT 0       COMMENT '方式二进群间隔上限(秒)',
    interval_label         VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '进群间隔展示(如10-20s),筛选下拉去重源',
    retry_enabled          TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '失败是否自动重试',
    retry_limit            INT          NOT NULL DEFAULT 0       COMMENT '重试次数上限',
    failure_policy         VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '失败处理策略快照(JSON/标签,编辑回填)',
    total                  INT          NOT NULL DEFAULT 0       COMMENT '计划进群次数(=实际生成的PENDING行数)',
    executed               INT          NOT NULL DEFAULT 0       COMMENT '已执行次数(引擎回写,建时0)',
    success                INT          NOT NULL DEFAULT 0       COMMENT '成功进群数(引擎回写,建时0)',
    failed                 INT          NOT NULL DEFAULT 0       COMMENT '失败数(引擎回写,建时0)',
    pending                INT          NOT NULL DEFAULT 0       COMMENT '待执行数(建时=total)',
    status                 VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态码:DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED(中文前端转)',
    created_by             BIGINT                DEFAULT NULL    COMMENT '创建人user_id(暂无鉴权上下文,NULL)',
    created_at             BIGINT       NOT NULL                 COMMENT '创建时间(epoch毫秒,应用层写)',
    updated_at             BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,应用层写)',
    deleted_at             BIGINT                DEFAULT NULL    COMMENT '软删时间(epoch毫秒),NULL=有效',
    PRIMARY KEY (id),
    KEY idx_join_task_tenant (tenant_id, deleted_at, id),
    KEY idx_join_task_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '进群任务';

CREATE TABLE join_task_result (
    id           BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id    BIGINT       NOT NULL                 COMMENT '租户ID',
    join_task_id BIGINT       NOT NULL                 COMMENT '→join_task.id',
    account      VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '执行账号号码/别名(快照,展示用)',
    account_id   BIGINT                DEFAULT NULL    COMMENT '→account.id;建任务时回填;无效链接行为NULL',
    link         VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '群链接',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '进群结果码:PENDING/SUCCESS/FAILED(中文前端转)',
    reason       VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '失败原因(无效链接行建时即写)',
    group_jid    VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '〔引擎〕进群成功后回填群JID',
    is_admin     TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '〔Kafka〕是否已成管理员',
    promoted_at  BIGINT                DEFAULT NULL    COMMENT '〔Kafka〕成为管理员时间(epoch毫秒)',
    created_at   BIGINT       NOT NULL                 COMMENT '创建时间(epoch毫秒)',
    updated_at   BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,引擎逐行回写)',
    PRIMARY KEY (id),
    KEY idx_jtr_task (tenant_id, join_task_id, id),
    KEY idx_jtr_admin_lookup (tenant_id, group_jid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '进群任务明细(每账号每链接结果)';
