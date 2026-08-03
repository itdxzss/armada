# 普通群链接任务创建链路设计

日期：2026-08-03
状态：待实现
对应 PRD 拆分任务：BE-01、BE-04、BE-05、BE-06
前置切片：`2026-08-02-pull-task-normal-link-data-model-design.md`（数据层，已完成）

## 1. 范围

本切片只做 armada 后端的普通群链接任务创建链路：结构化创建合同与参数校验、群链接逐行解析与真实预检、TXT 料子解析、链接—TXT 随机不放回匹配与草稿冻结、`DRAFT → WAIT_START` 提交。

不在本切片：

- 前端创建页（FE-01/02/03，独立切片，在 `wheel-saas-pure-web/`）。
- 执行器、调度、启动校验（EX-\*，下一切片）。
- 从群组列表分组选择群链接（PRD 明确为后续阶段）。
- 群资料/权限设置、归档分组（PRD 明确为第二阶段）。

### 1.1 前置事实

数据层已完成并合并，本切片直接使用：

- `V090` 迁移：`pull_task_standard_setting`、`pull_task_group_execution`、`pull_task_material_member`、`pull_task_group_account`、`pull_task_account_action`、`pull_task_pull_call` 六张表，以及 `pull_task` 的 `status='DRAFT'`、`primary_stage`、`version` 三列。
- 已可用的 Mapper 方法：`PullTaskGroupExecutionMapper.insertDraft` / `deleteDraftByTaskId` / `freezeDraftRows`、`PullTaskMaterialMemberMapper.batchInsert`、`PullTaskStandardSettingMapper.insert`、`PullTaskMapper.updateStatusWithVersion`。
- H2 测试基座：`PullTaskNormalLinkSchema` + `PullTaskNormalLinkH2Support`。
- ADR-0007：用草稿任务承载创建页预览计划，不建独立预览计划表。
- ADR-0008：拉手跨任务互斥由部分唯一索引承担。

### 1.2 数据层约束对本切片的硬性影响

`pull_task_group_execution` 的链接侧与文件侧列均为 `NOT NULL`：

```sql
normalized_link   VARCHAR(255) NOT NULL
invite_code       VARCHAR(64)  NOT NULL
source_link_line_no INT        NOT NULL
source_file_index INT          NOT NULL
source_file_name  VARCHAR(255) NOT NULL
```

`pull_task_material_member.group_execution_id` 同样 `NOT NULL`。

因此一条执行行必须链接与 TXT 同时齐全，**草稿期不存在"半成品行"，剩余未匹配资源在库内无处存放**。本设计的接口形态由这条约束直接推导得出，见第 2 节。

## 2. 已确认的设计决定

以下六条经与需求方逐条确认，是本设计不可回退的前提。

| # | 决定 | 理由 |
|---|---|---|
| D1 | 有状态草稿 + 增量接口，而非全量重建 | 契合 ADR-0007 对"清除全部 = 删草稿执行行"的定义；TXT 只传一次；单行移除不扰动其他配对 |
| D2 | 补传资源时增量追加，已有执行行不变 | 用户右侧看到的顺序稳定，不因多传一个文件全部洗牌；仍满足不放回一对一与 `min(有效链接数, 有效TXT数)` |
| D3 | 链接预检三态，只拒确定失效 | 抓取超时/网络错误标为"检测未完成"仍进池，启动时兜底校验；避免把自身网络问题当成用户链接失效 |
| D4 | 单行移除 = 整行丢弃，链接与 TXT 不回池 | 语义等于"这条不要了"，与"清除全部"同构；不引入未匹配池的回流状态 |
| D5 | 剩余未匹配 TXT 不落库，当场拒绝并由前端重发 | 见 1.2 的 `NOT NULL` 约束；后端零额外状态，不动数据层 |
| D6 | 新增独立路径 `/api/pull-tasks/standard/**`，旧 `POST /api/pull-tasks` 暂留 | 前端 `src/api/pull-task.ts:493` 仍在调旧接口；后端切片可独立合并上线，等 FE 切片完成后单独一个 PR 删旧接口 |

