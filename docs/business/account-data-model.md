# account 数据模型 —— 已冻结（DECIDED）

> 状态:**审核中(2026-06-25,四张支撑表用户尚在对账)**。取代 `account-data-model-WIP.md`。
> 这是 armada 账号块(账号分组 / 账号导入 / 账号列表)的权威表设计。`account` + `account_state` 的列边界一旦由 V005 落库即被后续状态块永久共享,故一次定死,杜绝 wheel `account` 表被 V029/V030/V046/V068 反复 ALTER 叠列的债。
> 实施顺序见 `armada_block_sequencing`:step0 锁模型(本文档)→ step1 CRUD 切片 → step2 平台地基 → step3 活状态回写。

---

## 一、已锁决策

| # | 决策 | 结论 | 依据 |
|---|---|---|---|
| 1 | 协议回写假字段(avatar_url/friends_num/groups_num/hyperlink_sent_count) | **不建列**,VO 占位返常量 | 遵 V002「禁死列」(IP 块没预留 bound_account_id);等协议回写路径(step3/二期)落地再加列+迁移 |
| 2 | 拆表 | **主表 `account` + 子表 `account_state` 两张** | 只隔离真正高频的 Kafka 回写;protocol 身份列是导入即冻结的低频身份留主表;risk/proxy 运行态并入 state;列表读只 1 个 JOIN |
| 3 | 三镜像归一 | **删 `tag_id` / `account_tag` / `role`,归一单 `account_group_id`** | wheel 实证 role 恒 null、角色任务执行时按分组分配、导入 `List.of(groupId)` 实为单组;现在归一代价最小 |
| 4 | ws_phone 唯一性 | **租户内唯一 `uq(tenant_id, ws_phone, is_active)`** | 多租户隔离;见 TODO-1(后续多租户同号导入 + 抢登) |
| 5 | 导入后自动上线 | **一期关**,`login_result` 恒 `SKIPPED` | 本块零协议;上线整套延后到 step3 平台地基 |
| 6 | 主键策略 | **BIGINT AUTO_INCREMENT** | 对齐 V002/V003;`account_id` 做普通外键列 |
| 7 | **时间列存储** | **一律 `BIGINT`(epoch 毫秒, UTC)**,非 DATETIME | 用户口径「时间用时间戳」;存储=出参=前端 `new Date(ms)`,无 DATETIME↔epoch 转换层;`created_at/updated_at` 应用层写 `System.currentTimeMillis()`(BIGINT 不能 `ON UPDATE CURRENT_TIMESTAMP`);`is_active` 虚拟列照按 `deleted_at IS NULL` 判 |
| 8 | **V001-V003 老表时间列** | **一并改 BIGINT**(营销/IP/导入链接),含代码改动 | 见 TODO-7;统一口径避免账号块与老三块分裂 |

**字段口径(2026-06-25 核对):**
- 「入库时间」= `account.created_at`(导入入库真实时间戳),**不是** first_login_time(wheel 恒 NULL);删该死列。
- `account_state` / `login_state` / `risk_status` / `mute_status` **可空、无默认**:`NULL` = 未上报(列表渲「待上线 / —」,不渲离线/正常,避免给业务假确定性)。连带:统计卡「在线+离线 < 总数」,差额=未上报号,step3 接 Kafka 后收敛。计数列保留 DEFAULT 0。
- `truth_ip` = 真实出口公网 IP(住宅 IP,WA 实际看到的),上线时由出口探测器解析;**不等于** `ip_proxy.host`(代理网关入口地址)。step3 才有值。

---

## 二、`account`（身份主表 · 低频冻结 · 21 列）

