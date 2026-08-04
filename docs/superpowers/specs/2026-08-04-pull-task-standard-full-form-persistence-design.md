# 普通群链接拉群任务完整表单持久化设计

日期：2026-08-04

状态：已实现（本地聚焦回归通过；完整 DB 回归待具备本地 MySQL/Docker 后复跑）

范围：`pull_task.task_type=STANDARD`、`mode=NORMAL_LINK` 的创建页完整表单持久化
不含：红框“模板与内容/营销规则”模块、原型标记“后期”的字段、群资料协议执行

## 1. 目标

前端点击一次“保存”后，Armada 后端必须接收、校验并持久化普通群链接拉群任务页面的完整大表单。页面字段按聚合分别落入规范化表，但对用户仍然是一次保存操作，不能单独保存群信息设置，也不能静默忽略尚未接入的字段。

本阶段交付边界：

1. 完整创建请求、字段校验、事务保存和详情回读。
2. 群头像上传到 Armada 本地文件系统，任务只保存安全文件引用。
3. 群组分组作为链接来源，支持与自定义粘贴链接合并规划。
4. 任务删除和临时文件超时清理。
5. 不在本阶段把新增配置接入 WhatsApp 协议执行；调度接入另行设计和实施。

## 2. 已确认的业务决策

1. 新建 `pull_task_standard_group_setting`，一条普通群链接任务对应一条群资料设置。
2. 群资料设置是任务希望应用的配置，不是 WhatsApp 群当前实时资料。
3. 页面保存的是整个大表单；`groupSetting` 只是创建请求中的结构分组，不是独立保存接口。
4. 群头像保存到 Armada 服务器本地，不保存数据库 BLOB，不新建头像文件表。
5. 数据库只在 `pull_task_standard_group_setting.avatar_file_key` 保存一次头像文件引用。
6. 头像只允许 JPG/JPEG、PNG，最大 500KB（512000 字节）。
7. 头像采用两步 HTTP 流程，但页面仍只有一次保存动作：保存处理器先上传新头像，再提交完整创建请求。
8. 头像随任务保留；任务删除后清理。上传后未绑定任务的文件超过 24 小时清理。
9. 开启“料子文件名为群名”时，以对应 TXT 文件名为准，手工群名忽略并保存为 `NULL`。
10. `group_link_label` 是导入链接来源分类；`group_folder` 是群组列表运营分组。两者语义独立，不复用字段或表。
11. “群组分组”和自定义粘贴链接可同时使用；最终链接规范化、去重后冻结。
12. `station_count_per_call=0` 时站台分组可空；大于 0 时站台分组必填。
13. 普通拉群不再把完整配置重复写入 `pull_task.config_json`，也不把配置群名重复写入 `pull_task.group_name`。

## 3. 实施前事实与缺口（现已闭环）

### 3.1 已有正常链路

- `POST /api/pull-tasks/standard/draft/plan` 已把群链接与 TXT 一对一配对写入草稿执行行。
- `POST /api/pull-tasks/standard` 已提交草稿并写入 `pull_task_standard_setting`。
- `pull_task_group_execution` 已保存群链接、TXT 文件名、解析统计和冻结顺序。
- `pull_task_material_member` 已保存解析后的规范化料子号码。
- 调度器已实际读取 `pull_task_standard_setting` 中的拉人数量、间隔、并发和账号分组等字段。

### 3.2 实施前缺口

- 当前创建 DTO 只接收旧执行字段，页面新增字段不会提交给后端。
- 当前不存在完整群资料任务配置表。
- 当前前端只有头像文件名状态，没有上传真实头像文件。
- 前端已调用 `/api/group-folders`，后端只有未完成的迁移草案，没有 Controller、Service、Mapper。
- `pull_task_standard_setting.station_group_id/name` 当前非空，和原型的可选规则冲突。
- `pull_task.config_json` 当前重复保存标准任务 DTO，但标准任务详情不从该 JSON 读取。
- `pull_task.group_name` 是旧接口遗留列，标准任务当前没有有效写入方。

## 4. 方案比较

### 4.1 群资料保存方案

采用独立任务级群资料设置表：

- 优点：任务执行策略和群资料/权限配置边界清楚；不会污染实际群状态表；列数可控。
- 不采用扩充 `pull_task_standard_setting`：会把调度、资料、权限和文件引用混成宽表。
- 不采用 `group_link_preview`：该表是协议观察到的实际群资料缓存，不能保存任务期望配置。

