# 超链任务六项实现 · 只读代码复核报告

| 项 | 值 |
| --- | --- |
| 日期 | 2026-08-29 |
| 后端 | `itdxzss/armada` @ `review/hyperlink-task-six-items` = `e64fcc52` |
| 前端 | `itdxzss/wheel-saas-pure-web` @ `review/hyperlink-task-six-items` = `aa914ea2` |
| 后端复核范围 | `1.0.3-snapshot(64b1b938)...e64fcc52` — 310 文件 / +27473 −128 |
| 前端复核范围 | `1.0.3-snapshot(e4ada709)...aa914ea2` — 78 文件 / +13047 −6 |
| 竞品事实源 | `hylbuiaxykfrontendsource/readable/assets/task-0vbZUOmq.js`（5984 行，670 个唯一中文 token） |
| 复核方式 | 独立 worktree 只读；**未修改代码、未提交、未合并、未部署** |

---

## 一、Findings

### P0

#### P0-1 H5 发信账号统计导出：作业类型编码不一致，轮询与下载恒 404

- **仓库/位置**
  - `armada/armada-api/src/main/java/com/armada/hyperlink/task/service/HyperlinkAccountStatExportService.java:48`
    `private static final String MODE = "HYPERLINK_ACCOUNT_STATS";`
  - 同文件 `:279` `job.setExportMode(MODE);`
  - 同文件 `:314` VO 却上报 `new HyperlinkTaskExportJobVO(job.getId(), "ACCOUNT_STATS", ...)`
  - `armada/armada-api/src/main/resources/mapper/hyperlink/task/HyperlinkTaskExportMapper.xml:52-58`
    `selectJobByIdForUser ... AND export_mode IN ('RECIPIENTS','ACCOUNT_STATS','ATTRIBUTION','VISIT_TREND')`
  - `armada/armada-api/src/main/java/com/armada/hyperlink/task/service/HyperlinkTaskExportService.java:321`
    `exportMapper.selectJobByIdForUser(id, principal.userId())`
  - 前端 `wheel-saas-pure-web/src/api/hyperlink-task-account-stats.ts:151` 与 `:162`

- **触发路径**
  详情抽屉「发信账号统计」页签 → 点「导出」→
  `POST /api/hyperlink-tasks/{id}/account-stats/export` 返回 202 + jobId →
  前端 `getHyperlinkTaskExportJob(jobId)` 打 `GET /api/hyperlink-task-exports/{jobId}` →
  该口经 `HyperlinkTaskExportService.requireJob` → `selectJobByIdForUser` 的
  `export_mode IN (...)` 白名单**不含 `HYPERLINK_ACCOUNT_STATS`** → 查不到行 →
  抛 `NOT_FOUND "导出作业不存在"`。下载口 `/download` 同理。

- **实际影响**
  H5 账号维度统计导出 **100% 不可用**。作业能创建、Worker 能生成 CSV 落盘，但用户永远拿不到状态也下不到文件；
  文件一直占磁盘直到过期清理。这是六项中「异步导出」的直接功能缺失，不是外部依赖问题。

- **为什么违反契约**
  `docs/superpowers/specs/2026-08-28-hyperlink-task-shared-contract.md` 与
  `V160__hyperlink_export_job_payload.sql` 里冻结的 `export_mode` 词表是
  `COUNTRY_ENTRY/FULL/RECIPIENTS/ACCOUNT_STATS/ATTRIBUTION/VISIT_TREND`，
  没有 `HYPERLINK_ACCOUNT_STATS`。H5 单方面另起了一套编码，还在 VO 层把它翻译回 `ACCOUNT_STATS` 掩盖了分歧。

- **为什么测试没拦住**
  `HyperlinkAccountStatExportServiceTest.java:53` 只断言 VO 的 `exportType()` 等于 `"ACCOUNT_STATS"`（翻译后的值），
  `HyperlinkAccountStatControllerTest` 把 Service 整个 mock 掉。**没有任何用例跨越"创建 → 公共状态口"这条边**。

- **建议修复方向**
  统一为 `ACCOUNT_STATS`：把 `MODE` 改成 `"ACCOUNT_STATS"`，同步改
  `HyperlinkTaskExportJobMapper.xml` 全部 10 处 `export_mode = 'HYPERLINK_ACCOUNT_STATS'`，
  并删掉 `:314` 的翻译。补一条端到端用例：创建 H4/H5/H6 三类作业后，
  逐一用公共 `/api/hyperlink-task-exports/{id}` 读得到。