```sql
CREATE TABLE account (
    id                  BIGINT       NOT NULL AUTO_INCREMENT  COMMENT '主键',
    tenant_id           BIGINT       NOT NULL                 COMMENT '租户ID(拦截器注入)',
    ws_phone            VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'WA号(按字节精确去重)',
    account_type        TINYINT      NOT NULL                 COMMENT '账号类型:1个人 2商业(导入即冻结,不得改写)',
    device_os           TINYINT               DEFAULT NULL    COMMENT '机型:1安卓 2苹果',
    number_source       TINYINT               DEFAULT NULL    COMMENT '来源:1买量 2裂变 3自购',
    channel_name        VARCHAR(64)           DEFAULT NULL    COMMENT '推广渠道名',
    ownership           TINYINT      NOT NULL DEFAULT 1       COMMENT '归属:1自有 2平台 3租借',
    lease_until         BIGINT                DEFAULT NULL    COMMENT '租借到期(epoch毫秒;ownership=3)',
    account_group_id    BIGINT                DEFAULT NULL    COMMENT '★归一单分组(→account_group.id)',
    protocol_id         VARCHAR(32)           DEFAULT NULL    COMMENT '接入协议标识(系统分配)',
    protocol_account_id VARCHAR(64)           DEFAULT NULL    COMMENT '协议账号句柄 acc_<wsPhone>',
    protocol_address    VARCHAR(128)          DEFAULT NULL    COMMENT '协议地址',
    priority            INT          NOT NULL DEFAULT 0       COMMENT '选号优先级',
    dispatched_at       BIGINT                DEFAULT NULL    COMMENT '首次派单时间(epoch毫秒;step1恒NULL=未分配)',
    remark              VARCHAR(255)          DEFAULT NULL    COMMENT '备注',
    created_at          BIGINT       NOT NULL                 COMMENT '入库时间(epoch毫秒,应用层写;账号列表「入库时间」列)',
    updated_at          BIGINT       NOT NULL                 COMMENT '更新时间(epoch毫秒,应用层写)',
    created_by          BIGINT                DEFAULT NULL    COMMENT '创建人user_id',
    deleted_at          BIGINT                DEFAULT NULL    COMMENT '软删时间(epoch毫秒);NULL=未删',
    is_active           TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL COMMENT '软删唯一键辅助:活=1 删=NULL',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_phone (tenant_id, ws_phone, is_active),
    KEY idx_tenant_group (tenant_id, account_group_id),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号身份主表';
```

## 三、`account_state`（生命周期子表 · 高频 Kafka · 与 account 1:1 · 20 列）

> step1:导入时一并 INSERT 一行,状态列全留 **NULL**(未上报),计数列 0。step3:Kafka handler 接上后逐列点亮。无独立软删,随 account JOIN 过滤。

```sql
CREATE TABLE account_state (
    id                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id             BIGINT      NOT NULL                COMMENT '租户ID',
    account_id            BIGINT      NOT NULL                COMMENT '→account.id',
    account_state         TINYINT              DEFAULT NULL   COMMENT '1新增 2正常 3封禁 4导出 5解绑;NULL=未上报',
    login_state           TINYINT              DEFAULT NULL   COMMENT '1在线 2离线;NULL=未上报',
    risk_status           TINYINT              DEFAULT NULL   COMMENT '1未风控 2风控中 3待解除;NULL=未上报',
    risk_end_time         BIGINT               DEFAULT NULL   COMMENT '风控倒计时终点(epoch毫秒)',
    cooldown_until        BIGINT               DEFAULT NULL   COMMENT '冷却到期(epoch毫秒)',
    mute_status           TINYINT              DEFAULT NULL   COMMENT '1禁言6h 2禁言24h;NULL=未上报',
    block_error_code      VARCHAR(32)          DEFAULT NULL   COMMENT '封号错误码(401/403/440)',
    block_reason          VARCHAR(255)         DEFAULT NULL   COMMENT '封号原因(落库前按列宽截断)',
    state_source          VARCHAR(64)          DEFAULT NULL   COMMENT '状态来源前缀 NEED_REAUTH/PROXY_FAILED(截断)',
    last_state_sync_time  BIGINT               DEFAULT NULL   COMMENT '最后对账时间(epoch毫秒)',
    invalidated_at        BIGINT               DEFAULT NULL   COMMENT '失效时间(epoch毫秒;导出/解绑)',
    truth_ip              VARCHAR(45)          DEFAULT NULL   COMMENT '真实出口公网IP(上线探测;≠ip_proxy.host网关)',
    proxy_country         VARCHAR(64)          DEFAULT NULL   COMMENT '出口国家',
    proxy_failure_count   INT         NOT NULL DEFAULT 0      COMMENT '代理失败计数',
    pull_into_group_count INT         NOT NULL DEFAULT 0      COMMENT '拉人数量',
    created_at            BIGINT      NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at            BIGINT      NOT NULL                COMMENT '更新时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_account (tenant_id, account_id),
    KEY idx_tenant_login (tenant_id, login_state),
    KEY idx_tenant_state (tenant_id, account_state),
    KEY idx_tenant_risk (tenant_id, risk_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号生命周期状态(高频Kafka回写)';
```

