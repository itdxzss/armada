# 超链任务发信账号纬度统计详细设计（H5）

> 日期：2026-08-28
> 状态：待实施
> 上游合同：[超链任务公共契约](./2026-08-28-hyperlink-task-shared-contract.md)
> 详情外壳：[详情与收信人流水统计设计](./2026-08-28-hyperlink-task-recipient-stats-design.md)
> 数据模型：[超链营销数据模型](../../business/hyperlink-marketing-data-model.md) §4.5、§4.8、§4.11

## 0. 结论

本 Tab 的统计事实只使用已经确认的两张表：

1. `hyperlink_task_account_stat`：无时间范围时的任务×账号累计查询投影，负责默认排序、分页和导出性能。
2. `hyperlink_task_recipient`：选择任意发送时间范围时的唯一发送事实，精确过滤后现场聚合。

不建 `hyperlink_task_account_stat_hourly`。任务发送量按当前业务不超过 10 万，任意时间范围扫描单任务 recipient
可以接受；默认高频打开则使用 account_stat，避免每次 GROUP BY 全量流水。

`hyperlink_task_account_usage` 已因运行限额和并发控制独立存在，本方案只 LEFT JOIN 其账号号码、国家、类型和入库
时间快照，不把它算作第三张统计表，也不使用它的调度计数拼页面指标。

## 1. 竞品对齐清单

| 证据 | 竞品界面/行为 | Armada 实现 |
|---|---|---|
| E-H5-01 | Tab 文案为“发信账号纬度统计”（竞品原字） | 保留原文，不擅自改“维度”导致验收截图差异 |
| E-H5-02 | 筛选一：开始/结束日期时间范围 | 同一个 datetimerange，精确到秒 |
| E-H5-03 | 筛选二：发送账号国家 | 可搜索国家下拉，带国旗和区号 |
| E-H5-04 | 筛选三：成功数最小值 `成功 ≥` 与最大值 `≤` | 两个非负整数输入，支持 Enter |
| E-H5-05 | 有重置、搜索、导出、刷新、列设置 | 所有按钮都实现真实行为 |
| E-H5-06 | 六列：发送账号、存活天数、单钩数、双钩数、失败数、最后发送 | 字段、顺序、图标和空值一致 |
| E-H5-07 | 单钩、双钩、失败三列可远程排序 | 白名单排序；默认单钩数降序 |
| E-H5-08 | 发送账号显示手机号、国家旗帜、个人/商业标签 | 使用首次选中时冻结的账号快照 |
| E-H5-09 | 存在“未分配”行，存活 0.0 天、最后发送为 `-` | accountId=null 的唯一汇总桶 |
| E-H5-10 | 分页默认 20，可选 10/20/50/100/200 | 与 H4 公共分页一致 |

证据来自只读竞品前端
`hylbuiaxykfrontendsource/readable/assets/task-0vbZUOmq.js` 的 `accounts-tab`。竞品前端未暴露内部聚合表；
account_stat + recipient 的选择是按已确认数据量和查询效率做的 Armada 适配设计。

## 2. 页面与交互

H5 挂载到 H4 共用详情抽屉，不请求第二份顶部 summary，也不改变五个 Tab 的顺序。首次切入懒加载；切出后保留
当前筛选/页码，关闭抽屉或换任务时恢复默认。

### 2.1 筛选区

从左到右：

1. 日期时间范围，宽 400px，格式 `yyyy-MM-dd HH:mm:ss`，只读输入框、可清空。
2. 发送账号国家，宽 200px，可过滤、可清空，选项显示国旗、国家名、区号。
3. 成功数区间：两个宽 110px 的非负整数输入，中间为 `~`。
4. 重置、搜索。
5. 右侧：导出、刷新、列设置。

日期范围选择或清空后按竞品行为立即搜索；其余筛选在“搜索”或 Enter 后应用。重置清空日期、国家和成功区间，
排序恢复 `successNum desc`，回到第 1 页并查询。

### 2.2 Query

