# 变更记录：群组列表数据模型

## 范围

本轮先敲定「群组列表」数据模型，不实现接口和页面。

当前字段依据现有 Flyway：

- `V003__group_import_links.sql`
- `V006__group_link_batch_name_nullable.sql`
- `V008__time_columns_to_epoch_ms.sql`

## 表级清单

| 表 | 当前是否已有 | 本轮动作 |
|---|---|---|
| `group_link` | 已有 | 保留为群入口主表；新增 `origin`、`membership_state`；收紧 `group_name` 语义 |
| `group_link_preview` | 新增 | 新建协议群元数据表 |
| `group_link_health` | 新增 | 新建群可用性/运行态表 |
| `group_link_import_batch` | 已有 | 调整批次统计语义；`skipped_rows` 改为 `duplicate_rows` |
| `group_link_import_detail` | 已有 | 调整逐行结果语义；新增 `success_type`、`existing_origin` |
| `group_link_label` | 已有 | 本轮不改 |

## 核心原则

1. 不复刻 wheel 的 `group_link` 宽表。armada 继续沿用 `group_link + group_link_preview + group_link_health` 的垂直拆分。
2. `group_link` 只放群入口身份、来源、导入归属和运营编辑字段。
3. 协议返回的群基础信息放 `group_link_preview`。
4. 可用性、封禁、检测失败、当前人数放 `group_link_health`。
5. 同租户同 `link_url` 在 `group_link` 里只能有一条入口记录；软删行复活，不插第二条。
6. 导入链接、进群任务、拉群任务、自建群都可以让链接进入群组列表，但入口业务规则不能互相污染。

## group_link

状态：**已有表，本轮新增列 + 语义收紧**。

群入口主表。表达「这个群链接在本控里的身份、来源、导入归属和运营编辑信息」。

### 当前已有列

| 字段 | 当前含义 / 本轮口径 |
|---|---|
| `id` | 主键 |
| `tenant_id` | 租户 ID |
| `link_url` | 归一化后的 WhatsApp 群邀请链接；同租户内唯一。自建群也要等拿到邀请链接后再入池 |
| `group_name` | 已有列，语义收紧为运营侧自定义群名称；默认空；导入链接不写；群组列表人工编辑写 |
| `label_id` | 导入链接分组 ID；为空表示还没有进入「导入链接」菜单 |
| `import_batch_id` | 首次进入导入链接分组时对应的导入批次 ID |
| `remark` | 运营备注；群组列表人工编辑 |
| `created_at` | 创建时间，epoch 毫秒 |
| `updated_at` | 更新时间，epoch 毫秒 |
| `created_by` | 创建人用户 ID；当前无用户上下文时为空 |
| `deleted_at` | 软删除时间，epoch 毫秒；为空表示未删除 |

### 本轮新增列

| 字段 | 动作 | 含义 |
|---|---|---|
| `origin` | 新增 | 首次进入群组池的来源：导入链接 / 进群任务 / 拉群任务 / 自建群 |
| `membership_state` | 新增 | 我方与群的关系：目标未进群 / 已进群 / 自建拥有 |

### 规则

- `origin` 表示首次进入群组池来源，创建后不因导入链接收编而改变。
- `label_id` / `import_batch_id` 只表达是否进入导入链接分组。
- 拉群、进群、自建先进入的链接，后续可被导入链接收编，补上 `label_id` / `import_batch_id`。
- 已经在导入链接里的链接再次导入，不迁移分组，明细失败原因记「重复」。
- `membership_state` 只升级：目标未进群 → 已进群 → 自建拥有。
- `group_name` 不由导入链接写入；协议真实群名也不写这里。

## group_link_preview

状态：**新增表，全部字段均为新增**。

协议群元数据表。表达「协议层解析/预览到的群基础信息」。

| 字段 | 含义 |
|---|---|
| `id` | 主键 |
| `tenant_id` | 租户 ID |
| `group_link_id` | 关联 `group_link.id`；一条群入口最多一条预览记录 |
| `group_jid` | WhatsApp 群 JID，协议层操作群的真实标识 |
| `invite_code` | 群邀请链接里的邀请码 code |
| `wa_subject` | WhatsApp 真实群名称，协议层返回；不覆盖 `group_link.group_name` |
| `member_size` | 预览时刻返回的群成员数量 |
| `owner_phone` | 群主号码，协议层返回 owner 后去掉后缀得到 |
| `announce_only` | 是否仅管理员可发言：未知 / 否 / 是 |
| `avatar_url` | 群头像 URL，协议层或上传后可访问地址 |
| `last_preview_at` | 最近一次预览/解析成功时间，epoch 毫秒 |
| `created_at` | 创建时间，epoch 毫秒 |
| `updated_at` | 更新时间，epoch 毫秒 |