### 4.2 群组分组方案

采用 `group_folder` 与 `group_link_label` 两个明确维度：

- `group_link_label`：导入批次的来源分类，回答“链接从哪里导入”。
- `group_folder`：实际群入口的运营分组，回答“群组列表归到哪里、任务从哪组取群”。
- 不复用同一张表：一个事实同时承担来源分类和运营归档会导致迁移、删除和统计语义冲突。

### 4.3 头像保存方案

采用租户目录下的本地文件 + 配置表单一文件 Key：

- 不保存绝对路径，避免部署目录变化和路径越权。
- 不新建文件元数据表；本业务每个任务最多一个头像，单独建表没有必要。
- 不复用营销模板图片表；营销模板图片是可复用素材并保存 BLOB，生命周期不同。

## 5. 字段归属

| 页面区域 | 页面字段 | 持久化位置 |
|---|---|---|
| 任务基础 | `taskName`、`remark` | `pull_task` |
| 任务基础 | `autoStart` | `pull_task_standard_setting` |
| 群链接配置 | `groupFolderId`、分组名快照 | `pull_task_standard_setting` |
| 链接与 TXT | 规范化链接、TXT 名称、配对顺序 | `pull_task_group_execution` |
| TXT 料子 | 规范化号码、A/a 管理标识 | `pull_task_material_member` |
| 执行策略 | `pullerSyncMode`、`materialAdminTiming`、`clearExistingMembers` | `pull_task_standard_setting` |
| 拉人参数 | 人数范围、站台数、间隔、拉手数、并发数 | `pull_task_standard_setting` |
| 账号分组 | 管理、拉手、站台分组及名称快照 | `pull_task_standard_setting` |
| 完成归档 | 管理、拉手完成分组及名称快照 | `pull_task_standard_setting` |
| 群信息设置 | 设置顺序、群名、头像、描述和权限 | `pull_task_standard_group_setting` |

## 6. 数据模型

### 6.1 扩充 `pull_task_standard_setting`

在现有冻结执行配置表中新增：

| 字段 | 类型 | 空值/默认 | 含义 |
|---|---|---|---|
| `source_group_folder_id` | BIGINT | NULL | 创建页选择的群组运营分组 ID |
| `source_group_folder_name` | VARCHAR(100) | NULL | 创建时分组名称快照 |
| `puller_sync_mode` | TINYINT | NOT NULL DEFAULT 1 | 1=单个，2=批量 |
| `is_clear_existing_members` | TINYINT(1) | NOT NULL DEFAULT 0 | 是否先清空群原成员 |
| `manager_finish_group_id` | BIGINT | NULL | 任务完成后管理员账号移入的账号分组 |
| `manager_finish_group_name` | VARCHAR(100) | NULL | 管理完成分组名称快照 |
| `puller_finish_group_id` | BIGINT | NULL | 任务完成后拉手账号移入的账号分组 |
| `puller_finish_group_name` | VARCHAR(100) | NULL | 拉手完成分组名称快照 |

同时把以下现有列改为可空：

- `station_group_id BIGINT NULL`
- `station_group_name VARCHAR(100) NULL`

规则：

- `station_count_per_call=0`：两列允许为 `NULL`。
- `station_count_per_call>0`：两列必须有值，Service 校验对应账号分组存在且属于当前租户。
- 完成归档分组 ID 和名称必须同时为空或同时有值。
- 所有名称均由后端按 ID 查询后生成快照，不信任前端传名称。

扩充后该表仍是同一个“普通任务冻结执行策略”聚合，没有引入第三张同义配置表。

### 6.2 新建 `pull_task_standard_group_setting`

一条普通任务一行：

