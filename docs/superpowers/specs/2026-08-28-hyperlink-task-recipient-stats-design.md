# 超链任务详情与收信人流水统计详细设计（H4）

> 日期：2026-08-28
> 状态：待实施
> 上游合同：[超链任务公共契约](./2026-08-28-hyperlink-task-shared-contract.md)
> 运行写入：[任务发布与运行生命周期设计](./2026-08-28-hyperlink-task-lifecycle-design.md)
> 数据模型：[超链营销数据模型](../../business/hyperlink-marketing-data-model.md) §4

## 0. 结论

收信人流水统计只需要三张超链任务表：

1. `hyperlink_task`：抽屉标题中的任务名称和任务身份。
2. `hyperlink_task_runtime`：顶部六张统计卡的累计投影和数据更新时间。
3. `hyperlink_task_recipient`：一位收信人一行的流水事实、筛选、分页和导出。

不新增 recipient_round、delivery_attempt 或详情统计表。一行 recipient 就是一位收信人在本任务中的唯一逻辑发送；
周期轮次不会复制它，协议重放也不会制造第二条流水。

H4 同时交付五个详情 Tab 共用的 1300px 右侧抽屉、顶部摘要、状态图例和四类详情导出作业外壳。H5/H6 只挂载
自己的 Tab，不得再实现第二套任务详情弹框。

## 1. 竞品对齐清单

### 1.1 已观察事实

| 证据 | 竞品界面/行为 | Armada 实现 |
|---|---|---|
| E-H4-01 | 点击“详情”打开右侧 1300px 抽屉，标题“任务收信人 · {任务名}” | 共用 `HyperlinkTaskDetailDrawer` |
| E-H4-02 | 遮罩可关闭，右上角有关闭按钮 | 两种关闭方式均保留 |
| E-H4-03 | 顶部依次显示单钩、双钩/率、失败/未开通、使用号数、封号数、号均发量 | 六张卡顺序、颜色、文案和口径一致 |
| E-H4-04 | 单钩/双钩带解释；双钩提示有延迟，“落地率 ≈ 双钩率 + 20%” | Tooltip + 常驻状态图例 |
| E-H4-05 | 五个 Tab：收信人流水、发信账号纬度、深度归因、访问趋势、封号原因分布 | Tab 名称、顺序、懒加载一致 |
| E-H4-06 | 收信人 Tab 有号码、收信国家、发信国家、完整失败原因四个筛选 | 四个筛选一个不少 |
| E-H4-07 | 有重置、搜索、导出、刷新、列设置 | 六个交互（含 Enter 搜索）全部实现 |
| E-H4-08 | 表格三列：收信号码、发送账号、状态/失败原因 | 列、国旗、状态标签、原因标签完整复刻 |
| E-H4-09 | 失败原因只在失败时显示，列表截断但悬浮展示全文 | 原因 tag + tooltip + 精确筛选 |
| E-H4-10 | 状态标签区分待发送、发送中、单钩、双钩、已读、失败 | 对应公共七种状态，未注册按失败原因展示 |
| E-H4-11 | 分页默认 20，可选 10/20/50/100/200 | 远程分页，筛选后回第 1 页 |
| E-H4-12 | 表格为紧凑、带边框、可纵向滚动，详情抽屉内不撑破页面 | 固定可用高度和横向最小宽度 |

证据来自只读竞品前端
`hylbuiaxykfrontendsource/readable/assets/task-0vbZUOmq.js` 的 `recipients-tab` 与
`hyperlink-recipients-modal`。竞品 API 把摘要附在收信人响应中；Armada 按公共契约拆为 `/summary` 与
`/recipients`，页面表现不变，并避免 H5/H6 为了顶部卡重复请求收信人第一页。

## 2. 详情抽屉公共外壳

### 2.1 打开与关闭

- 组件：`HyperlinkTaskDetailDrawer.vue`。
- `placement=right`、宽度 1300px；视口不足 1360px 时使用 `min(100vw, 1300px)`。
- 标题固定为 `任务收信人 · ${taskName}`；任务名为空时显示“任务收信人”。
- 打开参数：`taskId`、`taskName`、`initialTab`，默认 Tab 为 `recipients`。
- 点击遮罩或右上角关闭；关闭时取消未完成请求和导出轮询，清空上个任务数据。
- 重新打开同一任务也重新获取 summary；不能复用可能过期的上次指标。

### 2.2 五个 Tab