### 规则

- 生命周期跟随 `group_link`，不单独软删。
- 协议预览拿到的真实群名只写 `wa_subject`，不覆盖 `group_name`。
- 列表展示群名时优先 `group_link.group_name`，为空再显示 `wa_subject`。
- `member_size` 是预览快照，不一定是最新人数；最新人数优先看 `group_link_health.current_count`。
- 没有预览成功时可以没有记录，列表展示空/未知。

## group_link_health

状态：**新增表，全部字段均为新增**。

群可用性/运行态表。表达「这个群当前是否可用、是否封禁、最近检测情况」。

| 字段 | 含义 |
|---|---|
| `id` | 主键 |
| `tenant_id` | 租户 ID |
| `group_link_id` | 关联 `group_link.id`；一条群入口最多一条健康记录 |
| `health_status` | 健康状态：未检测 / 可用 / 链接失效 / 不可用 |
| `is_banned` | 是否被 WhatsApp 封禁：未知 / 未封禁 / 已封禁 |
| `current_count` | 当前群成员数量；健康检测或事件回报后的较新人数 |
| `last_check_at` | 最近一次健康检测时间，epoch 毫秒 |
| `last_health_error` | 最近一次健康检测失败原因，例如格式错误、链接失效、超时、限流 |
| `health_failure_count` | 连续健康检测失败次数；检测成功后归零 |
| `created_at` | 创建时间，epoch 毫秒 |
| `updated_at` | 更新时间，epoch 毫秒 |

### 列表状态派生

| 条件 | 展示状态 |
|---|---|
| 没有 health 记录，或 `health_status` 为空 | 未检测 |
| `is_banned = 1` | 封禁 |
| `health_status = 可用` 且 `is_banned != 1` | 可用 |
| `health_status = 链接失效` | 链接失效 |
| `health_status = 不可用` | 不可用 |

删除确认里的「存在可用数据」只认：

```text
health_status = 可用 AND is_banned = 0
```

### 规则

- `health_status` 和 `is_banned` 都允许未知，不能默认成可用或未封禁。
- `current_count` 比 `preview.member_size` 更新，列表人数优先展示 `current_count`。
- 健康失败原因只记录最近一次，不做历史审计。
- 生命周期跟随 `group_link`，不单独软删。

## group_link_import_batch

状态：**已有表，本轮改名列 + 语义调整**。

导入批次表。记录一次「导入链接」动作的批次头和统计。该表属于导入链接菜单，不表达群本身状态。

### 当前已有列

| 字段 | 当前含义 / 本轮口径 |
|---|---|
| `id` | 主键 |
| `tenant_id` | 租户 ID |
| `label_id` | 本次导入目标分组 ID |
| `batch_name` | 批次名称，用户填写；可为空 |
| `source_file_name` | 上传文件原名；纯文本粘贴导入时为空 |
| `total_rows` | 本次解析出的总行数 |
| `inserted_rows` | 真正新建 `group_link` 的成功数量 |
| `adopted_rows` | 收编已有群入口的成功数量；例如拉群、进群、自建已有，但还没进入导入链接分组 |
| `failed_rows` | 已有列，语义调整为失败总数，包含重复和格式错误 |
| `created_at` | 导入时间，epoch 毫秒 |
| `created_by` | 创建人用户 ID；当前无用户上下文时为空 |
| `deleted_at` | 软删除时间，epoch 毫秒；随导入分组删除时软删 |

### 本轮改名列

| 原字段 | 新字段 | 动作 | 含义 |
|---|---|---|---|
| `skipped_rows` | `duplicate_rows` | 改名 | 重复失败数量；包括本批次内重复，以及已在导入链接里又重复导入 |

### 前端展示口径

| 前端字段 | 来源 |
|---|---|
| `totalRows` | `total_rows` |
| `successRows` | `inserted_rows + adopted_rows` |
| `failedRows` | `failed_rows` |
| `duplicateRows` | `duplicate_rows` |
| `formatErrorRows` | `failed_rows - duplicate_rows`，如果页面需要 |

### 规则