---

### P1

#### P1-1 Flyway V157 与基线已落地的 V157 撞号，合并即启动失败

- **仓库/位置**
  - 本分支新增 `armada/armada-api/src/main/resources/db/migration/V157__hyperlink_task_lifecycle.sql`
  - 基线 `origin/1.0.3-snapshot` 已存在 `V157__hyperlink_image_asset_library.sql`
    （分叉点 `64b1b938` 时还没有，之后落地）

- **证据**

  ```
  $ git ls-tree --name-only origin/1.0.3-snapshot .../db/migration/ | sed -n '/V15/,$p'
  V156__hyperlink_data_package_full_replication.sql
  V157__hyperlink_image_asset_library.sql      ← 基线已占 157

  $ git ls-tree --name-only 64b1b938 .../db/migration/ | sed -n '/V15/,$p'
  V156__hyperlink_data_package_full_replication.sql   ← 分叉点只到 156
  ```

- **触发路径**：本分支合回 `1.0.3-snapshot` → 同目录出现两个 `V157__` → Flyway 启动即
  `FlywayException: Found more than one migration with version 157` → 应用起不来。

- **额外碰撞面**：在建分支 `feat/contact-marketing` 已占用
  `V157__account_contact_sync` / `V158__contact_friend_task` / `V159__contact_marketing_menu_rbac` /
  `V160__contact_task_engine` / `V161__account_contact_partial_status`，
  与本分支 `V157/V158/V159/V160` **四号全撞**。

- **建议修复方向**：本分支整体上移到未被占用的号段（如 V170~V173），
  同步改 `HyperlinkTaskLifecycleMigrationSqlTest` / `AccountProfileMigrationSqlTest` /
  `HyperlinkTaskListMenuMigrationSqlTest` 里硬编码的迁移文件路径。
  合并顺序需要和 contact 分支一起排。

#### P1-2 营销导出下载口未做类型围栏，可绕过超链导出权限与敏感归因二次校验

- **仓库/位置**
  - `armada/armada-api/src/main/resources/mapper/marketing/MarketingTaskExportMapper.xml:72-77`
    `selectJobByIdForUser` — 只有 `id` + `created_by`，**无 `export_mode` 条件**
  - 对比同文件 `:90-95`（`selectProcessableJobs`）、`:105-110`、`:114-120` 本次**都补了**
    `AND export_mode IN ('COUNTRY_ENTRY','FULL')`
  - `armada/armada-api/src/main/java/com/armada/marketing/export/service/impl/MarketingTaskExportServiceImpl.java:407`
  - `armada/armada-api/src/main/java/com/armada/marketing/export/controller/MarketingTaskExportController.java:30`
    `@PreAuthorize("hasAuthority('tenant:marketing_task:export')")`，`:78` `@GetMapping("/{id}/download")`

- **触发路径**
  用户 U 用超链权限创建了一个 `RECIPIENTS` / `ACCOUNT_STATS` / `ATTRIBUTION` / `VISIT_TREND` 作业（`created_by = U`）。
  之后 U 打 `GET /api/marketing-task-exports/{jobId}/download`。
  该口只校验 `tenant:marketing_task:export`，`selectJobByIdForUser` 没有类型围栏 → 命中同一张
  `marketing_task_export_job` 的超链行 → 直接下发 CSV。

- **实际影响**
  1. 绕过 `tenant:hyperlink_task:export`：只有营销导出权限也能取到超链收信人明细（含收件手机号）。
  2. 绕过 `HyperlinkTaskExportService.getDownload:164-170` 对 `ATTRIBUTION` 的
     `attribution_sensitive` **二次**校验 —— 该二次校验正是为「授权被回收后不能再下载」设计的，走营销口即失效。

  围栏是**单向**的：超链侧 `HyperlinkTaskExportMapper.xml:57` 有 `export_mode IN (...)`，
  营销侧没有。范围限于同一 `created_by`（不跨用户、不跨租户），故定 P1 而非 P0。

- **为什么违反契约**
  `2026-08-28-hyperlink-task-attribution-analysis-design.md:191`：
  "创建和下载都必须同时校验 export + attribution_sensitive；作业中保存权限需求，不能由无敏感权限用户猜 jobId 下载。"

