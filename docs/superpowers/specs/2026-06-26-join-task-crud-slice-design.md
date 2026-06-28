# 进群任务 · 第一刀(CRUD 切片)设计 spec

> 状态:数据模型 + 端点 + 算法 + 4 决策 已与用户逐节确认(2026-06-26)。本 spec 待用户复审 → 转 writing-plans。
> 数据模型详见 `docs/business/join-task-data-model.md`(step0 锁模型,本 spec 不重复 DDL,只引用)。

## Goal

把 wheel「进群任务」菜单重构进 armada 的**第一刀**:纯 DB、零协议、零引擎的 **CRUD 切片**。产出可建/查/改/删 DRAFT(待启动)进群任务、生成账号×链接计划行、明细可看。执行引擎、协议防腐层、Kafka、两段式启动全部延后。

## Scope

**做(第一刀):**
- V007 迁移建 `join_task` + `join_task_result` 两表(模型已锁)。
- 实体(纯 POJO)+ Mapper(plain `@Mapper` + 手写 XML)+ Service + Controller。
- 端点:列表(筛选 SQL 下推)/ 间隔下拉 / 建任务(生成计划行)/ 详情(回填)/ 编辑(DRAFT)/ 明细 / 批量软删。
- 计划行生成两种分配方式 + 无效链接落失败行。
- DbTest(真库,TDD)。

**不做(延后,见末尾 TODO):**
- 执行引擎 `JoinTaskWorker`、协议防腐层 `GroupJoinPort`、Kafka group-event 消费者。
- 两段式 `start()`、`group_link` 登记、失败分类重试、复制后端端点。

## 包归属

`com.armada.task.*`(现为 `.gitkeep` 空壳):`controller` / `service`(+`impl`)/ `mapper` / `model.{entity,dto,vo}`。迁移 `armada-api/src/main/resources/db/migration/V007__join_task.sql`;XML `resources/mapper/task/`。

---