- DB 保留 `inserted_rows` / `adopted_rows`，用于后端审计和排查。
- 前端不展示新增和收编细分，只展示成功总数。
- 页面文案用「导入成功数量」或「成功数量」，不叫「新增成功数量」。
- `failed_rows` 必须包含 `duplicate_rows`。
- 现有 `skipped_rows` 迁移为 `duplicate_rows`，避免「跳过」语义不清。
- `batch_name` 保持可为空。

## group_link_import_detail

状态：**已有表，本轮新增列 + 语义调整**。

导入明细表。记录每一行导入链接的处理结果。该表只服务导入链接菜单的明细和失败原因展示，不参与群状态判断。

### 当前已有列

| 字段 | 当前含义 / 本轮口径 |
|---|---|
| `id` | 主键 |
| `tenant_id` | 租户 ID |
| `batch_id` | 所属导入批次 ID |
| `line_no` | 原始导入内容中的行号 |
| `raw_url` | 原始链接文本；失败行也保留 |
| `group_name` | 已有列，本业务不再写群名称；后续确认无读者后再废弃 |
| `result` | 已有列，语义调整为处理结果：成功 / 失败 |
| `fail_reason` | 已有列，语义调整为失败原因：重复 / 格式错误；成功时为空 |
| `group_link_id` | 成功新增或收编时关联的 `group_link.id`；失败时为空 |
| `created_at` | 创建时间，epoch 毫秒 |

### 本轮新增列

| 字段 | 动作 | 含义 |
|---|---|---|
| `success_type` | 新增 | 成功类型：新增 / 收编已有群；失败时为空 |
| `existing_origin` | 新增 | 收编成功时，记录该链接原本来源：进群任务 / 拉群任务 / 自建群 |

### 规则

- 本批次内重复：`result=失败`，`fail_reason=重复`。
- 已经在导入链接里又重复导入：`result=失败`，`fail_reason=重复`。
- 已由拉群、进群、自建进入，但还未进入导入链接：`result=成功`，`success_type=收编已有群`，写 `existing_origin` 和 `group_link_id`。
- 全新链接：`result=成功`，`success_type=新增`，写 `group_link_id`。
- 格式错误：`result=失败`，`fail_reason=格式错误`。
- `group_name` 现阶段不再写；为避免迁移风险先保留，后续确认无读者再 DROP。

### 明细查询契约

| 查询参数 | 含义 |
|---|---|
| `result` | 可空；`1=成功`，`2=失败` |
| `failReason` | 可空；失败原因过滤，当前支持 `重复` / `格式错误` |

兼容旧四态调用：

- 旧 `result=3` 会转换为 `result=2&failReason=重复`。
- 旧 `result=4` 会转换为 `result=2&failReason=格式错误`。

前端新代码不要再用 `result=3/4` 作为正式契约，筛重复/格式错误统一用 `failReason`。

## 导入链接入池规则

| 场景 | `group_link` 处理 | 导入明细 |
|---|---|---|
| 链接全新 | 新建 `group_link`，`origin=导入链接`，写 `label_id` / `import_batch_id` | 成功，新增 |
| 已由拉群任务进入，且 `label_id IS NULL` | 不新建，保留 `origin=拉群任务`，写本次 `label_id` / `import_batch_id` | 成功，收编 |
| 已由进群任务进入，且 `label_id IS NULL` | 不新建，保留 `origin=进群任务`，写本次 `label_id` / `import_batch_id` | 成功，收编 |
| 已由自建群进入，且 `label_id IS NULL` | 不新建，保留 `origin=自建群`，写本次 `label_id` / `import_batch_id` | 成功，收编 |
| 已经在导入链接里，`label_id IS NOT NULL` | 不新建，不迁移分组，不覆盖批次 | 失败，原因=重复 |
| 本批次内重复 | 不处理 | 失败，原因=重复 |
| 格式错误 | 不处理 | 失败，原因=格式错误 |

## 待实现事项

- 新增 Flyway：补 `group_link.origin`、`group_link.membership_state`。
- 新增 Flyway：创建 `group_link_preview`。
- 新增 Flyway：创建 `group_link_health`。
- 新增 Flyway：`group_link_import_batch.skipped_rows` 迁移为 `duplicate_rows`。
- 新增 Flyway：调整 `group_link_import_detail` 的结果字段，新增 `success_type`、`existing_origin`。
- 更新实体、Mapper、导入服务和 DbTest。
- 更新自动数据模型文档。