- **建议修复方向**
  给 `MarketingTaskExportMapper.selectJobByIdForUser` 和 `selectActiveJob` 补
  `AND export_mode IN ('COUNTRY_ENTRY','FULL')`；补一条用例断言营销口读不到超链作业。

#### P1-3 复核分支自带的定向测试是红的，与 change 记录声称的验证结论不符

- **实测**（本机 `mvn -B test -Dtest='com.armada.hyperlink.task.**'`）：
  `Tests run: 218, Failures: 2, Errors: 2, Skipped: 4` / `BUILD FAILURE`

  | 用例 | 结果 |
  | --- | --- |
  | `HyperlinkShortLinkMutationGuardTest.startFailsBeforeVersionTransitionOrRoundDispatchIsScheduled` | 期望 `BusinessException`，实得 `NullPointerException` |
  | `HyperlinkTaskCleanupFlowTest.startAfterProvisionFailureResumesOriginalJobInsteadOfSchedulingRound` | `NullPointerException` |
  | `HyperlinkTaskCleanupFlowTest.startAfterQuoteStaleFailureReplacesUncalledBillingQuoteAndResumesOwnedClaim` | `NullPointerException` |
  | `HyperlinkQuoteStaleRecoveryH2Test.quoteStaleRecoveryStartFailsClosedWhenWalletWasCalledAfterQuote` | 期望 `40910`，实得 `40401` |

  这四个测试文件在本次提交里都是**新增**（diff 状态 `A`），即分支自带的用例就没绿。

- **根因（有源码证据）**
  `armada/armada-api/src/main/java/com/armada/hyperlink/task/service/HyperlinkTaskActionService.java:63-68`
  START 分支新增了两道前置门禁，且**排在**既有门禁之前：

  ```java
  if (request.action() == HyperlinkTaskAction.START) {
      capacityService.requireSufficient(task.getConcurrentNum());   // ← :64 新增，最先
      HyperlinkQuoteTokenService.QuoteClaims claims = quoteGuard.forStart(...);
      shortLinkGuard.requireConfigured(task.getShortLinkEnabled());
      HyperlinkMessageDeliveryGuard.requireSupported(store.requireContent(taskId));  // ← 新增
      auditPort.requireAvailable();
  ```

  1. `requireSufficient(int)` 形参是基本类型，`task.getConcurrentNum()` 是 `Integer`，
     为 null 时**自动拆箱 NPE**（3 个用例的直接堆栈都指向 `HyperlinkTaskActionService.java:64`）。
     生产库 `concurrent_num INT NOT NULL DEFAULT 10`（`V157:57`）不会为 null，
     但接口对 null 无防御，且 NPE 会**先于**所有业务门禁抛出，把 40910/50311 之类的稳定码换成 500。
  2. `store.requireContent(taskId)` 也进了 START 主路径；`HyperlinkQuoteStaleRecoveryH2Test:236`
     用 `mock(HyperlinkTaskContentMapper.class)` 构造 store → 返回 null → 抛 `NOT_FOUND(40401)`，
     抢在预期的 `HYPERLINK_TASK_STATE_CONFLICT(40910)` 之前。

- **实际影响**
  - 门禁顺序变了：`HyperlinkShortLinkMutationGuardTest` 断言的"短链未配置时 START 必须在 version 递增和轮次调度**之前**失败"
    这条不变量，现在**没有任何绿的用例在守**。
  - `HyperlinkQuoteStaleRecoveryH2Test` 守的是"报价过期但钱包已被调用 → 必须失败关闭且不重复冻结"——
    这正是用户点名要查的重复扣费防线，现在同样**没在守**。
  - 分支的 change 记录写"6 个定向测试类共 17 项通过，0 failure/error"，与全量 218 项的实测不符。

- **建议修复方向**
  `requireSufficient` 改收 `Integer` 并对 null 显式抛 VALIDATION，或在 `HyperlinkTask` 上做非空断言；
  重新确定 START 门禁顺序并在文档里固化；修 3 个测试夹具（补 `concurrentNum`、给 store 真实 content mapper）；
  把 `mvn test -Dtest='com.armada.hyperlink.task.**'` 全绿作为合并门槛。

#### P1-4 `attribution_sensitive` 权限没有任何迁移创建，H6 深度归因导出无法授权