D5 的连带结果：**链接文本不落库**。服务端每次用「本次请求携带的全量链接文本」减去「已成行链接」得到剩余链接池。链接是廉价文本，前端每次全量重传无成本；TXT 是文件，不能每次重传，所以 TXT 走"上传即配对，配不上当场拒绝"。

## 3. 接口契约

全部端点在 `/api/pull-tasks/standard` 下，与旧 `POST /api/pull-tasks` 完全隔离。权限沿用 `tenant:pull_task:view`（类级）与 `tenant:pull_task:create`（写操作）。

### 3.1 `POST /standard/draft/plan` — 草稿增量（multipart）

创建页唯一的写资源入口。

请求：

| 字段 | 类型 | 说明 |
|---|---|---|
| `linksText` | `String` | **当前链接框全量文本**，每次请求都必须携带；允许为空 |
| `files` | `MultipartFile[]` | 本次新增的 `.txt`；允许为空 |

服务端处理顺序：

1. 解析 `linksText` 逐行 → 归一化 → 批内去重
2. 三态预检（事务外，见 5.2）
3. 查链接占用
4. 解析本次上传的每个 TXT
5. 剩余链接池 = 有效链接 − 已成行链接（按归一化链接比对）
6. 剩余链接池与本次有效 TXT 做不放回一对一随机
7. 单事务写入追加的执行行与料子成员

响应 `PullTaskStandardDraftVO`：

```
draftTaskId        草稿任务 ID
version            草稿任务乐观锁版本，提交时回传
rows[]             已成行的执行行（seq / 归一化链接 / 原始行号 / 文件名 / 有效料子数 / 非法行数 / 重复行数）
linkLines[]        链接逐行结果（行号 / 原文脱敏 / 状态 / 原因）
fileResults[]      逐文件结果（文件名 / 是否入池 / 有效数 / 非法行明细）
matchedCount       已匹配执行行总数
remainingLinkCount 剩余未匹配有效链接数
ignoredFileCount   本次因剩余链接不足被忽略的文件数
```

`files` 为空时只回链接解析与预检结果，不建执行行。这是用户"只粘贴链接、还没传文件"阶段的正常调用。

### 3.2 `GET /standard/draft` — 回读草稿

按 `tenant_id + created_by` 取该用户的 `DRAFT` 任务，返回与 3.1 相同的 `PullTaskStandardDraftVO`（`linkLines` 为空，因为链接文本不落库）。

**已知取舍**：刷新页面后只能恢复已成行的执行行，未成行的链接需要用户重新粘贴。前端应把链接框内容存 `sessionStorage` 缓解。这是 D5"链接不落库"的必然结果。

### 3.3 `DELETE /standard/draft/rows/{rowId}` — 单行移除

删除该执行行及其料子成员。整行丢弃，链接与 TXT 均不回池（D4）。后续 `seq` 不重排，保持稀疏。

### 3.4 `DELETE /standard/draft` — 清除全部

删除该草稿的全部执行行与料子成员。草稿任务行本身保留以供复用。

### 3.5 `POST /standard` — 提交冻结（JSON）

请求 `PullTaskStandardCreateDTO`：草稿标识 + 全部配置字段，见第 4 节。

响应：创建完成的任务行（`id` / `taskName` / `status='WAIT_START'` / `groupCount` / `expectedPullCount`）。

## 4. 创建配置合同（BE-01）

全仓没有引入 Bean Validation（`jakarta.validation` 零使用），校验一律在 Service 手写并抛 `BusinessException(ErrorCode.VALIDATION, ...)`。本切片沿用该风格，不新引入校验框架。

### 4.1 字段

