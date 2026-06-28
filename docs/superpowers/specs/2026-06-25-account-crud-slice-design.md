# 账号 CRUD 切片(step1)设计 — armada

> 状态:**已 brainstorm 定稿,待用户审 → 转 writing-plans**(2026-06-25)
> 范围:armada 账号块的 step1「冷数据 CRUD 切片」。零协议、零 Kafka、零上线。
> 权威数据模型:`docs/business/account-data-model.md`(6 表冻结 DDL,本文不重复)。
> 上游决策:`armada_block_sequencing`(step0 锁模型 → **step1 本文** → step2 平台地基 → step3 活状态)。

---

## 1. 目标与非目标

**目标**:让账号分组、账号导入、账号列表三页跑起来产生可测真数据,解锁 IP 块 `validAccountCount`(部分)与后续选号的 `account_group_id` 根。

**非目标(明确延后,不在 step1):**
- 更换代理 / 登录 / 离线(协议操作 → step3)
- 六段格式导入(**协议层尚未支持** → TODO,届时六段也组装进 `creds_json`,表不改)
- 国家 / IP来源 / IP地址真值(依赖 IP 绑定关系 `bound_account_id`,TODO-2)
- 登录级批次计数真值(login_success/failed/abnormal → step3)
- 绑定客服筛选(客服模块未建)

**step1 红线**:`account_state` 状态列全 `NULL`=待上线;头像/好友群/超链等占位常量;导入后不上线(`login_result`=NULL/SKIPPED)。

---

## 2. 架构与子分块顺序

**模块布局**(照搬 armada 既有 resource/group/marketing 分层):
```
com.armada.account
  ├ controller/   AccountGroupController, AccountImportController, AccountController
  ├ service/+impl/ AccountGroupService, AccountImportService, AccountQueryService
  ├ mapper/+xml   AccountGroupMapper, AccountMapper, AccountStateMapper,
  │               AccountCredentialMapper, AccountImportBatchMapper, AccountImportDetailMapper
  ├ model/{entity,dto,vo}
  └ converter/    AccountConverter
```
**迁移**:`V005__account.sql`(**V004 已被 `V004__tenant.sql` 占用,防撞号用 V005**)= `account-data-model.md` 冻结的 6 表(account / account_state / account_group / account_credential / account_import_batch / account_import_detail)。所有时间列 `BIGINT` epoch 毫秒,应用层 `System.currentTimeMillis()` 写(BIGINT 无 DB 默认/ON UPDATE);软删唯一键照 `V002__ip_proxy.sql` 的 `is_active` 虚拟列写法。

**子分块顺序**(每块独立 TDD + 真库 DbTest):

| 序 | 子块 | 依赖 | 规模 |
|---|---|---|---|
| 1.1 | 账号分组(account_group CRUD) | — | S |
| 1.2 | 账号导入(JSON/全参解析 → 三步写 + 批次/明细 + 导出) | 1.1 | L |
| 1.3 | 账号列表(静态读 JOIN + 8 维筛选 + 统计 + 迁移分组 + 批量删) | 1.1+1.2 | M |

**复用**:shared 的 TenantContext / PageQuery / PageResult / ApiResponse;**JSON 与全参都是结构化 JSON**(非行式)→ 用自建 `ObjectMapper`(照 `MarketingTemplateConverter`)解析成 `List<ParsedEntry>`,过专用 `importEntries` 循环(照 wheel,批内去重+三步写);`LineImporter` 仅在某格式确为逐行文本时才套(step1 两格式都不是,故不强用)。只走 MyBatis 真库,丢 wheel in-memory stub。

---

## 3. 子块 1.1 — 账号分组

**端点**:`GET /api/account-groups`(keyword 模糊匹 ID/名称 + 分页)、`POST /api/account-groups`(name+remark)、`PUT /api/account-groups/{id}`、`POST /api/account-groups/batch-delete`。

**规则**:
- 名称租户内唯一(`uq_tenant_name`),重名报「分组名已存在」。
- **系统默认组懒创建**(`system_builtin=1`):列表时无则建一个;系统组不可删、不可改名。
- **删除闸门**:软删前 `COUNT(account WHERE account_group_id=? AND deleted_at IS NULL) > 0` → 拒删「请先清空组内账号」;批量删 = 全或无(回报哪个组挡的)。
- **改名自动同步**:列表「分组」列 JOIN 实时取名,不冗余存名 → 改名天然刷新。