- **仓库/位置**
  - 消费方：`HyperlinkTaskAnalysisController.java:64`
    `@PreAuthorize("hasAuthority('tenant:hyperlink_task:export') and hasAuthority('tenant:hyperlink_task:attribution_sensitive')")`
  - 消费方：`HyperlinkTaskAnalysisService.java:35`、`HyperlinkTaskExportService.java:162`
  - 前端：`wheel-saas-pure-web/src/views/hyperlink/task/components/AttributionTab.vue:53-58` 用它控制导出按钮显隐
  - 生产方：**无**。`V159__hyperlink_task_list_menu_rbac.sql` 只建
    `view / create / edit / action / export` 五个 perm_key，
    并由 `HyperlinkTaskListMenuMigrationSqlTest.java:32` 显式断言
    `.doesNotContain("tenant:hyperlink_task:attribution_sensitive")`

- **触发路径**
  RBAC 的 perm_key 来源是 `sys_menu` 行；没有对应 `sys_menu` 行的 perm_key
  无法被分配给任何角色 → 任何租户的任何用户都拿不到 `attribution_sensitive` →
  `POST /api/hyperlink-tasks/{id}/click-attribution/export` 恒 403，
  前端按钮因 `hasPerms` 为假而恒隐藏。

- **实际影响**
  - 深度归因的 ip / userAgent 恒被脱敏（**这部分是安全的默认，不算缺陷**）。
  - 但**深度归因导出这项功能在任何环境都跑不起来**。

- **为什么违反竞品**
  竞品该 Tab 有导出，无独立敏感权限门槛：
  `task-0vbZUOmq.js:4075-4081`
  ```js
  await j(n.taskId, { phone: u.value.phone, sender_phone: u.value.sender_phone },
          `${e}_深度归因_${N()}`), window.$message?.success(`导出成功`)
  ```
  H6 在本次六项范围内，这不是"未接入的外部依赖"（D-01~D-09 均未覆盖它），
  是本次范围内代码依赖了一个自己没建的权限。

- **建议修复方向**
  在本分支补一条迁移建 `attribution_sensitive` 按钮权限行（默认不授予任何角色，由管理员显式勾选），
  并同步放开 `HyperlinkTaskListMenuMigrationSqlTest` 的 `doesNotContain` 断言。
  若产品决定"本期不放开深度归因导出"，则应把该结论写进 D 清单并在 UI 上显式说明，而不是留一个永不可达的接口。

---

### P2

#### P2-1 前端同一套契约在三个 API client 里各写一遍，可空性还不一致

- **位置**
  - `src/api/hyperlink-task.ts:32-65` `HyperlinkAccountFilter`（数组字段**非空**）
  - `src/api/hyperlink-task-list.ts:41-67` `HyperlinkAccountFilter`（同名字段 **`| null`**）
  - `HyperlinkTaskCreateContext` 在 `hyperlink-task.ts:105` 与 `hyperlink-task-list.ts:22` 各一份
  - `HyperlinkTaskMutationReceipt` 在三份文件里各一份
  - `quoteHyperlinkTask` / `createHyperlinkTask` / `updateHyperlinkTask` / `getHyperlinkTaskProvisionStatus`
    在 `hyperlink-task.ts` 与 `hyperlink-task-lifecycle.ts` 各一份
- **影响**：后端 `HyperlinkAccountFilterDTO` 改一个字段要人工同步 2~3 处；
  两份 `HyperlinkAccountFilter` 可空性已经不一致，编译期抓不到语义漂移。
- **建议**：抽 `src/api/hyperlink-task-contract.ts` 单一真源，其余文件 re-export。

#### P2-2 共享组件 `PureTableBar` 行为被改，波及 ~24 个既有页面且无测试

- **位置**：`wheel-saas-pure-web/src/components/RePureTableBar/src/bar.tsx`
  - `emits` 增加 `columns-change`
  - `handleCheckAllChange`：取消全选时**保留 fixed 列可见**（原逻辑全部隐藏）
  - `handleCheckColumnListChange`：`if (isFixedColumn(label)) return;` → **fixed 列不可单独隐藏**
  - 复选框 `disabled={isFixedColumn(item)}`
- **影响面**：`grep -rl PureTableBar src/views` 命中 24 个既有页面，其中
  `task/group-marketing`、`task/group-pull-marketing`、`task/pull-task`、`task/join-task`、
  `account/index`、`group/list`、`system/{menu,role,user}`、`buyer/{channel,template}`、`resource/ip*`
  等都带 `fixed` 列，列显隐行为随之改变。