| 字段 | 类型 | 空值/默认 | 含义 |
|---|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | 主键 | 群资料设置主键 |
| `tenant_id` | BIGINT | NOT NULL | 租户 ID |
| `task_id` | BIGINT | NOT NULL | `pull_task.id` |
| `setting_timing` | TINYINT | NOT NULL DEFAULT 2 | 1=拉人前，2=拉完后 |
| `group_name` | VARCHAR(128) | NULL | 手工群名称；使用 TXT 文件名时为 NULL |
| `is_material_filename_as_group_name` | TINYINT(1) | NOT NULL DEFAULT 0 | 是否使用对应 TXT 文件名作为群名 |
| `avatar_file_key` | VARCHAR(512) | NULL | 当前租户头像目录内的安全相对文件 Key |
| `group_description` | VARCHAR(1024) | NULL | 群描述 |
| `is_auto_unmute_after_task` | TINYINT(1) | NOT NULL DEFAULT 0 | 任务完成后是否自动解除禁言 |
| `is_auto_close_invite_after_task` | TINYINT(1) | NOT NULL DEFAULT 0 | 任务完成后是否关闭拉人权限 |
| `edit_permission_mode` | TINYINT | NOT NULL DEFAULT 0 | 0=不操作，1=允许，2=不允许 |
| `mute_mode` | TINYINT | NOT NULL DEFAULT 0 | 0=不操作，1=禁言，2=不禁言 |
| `link_permission_mode` | TINYINT | NOT NULL DEFAULT 2 | 1=所有人，2=仅管理员 |
| `disappearing_message_mode` | TINYINT | NOT NULL DEFAULT 0 | 0=不操作，1=24小时，2=7天，3=90天，4=关闭 |
| `created_at` | BIGINT | NOT NULL | 创建时间，epoch 毫秒 |
| `updated_at` | BIGINT | NOT NULL | 更新时间，epoch 毫秒 |

索引：

- `PRIMARY KEY (id)`
- `UNIQUE KEY uq_pull_task_standard_group_setting_task (tenant_id, task_id)`
- `UNIQUE KEY uq_pull_task_standard_group_setting_avatar (tenant_id, avatar_file_key)`

MySQL 唯一索引允许多行 `NULL`，因此无头像任务不冲突；非空头像文件不能被两个任务共同绑定，避免删除一个任务后破坏另一个任务的头像。

不增加 `group_jid`、`group_link_id`。这张表是父任务级模板，创建时实际群可能尚未解析出 JID；执行结果继续属于执行行和实际群聚合。

### 6.3 `group_folder` 与群入口

`group_folder` 是租户级群运营分组，最小字段：

- `id`
- `tenant_id`
- `name`
- `created_at`
- `updated_at`
- `deleted_at`

`group_link.folder_id` 表达当前运营归属；一个群入口同一时刻最多属于一个运营分组。`group_link.label_id` 继续只表达导入来源分类。

群组分组删除采用软删除，已有 `group_link.folder_id` 置空而不是删除群入口。已经冻结的拉群任务仍保留 `source_group_folder_id/name` 快照和执行行，不受后续分组移动或删除影响。

### 6.4 避免重复事实

`pull_task` 的遗留列按以下规则处理：

- `config_json`：旧 `OLD_LINK/CREATE_NEW` 接口继续使用；新 `STANDARD/NORMAL_LINK` 草稿和提交均保持 `{}`，所有新配置从规范化表读取。
- `group_name`：旧接口兼容保留；新标准任务不写。标准任务列表需要群名时通过群资料设置表读取。
- 不把 `avatar_file_key` 写到 `pull_task`、`pull_task_standard_setting` 或 `group_link_preview`。
- `group_link_preview.avatar_url` 继续表示协议同步到的实际 WhatsApp 群头像，不表示任务上传文件。

本次不直接 DROP 遗留列，因为旧接口仍有真实读写方；通过模式边界停止新标准任务重复写入。

## 7. API 契约

JSON 字段统一使用 camelCase。

### 7.1 草稿规划

继续使用：

```http
POST /api/pull-tasks/standard/draft/plan
Content-Type: multipart/form-data
```

参数：

- `groupFolderId`：可空，群组运营分组 ID。
- `linksText`：可空，自定义粘贴链接，一行一个。
- `files`：本次新增 TXT 文件。

至少要有分组链接或自定义链接之一。后端加载所选分组中的当前有效链接，与 `linksText` 合并后按规范化 URL 去重，再复用现有不放回随机匹配逻辑与 TXT 配对。

“有效群”计数和规划使用同一口径：群入口未删除、属于目标 folder、存在非空邀请链接、健康状态为可用且未封禁。规划完成后实际链接已经冻结，后续 folder 变化不修改草稿或正式任务。

### 7.2 头像上传

```http
POST /api/pull-tasks/standard/group-avatars
Content-Type: multipart/form-data
```

参数：`file`。

返回：