| 字段 | 类型 | 约束 | 落库 |
|---|---|---|---|
| `draftTaskId` | `Long` | 必填 | — |
| `version` | `Integer` | 必填，草稿乐观锁版本 | — |
| `taskName` | `String` | 必填，1–128 字符 | `pull_task.task_name` |
| `remark` | `String` | 可空，≤512 字符 | `pull_task.remark` |
| `autoStart` | `Integer` | 必填，0/1 | `autoStart` |
| `materialAdminTiming` | `Integer` | 必填，1=入群后立即 2=本群料子全部终态后 | `material_admin_timing` |
| `pullCountMin` | `Integer` | 必填，≥1 | `pull_count_min` |
| `pullCountMax` | `Integer` | 必填，≥`pullCountMin` | `pull_count_max` |
| `pullIntervalSeconds` | `Integer` | 必填，≥0 | `pull_interval_seconds` |
| `pullerCountPerGroup` | `Integer` | 必填，≥1 | `puller_count_per_group` |
| `stationCountPerCall` | `Integer` | 必填，≥0 | `station_count_per_call` |
| `concurrentGroupCount` | `Integer` | 必填，≥1 | `concurrent_group_count` |
| `pullerRiskMinutes` | `Integer` | 必填，≥0；0=不建立定时恢复 | `puller_risk_minutes` |
| `managerGroupId` | `Long` | 必填，须属本租户 | `manager_group_id` + 名称快照 |
| `pullerGroupId` | `Long` | 必填，须属本租户 | `puller_group_id` + 名称快照 |
| `stationGroupId` | `Long` | 必填，须属本租户 | `station_group_id` + 名称快照 |

`requiredManagerCount` 不由创建接口填写，写 0；PRD 第 37 条要求在任务启动时按管理分组可用账号数冻结，由 `PullTaskStandardSettingMapper.freezeRequiredManagerCount` 在下一切片完成。

### 4.2 明确不接收的字段

请求体出现以下字段时直接以 `VALIDATION` 拒绝，不静默忽略——静默忽略会让前端误以为配置生效：

审核模式、完成后开审核、建立空白群、次管理、两个前期拉人字段、退群方式、创群成功账号移至分组、营销分组、拉手同步料子方式（第一阶段固定"单个"）、群资料与权限设置全部字段、归档分组。

### 4.3 分组校验

三个分组 ID 均需校验存在且 `tenant_id` 匹配，并把分组名称写入快照列。分组不存在或跨租户时回 `VALIDATION`，错误信息不泄露其他租户的分组是否存在。

## 5. 解析与校验合同

### 5.1 链接侧（BE-04）

逐行状态，一行只落一个终态：

| 状态 | 触发条件 | 进匹配池 |
|---|---|---|
| `VALID` | 格式合法且公开页识别出群名或真实头像 | 是 |
| `INVALID_FORMAT` | 未提取到 22 位邀请码，或长度不足 | 否 |
| `DUPLICATE` | 批内归一化后重复，保留首次出现 | 否 |
| `LINK_EXPIRED` | 公开页可访问但只有 WhatsApp 默认资料 | 否 |
| `PROBE_INCOMPLETE` | 抓取超时或网络错误 | 是（标黄） |
| `OCCUPIED` | 已被本租户其他运行中任务占用 | 否 |

- 空行忽略，不计入有效、非法或任何计数。
- 每行携带**原始物理行号**。
- 归一化复用 `GroupLinkUrls.normalizeImportLine`：一行运营文本中提取第一个 `chat.whatsapp.com/<22位邀请码>`，允许 `http/https`、行首序号、说明文字、查询串与尾部标点；结果为 `chat.whatsapp.com/<邀请码>`，host 小写、邀请码原样保留大小写、去 scheme 与查询串与尾斜杠。
- 批内去重复用 `LineImporter` 的逐行语义。
- 同一批内相同归一化链接只抓一次公开页，重复行复用首次结果但保留自己的行号。

`PROBE_INCOMPLETE` 仍进池是 D3 的直接结果。启动时会重新校验冻结执行行，链接届时失效则该行记 `LINK_INVALID` 终态、绑定 TXT 不重新分配、其他行继续——这条兜底已在 PRD 中确认，是本设计允许"检测未完成"进池的依据。

**不复用 `GroupLinkPrecheckServiceImpl`**：它是二态口径（非 `AVAILABLE` 即不可用，抓取异常与无群资料合并），且服务于历史群导入弹窗。改动它会改变既有业务行为。本切片新建判定层，只复用底层的 `GroupInvitePageFetcher` 端口。

#### 并发与规模

`HttpGroupInvitePageFetcher` 为 2s 连接超时 / 3s 请求超时，现有调用方为纯串行。本切片改为有界并发抓取。