- **本次改动对该文件 0 行测试**（`git diff --stat src/components/` 只有 bar.tsx 13+/3−）。
- **判断**：改动本身方向合理（防止误藏「操作」列），但属于六项范围之外的共享 UI 行为变更。
- **建议**：单独成 PR 或至少补 `bar.tsx` 单测 + 在 change 记录里点名影响页面清单，让回归有据可依。

#### P2-3 深度归因「国家/地区」列后端恒写 null

- **位置**
  - 写入点唯一：`HyperlinkPublicClickService.java` `recordPublicVisit(..., facts.language(), null)`
    —— 最后一个入参就是 `countryIso2`，**写死 null**
  - 列存在：`V157__hyperlink_task_lifecycle.sql:203` `first_visit_country_iso2 CHAR(2)`
  - Mapper 支持：`HyperlinkTaskRecipientMapper.xml:114`
  - 前端列存在：`AttributionTab.vue:38` `{ label: "国家/地区", prop: "countryIso2" }`
- **影响**：该列永远显示 `-`。竞品 `task-0vbZUOmq.js:3983` `title: 国家/地区` 是有值的。
- **说明**：缺的是 GeoIP 解析能力，但 **D-01~D-09 没有任何一条覆盖它**，所以不能算已声明的外部依赖。
- **建议**：要么接 GeoIP（或复用 `country_phone_prefix_mapping` 按收件号码兜底），
  要么把它登记为 D-10 并在列头标注不可用，不要留一个恒空列。

#### P2-4 发送间隔是确定性递增而非随机

- **位置**：`HyperlinkDispatchService.java` 末尾
  ```java
  private long interval(HyperlinkTask task, long recipientId) {
      int range = task.getMsgIntervalMaxMs() - task.getMsgIntervalMinMs();
      return task.getMsgIntervalMinMs() + (range == 0 ? 0 : Math.floorMod(recipientId, range + 1));
  }
  ```
- **影响**：recipient 按 id 顺序领取，间隔就是 `min, min+1, min+2 …` 的锯齿，不是随机分布。
  区间被遵守，但风控上的"模拟真人节奏"意义被抵消。
- **为什么违反竞品**：竞品文案明确是随机 ——
  `会在最小值与最大值之间随机延迟` / `实际发送时会在设定范围内随机等待` / `模拟真人发送节奏`。
- **建议**：换 `ThreadLocalRandom` 或以 `(recipientId * 大质数) % (range+1)` 打散；
  若为可复现性刻意选确定性，请在设计文档里写明理由。

#### P2-5 三套导出 Mapper 职责重叠，是 P0-1 的土壤

- `HyperlinkTaskExportMapper`（RECIPIENTS）
- `HyperlinkTaskExportJobMapper`（HYPERLINK_ACCOUNT_STATS）
- `HyperlinkTaskAnalysisExportMapper`（ATTRIBUTION / VISIT_TREND）

三者操作**同一张** `marketing_task_export_job`，各有一套 `selectByIdForUser` / `claim` / `markSuccess` / `markFailed` /
`selectExpired`，方法名和 `export_mode` 词表各不相同。
change 记录说"三个业务各自保留独立生成 Service/Scheduler，避免同名类覆盖"——
Service/Scheduler 分开是合理的，但**数据访问层不该分家**，分家的直接后果就是 P0-1。
**建议**：合并为一个 `HyperlinkTaskExportJobMapper`，`export_mode` 作参数传入，只保留一份状态机 SQL。

#### P2-6 「最后核对」弹框缺单价算式

- **位置**：`HyperlinkTaskFinalReview.vue:96-183`
- 现有：余额→预计冻结、超级模式 tag、任务名、受众数据包（· 未使用 N 条）、消息类型、匹配账号、推广链接、深度追踪，
  以及 `pricingBreakdown.length > 1` 时的分国家单价表。
- **缺**：单国家数据包（最常见）时用户看不到任何单价。
  竞品固定展示算式（`task-0vbZUOmq.js:2915` 附近）：
  `数据包号码数量 <b>N</b> × 超链单价 <b>x USDT/条</b>`，未就绪时显示`暂无法估算（数据包或单价未就绪）`。
- **建议**：无条件展示 `recipientCount × unitPrice`（`quote.unitPrice` 或 `createContext.referenceUnitPrice`），
  多国家时再叠加明细表。

---