```json
{
  "avatarFileKey": "8f21c7a9d6b14f7ca7a5.png",
  "originalFileName": "头像.png",
  "previewUrl": "/api/pull-tasks/standard/group-avatars/8f21c7a9d6b14f7ca7a5.png"
}
```

`originalFileName` 只用于本次前端展示，不落数据库。新建任务详情通过 `avatarPreviewUrl` 展示已经绑定的头像。

读取和删除临时头像：

```http
GET    /api/pull-tasks/standard/group-avatars/{avatarFileKey}
DELETE /api/pull-tasks/standard/group-avatars/{avatarFileKey}
```

读取受租户和拉群任务查看权限保护。DELETE 只能删除当前租户且尚未被有效任务绑定的头像。

### 7.3 完整表单创建

继续使用一个创建接口：

```http
POST /api/pull-tasks/standard
Content-Type: application/json
```

请求示例：

```json
{
  "draftTaskId": 1001,
  "version": 1,
  "taskName": "拉群任务",
  "remark": null,
  "autoStart": 1,
  "groupFolderId": 21,
  "pullerSyncMode": "SINGLE",
  "materialAdminTiming": 2,
  "clearExistingMembers": false,
  "pullCountMin": 50,
  "pullCountMax": 50,
  "pullIntervalSeconds": 6,
  "pullerCountPerGroup": 2,
  "stationCountPerCall": 0,
  "concurrentGroupCount": 1,
  "managerGroupId": 101,
  "pullerGroupId": 102,
  "stationGroupId": null,
  "managerFinishGroupId": 103,
  "pullerFinishGroupId": 104,
  "groupSetting": {
    "settingTiming": "AFTER_PULL",
    "groupName": null,
    "useMaterialFileNameAsGroupName": true,
    "avatarFileKey": "8f21c7a9d6b14f7ca7a5.png",
    "groupDescription": "群描述",
    "autoCloseMuteAfterTask": false,
    "autoCloseInviteAfterTask": false,
    "editPermission": "UNCHANGED",
    "muteMode": "UNCHANGED",
    "linkPermission": "ADMIN_ONLY",
    "disappearingMessage": "UNCHANGED"
  }
}
```

`groupSetting` 是完整请求的一部分且必填。后端不提供单独的群资料保存接口。

前端保存处理器：

1. 校验本地完整表单。
2. 如果选择了新头像，先调用头像上传接口。
3. 把返回的 `avatarFileKey` 放入 `groupSetting`。
4. 调用一次完整表单创建接口。
5. 头像上传失败则不提交任务；任务提交失败则保留临时头像供原页面重试。

### 7.4 详情回读

继续使用：

```http
GET /api/pull-tasks/standard/{taskId}
```

响应在现有任务事实和执行摘要基础上增加：

- `standardSetting`：执行策略、拉人参数、源群组分组、账号分组和完成归档配置。
- `groupSetting`：群资料设置的全部 API 枚举值、`avatarFileKey` 和 `avatarPreviewUrl`。

详情只从规范化表组装，不从 `config_json` 恢复标准任务配置。

## 8. 校验规则

### 8.1 完整表单

- `groupSetting` 缺失时拒绝，不使用静默默认值。
- 所有枚举必须是已定义字符串；Java 使用 enum 映射数据库 `TINYINT`，禁止魔法值散落。
- `groupName` 去首尾空格后最长 128；空串归一为 NULL。
- `groupDescription` 去首尾空格后最长 1024；空串归一为 NULL。
- `useMaterialFileNameAsGroupName=true` 时强制保存 `group_name=NULL`。
- `managerGroupId`、`pullerGroupId` 必填且属于当前租户。
- `stationCountPerCall>0` 时 `stationGroupId` 必填；为 0 时允许 NULL。
- 原型标记“后期”的 `pullerRiskMinutes` 不进入创建/回读合同；既有执行列由服务端暂存默认值 0。
- 完成归档分组可空；非空时必须存在且属于当前租户。
- `groupFolderId` 可空；非空时必须存在且属于当前租户。
- 最终草稿必须至少有一条冻结执行行。
- 新字段不允许被 Jackson 静默忽略；合同外字段返回明确校验错误。

### 8.2 头像