| 参数 | 取值 | 依据 |
|---|---|---|
| 单次有效链接上限 | 200 | 与并发数共同决定最坏等待时长 |
| 抓取并发 | 16 | 最坏 `200/16 × 3s ≈ 38s`；再高对 `chat.whatsapp.com` 有被限流风险 |

**待确认假设**：本设计假定实际运营一次粘贴的链接量在**几十条量级**。若实际经常达到上千条，200 上限与同步等待模型不成立，需改为后台异步检测 + 前端轮询进度，接口形态随之变化。此假设需在实现前向需求方确认。

### 5.2 事务边界

链接预检是外部 HTTP 调用，最坏耗时约 40 秒。**预检必须在事务外完成**，拿到全部判定结果后才开启事务写入执行行与料子成员。否则数据库连接会被外部网络阻塞占用，在并发创建时拖垮连接池。

这是本设计的硬性约束，不是优化建议。

### 5.3 TXT 侧（BE-05）

**新建 `PullTaskMaterialTxtParser`，不复用 `HistoricalGroupMaterialParser`。** 后者三处与新合同冲突：

1. 把末尾 `A/a` 解释为"营销账号"，本合同解释为"需设群管理员"；
2. 返回时把营销号重排到列表最前，本合同要求保留首次出现顺序；
3. 只回聚合统计（非法数、重复数），本合同要求逐行错误明细（文件名 + 原始行号 + 原因）。

**也不能复用 `FileLinesExtractor`**：它的 `parseTxt` 在读行时就丢掉空行（`if (!l.isBlank())`），物理行号随之丢失，无法满足"首次有效出现的原始行号"与"非法行回原始行号"。

改为：把上传文件按 UTF-8 读成字符串，交给 `com.armada.shared.util.LineImporter.run`。该工具按 `\R` 切行、`lineNo = i + 1` 保真物理行号、空行 `continue` 不计数、并内建批内去重——与本合同逐条吻合，链接侧与 TXT 侧共用同一套逐行语义。

`LineImporter` 的批内去重把后续重复行标为 `DUPLICATE` 且 `record` 非空，因此 `A/a` 提升规则实现为一次后处理：遍历产出，凡 `DUPLICATE` 且其 `record.adminRequired` 为真，就把该号码首次出现的唯一记录提升为 `adminRequired`。

#### 号码清洗管线（顺序固定）

1. 识别并剥离末尾 `A` 或 `a` → `adminRequired = true`
2. 移除 `+`、空格、`-`、`(`、`)` 等常见展示字符
3. 结果必须为 **7–15 位纯数字**，否则该行非法
4. **显式拒绝 `@s.whatsapp.net` 等完整用户 JID**
5. 不根据任务国家推断或补齐国家码，要求上传方提供完整国际国家码

第 4 条是不能走 `WhatsappJids.userJid` 的原因——该工具接受 JID 形式，与本合同相反。新解析器自行实现严格清洗。

#### 其余规则

- 空行忽略，不计入有效、非法或计划人数。
- 单文件内按归一化号码去重，唯一记录保留**首次有效出现的原始行号与顺序**（写入 `source_line_no` 与 `member_seq`）。
- 同号任一重复行带 `A/a` 时，最终唯一记录提升为 `adminRequired = true`。
- 跨文件不做全局去重；同一号码允许随不同 TXT 进入不同群链接。
- **零有效号码的文件拒绝进池**并提示修正。
- 非法行回「文件名 + 原始行号 + 失败原因」；原文脱敏后展示，只用于错误定位与审计。

#### 文件约束

| 约束 | 取值 |
|---|---|
| 扩展名 | 仅 `.txt`；按扩展名 + 内容嗅探双重拒绝 |
| 单次上传文件数 | ≤ 50 |
| 单文件大小 | ≤ 2 MB |
| 单文件行数 | ≤ 20000 |

### 5.4 匹配（BE-06）

在剩余链接池与本次有效 TXT 之间做不放回一对一随机匹配：

- 本次新增行数 = `min(剩余链接数, 本次有效TXT数)`。
- 追加到执行行尾部，`seq` 从当前最大值连续递增（D2）。
- 已有执行行不参与重排、不重新随机。
- 多出的 TXT 当场拒绝，计入 `ignoredFileCount`；前端保留 `File` 对象，用户补粘链接后自动重发（D5）。
- 多出的链接留在剩余池，计入 `remainingLinkCount`。