### P3

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| P3-1 | principal 空判断前后不一致 | `HyperlinkTaskAnalysisController.java:44` 判了 `principal != null`，`:47` 又无条件 `principal.tenantId()` | `@PreAuthorize` 保证非空，但同一表达式内两种口径，读起来像有漏洞 |
| P3-2 | 分析导出下载缺 content-type 守卫 | `src/api/hyperlink-task-analysis.ts:194-216` | `hyperlink-task-detail.ts:223-232` 和 `hyperlink-task-account-stats.ts:172-187` 都会在拿到 JSON 错误体时抛错，分析这份不会，会把错误 JSON 存成 `.csv` |
| P3-3 | 分析导出没用 V160 新增列 | `HyperlinkTaskAnalysisExportService.java:126` `job.setCountryIso2sJson(requestJson)` | V160 专门加了 `request_payload_json`，收信人导出用了，分析导出把请求塞进了 `country_iso2s_json` |
| P3-4 | 收信人列表深翻页成本 | `HyperlinkTaskDetailMapper.xml:133-149` | 列表用 `LIMIT/OFFSET` + 每页 `COUNT(*)`；10 万 recipient 时末页 offset ≈ 10 万，且每页都全量 count。导出侧 `:151-165` 已用 keyset，列表侧可对齐或缓存 total |
| P3-5 | 成功回执也会写 failCode | `HyperlinkProtocolResultService.handleSendResultReported` | 成功分支同样执行 `setFailCode(event.reasonCode())`；协议若在成功事件里带 reasonCode 会污染流水 |

---

## 二、依赖闭环问题（与代码缺陷分开列）

D-01~D-09 均**确认为已声明的外部依赖**，且失败关闭**经源码核验有效**：

| 编号 | 失败关闭是否真的有效 | 核验证据 |
| --- | --- | --- |
| D-01 系统业务组 | ✅ | `create-context` 返回空 `defaultAccountGroupIds`，前端据此禁用启用 |
| D-02 账号画像事件 | ✅ | `account_profile` 全部画像列 `DEFAULT NULL` + `CHECK (... IS NULL OR ...)`（`V158`），未采集保持 NULL 不猜测 |
| D-03 策略候选接口 | ✅ | `useHyperlinkTaskEditor.ts:345-346` 走 `resourceErrors['策略']` 错误态，无假候选 |
| D-04 图片素材接口 | ✅ | `HyperlinkAssetPicker.vue:59/92/100` 全部走真实报错，不退化为外链 |
| D-05 钱包适配器 | ✅ | `UnavailableHyperlinkWalletPort` + `HyperlinkBillingSagaService` 全链路 `billingUnavailable(50310)` |
| D-06 审计系统 | ✅ | `UnavailableHyperlinkTaskAuditPort`；`auditPort.requireAvailable()` 在 action / billing / export 三处前置 |
| D-07 公网短链基址 | ✅ | `HyperlinkShortLinkGuard.requireConfigured` 在 START 校验 |
| D-08 私聊协议能力 | ✅ **重点核验通过** | `ConfiguredHyperlinkPrivateCapabilityPort` 默认空集 → `HyperlinkAccountCandidateSelector.protocolCount():68-71` 返回 0 → `HyperlinkProtocolCapacityService.requireSufficient` 因 `concurrent_num > 0`（V157 CHECK）必然抛 `42211`。**START 会被拦住，不会出现"已冻结钱包 + 永远不发送"的静默僵死** |
| D-09 真实环境联调 | ✅ 保持待办 | 本轮同样未连真 MySQL / Redis / 钱包 / 协议节点 |

已冻结的产品决策（**不是缺陷**，本轮不重复计入）：
- 一任务一收信人一行，不建 `recipient_round` / `delivery_attempt` / 独立封号表。
- 不建 30 分钟桶表；访问趋势直接由 `hyperlink_task_recipient` 聚合。
- 不建 `hyperlink_click` 表；首触环境存 recipient，90 天后脱敏清理。
- 历史双图文只读兼容，新建不可选。
- 任务无删除 API。

---