- 非空。
- 文件长度 `<=512000` 字节，以实际读取字节数复核。
- 扩展名只允许 `.jpg`、`.jpeg`、`.png`，忽略大小写。
- MIME 只允许 `image/jpeg`、`image/png`。
- 检查真实文件签名：JPEG `FF D8 FF`；PNG 标准八字节签名。
- 扩展名、MIME 与真实签名必须一致。
- 文件 Key 只能是后端生成的随机 basename 加合法扩展名，不接受斜杠、反斜杠、绝对路径或 `..`。
- 创建时文件必须存在于当前租户目录，且未被其他任务绑定。
- 跨租户 ID 或 Key 对调用方统一表现为不存在，避免泄露其他租户资源。

## 9. 事务、幂等与失败行为

创建任务的数据库事务顺序：

1. 校验完整 DTO、草稿归属、状态、版本和冻结执行行。
2. 查询并冻结 group folder、账号分组和完成分组名称快照。
3. 校验头像 Key 属于当前租户且未被其他任务绑定。
4. 插入 `pull_task_standard_setting`。
5. 插入 `pull_task_standard_group_setting`。
6. 复用或登记群入口并回填执行行。
7. 冻结执行行。
8. 原子推进 `pull_task DRAFT -> WAIT_START`，写任务名、备注和汇总，不写完整 `config_json`。
9. 数据库提交成功后才允许自动启动。

任一步失败，数据库全部回滚。头像文件不参与数据库事务：创建失败时仍作为临时文件保留，用户可用同一 Key 重试；24 小时未绑定后清理。

重复提交处理：

- 同一草稿已经处于 `WAIT_START` 或 `EXECUTING` 时返回既有任务。
- 不再次插入两张设置表，不重新绑定头像，不重新随机配对。
- 相同任务、相同版本的并发提交只允许一个事务推进状态；另一个走幂等返回或冲突复查。

## 10. 头像文件系统与生命周期

配置根目录：

```text
/app/data/pull-task-avatars
```

实际路径：

```text
/app/data/pull-task-avatars/{tenantId}/{avatarFileKey}
```

数据库只保存随机文件名形式的 `avatar_file_key`，不包含根目录和租户目录。路径解析必须 `normalize()` 后验证仍位于当前租户根目录内。

写入先落同目录临时文件，再原子移动为最终文件，避免读取到半文件。不得使用用户原始文件名作为物理文件名。

生命周期：

- 前端上传后清除选择：调用 DELETE 删除未绑定文件。
- 上传后关闭页面：根据文件最后修改时间，超过 24 小时且没有有效任务引用时删除。
- 创建成功：由唯一索引和任务行形成绑定。
- 任务批量软删除：事务提交后立即尝试删除对应文件。
- 立即删除失败：记录脱敏错误；定时清理扫描到父任务已删除后重试。
- 清理器判断“已绑定”时必须关联 `pull_task.deleted_at IS NULL`，不能因为子设置行仍在就永久保留文件。

## 11. 群组分组接口依赖

当前前端已有以下合同，但后端缺少实现：

- `GET /api/group-folders`
- `GET /api/group-folders/options`
- `POST /api/group-folders`
- `PATCH /api/group-folders/{id}`
- `POST /api/group-folders/batch-delete`

完整表单接入至少依赖列表/选项查询和 ID 校验；为了让原型的“管理群组分组”和批量迁移行为闭环，本切片一并实现上述 CRUD，并让 `group_link.folder_id` 的迁移/删除遵循第 6.3 节规则。

接口和页面文案必须明确：

- `/api/group-link-labels`：链接来源分类。
- `/api/group-folders`：群组分组。

## 12. 权限与租户隔离

- 头像上传使用 `tenant:pull_task:create`。
- 头像读取允许 `tenant:pull_task:view`。
- 删除未绑定头像使用 `tenant:pull_task:create`；任务删除继续使用 `tenant:pull_task:delete`。
- group folder 查询允许群组列表查看或拉群任务查看；写操作使用群组管理对应权限，不以页面隐藏代替后端授权。
- 所有新表均带 `tenant_id`，生产查询接受租户拦截器约束。
- Service 对 group folder、账号分组、头像 Key 做当前租户存在性校验，不信任请求中的名称或路径。
- 日志不输出头像字节、完整本地路径、TXT 号码或未脱敏群链接。

## 13. 错误合同