| key | 文案 | 所属方案 | 加载策略 |
|---|---|---|---|
| `recipients` | 收信人流水统计 | H4 | 默认立即加载 |
| `accounts` | 发信账号纬度统计 | H5 | 首次切入懒加载 |
| `clicks` | 深度归因 | H6 | 首次切入懒加载 |
| `visit-trend` | 访问趋势 | H6 | 首次切入懒加载；每次打开新任务重建图表 |
| `ban-stats` | 封号原因分布 | H6 | 首次切入懒加载 |

切换 Tab 保留本次抽屉内各 Tab 的筛选和页码；切换任务必须全部重置。H1 的“详情”默认打开 recipients；后续如有
明确入口可以传 initialTab，但不新增竞品没有的顶层菜单。

### 2.3 顶部六张卡

`GET /api/hyperlink-tasks/{id}/summary` 返回公共 `HyperlinkTaskSummary`。页面按以下顺序显示：

| 卡片 | 主值 | 辅值/公式 | 空值 |
|---|---:|---|---|
| 单钩总数 | `successNum` | 单钩图标 | 0 |
| 双钩总数 / 双钩率 | `deliveredNum` | `deliveredNum / successNum` | `0 0.00%` |
| 失败总数 / 未开通 WS | `failedNum` | `/ unregisteredNum` | `0 / 0` |
| 使用号数 | `usedAccountCount` | — | 0 |
| 封号数 | `invalidAccountCount` | — | 0 |
| 号均发量 | `successNum / usedAccountCount` | 整数不带小数，否则一位 | 0 |

顶部摘要是整项任务累计值，不受当前 Tab 筛选影响。详情打开时 summary 与当前 Tab 请求并行；收信人 Tab 点“刷新”时
两者都刷新。其他 Tab 刷新自己的数据时也刷新 summary，但请求去重器在 500ms 内只发一次摘要请求。

### 2.4 状态图例

六张卡下方常驻一行：

- `✓ 单钩`：消息已发送到对方手机，手机关机/无网络时也算。
- `✓✓ 双钩`：对方 WhatsApp 在线，设备 100% 收到。
- 提示：双钩有延迟，仅作参考；落地率 ≈ 双钩率 + 20%。

单钩卡和双钩卡图标悬浮时重复给出完整解释，保证竞品的 tooltip 功能没有丢失。

## 3. 收信人查询合同

### 3.1 Query

```typescript
interface HyperlinkRecipientQuery {
  page: number;                         // 默认 1
  pageSize: 10 | 20 | 50 | 100 | 200; // 默认 20
  phone: string | null;                // 收信号码模糊搜索
  recipientCountryIso2: string | null;
  senderCountryIso2: string | null;
  failReason: string | null;           // 完整原因精确匹配
  sortField: "id";
  sortOrder: "asc" | "desc";          // 默认 asc
}
```

竞品没有状态、轮次、日期筛选，不在一期自行增加。`phone` trim 后最多 32 字符，只允许数字和可选前导 `+`；后端按
规范化号码片段做 LIKE 并转义 `%/_`。两个国家码必须来自国家选项或为空。失败原因 trim 后最多 255 字符，严格等值
匹配，不能因为页面提示“完整原因”却实现成模糊搜索。

点击“搜索”或任一文本框 Enter：复制编辑态筛选到已应用 Query 并回第 1 页。点击“重置”：清空四项、回第 1 页并
立即查询。分页只修改 page/pageSize，不重新解释输入框中尚未搜索的文字。

### 3.2 响应元素

```typescript
interface HyperlinkRecipientItem {
  id: number;
  recipientPhone: string;
  recipientCountryIso2: string | null;
  accountId: number | null;
  senderPhone: string | null;
  senderCountryIso2: string | null;
  status: "PENDING" | "SENDING" | "SUCCESS" | "DELIVERED" | "READ" | "FAILED" | "UNREGISTERED";
  failCode: string | null;
  failReason: string | null;
  statusAt: number | null;
}
```

`statusAt` 由服务端按当前状态选择：READ→readAt、DELIVERED→deliveredAt、SUCCESS→sentAt、FAILED/UNREGISTERED→
failedAt；PENDING/SENDING 没有对应终态时间时为 null。前端不能根据多个时间字段再次猜状态优先级。

## 4. 表格展示

### 4.1 固定逻辑列

| 列 | 宽度 | 展示 |
|---|---:|---|
| 收信号码 | 180 | 原样字符串；有国家时显示国旗 + 国家 tooltip |
| 发送账号 | 180 | `senderPhone`；为空但有 accountId 时显示 `#accountId`；否则 `-`；有国家显示国旗 |
| 状态 / 失败原因 | 360 | 状态圆角标签；失败时追加原因标签和全文 tooltip |