**VO**:`id/name/remark/systemBuiltin/accountCount(真值)/onlineCount(占位0,step3)/createdAt`。

**复用**:`create` 被 1.2 导入「新增分组」、1.3「迁移→新建分组」共用。

**DbTest**:重名拒 / 系统组懒创建幂等(并发只建一个)/ 系统组拒删拒改名 / 有账号拒删 / 批量删全或无 / 软删后同名可再建。

---

## 4. 子块 1.2 — 账号导入(核心)

**端点**:
- `POST /api/account-imports`(multipart:file + `{accountGroupId, importFormat, deviceOs, accountType, ipRegion, batchName, remark}`)
- `GET /api/account-imports`(批次列表 + 搜索 + 分页)
- `GET /api/account-imports/{batchId}/details`(明细 + 筛选 全部/成功/失败 + 分页)
- `GET /api/account-imports/{batchId}/export?scope=all|success|fail`(CSV 5 列:账号/状态/失败原因/分组/创建时间)

**格式**:`importFormat` 枚举保留 `1六段/2JSON/3全参`(向前兼容);step1 只接受 **2 JSON / 3 全参**,选 `1六段` → 拒「六段暂不支持(协议层未接)」。两格式均为结构化 JSON(用自建 `ObjectMapper` 解析):JSON 支持 单对象/数组/`.zip`(内存解压,一号一文件);全参为参数 JSON(单对象/数组)。各格式解析器产出 `List<ParsedEntry>{wid, data, parseError}`,统一进 `importEntries` 循环。

**完整性校验(导入即校验,过不了 → `parse_result=4 凭据不全`,不建号):**
| 格式 | 门槛 |
|---|---|
| baileys_json | 合法 JSON + 含 creds + 关键键齐(`registrationId`/`noiseKey`/`signedIdentityKey`/`signedPreKey`) |
| 全参(params) | 合法 JSON/参数 + wid 合法 + 必需键齐(确切键清单写计划时按真实样例定) |

**导入核心流程**(armada 重建 wheel `importEntries` 骨架):
```
parseEntries(format, file) → List<ParsedEntry>{wid, data, parseError}
逐条(批内 seenWid 去重):
  ├ 解析失败           → parse_result=3 格式错误,不建号
  ├ 凭据不全           → parse_result=4 凭据不全,不建号
  ├ 批内 wid 重复      → parse_result=2 重复(批内),不建号
  └ 否则【单行事务·3 写原子】:
       ① INSERT account(离线身份行;account_type 冻结;protocol_account_id='acc_'+wid 同行写入)
       ② INSERT account_state(默认行,状态列全 NULL=待上线,计数 0)
       ③ INSERT account_credential(creds_json + cred_format)
       ├ 成功            → parse_result=1 成功入库,回填 detail.account_id
       └ DB uq 撞键并发  → parse_result=2 重复(库内已存在)
写 account_import_batch(计数)+ 批量 account_import_detail
  status = 2 已完成(step1 同步导入流程即时结束恒「已完成」;1进行中 是 step3 登录结果未齐时的态)
  成败不进 status,由计数列(imported/duplicate/format_error)表达
login_result 全 NULL/SKIPPED(不上线)
```
**单行 3 写做成一个事务**(account+state+cred 同生,杜绝孤儿身份行);一行失败只记该行明细、不回滚整批;批次+明细在循环后落库。`protocol_account_id='acc_'+wid` 折进 ① 的 INSERT(普通列写,非 HTTP),省独立 UPDATE。

**parse_result ↔ 批次计数映射**:
- `imported_rows` = parse_result 1
- `duplicate_rows` = parse_result 2(批内重复 ∪ 库内已存在,fail_reason 区分)
- `format_error_rows` = parse_result 3 + 4(格式错误 ∪ 凭据不全,明细 parse_result 区分)

**step1 列表的"登录级"列**:`任务进度`=解析进度(同步导入恒满 `total/total`);`登录成功/失败/异常`=NULL→渲「未登录」。真正导入结果(成功入库/重复/格式错误/凭据不全)在明细 + 批次解析级计数里看。

**铁律**:`creds_json` 敏感 → 全路径日志只打 maskPhone + 凭据长度,绝不打明文;序列化失败抛 VALIDATION。