随机源用 `ThreadLocalRandom` 即可。ADR-0005 要求的是"配对由服务端生成、禁止信任客户端配对"，服务端生成本身已满足；这不是安全边界，不需要 `SecureRandom`。

## 6. 数据写入与事务

### 6.1 草稿建立与复用

每个用户同一时刻只保留一条 `STANDARD` 草稿（ADR-0007）。首次调 `POST /standard/draft/plan` 时按 `tenant_id + created_by` 查，查不到才插入：`task_type='STANDARD'`、`status='DRAFT'`、`mode='NORMAL_LINK'`、`config_json='{}'`、`group_count=0`、`expected_pull_count=0`。

`task_type` 决定状态机与统计口径，必须显式写 `STANDARD`（列有默认值，但不依赖默认值）。

`mode` 取新值 `NORMAL_LINK`。该列是 `VARCHAR(32)`，加值不需要迁移；全仓只有旧 `PullTaskController` 第 62、66 行读它，且只用于旧接口自身的入参校验，不影响新链路。V078 的列注释（`OLD_LINK老群链接 CREATE_NEW自建群`）因此过时，在本切片的 change 记录中说明，不单独发迁移改注释。

**并发处理**：同一用户双击或多标签页可能插出两条草稿。当前 `pull_task` 没有对应唯一索引，本设计**不新增迁移**，改为「取最新一条草稿」容忍重复。理由是遗留草稿的上限是每用户常量级，ADR-0007 已明确接受草稿残留，为此加一条迁移不划算。查询方法命名为 `selectLatestDraftByCreator`，语义在方法名上显式表达。

### 6.2 增量追加

单次 `plan` 请求的写入在一个事务内完成：批量 `insertDraft` 执行行 → 取回自增 ID → `batchInsert` 料子成员。预检已在事务外完成（5.2）。

### 6.3 提交冻结

单事务，顺序固定：

1. 按 `draftTaskId + tenantId + createdBy` 取草稿，校验 `status='DRAFT'` 且至少存在一条执行行；无执行行时回 `VALIDATION`（"至少需要一条群链接与 TXT 的匹配"）
2. 插入 `pull_task_standard_setting`（`required_manager_count` 写 0）
3. 逐行复用或插入 `group_link`，回填 `group_link_id`
4. `freezeDraftRows` 把 `execution_status` 由 `0` 改为 `1` —— 此刻生成列 `link_occupancy_key` 生效，跨任务占用开始
5. `submitDraft` 在一条带守卫的 `UPDATE` 中完成 `DRAFT → WAIT_START`，同时写入 `task_name`、`remark`、`config_json`、`group_count`（执行行数）与 `expected_pull_count`（全部执行行的 `valid_member_count` 之和）

**不复用 `updateStatusWithVersion`**：该方法只写 `status` / `version` / `started_at` / `finished_at`，不写计数列与任务名。拆成两条 UPDATE 会让"状态已推进但计数未写"成为可观测的中间态，且第二条没有乐观锁保护。因此新增 `PullTaskMapper.submitDraft`，守卫条件为 `status='DRAFT' AND version=#{expectedVersion} AND deleted_at IS NULL`，一条语句原子完成。

任务名与备注在草稿期不落库（用户可能随时改），只在提交时写入，与配置字段同批。

不重新随机，落库计划与用户在创建页看到的完全一致。

### 6.4 占用冲突处理

第 4 步触发 `uq_pull_task_execution_link_occupancy` 唯一键冲突时：**整单回滚**，回 `CONFLICT`「链接 X 已被其他任务占用，请移除该行后重试」。

不采用"跳过冲突行、其余继续"的方案。PRD 硬性要求"最终创建时持久化用户右侧看到的匹配计划"，偷偷少落一行违反这条，且用户无法察觉自己少了一个群。

### 6.5 幂等

重复提交由第 5 步的状态前置条件 + 乐观锁版本挡住：`submitDraft` 影响行数为 0 说明已提交过，此时不报错、不建第二个任务，直接回既有任务行。这满足 ADR-0007"幂等由状态前置校验承担，不需要 plan_token"的设计。