## 端点面(camelCase,base `/api/join-tasks`)

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/join-tasks` | 分页列表 + 筛选 |
| GET | `/api/join-tasks/intervals` | 进群间隔下拉(`interval_label` 去重) |
| POST | `/api/join-tasks` | 建任务(落库 + 生成计划行,DRAFT) |
| GET | `/api/join-tasks/{id}` | 详情(完整配置,编辑/复制回填) |
| PUT | `/api/join-tasks/{id}` | 编辑(仅 DRAFT 且 executed=0,整表单覆盖 + 重建计划行) |
| GET | `/api/join-tasks/{id}/results` | 明细(账号×链接行,群链接原样直出,不脱敏) |
| POST | `/api/join-tasks/batch-delete` | 批量软删(任意状态可删,幂等,返回删除数) |

> 权限:armada 现有 controller(account/group)**均不带 `@RequirePerm`**,权限/JWT 后置。本块对齐现状 —— 不加权限注解、不种子权限码;租户隔离仍由 `X-Tenant-Code` 拦截器 + MyBatis 行隔离保证。权限收口留 TODO,与全站统一时再加。

## 入参 DTO

```
CreateJoinTaskDTO {
  name: String,                          // 必填
  accountGroupIds: [Long],               // 选中账号分组 id
  accountGroupNames: [String],           // 分组名(展示快照,后端以 / 连接落 account_group_names)
  selectedAccounts: [{ accountId: Long, phone: String }],  // ★前端传 id+号码,有序;后端直接用
  linksText: String,                     // 进群链接框原文(多行)
  distributionMode: String,              // FIXED_ACCOUNTS_PER_LINK / FIXED_ACCOUNT_MULTI_LINK
  accountsPerLink: Integer,              // 方式一
  executorAccountCount: Integer,         // 方式二
  linksPerAccount: Integer,              // 方式二
  fixedIntervalMinSec / fixedIntervalMaxSec: Integer,   // 方式一间隔
  multiIntervalMinSec / multiIntervalMaxSec: Integer,   // 方式二间隔
  retryEnabled: Boolean,
  retryLimit: Integer,
  failurePolicy: String                  // 策略快照(JSON/标签),原样落库
}
JoinTaskFilter { keyword, status, groupId, distributionMode, interval, dateFrom, dateTo }
BatchDeleteRequest { ids: [Long] }
```

> ★ 与 wheel 的关键差异:wheel 传 `selectedAccountIds:[id]` 再后端 lookup 号码;armada 让前端把 `{accountId, phone}` 一起传(选号时前端本就有),**删掉 `resolveAccountDisplays` lookup**。`selected_account_ids` 快照列 = `selectedAccounts[].accountId` 的 JSON 数组(供编辑回填)。

## 计划行生成算法(create / update 共用)

1. **拆链接**:`linksText` 按行拆 → 去空行 → 去重保序 → 含 `chat.whatsapp.com` 判为有效群链接,否则无效。
2. **无效链接**:每条生成一条 `FAILED` 行(`account=''`,`account_id=NULL`,`reason='非群链接'`)。
3. **有效计划行**(账号取自 `selectedAccounts` 有序列表,`account=phone`、`account_id=accountId`,**两列都填**):
   - **方式一 `FIXED_ACCOUNTS_PER_LINK`**:遍历每条有效链接,每条配 `accountsPerLink` 个账号;账号用**跨链接连续游标** `rr`:`accounts[rr % n]`,`rr++`。
   - **方式二 `FIXED_ACCOUNT_MULTI_LINK`**:`linkCap = min(linksPerAccount, 有效链接数)`;遍历 `executorAccountCount` 个账号(`accounts[a % n]`),每账号配**前 `linkCap` 条**链接。
4. **`total` = 实际生成的 PENDING 行数**(数真实行,非公式);`pending = total`,`executed/success/failed = 0`,`status = DRAFT`。
5. **`account_id` 信任前端传值**:不再 lookup。引擎执行时按 `account_id` + `tenant_id` 重新加载账号(租户作用域),坏 id 只会让该行执行失败,不泄露跨租户动作。

> 边界:`selectedAccounts` 为空时仍生成等量空账号行(`account=''`),由校验要求 ≥1 账号兜底(见校验)。

## 编辑(update)

仅 `status=DRAFT` 且 `executed=0` 可编辑,否则抛 VALIDATION「任务已执行,不能编辑」;不存在抛 RESOURCE_NOT_FOUND。逻辑与 create 一致:覆盖配置列 → `deleteResultsByTask` → 重建计划行。`executed/success/failed` 不动(DRAFT 恒 0),`pending=total`。

## 校验口径(第一刀:结构性 + 宽松,与 wheel 持平)

- `name` 必填,空抛 VALIDATION。
- 负数配置参数归一为 0(`positive()`),不抛。
- 详尽的"保存 schema"校验(≥1 分组 / ≥1 可用账号 / ≥1 分配方式 / 间隔 min≤max / 模式参数正整数 / 离线号不可作执行号)主要由**前端**承担(需求即如此);后端第一刀不强校,留 **TODO** 后续按需硬化(armada 铁律:可恢复错误必抛 VALIDATION 不吞)。

## VO(出参,camelCase;status/进群结果均为**英文枚举码**,中文展示前端/VO 边界转)

- `JoinTaskVo`(列表):`id, name, accountGroupNames, total, executed, success, failed, pending, intervalLabel, distributionMode, failurePolicy, retryEnabled, retryLimit, status, createdBy, createdAt`。
- `JoinTaskDetailVo`(详情/回填):`id, name, status, accountGroupIds:[Long], accountGroupNames, selectedAccountIds:[Long], linksText, distributionMode, accountsPerLink, executorAccountCount, linksPerAccount, fixedIntervalMinSec/Max, multiIntervalMinSec/Max, retryEnabled, retryLimit, total, executed, success, failed, pending, intervalLabel, createdAt`。
- `JoinResultRow`(明细):`account, link(群链接原样直出,不脱敏), status, reason, isAdmin`。

> **决策(0626 修订):群链接不脱敏。** 明细直显原始群链接 —— armada 为平台内部系统、运营需点链接核查,无对外泄露面。原设计沿用 wheel 的 `maskGroupLink`(`chat.whatsapp.com/****`+末3)脱敏**已撤销**:`GroupLinkMask` 不实现,`results` 出参 `link` 原样,后续菜单同样不脱敏。

> `createdBy`:armada 暂无当前用户上下文(与现有 `group_link`/`label`/`import_batch` 的 `created_by` 一致),建任务时不设 → NULL;接入鉴权后再填,操作员展示名届时 JOIN 用户表解析。

## 测试(TDD,真库 DbTest,`DbTestBase` + `TenantContext.set(1)` + `@Transactional` 回滚)

- V007 迁移生效(两表存在,列对齐锁定 DDL)。
- create 方式一:`链接×N` 行、跨链接轮询账号正确、`total`/`pending` 对齐真实行、`status=DRAFT`、`account`+`account_id` 双填。
- create 方式二:`账号×linkCap` 行、`linkCap=min(K,链接数)`、链接不足时只取前 K 条、`total=实际行`。
- 无效链接 → `FAILED` 行 + reason。
- 列表 + 各筛选条件 SQL 下推命中;间隔下拉去重。
- 详情回填(JSON 列解析回 List)。
- update:DRAFT 重建计划行;非 DRAFT / executed>0 抛 VALIDATION。
- 批量软删:置 `deleted_at`、幂等、返回删除数。
- 明细群链接原样直出(不脱敏)。
- 跨租户隔离(tenant=2 查不到 tenant=1 的任务/明细,join_task + join_task_result 两表均验)。

---

## 延后 TODO(D4 记录;不在第一刀)

- [ ] **TODO(引擎切片)**:`POST /api/join-tasks/{id}/start` 两段式启动 + `JoinTaskWorker` + `GroupJoinPort` 协议防腐层(join→groupJid) + 回写 `status/计数器/group_jid`。
- [ ] **TODO(引擎切片)**:计划行账号过滤 —— 封禁/导出/解绑账号不入计划行(依赖 account_state 活状态 step3)+ 跳过被其他 RUNNING 任务占用的号。
- [ ] **TODO(Kafka 切片)**:`group.participant_changed` promote → 回写 `is_admin`/`promoted_at`。
- [ ] **TODO(群组列表块)**:`registerJoinTaskTargets` —— 有效链接登记进 `group_link`(需先给 `group_link` 加 `origin`/`membership_state` 列)。
- [ ] **TODO(后端硬化)**:保存 schema 严格校验(≥1 分组/账号、模式参数正整数、间隔 min≤max)。
- [ ] **TODO(前端)**:复制 = 读详情 → 改名加「副本」→ 调 create(无后端端点)。

## 决策日志

- **D1** create/update 共用计划行生成器,`account`(号码)+`account_id` **两列都填**;修 wheel update 路径丢 accountId 的 wart。
- **D2** 前端传 `selectedAccounts:[{accountId,phone}]`,后端直接用,删除 id→号码 lookup 与回退分支;第一刀不做账号活性过滤(前端选号已排除 + 引擎执行复检)。
- **D3** `total` = 实际生成 PENDING 行数(非公式),`pending` 与真实行恒一致;修 wheel 方式二链接不足时 total 虚高。
- **D4** start / group_link 登记 / 严格校验 / 复制端点 → 延后 TODO;第一刀只产 DRAFT。
- 状态存英文枚举码,中文前端转(account-list 6 态先例)。
