# 进群任务 数据模型(armada · V007)— step0 锁模型

> 状态:用户已**逐表逐列确认锁定**(2026-06-26)。
> 这是「进群任务」重构块的数据地基。后续 CRUD 切片 / 引擎切片均以本表结构为准。

## 范围

进群任务第一刀 = **纯 DB 零协议的 CRUD 切片**(列表/筛选/详情/建任务生成计划行/编辑/复制/批删,DRAFT 态)。
数据模型 = **两张新表**:

- `join_task` —— 任务配置(建时写一次 + 计数器)。
- `join_task_result` —— 账号×链接计划行(引擎逐行回写)。

引擎 / 协议防腐层 / Kafka 全部延后到后续切片,**但其列在 V007 一次建齐**(沿用账号块 `account_state` 先例:状态列一次冻死、可空、留给后填,不做二次 ALTER)。

## 口径(沿用 V005 账号块)

- 时间列全 **BIGINT epoch 毫秒**,应用层写(不用 DB `CURRENT_TIMESTAMP`)。
- 软删 `deleted_at BIGINT NULL`;两表**无业务唯一键**,故**不建 `is_active` 虚拟列**。
- `tenant_id` 由租户拦截器注入,**永不手写**。
- **状态存英文枚举码,中文展示在前端 / VO 边界转**(account-list 6 态先例);筛选前端送码、后端按码筛。
- 出入参 camelCase;列 snake_case + `map-underscore-to-camel-case`。
- 实体纯 POJO + 手写 getter/setter(无 Lombok / 无 MyBatis-Plus 注解);Mapper plain `@Mapper` + 手写 XML(比较符 `&lt;`/`&gt;`)。
- 迁移 **V007**(armada 现到 V006);表/列必带 COMMENT;`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`。

---

## 表一:`join_task`(进群任务)

```sql
CREATE TABLE join_task (
    id                     BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id              BIGINT       NOT NULL                 COMMENT '租户ID(拦截器注入)',
    name                   VARCHAR(128) NOT NULL DEFAULT ''      COMMENT '任务名称',
    account_group_ids      VARCHAR(512) NOT NULL DEFAULT ''      COMMENT '选中账号分组id(JSON数组快照)',
    account_group_names    VARCHAR(512) NOT NULL DEFAULT ''      COMMENT '账号分组名快照(展示用,/连接,免JOIN)',
    selected_account_ids   TEXT                  DEFAULT NULL    COMMENT '选中账号id(JSON数组快照,编辑回填+解析号码)',
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
    total                  INT          NOT NULL DEFAULT 0       COMMENT '计划进群次数(按分配方式推算)',
    executed               INT          NOT NULL DEFAULT 0       COMMENT '已执行次数(引擎回写,建时0)',
    success                INT          NOT NULL DEFAULT 0       COMMENT '成功进群数(引擎回写,建时0)',
    failed                 INT          NOT NULL DEFAULT 0       COMMENT '失败数(引擎回写,建时0)',
    pending                INT          NOT NULL DEFAULT 0       COMMENT '待执行数(建时=total)',
    status                 VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态码:DRAFT待启动/RUNNING进行中/PAUSED暂停/STOPPED已停止/DONE完成/FAILED失败(一期CRUD只产DRAFT;中文展示前端转)',
    created_by             BIGINT                DEFAULT NULL    COMMENT '创建人user_id(操作员;展示名后续JOIN解析)',
    created_at             BIGINT       NOT NULL                 COMMENT '创建时间(epoch毫秒,应用层写)',
    updated_at             BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,应用层写)',
    deleted_at             BIGINT                DEFAULT NULL    COMMENT '软删时间(epoch毫秒),NULL=有效',
    PRIMARY KEY (id),
    KEY idx_join_task_tenant (tenant_id, deleted_at, id),
    KEY idx_join_task_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '进群任务';
```

## 表二:`join_task_result`(进群任务明细 · 账号×链接计划行)