```typescript
interface HyperlinkAccountStatQuery {
  page: number;
  pageSize: 10 | 20 | 50 | 100 | 200;
  startAt: number | null;
  endAt: number | null;                  // [startAt, endAt)
  senderCountryIso2: string | null;
  successNumMin: number | null;
  successNumMax: number | null;
  sortField: "successNum" | "deliveredNum" | "failedNum";
  sortOrder: "asc" | "desc";
}
```

- 时间必须同时为空或同时有值，`startAt < endAt`；精确到秒但仍传 epoch 毫秒。
- 成功数必须是非负整数，min≤max；单端允许为空。
- 国家为空时包含未分配桶；指定国家时未分配桶自然不匹配。
- 默认 `successNum desc`，相同指标按 `accountBucketKey asc` 稳定分页。

## 3. 响应与六列

### 3.1 响应元素

```typescript
interface HyperlinkAccountStatItem {
  bucketKey: number;                     // 真实 accountId；未分配固定 0
  accountId: number | null;
  senderPhone: string | null;
  senderCountryIso2: string | null;
  accountType: "PERSONAL" | "BUSINESS" | null;
  retentionDays: number;
  successNum: number;
  deliveredNum: number;
  failedNum: number;
  lastSendAt: number | null;
}
```

`bucketKey` 只用于前端稳定 rowKey，不展示。`retentionDays` 由后端用查询快照时刻减
`accountCreatedAtSnapshot` 计算天数，保留一位且最小为 0；未分配固定 0.0。页面不拿当前账号表资料覆盖历史快照。

### 3.2 列定义

| 列 | 宽度 | 展示/交互 |
|---|---:|---|
| 发送账号 | 240 | 手机号 + 国旗 + “个人/商业”圆角标签；无账号显示淡色“未分配” |
| 存活天数 | 110 | `{retentionDays.toFixed(1)} 天` |
| 单钩数 | 120 | 单钩图标 + 绿色加粗，可排序 |
| 双钩数 | 120 | 双钩图标 + 蓝色加粗，可排序 |
| 失败数 | 110 | 大于 0 红色加粗，否则淡色，可排序 |
| 最后发送 | 160 | 系统时区 `yyyy-MM-dd HH:mm:ss`；空为 `-` |

表格 small、bordered、非单行、远程排序，最小横向宽度 900，高度沿用 H4。列设置持久化显示/顺序/宽度，不能
删除六个逻辑定义。点击已激活排序列在升/降序切换；清空 sorter 恢复默认单钩降序，而不是无序查询。

## 4. 两条查询路径

### 4.1 默认累计路径

当 startAt/endAt 均为空：

```sql
SELECT s.account_bucket_key,
       s.account_id,
       u.account_phone_snapshot,
       u.sender_country_iso2_snapshot,
       u.account_type_snapshot,
       u.account_created_at_snapshot,
       s.success_num, s.delivered_num, s.failed_num, s.last_send_at
FROM hyperlink_task_account_stat s
LEFT JOIN hyperlink_task_account_usage u
  ON u.tenant_id = s.tenant_id
 AND u.hyperlink_task_id = s.hyperlink_task_id
 AND u.account_id = s.account_id
WHERE s.tenant_id = ?
  AND s.hyperlink_task_id = ?
  /* 可选 sender_country、success min/max */
ORDER BY /* 白名单指标 */ DESC, s.account_bucket_key ASC
LIMIT ? OFFSET ?;
```

- success/delivered/failed 分别命中 account_stat 三个排序索引。
- 先对 stat 做任务、国家/区间过滤和分页，再只 JOIN 当前页 usage；实现可用派生子查询/CTE，避免先 JOIN 后宽排序。
- accountId=null 的 stat 行 LEFT JOIN 不到 usage，服务层填充未分配展示值。
- `COUNT(*)` 在同一筛选上完成；task×account 行数远小于 recipient。

### 4.2 时间范围精确路径

选择时间后，所有指标必须在 recipient 的 `submitted_at` 范围内重新计算，不能读取累计 stat 假装是区间数字：

