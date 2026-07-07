# 建群营销任务导出设计

## 背景

建群营销任务列表需要支持批量选择任务，并按运营提供的 Excel 样式导出建群统计。截图中的导出表包含标题、导出时间、明细列和合计行，明细列为:

- 任务ID
- 群名称
- 建群人数
- 进群人数（目标数据人数）

现有建群营销数据结构中，任务主表 `group_creation_marketing_task` 表示一次批量任务，明细表 `group_creation_marketing_item` 表示一个账号使用一个料子文件创建的一个群。导出的“群名称、人数”应落在执行项粒度。

## 目标

- 在建群营销任务列表 ID 列前增加选择框，支持全选当前页、单选、多选。
- 在“新增建群营销”按钮右侧增加“导出”按钮。
- 用户点击导出时，只导出当前选中的任务。
- 后端生成 `.xlsx` 文件，前端只传选中任务 ID 并下载附件。
- 导出文件按截图结构输出标题、导出时间、明细表头、明细行和合计行。
- 进群人数使用“发送营销消息前实时查询到的群人数快照 - 1”，其中 `-1` 表示扣除我方管理员账号。

## 非目标

- 不导出所有筛选结果，只导出用户当前选中的任务。
- 不在前端拼 Excel 或计算业务人数。
- 不在导出时实时调用协议层查询群成员。
- 不改变建群营销任务创建、重试、停止和发送结果回写的现有状态机。
- 不补算历史任务缺失的发送前群人数快照；历史数据没有快照时，进群人数导出为空。

## 前端设计

前端项目为 `wheel-saas-pure-web`。

`GroupCreationMarketingTaskTable.vue`:

- 在 `<el-table>` 上增加 `@selection-change`。
- 在 ID 列前增加 `<el-table-column type="selection" width="48" />`。
- 新增 props:
  - `selectedCount: number`
  - `exporting: boolean`
- 新增 emits:
  - `selection-change`
  - `export-selected`
- 在现有“新增建群营销”按钮右侧增加“导出”按钮。
- 导出按钮未选中任务时禁用；导出中展示 loading。

`useGroupCreationMarketingPage.ts`:

- 新增 `selectedRows: Ref<GroupCreationMarketingTaskRow[]>`。
- 新增 `selectedCount: ComputedRef<number>`。
- 新增 `exporting: Ref<boolean>`。
- 新增 `onSelectionChange(rows)` 保存当前选择。
- 新增 `exportSelectedTasks()`:
  - 未选择时提示“请先选择要导出的建群营销任务”。
  - 提取 `selectedRows.value.map(row => row.id)`。
  - 调用 `exportGroupCreationMarketingTasks(ids)`。
  - 下载后端返回的 blob。
  - 失败时展示后端错误或“建群营销任务导出失败”。

`src/api/group-creation-marketing.ts`:

- 新增类型 `GroupCreationMarketingTaskExport`，包含 `filename` 和 `blob`。
- 新增 `exportGroupCreationMarketingTasks(ids: number[])`。
- 请求:
  - method: `post`
  - url: `/api/group-creation-marketing-tasks/export`
  - body: `{ ids }`
  - `responseType: "blob"`
- 解析 `Content-Disposition` 中的文件名；取不到时使用 fallback，例如 `建群营销统计导出.xlsx`。

## 后端设计

后端项目为 `armada/armada-api`。

### 数据模型

新增 Flyway 迁移，在 `group_creation_marketing_item` 增加发送前群人数快照字段:

- `send_member_count INT DEFAULT NULL COMMENT '发送营销消息前查询到的群人数快照'`
- `send_member_count_checked_at BIGINT DEFAULT NULL COMMENT '群人数快照查询时间(epoch毫秒)'`

实体 `GroupCreationMarketingItem`、MyBatis `ItemResultMap`、`ItemColumns` 同步增加字段。

### 发送前群人数快照

`GroupCreationMarketingWorker` 在建群成功后、营销消息入队前:

1. 使用当前执行账号 `protocolAccountId` 和建群返回的 `groupJid` 调用现有 `GroupParticipantPort.listParticipants(...)`。
2. 得到成员列表长度 `memberCount`。
3. 将 `memberCount` 和查询时间随 `markItemMarketingSending(...)` 一起写入执行项。
4. 如果成员查询失败，不阻断营销消息发送；记录日志，快照字段保持 `NULL`。

这样导出口径固定为“发送消息时查询到的群人数”，不会被后续退群、进群或账号离线影响。

### 导出接口

`GroupCreationMarketingTaskController` 新增:

- `POST /api/group-creation-marketing-tasks/export`
- 请求体: `{ "ids": [1, 2, 3] }`
- 响应: `ResponseEntity<byte[]>` 附件，不包 `ApiResponse`
- Content-Type: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- Content-Disposition: UTF-8 文件名，例如 `建群营销统计导出_20260707_153000.xlsx`

`GroupCreationMarketingTaskService` 新增:

- `GroupCreationMarketingExportFile exportTasks(List<Long> ids)`

校验:

- `ids` 不能为空。
- 过滤重复 ID，保留用户选择顺序。
- 仅查询当前租户下未删除任务的执行项。
- 如果没有可导出的执行项，抛业务校验错误“选中的任务没有可导出的建群明细”。

### 导出数据口径

导出明细是一行一个 `group_creation_marketing_item`。

- `任务ID`: `item.task_id`
- `群名称`: `item.group_subject`
- `建群人数`: `item.participant_count + 1`
- `进群人数`: `max(item.send_member_count - 1, 0)`；`send_member_count` 为空时导出空单元格，不写 `0`

一个任务包含多个执行项时，导出多行，任务 ID 重复展示。

合计行:

- 第一列为 `合计`
- 第二列为空
- 第三列为所有明细行建群人数求和
- 第四列为所有有群人数快照的明细行进群人数求和；没有快照的空单元格不参与求和

### XLSX 样式

使用现有 `cn.idev.excel:fastexcel` 生成 `.xlsx`，不新增依赖。

表格结构:

- 第 1 行合并 A1:D1: `建群统计导出-<yyyy-MM-dd HH:mm:ss>（导出时间）`
- 第 2 行表头: `任务ID / 群名称 / 建群人数 / 进群人数（目标数据人数）`
- 第 3 行起为明细。
- 最后一行为合计。

样式保持实用优先:

- 标题加粗。
- 表头加粗居中。
- 数字列使用整数格式。
- 合计行加粗并设置浅绿色背景。

## 错误处理

- 前端未选择任务: 不请求后端，直接提示。
- 后端 ID 为空: 返回业务校验错误。
- 选中任务不存在或跨租户不可见: 不导出这些行；若最终无行则返回业务校验错误。
- 群成员查询失败: Worker 不阻断营销发送，导出时该执行项进群人数为空。
- 文件生成失败: 抛业务校验错误“建群营销统计导出失败”。

## 测试

后端:

- Worker 测试: 建群成功后调用 `GroupParticipantPort.listParticipants`，并把成员数写入 `markItemMarketingSending`。
- Worker 测试: 成员查询失败不阻断 outbox 入队。
- Mapper DB 测试: `send_member_count` 字段可写可读。
- Service DB 测试: 按选中任务 ID 导出执行项，建群人数为 `participant_count + 1`，进群人数为 `send_member_count - 1`。
- Controller 测试: 导出接口返回 `.xlsx` content type 和 UTF-8 attachment 文件名。

前端:

- API 测试: `exportGroupCreationMarketingTasks([1, 2])` 请求 POST `/export`，body 为 `{ ids: [1, 2] }`，使用 `responseType: "blob"`。
- Composable 测试: 未选择时不请求接口并提示；选择任务后触发导出下载。
- 表格模板测试或静态校验: 表格包含 `type="selection"`，按钮区包含“新增建群营销”和“导出”。

## 自检

- 无开放占位项。
- 导出粒度明确为执行项，不是任务主表。
- 人数口径与需求一致: 建群人数为料子有效号码数加管理员，进群人数为发送前群人数快照减管理员。
- 前端只传 ID，后端负责租户隔离、业务计算和文件生成。
- 历史数据缺少快照的行为已作为兼容处理列入非目标和错误处理。