## 三、正向核验结论（做对了的部分，供验收时不必重复查）

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 竞品列表列 | **15 列 1:1 全覆盖** | 竞品 `id/name/data_package_id/account_filter/country_iso2/task_status/account_stats/progress/delivered_num/click_uv_num/actual_concurrency/execution_duration_sec/task_planned_end_at/created_at/operate` ↔ `HyperlinkTaskTable.vue:136-326` 同序同名 |
| 竞品列表筛选 | **全覆盖** | 任务名/状态(5)/任务类型(3)/目标国家/创建时间 ↔ `HyperlinkTaskSearchCard.vue:16-104` |
| 竞品行操作 | **全覆盖** | 竞品 `start/pause/resume/stop/编辑|查看/详情/复制` + 三条二次确认文案 ↔ `list-display.ts:116-124` + `useHyperlinkTaskPage.ts:187-197` |
| 竞品详情 5 页签 | **全覆盖** | `recipients/accounts/clicks/visit-trend/ban-stats` ↔ `HyperlinkTaskDetailDrawer.vue` |
| 竞品收信人筛选 | **全覆盖** | 号码模糊/收信国家/发信国家/失败原因 ↔ `HyperlinkRecipientQuery` |
| 页面是否有占位 | **无** | 全目录 grep `TODO/FIXME/待接入/占位/暂未/未实现` 零命中；5 个页签均挂真实组件 |
| `.vue` 600 行红线 | **无超标** | 最大 `HyperlinkAccountFilterDrawer.vue` 583 行 |
| START 四道关 | **齐全** | 服务端报价（`quoteHyperlinkTask purpose=START`）→ 7 秒倒计时（`HyperlinkTaskStartReviewDialog.vue:22/41-48/200`）→ 版本号（`actOnHyperlinkTask{version}` + `store.incrementVersion` 乐观锁）→ PROCESSING 轮询（`provisionStatus` + `pollAfterMs`） |
| 租户隔离 | **fail-closed** | `MyBatisConfig.java:23` 无上下文回退哨兵 `-1`；忽略表只有 `tenant/country/country_phone_prefix_mapping`；调度器均显式 `TenantContext.set(candidate.tenantId())` 后 `restore` |
| SQL 注入 | **无** | `grep -rn '\${' mapper/hyperlink/` 零命中；排序字段 Java 白名单 + XML `<choose>` 双重收敛 |
| 重复发送 | **有防线** | `uq_hyperlink_recipient(tenant,task,phone)` + `uq_hyperlink_recipient_command` + `assignCommand` 要求 `updated==1` + 确定性 `commandId` |
| 未知结果恢复 | **不猜结果** | `HyperlinkUnknownResultRecoveryService` 只 `recoveryPort.replay(同一 commandId)`；Android 超 29 天安全窗保持 SENDING 不猜失败 |
| 计费幂等 | **先落幂等键再调外部** | `markPendingSettlement/markPendingRelease` 先写 `operation_idempotency_key`，未知结果始终重放同键；`uq_hyperlink_billing_task` 任务 1:1 |
| 访问趋势 PV | **未伪装** | `HyperlinkTaskAnalysisService.visitTrend` 每个 bucket 的 `pv` 恒为 `null`，`pvBucketMode` 恒 `UNAVAILABLE_CUMULATIVE_ONLY`，累计 PV 只出现在 `summary.pvTotal` |
| 归因脱敏 | **正确** | 无敏感权限时 `ip`/`userAgent` 置 null 且回 `maskedFields`；有权限时写审计 |
| 90 天清理 | **有** | `HyperlinkAttributionRetentionScheduler` + `idx_hyperlink_recipient_attribution_retention` + `attribution_purged_at` |
| 公网短链 | **可达且安全** | `SecurityConfig.java:31` `/api/public/**` permitAll；`HyperlinkPublicClickService` 先按全局唯一 `short_code` 定位再 `TenantContext.set(recipient.getTenantId())` |
| 10 张任务表 | **齐** | `V157` 建 `hyperlink_task/_content/_runtime/_recipient/_round/_round_account/_account_usage/_account_stat/_recipient_claim` + `hyperlink_billing_reservation`；唯一约束与查询索引齐备 |
| 营销导出被误伤 | **Worker 侧已围栏** | `MarketingTaskExportMapper.xml` 的 `selectProcessableJobs`/`markExhausted`/`selectExpiredFiles` 均补 `export_mode IN ('COUNTRY_ENTRY','FULL')`（下载口未补 → 见 P1-2） |
| 协议 GROUP 路径 | **向后兼容** | `WebMessageSendBackend`/`AndroidMessageSendBackend` 新增 `jid/targetKind/hyperlink*`，GROUP 时 `groupJid` 仍原值 |

---