- `VALIDATION`：字段缺失、枚举非法、数量关系非法、头像格式或大小非法。
- `NOT_FOUND`：当前租户下 group folder、账号分组、头像或任务不存在。
- `CONFLICT`：草稿版本过期、链接占用冲突、头像已绑定其他任务。
- 文件系统写入失败：返回明确的头像保存失败，不继续创建任务。
- 数据库创建失败：返回原业务错误，已上传头像保持临时状态供重试。
- 不允许“后端暂未接入所以忽略字段”的成功响应。

## 14. 测试与验收

### 14.1 数据库与 Mapper

- Flyway 新表、列、注释、唯一索引和站台列可空性测试。
- 使用 H2 MySQL 模式加载真实 MyBatis 配置和 Mapper XML。
- 两张设置表插入、按任务读取、租户隔离和头像唯一绑定测试。
- group folder CRUD、软删除、群入口解除分组和有效群统计测试。
- 标准任务不再更新 `pull_task.group_name`，`config_json` 保持 `{}`。

### 14.2 Service

- 完整大表单全部字段保存后原值回读。
- API 字符串 enum 与数据库 TINYINT 双向映射。
- 使用 TXT 文件名时手工群名归一为 NULL。
- `station_count_per_call=0` 允许无站台分组；大于 0 时拒绝缺失分组。
- 可选完成归档分组和必填管理/拉手分组校验。
- group folder 与粘贴链接合并、规范化去重、冻结后不受分组变化影响。
- 跨租户 folder、账号组、头像 Key 拒绝。
- 任一写入失败后三张任务配置表不留半成品。
- 重复提交不产生第二条设置记录。

### 14.3 头像

- 合法 JPG、JPEG、PNG 且不超过 512000 字节上传成功。
- 空文件、超限、GIF/WebP、伪造后缀、错误 MIME、签名不匹配被拒绝。
- 随机文件 Key、路径归一化和越权读取测试。
- 删除未绑定文件、删除任务文件、24 小时孤儿文件清理和删除失败重试测试。
- 同一个非空头像 Key 不能绑定两个任务。

### 14.4 API 与前端

- 前端点击一次保存：需要时先上传头像，再提交完整表单。
- 头像上传失败不发创建请求。
- 创建失败保留表单和头像 Key，可直接重试。
- 创建请求包含所有当前非“后期”、非营销模块字段。
- 任务详情返回 `standardSetting` 和 `groupSetting`，前端可完整回显。
- 删除前端“新增配置待后端接入”警告，不能再显示已接入字段未保存。
- 前端类型检查、拉群任务相关测试、ESLint、Prettier 和构建通过。

## 15. 迁移与回滚

- 数据库变更只走新的 Flyway 迁移；实施前重新检查当前分支 V090-V094 的在途文件，分配当时下一个可用版本，禁止撞号或覆盖其他会话文件。
- 新增 `pull_task_standard_group_setting`，扩充 `pull_task_standard_setting`，并完成 `group_folder/group_link.folder_id` 的正式迁移收口。
- 更新 Mapper H2 schema、Flyway 兼容测试和自动数据模型文档。
- change 目录提供 `db-migrations.sql`、`rollback.sql` 和 `summary.md`。
- 回滚先停用新前端字段和头像入口，再删除本次新增设置表/列；头像目录中的文件按租户保留到确认无回滚读取需求后再清理。
- 不在本次回滚中删除旧 `pull_task.config_json` 或 `pull_task.group_name`，避免影响旧接口。

## 16. 非目标

- 不实现群名称、头像、描述、禁言、链接权限和限时消息的 WhatsApp 协议调用。
- 不实现红框“模板与内容/营销规则”模块。
- 不实现原型标记“后期”的审核模式、次管理、退群方式、最低拉人标准等字段。
- 不新建头像文件表、第二套群入口表或第二套群组列表主表。
- 不修改真实数据库、远程环境或部署配置；这些动作须在实施完成后另行确认目标环境。

## 17. 完成标准

满足以下条件才算本阶段完成：

1. 页面完整大表单的全部本期字段都有唯一、明确的持久化位置。
2. 后端创建接口接收并校验全部字段，不静默忽略。
3. 数据库事务保存和详情回读一致。
4. 头像安全上传、预览、绑定和清理闭环。
5. group folder 与 link label 语义和 API 完全分离。
6. 普通标准任务不再通过 JSON 或主表群名重复保存新配置。
7. 相关数据库、Service、接口和前端测试均有真实通过输出。