```sql
SELECT COALESCE(r.account_id, 0) AS account_bucket_key,
       r.account_id,
       SUM(CASE WHEN r.send_status IN (3,4,5) THEN 1 ELSE 0 END) AS success_num,
       SUM(CASE WHEN r.send_status IN (4,5) THEN 1 ELSE 0 END) AS delivered_num,
       SUM(CASE WHEN r.send_status IN (6,7) THEN 1 ELSE 0 END) AS failed_num,
       MAX(r.submitted_at) AS last_send_at
FROM hyperlink_task_recipient r
WHERE r.tenant_id = ?
  AND r.hyperlink_task_id = ?
  AND r.submitted_at >= ? AND r.submitted_at < ?
  /* 可选 sender_country */
GROUP BY r.account_id
HAVING /* 可选 success min/max */
ORDER BY /* 白名单聚合别名 */ DESC, account_bucket_key ASC
LIMIT ? OFFSET ?;
```

服务层再批量查询本页 accountId 对应的 usage 快照。查询命中
`idx_hyperlink_recipient_task_time(tenant_id, hyperlink_task_id, submitted_at, account_id, send_status, id)`。
单任务上限 10 万，在限定任务和时间后聚合可接受；查询超时阈值、慢 SQL 监控必须开启。

`TASK_STOPPED` 且从未提交协议的 recipient 没有 submittedAt，因此不进入任意“发送时间”范围；它只在默认累计
路径的未分配失败桶中出现。这比给未发送数据伪造发送时间更准确。

## 5. account_stat 写入与校准

### 5.1 何时记录

创建任务、选择账号和生成 PENDING recipient 时不写 account_stat。只有以下事实变化才由 H3 指标投影器差量 UPSERT：

- recipient 首次离开 PENDING 并被协议接受：真实账号桶 sendTotal +1，记录首/末发送。
- SENDING→SUCCESS/DELIVERED/READ/FAILED/UNREGISTERED：按旧状态与新状态对包含式指标增减。
- 未提交 recipient 因 STOP 直接 PENDING→FAILED：按是否已分配账号进入真实账号桶或未分配桶。
- 极少数纠错从一种终态改到另一种时仍做旧/新差量，不能只累加。

投影完成后更新 recipient.metricsProjectedStatus；worker 崩溃重跑不会重复加数。`reconciledAt` 仅在从 recipient 全量
校准成功后更新。

### 5.2 两张 task×account 表的边界

| 表 | 写入时效 | 用途 | 禁止用途 |
|---|---|---|---|
| account_usage | 派发/结果同步 | 成功限额、在途并发、账号失效、展示快照 | 不现场拼统计页累计数 |
| account_stat | 分钟级异步 | 默认统计排序、分页、导出 | 不参与派发和账号上限判断 |

即使二者粒度相同也不能合并：一个要求同步锁竞争小且决定能否继续发，一个允许分钟级延迟且为查询冗余。合并会让
ACK/派发与页面排序更新争抢同一热点行。

## 6. 导出

### 6.1 作业

`POST /api/hyperlink-tasks/{id}/account-stats/export` 请求体复用当前筛选和排序，不含分页，返回公共异步
`HyperlinkTaskExportJob`。前端复用 H4 `useHyperlinkExportJob`，最终成功自动下载并提示“导出成功”。

默认累计导出走 account_stat；时间范围导出按 recipient 分批聚合。作业 `snapshotAt` 固定：

- 存活天数以 snapshotAt 计算。
- 默认累计导出只包含 stat.updatedAt≤snapshotAt 的可见投影；开始前先等待/记录同一投影水位。
- 时间范围额外把 endAt 钳制为 `min(endAt, snapshotAt+1)`，保证导出期间新增发送不漂移。

### 6.2 CSV 列

| 顺序 | 表头 |
|---:|---|
| 1 | 发送账号 |
| 2 | 发信国家 |
| 3 | 账号类型 |
| 4 | 存活天数 |
| 5 | 单钩数 |
| 6 | 双钩数 |
| 7 | 失败数 |
| 8 | 最后发送 |