---

## 四、支撑表（逐列 · 用户对账中）

### `account_group`（账号分组 · 纯 CRUD · 10 列)
```sql
CREATE TABLE account_group (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id      BIGINT       NOT NULL                COMMENT '租户ID',
    name           VARCHAR(100) NOT NULL                COMMENT '分组名(租户内不重复)',
    remark         VARCHAR(255)          DEFAULT NULL   COMMENT '备注',
    system_builtin TINYINT      NOT NULL DEFAULT 0      COMMENT '系统内置默认组:1=不可删',
    created_at     BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at     BIGINT       NOT NULL                COMMENT '更新时间(epoch毫秒)',
    created_by     BIGINT                DEFAULT NULL   COMMENT '创建人user_id',
    deleted_at     BIGINT                DEFAULT NULL   COMMENT '软删时间(epoch毫秒);NULL=未删',
    is_active      TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL,1,NULL)) VIRTUAL COMMENT '软删唯一键辅助',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_name (tenant_id, name, is_active),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号分组';
```
**删组前若组内有账号 → 拒删**(需求口径)。

### `account_credential`（自托管凭据 · 12 列)
```sql
CREATE TABLE account_credential (
    id                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id          BIGINT      NOT NULL                COMMENT '租户ID',
    account_id         BIGINT      NOT NULL                COMMENT '→account.id',
    ws_phone           VARCHAR(32)          DEFAULT NULL   COMMENT 'WA号(冗余便反查)',
    cred_format        TINYINT     NOT NULL                COMMENT '凭据格式:1六段 2JSON 3全参',
    creds_json         TEXT                 DEFAULT NULL   COMMENT '完整凭据blob(六段/全参也解析组装进这里;敏感,日志只打maskPhone+长度)',
    proxy_session_id   VARCHAR(64)          DEFAULT NULL   COMMENT 'sticky代理session(同IP复用键;上线时填)',
    proxy_retain_until BIGINT               DEFAULT NULL   COMMENT '代理session保留到期(epoch毫秒;下线时填)',
    created_at         BIGINT      NOT NULL                COMMENT '创建时间(epoch毫秒)',
    updated_at         BIGINT      NOT NULL                COMMENT '更新时间(epoch毫秒)',
    deleted_at         BIGINT               DEFAULT NULL   COMMENT '软删时间(epoch毫秒)',
    is_active          TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL,1,NULL)) VIRTUAL COMMENT '软删唯一键辅助',
    PRIMARY KEY (id),
    UNIQUE KEY uq_tenant_account (tenant_id, account_id, is_active),
    KEY idx_tenant_phone (tenant_id, ws_phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号自托管凭据';
```
**作用**:wheel 自托管 creds、协议层上线时现喂(load+connect)的密钥;导入只落库不连协议;`proxy_session_id/proxy_retain_until` step1 恒 NULL(上线时才填)。upsert 支持同号重导覆盖+复活软删行。**铁律**:日志只打 maskPhone+凭据长度,绝不打 `creds_json` 明文。