### 6.6 审计

写操作按现有约定记录租户、操作者、`requestId`、动作前后状态与非敏感失败原因。链接与号码在日志中脱敏。

## 7. 文件组织

新增文件落在 `com/armada/task/` 下，与现有 `PullTaskGroupMarketing*` 同构。

```
controller/
  PullTaskStandardController.java          5 个端点
model/dto/
  PullTaskStandardDraftPlanRequest.java    multipart 绑定
  PullTaskStandardCreateDTO.java           提交配置（record）
model/vo/
  PullTaskStandardDraftVO.java             草稿视图
  PullTaskStandardExecutionRowVO.java      执行行
  PullTaskStandardLinkLineVO.java          链接逐行结果
  PullTaskStandardFileResultVO.java        逐文件结果
  PullTaskStandardMaterialLineErrorVO.java 非法行明细
model/enums/
  PullTaskStandardLinkLineStatus.java      六态
service/
  PullTaskMaterialTxtParser.java           纯函数
  PullTaskLinkMatcher.java                 纯函数
  PullTaskLinkProbeService.java            六态判定 + 有界并发
  PullTaskStandardDraftService.java        + impl/
  PullTaskStandardCreateService.java       + impl/
```

把解析与匹配做成**无 Spring 依赖的纯函数**是刻意的：这两块逻辑分支最密、最需要密集测试，不应被 Spring 上下文与数据库拖慢反馈循环。

### 7.1 Mapper 新增方法

| Mapper | 方法 | 用途 |
|---|---|---|
| `PullTaskMapper` | `insertDraft(PullTask)` | 建草稿任务行 |
| `PullTaskMapper` | `selectLatestDraftByCreator(tenantId, createdBy)` | 复用草稿 |
| `PullTaskMapper` | `submitDraft(...)` | `DRAFT → WAIT_START` 并写任务名、备注、配置与计数，带状态守卫与乐观锁 |
| `PullTaskGroupExecutionMapper` | `deleteDraftRow(taskId, rowId)` | 单行移除 |
| `PullTaskGroupExecutionMapper` | `selectOccupiedLinks(tenantId, links)` | 占用查询 |
| `PullTaskMaterialMemberMapper` | `deleteByExecution(groupExecutionId)` | 单行移除连带删料子 |

`deleteDraftRow` 与 `deleteByExecution` 都必须带 `execution_status = 0` 前置条件，防止误删已冻结行。

### 7.2 跨域依赖

工程结构规范要求跨业务域只能调对方 **Service**，禁碰对方 mapper。本切片有两处跨域调用：

**`group` 域** —— 提交时需要按归一化链接拿回 `group_link.id`。现有 `GroupLinkRegistryService.registerJoinTaskTargets(List<String>)` 返回 `void`，拿不到 ID。因此在该接口上**新增一个方法**：

```java
Map<String, Long> registerPullTaskTargets(List<String> normalizedLinks, long now);
```

实现复用 `GroupLinkRegistryServiceImpl` 已有的 `registerOne` 内部逻辑（复用活跃行 / 复活软删行 / 新建），`origin` 取已存在的 `GroupLinkOrigin.PULL_TASK`（枚举值 3，已定义），返回归一化链接到 `group_link.id` 的映射。

**`account` 域** —— 三个分组 ID 的存在性与租户校验调 `AccountGroupService.requireExisting(Long)`，它抛业务异常并返回 `AccountGroup`（含 `name`，正好用于三个名称快照列）。`group` 域的 `HistoricalGroupPullCreateValidator` 已是同样用法，沿用该先例。

### 7.3 测试基座可见性

`PullTaskNormalLinkSchema` 与 `PullTaskNormalLinkH2Support` 当前是 `com.armada.task.mapper` 包内的包级私有类。第 4 层服务集成测试位于 `com.armada.task.service`，需要它们提升为 `public`（含 `dataSource` / `sqlSessionFactory` / `resetSchema` 三个方法）。这是纯测试代码的最小放宽，不影响 `src/main`。

## 8. 测试策略