**DbTest**:四来源解析(JSON对象/数组/zip/params行)/ 凭据不全各格式缺键拦截 / 批内去重 / DB uq 并发兜底 DUPLICATE_KEY→重复 / 三步写原子(中途失败不留孤儿 account)/ PARTIAL vs DONE / 明细筛选分页 / 导出 CSV 5 列 / 跨租户(A 租户号 B 租户可导=租户内唯一)。

---

## 5. 子块 1.3 — 账号列表(静态读)

**端点**:
- `GET /api/accounts`(8 维筛选 + 分页;`account a LEFT JOIN account_state s LEFT JOIN account_group g`)
- `GET /api/accounts/stats`(平台级 SQL summary)
- `POST /api/accounts/batch-migrate-group`(`{ids, accountGroupId}` 或新建分组)
- `POST /api/accounts/batch-delete`
- 更换代理/登录/离线 = **step3,不在本块**

**筛选**:account 列真筛(账号开头模糊/协议/渠道·来源/账号类型/绑定分组);account_state 列(账号状态/风控)与 ip_proxy 列(国家/IP地址)筛选项保留但 step1 无数据可命中,step3 点亮;客服筛选占位。全程 SQL 下推分页/count/筛选(禁内存分页)。

**VO 列来源**:account 真值 / account_state 列 NULL→「待上线·—」/ 头像·好友群·超链=占位常量 / 国家·IP来源·IP地址=占位(待绑定 TODO-2)。

**统计卡(step1)**:总账号数=真值;封禁/在线/离线/风控/已分配/未分配=读 account_state+dispatched_at(全 NULL)→0/待上线;差额(总数−在线−离线)=待上线号。step3 变真值。

**删除口径(严格按需求,不放宽)**:可删 = `account_state ∈ {3封禁,4导出,5解绑}` **且** `dispatched_at IS NULL`(不在任务),否则拒删 + 回报原因。**连带后果**:step1 待上线号(state=NULL)全不可删(报「仅导出/封禁/解绑状态可删」)——规则正确实现,清测试数据走 DB 硬删/重导清表,不走列表删除;step3 有真状态后自然生效。

**DbTest**:8 维筛选 SQL 下推(account 真筛 / state NULL 安全)/ JOIN 分组名随改名刷新 / 统计卡平台级 summary / 迁移分组(含新建分组)/ 批量删严格口径(NULL 状态拒删)/ 跨租户不串号。

---

## 6. 错误处理

- 响应信封 `code≠0`+message(BusinessException→GlobalExceptionHandler),HTTP 恒 200。
- 导入:文件空 / 选六段 / zip 无 json / creds 序列化失败 → 全抛 VALIDATION,不静默吞。
- 单行失败 per-row 隔离不中断整批;批量删/迁移 = 全或无(回报哪条挡的)。
- 凭据日志掩码(maskPhone + 长度),绝不打 creds_json 明文。
- 租户 fail-closed(无上下文注 -1);Mapper XML 裸尖括号转义(archtest 兜)。
- 导入用 DB `uq` 兜并发,不用 FOR UPDATE(避开 FOR UPDATE+拦截器坑)。

---

## 7. 测试线

真库 DbTest(照 import-links 块):`DbTestBase` + `armada-api/dbtest.sh`(本机 MySQL 9.3 armada 库)。每子块红→绿 TDD;archtest 解析全 mapper XML + 断言 tenant 隔离列。**禁 mock 假数据,只真库。**

---

## 8. 决策日志

| 决策 | 结论 |
|---|---|
| 导入完整性校验时机 | **导入即校验**(A),不全→parse_result=4,不建号 |
| 六段格式 | step1 **跳过**(协议层未支持),TODO |
| 单行写 | **3 步原子**(account 含 protocol_account_id / account_state / credential) |
| 重复口径 | duplicate=批内 ∪ 库内,DB uq 兜并发 |
| 批次状态 | step1 恒 2 已完成(同步导入结束);成败看计数,不用 DONE/PARTIAL 单列 |
| 删除口径 | **严格**(state∈终态 且 不在任务),不放宽;step1 待上线号不可删 |
| 时间列 | BIGINT epoch 毫秒,应用层写 |
| 实施 | 子代理驱动 TDD,1.1→1.2→1.3 |

---

## 9. 关联 TODO(见 account-data-model.md §六)

TODO-1 多租户同号抢登 / TODO-2 国家·IP来源·validAccountCount 真值 / TODO-3 协议回写富字段 / TODO-4 自动上线 / TODO-6 wheel↔协议层 creds 权威+重连对账 / TODO-7 V001-V003 时间列改 BIGINT。