### `account_import_batch`（账号导入批次 · 20 列)
```sql
CREATE TABLE account_import_batch (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id         BIGINT       NOT NULL                COMMENT '租户ID',
    account_group_id  BIGINT       NOT NULL                COMMENT '导入目标分组(→account_group.id)',
    batch_name        VARCHAR(255) NOT NULL                COMMENT '批次/任务名',
    source_file_name  VARCHAR(255)          DEFAULT NULL   COMMENT '上传文件原名;纯文本导入=NULL',
    import_format     TINYINT      NOT NULL                COMMENT '导入格式:1六段 2JSON 3全参',
    device_os         TINYINT               DEFAULT NULL   COMMENT '机型:1安卓 2苹果',
    account_type      TINYINT               DEFAULT NULL   COMMENT '账号类型:1个人 2商业',
    ip_region         VARCHAR(64)           DEFAULT NULL   COMMENT '导入时选的IP国家',
    total_rows        INT          NOT NULL DEFAULT 0      COMMENT '解析总行数',
    imported_rows     INT          NOT NULL DEFAULT 0      COMMENT '成功入库行数',
    duplicate_rows    INT          NOT NULL DEFAULT 0      COMMENT '重复行数(批内/库内)',
    format_error_rows INT          NOT NULL DEFAULT 0      COMMENT '格式/凭据不全行数',
    login_success     INT                   DEFAULT NULL   COMMENT '登录成功(step1 NULL=未登录)',
    login_failed      INT                   DEFAULT NULL   COMMENT '登录失败(step1 NULL)',
    login_abnormal    INT                   DEFAULT NULL   COMMENT '登录异常密钥/封号(step1 NULL)',
    status            TINYINT      NOT NULL DEFAULT 2      COMMENT '批次状态:1进行中 2已完成(step1同步导入即2)',
    created_at        BIGINT       NOT NULL                COMMENT '导入时间(epoch毫秒)',
    created_by        BIGINT                DEFAULT NULL   COMMENT '创建人user_id',
    deleted_at        BIGINT                DEFAULT NULL   COMMENT '软删时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_tenant_group (tenant_id, account_group_id),
    KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号导入批次';
```
解析级计数(total/imported/duplicate/format_error)step1 真值;登录级计数(login_*)step1 NULL,step3 回写。前端「任务进度/登录成功失败」=登录级 → step1 渲「未登录/已导入」。

### `account_import_detail`（账号导入明细 · 10 列)
```sql
CREATE TABLE account_import_detail (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id    BIGINT       NOT NULL                COMMENT '租户ID',
    batch_id     BIGINT       NOT NULL                COMMENT '所属批次(→account_import_batch.id)',
    line_no      INT          NOT NULL                COMMENT '行号',
    ws_phone     VARCHAR(32)           DEFAULT NULL   COMMENT '该行WA号',
    account_id   BIGINT                DEFAULT NULL   COMMENT '成功入库时回填(→account.id)',
    parse_result TINYINT      NOT NULL                COMMENT '1成功入库 2重复(批内或库内已存在) 3格式错误 4凭据不全',
    fail_reason  VARCHAR(255)          DEFAULT NULL   COMMENT '失败原因',
    login_result TINYINT               DEFAULT NULL   COMMENT 'NULL=未登录/跳过(step1);step3:1成功 2失败 3密钥异常 4封号',
    created_at   BIGINT       NOT NULL                COMMENT '创建时间(epoch毫秒)',
    PRIMARY KEY (id),
    KEY idx_tenant_batch (tenant_id, batch_id),
    KEY idx_tenant_batch_result (tenant_id, batch_id, parse_result)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账号导入明细';
```
导出全部/失败 CSV 5 列=账号/状态/失败原因/分组/创建时间。

**六表合计列数**:account 21 / account_state 20 / account_group 10 / account_credential 12 / import_batch 20 / import_detail 10。

---

## 五、账号列表列 → 来源映射

| 列 | 来源 | step1 |
|---|---|---|
| 账号 / 账号类型·设备 / 渠道·来源 / 协议 / 分组 / 入库时间 | `account`(ws_phone/account_type+device_os/channel_name+number_source/protocol_id/account_group_id→组名/created_at) | ✓ 真值 |
| 状态 / 登录 / 风控 / 封号码·原因 / 拉人数 / IP地址 / 失效时间 | `account_state` | NULL=待上线;step3 点亮 |
| 头像 / 好友·群 / 超链寿命 | **VO 占位**(禁死列;协议回写,step3/二期) | 常量 |
| 国家 / IP来源 | **JOIN ip_proxy**(经 IP 绑定 region/source) | 待绑定;见 TODO-2 |