表格 `rowKey=id`、small、bordered、非单行、最小横向宽度 900，纵向高度 `calc(100vh - 360px)`。列设置允许调整
显示/顺序/宽度并按用户本地持久化；三列均为竞品核心列，“恢复默认”可用，但不能永久删除列定义。

### 4.2 状态渲染

| API 状态 | 标签 | 图标/颜色 | 时间 tooltip |
|---|---|---|---|
| PENDING | 待发送 | 默认灰 | 无 |
| SENDING | 发送中 | 信息蓝 | 无 |
| SUCCESS | 单钩 | 单钩图标、绿/蓝 | statusAt |
| DELIVERED | 双钩 | 双钩图标、蓝绿 | statusAt + 双钩说明 |
| READ | 已读 | 成功绿 | statusAt |
| FAILED | 失败 | 红 | statusAt |
| UNREGISTERED | 失败 | 红 | statusAt |

UNREGISTERED 仍显示“失败”，右侧原因显示“号码未注册”；这是竞品流水表现，也是公共契约中失败子类型的定义。
失败原因只在 FAILED/UNREGISTERED 时渲染。标签内以 `原因：` 开头，最大可见宽度 220px、溢出省略；hover 展示
完整 `failReason`。不得把协议堆栈、凭据或原始敏感错误回传页面。

## 5. 查询实现与性能

### 5.1 SQL 路径

摘要：

```sql
SELECT t.id, t.task_name, t.task_type,
       r.is_enabled, r.run_status,
       r.recipient_total, r.send_total, r.success_num, r.delivered_num,
       r.read_num, r.fail_num, r.fail_404_num,
       r.used_account_count, r.invalid_account_count,
       r.click_uv_num, r.click_total, r.actual_concurrency,
       r.execution_duration_sec, r.active_since_at,
       r.first_visit_at, r.last_visit_at, r.metrics_updated_at
FROM hyperlink_task t
JOIN hyperlink_task_runtime r
  ON r.tenant_id = t.tenant_id AND r.hyperlink_task_id = t.id
WHERE t.tenant_id = ? AND t.id = ?;
```

流水：从 `idx_hyperlink_recipient_task` 以 `(tenantId, taskId, sendStatus, id)` 为基础，按筛选选择国家或发信号码索引；
只选择响应的十一个小字段，禁止 `SELECT *` 把 UA/IP 等归因列读进普通流水。任务最多 10 万行，普通分页用
`COUNT(*) + LIMIT/OFFSET` 足够；pageSize 上限 200。若以后任务上限提高再评审游标分页，不提前引入第二套合同。

### 5.2 数据一致性

- summary 读 runtime 分钟级投影，recipient 列表读实时事实，因此刚收到 ACK 时个别行可能先于顶部卡变化。
- 页面显示公共“聚合数据约每分钟同步一次”提示和 metricsUpdatedAt，不伪装强一致。
- reconciliation 从 recipient 重建 runtime 后，相同状态必须得到相同摘要。
- 任务、runtime、recipient 查询均带 tenant 条件；不能只凭 taskId 查明细。

## 6. 收信人导出

### 6.1 创建作业

`POST /api/hyperlink-tasks/{id}/recipients/export` 请求体复用当前已应用的四个筛选和排序，不含 page/pageSize。
返回 HTTP 202 + 公共 `HyperlinkTaskExportJob`。前端行为：

1. 按钮 loading，创建作业成功后显示“导出任务已创建”。
2. 仅在 PENDING/PROCESSING 轮询公共作业状态；关闭抽屉只取消前端轮询，不取消服务端作业。
3. SUCCESS 自动下载；FAILED 展示 errorMessage；EXPIRED 提示重新导出。
4. 下载成功提示“导出成功”，与竞品最终用户体验一致。

### 6.2 CSV 列顺序

| 顺序 | 表头 | 来源 |
|---:|---|---|
| 1 | 收信号码 | recipient_phone_snapshot |
| 2 | 收信国家 | recipient_country_iso2_snapshot |
| 3 | 发送账号 | sender_phone_snapshot / 未分配 |
| 4 | 发信国家 | sender_country_iso2_snapshot |
| 5 | 状态 | 公共状态中文映射 |
| 6 | 失败码 | fail_code |
| 7 | 失败原因 | fail_reason |
| 8 | 状态时间 | statusAt，系统时区格式化 |