## 四、已执行的验证及结果

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| 后端主源码 + 测试源码编译 | `mvn -B -q -DskipTests test-compile` | ✅ **通过**（exit 0） |
| 后端超链定向测试 | `mvn -B test -Dtest='com.armada.hyperlink.task.**'` | ❌ **失败**：`Tests run: 218, Failures: 2, Errors: 2, Skipped: 4`，见 P1-3 |
| 前端 tsc | `npx tsc --noEmit` | ✅ 通过（exit 0） |
| 前端 vue-tsc | `npx vue-tsc --noEmit --skipLibCheck` | ✅ 通过（exit 0） |
| 前端 ESLint | `npx eslint --max-warnings 0 src/views/hyperlink/task/** src/api/hyperlink-task*.ts` | ✅ 通过（0 输出，exit 0） |
| 前端 Prettier | `npx prettier --check ...`（只读，未用 `--write`） | ✅ `All matched files use Prettier code style!` |
| 前端 Stylelint | `npx stylelint "src/views/hyperlink/task/**/*.vue"`（只读，未用 `--fix`） | ✅ 通过（exit 0） |
| 前端超链定向测试 | `node --import tsx --test "src/views/hyperlink/task/**/*.test.ts" "src/api/hyperlink-task*.test.ts"` | ⚠️ `tests 71 / pass 65 / fail 6` |
| 前端 domain 层单跑 | `node --import tsx --test "src/views/hyperlink/task/domain/*.test.ts"` | ✅ `tests 35 / pass 35 / fail 0` |

### 前端 6 项失败的定性：**测试环境阻断，非业务断言失败**

6 个失败全部是同一个报错，落在 import 阶段，未进入任何断言：

```
TypeError [ERR_UNKNOWN_FILE_EXTENSION]: Unknown file extension ".css" for
  node_modules/.pnpm/nprogress@0.2.0/node_modules/nprogress/nprogress.css
code: 'ERR_UNKNOWN_FILE_EXTENSION'
```

受影响文件：`hyperlink-task{,-list,-detail,-lifecycle,-account-stats}.test.ts`、
`composables/useAccountStatQuery.test.ts` —— 都是经 `@/utils/http` 间接引入 `nprogress` 的。
这与仓库 change 记录里记录的既有问题一致，**不计入代码缺陷**。

> 注意：这条环境阻断的副作用是——**P0-1 那条"创建 → 公共状态口"的跨边契约，前端侧也恰好落在跑不起来的 6 个文件里**。
> 修 P0-1 时应一并解决 nprogress 的 loader 配置，否则这条链路仍然无人守。

### 未执行

- 未连接真实 MySQL / Redis / 钱包 / 协议节点 / 远程接口（D-09 保持待办）。
- 未跑 Playwright E2E。
- 未跑后端全量 `mvn test`（只跑了 `com.armada.hyperlink.task.**` 定向范围）。

---

## 五、是否适合进入人工验收

**不适合。** 需先关闭以下四项：

1. **P0-1** — H5 账号统计导出全链路不可用，六项中的一项功能是死的。
2. **P1-1** — Flyway V157 撞号，合并即启动失败；V158/V159/V160 还与 `feat/contact-marketing` 撞。
3. **P1-3** — 分支自带定向测试红，且红掉的两条正好是"START 门禁顺序"和"重复扣费失败关闭"这两条关键不变量，
   目前无人守。change 记录的验证结论需要按实测订正。
4. **P1-2** — 营销导出下载口可绕过超链导出权限和敏感归因二次校验。

P1-4（`attribution_sensitive` 无处授予）需要产品先明确：本期是否放开深度归因导出。
若放开 → 补迁移；若不放开 → 登记进 D 清单并在 UI 说明，不要留永不可达的接口。

P2/P3 可以进验收后再排期，但 **P2-2（共享 `PureTableBar` 行为变更）建议在验收时点名回归**
营销任务、拉群任务、账号列表、系统管理这几个页面的列显隐。

正面判断：竞品字段、列、筛选、弹框、页签、行操作和状态的**还原度很高，本轮没有发现竞品有而这边整块缺失的功能**；
租户隔离、SQL 注入、重复发送、未知结果恢复、计费幂等、PV 不伪装、归因脱敏、D-08 失败关闭这些高风险点**核验均通过**。
问题集中在"跨模块拼接处"——三套导出 Mapper 的词表分歧、Flyway 号段、共享导出表的单向围栏、START 新门禁的插入位置。

---

*本报告为只读复核产物。复核期间未修改、未提交、未合并、未部署任何代码。*