---

## 六、TODO（后续块/二期）

- **TODO-1 多租户同号导入 + 抢登**:当前 ws_phone 租户内唯一,允许多租户各导同一号。后续需求是多个租户都能导入同号并**抢登**(谁先上线谁占用);届时要定:抢登仲裁(协议层同号互斥/IP 绑定竞争)、跨租户号状态可见性、败者处理。当前隔离实现不阻断未来扩展。
- **TODO-2 国家/IP来源真值 + validAccountCount**:需在 `ip_proxy` 补 `bound_account_id`(遵 V002 禁死列,绑定流程建时再加)+ JOIN 算账号列表「国家/IP来源」与 IP 块 `validAccountCount`(排除封禁/导出/解绑终态,照 wheel `selectTenantManagedPage` 口径)。
- **TODO-3 协议回写富字段**:avatar_url/friends_num/groups_num/hyperlink_sent_count 待协议同步路径(step3/二期)落地后加列+回写。
- **TODO-4 自动上线**:autoOnline 开关 + ImportOnlineJob(LOGIN_QUEUED 出队)在 step3 平台地基后接入,届时 `login_result`/批次登录级计数才有真值。
- **TODO-5 referenceTaskCount**:营销模板引用计数真值依赖营销任务模块。
- **TODO-6 wheel↔协议层 creds 权威 + 重连对账(step3 必解)**:codegraph 实测协议层(laqunxitong)**非纯瞬态**——自己有三级 creds 持久库(L1内存/L2 Redis/L3 MySQL `creds_store` 表「长期持久化」),掉线自动重连走 `ReconnectController`(A/B/C 退避+45s 看门狗)→`buildAuthState`→`credsStore.load`(内存优先,内存没了回落 Redis/MySQL,**无需 wheel 重喂**)。与 wheel `AccountLifecycleClient.online()` javadoc「协议层纯瞬态/下线即丢/creds 每次现喂」**冲突**。风险:协议层可能在 armada 不知情时自重连上线,而 `AutoOnlineSupervisor` 又重上线它以为离线的号 → **双拉同号/抢登/双占 IP/封号**(=幽灵在线同源)。step3 开工前须定清:① account_credential(armada)vs creds_store(协议层)谁权威;② 是否强制走「方案B」inline 喂 creds 跳过协议层自存;③ wheel↔协议层重连状态对账机制。详见记忆 `protocol_self_persists_creds_autoreconnect_0625`、契约 `platform-protocol-contract.md`。
- **TODO-7 统一 V001-V003 时间列为 BIGINT**:营销(V001)/IP(V002)/导入链接(V003)三表现用 DATETIME,需改 BIGINT 与账号块统一。**含代码改动**:实体 `LocalDateTime`→`Long`、mapper XML、去掉 DATETIME→epoch 出参转换层、VO 字段、单测/DbTest;**出参 wire 仍是 epoch 毫秒不变 → 对接前端的 agent 无感**;另需对存量行做 DATETIME→epoch 数据迁移(或清测试数据重导)。这三块正被另一 agent 对接前端,改动要与之协调时序。

---

## 七、step1 CRUD 切片范围(本模型落地后)

零协议、零 Kafka:账号分组 CRUD + 账号导入(解析六段/JSON/全参 → 建 account 行 + account_state 默认行 + account_credential + 批次/明细)+ 账号列表静态读 + 迁移分组。导入后不上线(login_result=SKIPPED/NULL)。须自建 DbTest:查重(批内 wid 去重 + DB uq 兜底 DUPLICATE_KEY)、PARTIAL/DONE 批次语义、credential 日志掩码。只实现 MyBatis 真库路径,丢 wheel 的 in-memory stub。**迁移版本号 = V005**(V004 已被 `V004__tenant.sql` 占用,防撞号),按 TDD 在 step1 与 DbTest 一起建(本文档 DDL 为冻结草案)。