CSV 文件名：`hyperlink-recipients-{taskId}-{yyyyMMddHHmmss}.csv`。使用 UTF-8 BOM；手机号按文本写出，避免 Excel
科学计数法。作业冻结 `snapshotAt`，分批查询时额外限制 `created_at <= snapshotAt`，每批最多 2000 行。

### 6.3 公共导出作业外壳

不新建 `hyperlink_export_job` 表，复用 `marketing_export_job`：扩展业务类型 RECIPIENTS、ACCOUNT_STATS、ATTRIBUTION、
VISIT_TREND，并复用其租约、文件保留、清理和用户隔离。公共接口：

- `GET /api/hyperlink-task-exports/{jobId}`：只返回当前租户、当前创建人的作业。
- `GET /api/hyperlink-task-exports/{jobId}/download`：校验 SUCCESS、未过期及权限后流式下载。
- 归因导出还需敏感权限；公共下载器按作业类型重新校验，不能只在创建时校验一次。

## 7. 前端结构

```text
src/views/hyperlink/task/
├── components/
│   ├── HyperlinkTaskDetailDrawer.vue
│   ├── TaskSummaryCards.vue
│   ├── DeliveryStatusLegend.vue
│   ├── RecipientStatsTab.vue
│   └── RecipientStatusCell.vue
├── composables/
│   ├── useTaskSummary.ts
│   ├── useRecipientQuery.ts
│   └── useHyperlinkExportJob.ts
└── constants/
    └── recipient-columns.ts
```

API 类型统一放 `src/api/hyperlink-task.ts`；H5/H6 复用 Drawer、Summary、Legend 和 export job composable，不能复制。

## 8. 后端结构

建议落在 H3 建立的 `com.armada.hyperlink.task` 包：

- `controller/HyperlinkTaskDetailController`：summary、recipients、四类导出创建入口。
- `query/HyperlinkTaskSummaryQueryService`：task+runtime 读取和运行时长计算。
- `query/HyperlinkRecipientQueryService`：白名单排序、分页、展示映射。
- `export/HyperlinkTaskExportService`：适配现有 marketing export job。
- `mapper/HyperlinkTaskQueryMapper.xml`：显式列查询和索引友好筛选。

DTO 不直接复用 Entity；手机号始终是字符串，数据库 snake_case 不穿透 API。

## 9. 测试

### 9.1 API/Mapper

- 当前租户任务正常返回；跨租户 taskId 统一 NOT_FOUND。
- summary 十三个指标、运行中执行时长、null 时间映射准确。
- 四筛选单独和组合命中，失败原因严格等值，LIKE 通配字符被转义。
- 七种 recipient 状态映射和 statusAt 优先级准确。
- 默认 asc、分页边界、pageSize=200 和非法排序字段校验。
- 10 万行任务的第一页、末页和组合筛选执行计划命中预期索引。
- ACK 先更新 recipient、投影后 summary 收敛，重复投影不重复计数。

### 9.2 前端

- 抽屉宽度/标题/遮罩关闭/关闭按钮与五个 Tab 顺序一致。
- 六张卡的值、0 分母、两位百分比、一位号均和 tooltip 文案准确。
- 四项筛选、Enter、搜索、重置、分页和任务切换重置准确。
- 三列国旗、未分配账号、七种状态、失败原因截断与 tooltip 逐一快照测试。
- 导出、刷新、列设置按钮始终存在；loading 防重复点击。
- 异步导出五种状态、关闭抽屉、重新打开和下载失败均正确处理。

## 10. 竞品一致性红线

- [ ] 1300px 右侧“任务收信人”抽屉和两种关闭方式存在。
- [ ] 六张顶部卡一个不少，顺序、颜色、说明和公式一致。
- [ ] 单钩/双钩状态图例及“落地率 ≈ 双钩率 + 20%”存在。
- [ ] 五个详情 Tab 名称和顺序一致。
- [ ] 收信号码、收信国家、发信国家、完整失败原因四个筛选存在。
- [ ] 重置、搜索、导出、刷新、列设置全部可用，不是空按钮。
- [ ] 三列流水、国旗、状态标签、状态时间提示和失败原因全文提示完整。
- [ ] 默认 20 与 10/20/50/100/200 分页完整。
- [ ] 同一收信人永远只显示一行，不因轮次或协议恢复出现重复记录。
- [ ] 导出应用同一筛选，最终下载合法 CSV。

以上全部通过后，H4 才能标记完成。