未分配行第一列写“未分配”，国家/类型留空，存活天数写 `0.0`。手机号按文本导出。文件名
`hyperlink-account-stats-{taskId}-{yyyyMMddHHmmss}.csv`。

## 7. API、权限与错误

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/hyperlink-tasks/{id}/account-stats` | `tenant:hyperlink_task:view` |
| POST | `/api/hyperlink-tasks/{id}/account-stats/export` | `tenant:hyperlink_task:export` |

taskId 必须先做租户归属验证。时间、区间、国家和排序非法返回 VALIDATION；普通用户不可通过构造请求读取跨租户账号
快照。日志只打印 taskId、筛选哈希和行数，不打印完整手机号。

## 8. 代码落点

### 8.1 前端

```text
src/views/hyperlink/task/components/
├── AccountStatsTab.vue
└── AccountStatAccountCell.vue

src/views/hyperlink/task/composables/
└── useAccountStatQuery.ts
```

复用 H4 的国家选项、详情抽屉、summary、列设置和导出 composable。

### 8.2 后端

- `HyperlinkTaskDetailController.accountStats/exportAccountStats`。
- `HyperlinkAccountStatQueryService`：选择累计或区间查询策略、填充 usage 快照和存活天数。
- `HyperlinkTaskQueryMapper`：两条白名单 SQL，不接受原始 sort 字符串。
- H3 `HyperlinkRecipientMetricsProjector`：负责 account_stat 差量 UPSERT。
- `HyperlinkTaskExportService`：ACCOUNT_STATS writer。

## 9. 测试与性能验收

### 9.1 查询正确性

- 无日期走 account_stat；任意合法日期走 recipient，禁止混用。
- 两条路径在全时间范围且投影收敛后得到相同单钩/双钩/失败结果。
- 国家、成功 min/max、三种排序和组合筛选准确，分页无重复/漏行。
- SUCCESS/DELIVERED/READ 的包含式计数准确；FAILED/UNREGISTERED 都计失败。
- 未分配桶唯一，STOP 行进入默认累计，时间范围不伪造 submittedAt。
- 账号删除或改号后仍展示冻结快照；存活天数以查询 snapshot 计算。
- 重复投影、乱序 ACK、全量 reconciliation 不重复计数。

### 9.2 页面一致性

- 日期范围、国家、成功区间、重置、搜索、导出、刷新、列设置全部存在并工作。
- 六列顺序、宽度、图标、颜色、个人/商业标签和未分配样式逐一截图验证。
- 默认单钩降序；三个表头排序触发远程第 1 页查询。
- 默认 20 和 10/20/50/100/200 分页准确。
- 切 Tab 保留状态；关闭/换任务恢复默认。

### 9.3 性能门槛

- 默认累计查询在 1,000 个账号桶下 P95≤200ms。
- 10 万 recipient、24 小时时间范围、无筛选 GROUP BY P95≤800ms；失败则先检查索引和 SQL，不新增 hourly 表掩盖问题。
- 10 万行事实的账号统计导出必须异步、流式/分批处理，应用堆内存不随 recipient 总数线性增长。

## 10. 竞品一致性红线

- [ ] Tab 名“发信账号纬度统计”与竞品一致。
- [ ] 日期时间、发信国家、成功数区间三个筛选完整。
- [ ] 重置、搜索、导出、刷新、列设置一个不少。
- [ ] 发送账号、存活天数、单钩、双钩、失败、最后发送六列完整。
- [ ] 个人/商业标签、国旗、未分配行、空时间 `-` 完整。
- [ ] 单钩/双钩/失败三列远程排序且默认单钩降序。
- [ ] 10/20/50/100/200 分页完整。
- [ ] 时间范围数字来自 recipient 精确事实，默认数字来自 account_stat 投影。
- [ ] 不建 hourly 表，不因取消 hourly 而删掉日期筛选、排序或导出能力。

以上全部通过后，H5 才能标记完成。