```sql
CREATE TABLE join_task_result (
    id           BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id    BIGINT       NOT NULL                 COMMENT '租户ID',
    join_task_id BIGINT       NOT NULL                 COMMENT '→join_task.id',
    account      VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '执行账号号码/别名(快照,展示用)',
    account_id   BIGINT                DEFAULT NULL    COMMENT '→account.id;建任务时回填(过滤封禁/导出/解绑+跳过被占号);无效链接行为NULL',
    link         VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '群链接',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '进群结果码:PENDING待执行/SUCCESS成功/FAILED失败(中文展示前端转)',
    reason       VARCHAR(255) NOT NULL DEFAULT ''      COMMENT '失败原因(无效链接行建时即写)',
    group_jid    VARCHAR(64)  NOT NULL DEFAULT ''      COMMENT '〔引擎〕进群成功后回填群JID(Kafka promote匹配)',
    is_admin     TINYINT(1)   NOT NULL DEFAULT 0       COMMENT '〔Kafka〕是否已成管理员(participant_changed promote回写)',
    promoted_at  BIGINT                DEFAULT NULL    COMMENT '〔Kafka〕成为管理员时间(epoch毫秒)',
    created_at   BIGINT       NOT NULL                 COMMENT '创建时间(epoch毫秒)',
    updated_at   BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,引擎逐行回写)',
    PRIMARY KEY (id),
    KEY idx_jtr_task (tenant_id, join_task_id, id),
    KEY idx_jtr_admin_lookup (tenant_id, group_jid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '进群任务明细(每账号每链接结果)';
```

---

## 决策日志(对 wheel 的取舍)

1. **两表**(配置 `join_task` + 计划行 `join_task_result`,1:N),非账号块的 1:1 高频拆分。
2. **时间 DATETIME → BIGINT epoch 毫秒**,应用层写(armada 口径)。
3. **`owner` 展示串 → `created_by` BIGINT(user_id)**,对齐 `account_group`;操作员展示名后续 JOIN 用户表解析。
4. **`selected_account_ids` VARCHAR(2048) → TEXT**(防选号多溢出)。
5. **状态中文字面 → 英文枚举码**:`join_task.status` = DRAFT/RUNNING/PAUSED/STOPPED/DONE/FAILED;`join_task_result.status` = PENDING/SUCCESS/FAILED。**中文展示(待启动/进行中/待执行/成功/失败…)在前端/VO 边界转,不进存储;筛选前端送码、后端按码筛**(account-list 6 态先例)。
6. **`join_task_result` 加 `updated_at`**(wheel 无;行会被引擎回写)。
7. **引擎/Kafka 列一次建齐**:`group_jid`/`is_admin`/`promoted_at` + `idx_jtr_admin_lookup`,带安全默认值,CRUD 第一刀不写它们,引擎切片直接用(account_state 先例,免二次 ALTER)。
8. **`account`/`account_id` 属第一刀**:建任务生成计划行时即回填(用前端选中账号,`account`=号码、`account_id`=id 双填),非引擎列。⚠️ 账号**活性过滤**(剔除封禁/导出/解绑 + 跳过被其他 RUNNING 任务占用的号)**延后**(依赖 account_state step3,见〔延后〕),第一刀直接信任前端传入的选号 —— 与 design.md 决策 D2 一致。

## 已完成补充

- **group_link 登记**已由群组列表块实现:进群任务创建/编辑时,有效群邀请链接经内部 `GroupLinkRegistryService.registerJoinTaskTargets` 登记到 `group_link`,`origin=JOIN_TASK`,`membership_state=TARGET`;只做本地入池/复活,不调用协议层。

## 延后(后续切片,不在 step0/第一刀)

- **执行引擎** `JoinTaskWorker` → `GroupJoinPort`(join→groupJid + 回写 status/计数器/group_jid)。
- **协议防腐层** `GroupJoinPort`(join + metadata)—— armada 首个协议写适配器。
- **Kafka group-event 消费者**(`group.participant_changed` promote → 回写 `is_admin`/`promoted_at`)。
- **计划行账号活性过滤**(剔除封禁/导出/解绑 + 跳过被其他 RUNNING 任务占用的号)—— 依赖 account_state step3(account 块活状态),第一刀信任前端选号、引擎执行时复检。
- **两段式 `start()`**、复制(名称加「副本」)、失败分类重试(`JoinFailureClassifier`)。