TDD，先写失败测试。四层：

### 第 1 层 纯单元（无 Spring，无数据库）

`PullTaskMaterialTxtParser`：
- `A/a` 识别与剥离；同号重复行任一带 `A/a` 时唯一记录提升为 `adminRequired`
- 单文件内按归一化号码去重，保留首次出现的行号与顺序
- 展示字符移除（`+`、空格、`-`、`(`、`)`）
- 7 位与 15 位边界通过；6 位与 16 位拒绝
- 显式拒绝 `@s.whatsapp.net`
- 空行不计入任何计数
- 零有效号码的文件拒绝进池
- 非法行回文件名 + 原始行号 + 原因

`PullTaskLinkMatcher`：
- 不放回一对一
- 本次新增行数 = `min(剩余链接数, 有效TXT数)`
- 增量追加不改动已有行的链接、文件与 `seq`
- `seq` 从当前最大值连续递增
- 多出的 TXT 计入 `ignoredFileCount`，多出的链接计入 `remainingLinkCount`

### 第 2 层 Mock 抓取器

`PullTaskLinkProbeService`：
- 抓取超时 → `PROBE_INCOMPLETE` 且进池
- 公开页无群资料 → `LINK_EXPIRED` 且不进池
- 批内同一归一化链接只抓一次，重复行标 `DUPLICATE` 且保留自己的行号
- 格式非法 → `INVALID_FORMAT`，区分"缺少群邀请链接"与"链接长度不足"
- 已占用链接 → `OCCUPIED`
- 超过 200 条上限时拒绝

### 第 3 层 H2 Mapper

复用 `PullTaskNormalLinkH2Support`，覆盖 7.1 的六个新方法，含 `execution_status = 0` 前置条件的负例，以及 `submitDraft` 在状态非 `DRAFT` 或版本不符时返回 0 的负例。

### 第 4 层 H2 服务集成

- 草稿首次建立与二次复用（同用户只有一条）
- 增量追加：已有行不变，新行追加在尾部
- 单行移除：执行行与料子同时删除，已冻结行删不掉
- 清除全部：执行行与料子清空，草稿任务行保留
- 提交冻结全链路：`standard_setting` 落库、`group_link` 回填、`execution_status` 0→1、`status` `DRAFT → WAIT_START`、`group_count` 与 `expected_pull_count` 正确
- **占用冲突整单回滚**：冲突后草稿完整保留，任务未创建
- **重复提交幂等**：第二次提交不建第二个任务，回既有任务
- 跨租户隔离：他租户的草稿不可见、不可提交

第 4 层的占用冲突与幂等两条是本切片最易出错处，必须有真实数据库断言，不得用 Mock 替代。

### 覆盖率

按项目要求 ≥80%。纯函数层应接近 100%。

## 9. 验收标准

1. `POST /standard/draft/plan` 能对 200 条链接完成三态预检并在 60 秒内返回。
2. 链接逐行结果的六态判定与 5.1 表格逐条一致，行号保真。
3. TXT 解析结果与 5.3 管线逐条一致，包含拒绝 JID 与 7/15 位边界。
4. 补传资源时已有执行行的链接、文件与 `seq` 不变。
5. 单行移除与清除全部均不影响已冻结任务。
6. 提交后落库的执行行与最后一次 `plan` 响应中的 `rows` 完全一致，无重新随机。
7. 占用冲突整单回滚，草稿可继续编辑。
8. 重复提交不产生第二个任务。
9. 旧 `POST /api/pull-tasks` 行为不变。
10. 全部读写按租户隔离，草稿不出现在任务列表、看板与任何聚合统计中。

## 10. 未决事项

| 事项 | 影响 | 处理 |
|---|---|---|
| 单次粘贴链接的真实量级 | 若经常上千条，200 上限与同步等待模型不成立，需改后台异步检测 + 前端轮询 | 实现前向需求方确认；本设计按几十条量级 |
| 链接文本不落库导致刷新后需重新粘贴 | 前端体验 | 已接受；前端用 `sessionStorage` 缓解 |
| 同用户并发产生多条草稿 | 常量级残留 | 已接受，用 `selectLatestDraftByCreator` 容忍 |
