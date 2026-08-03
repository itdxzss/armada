# 群组列表运营分组设计

- 日期：2026-08-03
- 状态：已确认，待实施计划
- 涉及仓库：`armada`（后端）、`wheel-saas-pure-web`（前端）
- 不涉及：`armada-protocol`

## 1. 背景与事实源

用户要求在 Armada 群组列表实现竞品的“批量分组”和“管理群组分组”能力。2026-08-03 对 `https://v2.freews.site/#/groups` 的已登录页面与其前端资源进行只读分析，确认竞品行为如下：

1. 群组列表可按单个 `groupTagId` 筛选。
2. 勾选群组后可调用批量分组，提交 `{ ids, groupTagId }`。
3. 批量分组支持“不绑定”，即清空群组的运营分组。
4. 分组管理是独立的分页字典，字段只有名称，支持新增、编辑、删除。
5. 竞品还允许在新建群、获取账号下群组和批量进群时指定分组，但这些入口不属于本次首期范围。

Armada 当前事实：

- `group_link` 是导入链接、进群任务、拉群任务、自建群和账号同步共用的群组池。
- `group_link.label_id` 明确表示“WS 链接分组”，当前主要服务导入链接管理与导入统计。
- 现有 `/api/group-links/migrate` 会修改 `label_id`，从而改变导入分组的当前归属统计。
- 删除 `group_link_label` 会级联软删除关联群链接和导入批次。
- 一期需求表的“群组列表”页尚未定义运营分组；“导入链接”页定义的是另一种 WS 链接导入分组。

事实源优先级：

1. 本次用户确认：一个群最多属于一个运营分组，也允许未分组。
2. `docs/business/requirements/一期需求.xlsx` 的“群组列表”“导入链接”工作表。
3. 当前 `group_link`、`group_link_label`、群组列表 API 和前端实现。
4. 竞品页面与前端资源，仅用于交互和能力参考，不覆盖 Armada 已确认的数据语义。

## 2. 目标与非目标

### 2.1 目标

1. 在群组列表按运营分组或“未分组”筛选。
2. 将一批群组绑定到同一个运营分组。
3. 将一批群组取消分组。
4. 在群组列表内新增、改名和删除运营分组。
5. 删除运营分组时保留群组，只把群组移入“未分组”。
6. 保持 WS 导入分组、导入批次和导入统计语义不变。
7. 所有数据操作满足 Armada 租户隔离、事务和 MyBatis SQL 下推规范。

### 2.2 非目标

- 不支持一个群绑定多个分组或标签。
- 不支持分组层级、颜色、自定义排序、容量上限和拖拽排序。
- 不在新建群、获取账号下群组、批量进群等入口增加分组选择。
- 不改变 `group_link_label`、导入批次或历史导入归属。
- 不调用协议层，不修改 WhatsApp 真实群资料。
- 不新增更细粒度的 RBAC 权限。

## 3. 方案比较与决策

### 3.1 方案 A：独立运营分组表与关联列（采用）

新增 `group_folder`，并在 `group_link` 增加可空的 `folder_id`。`label_id` 继续表示 WS 导入分组，`folder_id` 只表示群组列表运营分组。

优点：语义隔离、查询直接、批量更新简单、删除规则安全，未来其他群组入口也能复用 `folder_id`。代价是需要一张新表和一列新字段。

### 3.2 方案 B：复用 `group_link_label/label_id`（否决）

虽然已有 CRUD 和迁移接口，但运营改组会改变导入统计与归属；删除分组还会级联删除群链接和导入批次。修正这些行为会破坏现有导入语义，风险大于新增独立模型。

### 3.3 方案 C：独立关系表（否决）

通过 `group_folder_member` 维护群与分组关系，可为多标签预留空间。但需求已确认单分组，关系表会增加 JOIN、唯一约束和事务复杂度，属于首期过度设计。

## 4. 数据模型

### 4.1 新表 `group_folder`

计划使用迁移 `V090__group_folder.sql`：

```sql
CREATE TABLE group_folder (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id BIGINT NOT NULL COMMENT '租户ID',
    name VARCHAR(64) NOT NULL COMMENT '群组运营分组名称',
    created_at BIGINT NOT NULL COMMENT '创建时间(epoch毫秒)',
    updated_at BIGINT NOT NULL COMMENT '更新时间(epoch毫秒)',
    created_by BIGINT DEFAULT NULL COMMENT '创建人user_id',
    deleted_at BIGINT DEFAULT NULL COMMENT '软删除时间(epoch毫秒);NULL=未删',
    PRIMARY KEY (id),
    UNIQUE KEY uq_group_folder_name (tenant_id, name),
    KEY idx_group_folder_active (tenant_id, deleted_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='群组列表运营分组';
```

同租户名称使用普通唯一键。创建命中同名软删记录时复活旧记录并更新资料，沿用 Armada 现有分组字典的处理模式；复活不会恢复此前已解除的群组关系。

### 4.2 修改 `group_link`

```sql
ALTER TABLE group_link
    ADD COLUMN folder_id BIGINT DEFAULT NULL
      COMMENT '群组运营分组(关联group_folder.id);NULL=未分组' AFTER label_id,
    ADD KEY idx_group_link_folder (tenant_id, deleted_at, folder_id);
```

语义固定如下：

```text
group_link.label_id  -> WS 链接导入分组
group_link.folder_id -> 群组列表运营分组
```

不增加数据库物理外键，沿用当前项目的逻辑关联方式；Service 负责目标存在性校验，MyBatis 租户拦截器负责租户条件注入。

### 4.3 出入参扩展

- `GroupLink` 增加 `folderId`。
- `GroupLinkQuery` 增加 `folderId`、`withoutFolder`。
- `GroupLinkVoRow`、`GroupLinkVO` 增加 `folderId`、`folderName`。
- 群列表 SQL `LEFT JOIN group_folder f ON f.id = g.folder_id AND f.tenant_id = g.tenant_id AND f.deleted_at IS NULL`。
- 列表和总数查询复用同一运营分组筛选片段。

`folderId` 与 `withoutFolder=true` 同时出现时返回参数校验错误；两者都不传表示查询全部群组。

## 5. 后端组件与接口

### 5.1 组件边界

```text
GroupFolderController
  -> GroupFolderService
    -> GroupFolderMapper + GroupLinkMapper

GroupLinkController
  -> GroupLinkService
    -> GroupLinkMapper + GroupFolderMapper
```

`GroupFolderService` 负责字典 CRUD 与删除时解除群组关系；`GroupLinkService` 负责群列表筛选和批量设置运营分组。两种职责不互相穿透。

### 5.2 分组列表

```http
GET /api/group-folders?keyword=&page=1&pageSize=10
```

响应使用现有 `PageResult`，单行字段：

```json
{
  "id": 10,
  "name": "印度90人群",
  "groupCount": 36,
  "createdAt": 1785700000000,
  "updatedAt": 1785700000000
}
```

`groupCount` 统计当前未删除且 `folder_id` 指向该分组的群组数。

### 5.3 分组选项

```http
GET /api/group-folders/options
```

只返回当前租户全部活跃分组的 `id/name`，按名称和 ID 稳定排序。筛选器与批量分组弹窗使用此接口，不使用超大 `pageSize` 模拟全量查询。

### 5.4 新增和编辑

```http
POST /api/group-folders
Content-Type: application/json

{"name":"印度90人群"}
```

```http
PATCH /api/group-folders/{id}
Content-Type: application/json

{"name":"印度90人群-新"}
```

名称先 `trim`，长度限制为 1～64 个字符。同租户活跃分组不可重名；创建命中同名软删记录时复活该记录。编辑改名若命中另一个软删记录占用的唯一键，也按名称重复拒绝，不合并两个分组 ID。

### 5.5 删除分组

```http
POST /api/group-folders/batch-delete
Content-Type: application/json

{"ids":[10,11]}
```

响应：

```json
{
  "deletedFolderCount": 2,
  "ungroupedGroupCount": 36
}
```

事务顺序：

1. ID 去重并校验数量为 1～100。
2. 校验当前租户下所有分组都存在且未删除；任一缺失则整批失败。
3. 统计即将解除关系的活跃群组数。
4. 将这些群组的 `folder_id` 更新为 `NULL`，同时更新 `updated_at`。
5. 软删除分组。

删除分组绝不修改 `group_link.deleted_at`、`label_id` 或 `import_batch_id`。

### 5.6 批量设置或取消分组

```http
POST /api/group-links/batch-assign-folder
Content-Type: application/json

{"ids":[101,102,103],"folderId":10}
```

取消分组：

```json
{"ids":[101,102,103],"folderId":null}
```

规则：

1. ID 去重后数量必须为 1～100。
2. `folderId` 非空时，目标分组必须属于当前租户且未删除。
3. 请求中的所有群组都必须属于当前租户且未删除。
4. 任一群组不存在或已删除时整批失败，不允许静默部分成功。
5. 事务内只更新 `folder_id` 和 `updated_at`。
6. 返回实际更新数量；重复绑定到原分组允许成功，返回数据库实际影响数。

### 5.7 权限

首期沿用 `tenant:group_link:view`。`GroupFolderController` 和批量设置接口均使用该权限，与现有群组列表资料修改和删除能力保持一致。本次不扩展 RBAC 模型。

## 6. 前端设计

### 6.1 搜索区

群组列表搜索表单增加“群组分组”选择器：

- “全部分组”：不传 `folderId/withoutFolder`。
- “未分组”：传 `withoutFolder=true`。
- 具体分组：传 `folderId`。

分组选项加载失败时不阻断其他群组条件查询，并提供重新加载能力。

### 6.2 工具栏

群组列表工具栏按钮顺序：

```text
管理群组分组 | 批量分组 | 批量删除
```

“管理群组分组”始终可用；“批量分组”和“批量删除”在未勾选群组时禁用。

### 6.3 批量分组弹窗

新增 `BatchAssignFolderDialog.vue`：

- 展示已选择群组数量。
- 必选目标项，选项为全部活跃分组加“不绑定”。
- “不绑定”在提交时映射为 `folderId: null`。
- 成功后关闭弹窗、清空表格勾选并刷新列表。
- 失败时保留弹窗、目标分组和表格勾选，允许直接重试。

### 6.4 分组管理弹窗

新增 `GroupFolderManageDialog.vue`，内部列表字段：

```text
分组名称 | 群组数量 | 创建时间 | 操作
```

新建和编辑使用独立的小表单弹窗，只有“分组名称”字段。删除确认文案为：

```text
删除后，该分组下 N 个群组将进入未分组。确认删除吗？
```

新增、编辑、删除成功后刷新管理列表和全局分组选项。当前群列表正在筛选的分组被删除后，搜索条件切回“全部分组”并刷新群列表。

### 6.5 群名称展示

不新增独立的“群组分组”表格列。在群名称单元格内用小尺寸标签显示 `folderName`；未分组时不显示标签，避免进一步增加横向宽度。

### 6.6 前端文件边界

计划新增或修改：

- `src/api/group-folder.ts`：运营分组接口和类型。
- `src/api/group.ts`：群列表查询/返回字段和批量设置接口。
- `src/views/group/list/index.vue`：搜索区和弹窗编排。
- `src/views/group/list/components/GroupListTable.vue`：工具栏事件与分组标签。
- `src/views/group/list/components/BatchAssignFolderDialog.vue`：批量设置弹窗。
- `src/views/group/list/components/GroupFolderManageDialog.vue`：分组管理弹窗。
- `src/views/group/list/composables/useGroupListPage.ts`：列表筛选、选择和刷新状态。

保持页面文件小于项目规定的体积上限；CRUD 和批量弹窗不直接堆入 `index.vue`。

## 7. 数据流与状态变化

### 7.1 批量分组

```text
用户勾选群组
  -> 打开批量分组弹窗
  -> 加载分组选项
  -> 选择目标分组或不绑定
  -> POST /api/group-links/batch-assign-folder
  -> Service 校验租户内分组和全部群组
  -> 单次 SQL 批量更新 folder_id
  -> 前端清空选择并刷新列表
```

### 7.2 删除分组

```text
用户点击删除
  -> 前端显示 groupCount 风险提示
  -> POST /api/group-folders/batch-delete
  -> Service 事务内先解除群关系，再软删除分组
  -> 前端刷新分组管理列表、选项和群列表
```

## 8. 错误处理与并发

- 名称为空或过长：返回参数校验错误。
- 名称重复：提示“群组分组名称已存在”。
- 目标分组被并发删除：批量分组整体失败，前端刷新选项。
- 部分群组被并发删除：活跃群组计数与请求 ID 数不一致，整体失败并刷新列表。
- 跨租户 ID：按不存在处理，不返回其他租户实体信息。
- 网络失败：不清空用户当前选择，保留弹窗以便重试。
- 数据库唯一键竞争：转换为稳定的名称重复业务错误。
- 不做部分成功，也不触发协议层补偿流程。

## 9. 测试设计

### 9.1 后端

1. `GroupFolderMigrationDbTest`
   - `group_folder` 表、唯一键、索引和 `group_link.folder_id` 存在。
2. `GroupFolderMapperDbTest`
   - 分页、关键字、群组数量、选项排序、租户隔离和软删除。
3. `GroupFolderServiceImplTest`
   - 创建、同名拒绝、同名复活、改名、改名命中软删名称、删除参数校验。
4. `GroupFolderDeleteDbTest`
   - 删除分组只解除 `folder_id`，群链接和导入批次仍活跃；事务回滚与跨租户隔离。
5. `GroupLinkMapperDbTest`
   - `folderId`、`withoutFolder` 过滤以及 count/list 口径一致。
6. `GroupLinkServiceImplTest`
   - 批量绑定、取消绑定、目标分组缺失、部分群组缺失、上限和全有或全无。
7. Controller 权限测试
   - 无 `tenant:group_link:view` 权限时拒绝访问。

### 9.2 前端

1. `group-folder` API 请求方法、URL 和 camelCase 参数测试。
2. 群列表搜索状态到 `folderId/withoutFolder` 的转换测试。
3. 无选择时“批量分组”禁用。
4. “不绑定”提交 `folderId: null`。
5. 新增、改名、删除成功后刷新选项。
6. 当前筛选分组删除后重置查询。
7. 请求失败时保留弹窗和选择状态。
8. 运行相关 Vitest、`vue-tsc --noEmit` 和生产构建。

## 10. 部署与回滚

### 10.1 部署

1. 先部署包含 `V090` 和新接口的后端。
2. 验证迁移、分组选项接口和旧群组列表查询。
3. 再部署前端。
4. 冒烟验证新增分组、批量绑定、未分组筛选、取消绑定和删除分组。

迁移是纯新增表/列，无历史数据回填。旧前端忽略新增返回字段，因此后端先部署具备向后兼容性。

### 10.2 回滚

应用回滚时保留 `group_folder` 和 `folder_id`，旧版本不会读取这些结构，无需在故障窗口执行破坏性 DDL。若最终确认永久撤销功能，再单独评审删除新表、索引和列的清理迁移。

## 11. 验收标准

1. 不同租户可以创建同名运营分组，且数据完全隔离。
2. 同租户不能存在两个同名活跃运营分组。
3. 群组列表能筛选全部、未分组和指定分组，分页总数准确。
4. 一批群组可以一次绑定到目标分组，也可以一次取消分组。
5. 批量请求包含无效或跨租户 ID 时整体失败，无部分更新。
6. 删除运营分组后，所属群组仍在群组列表中并显示为未分组。
7. 删除、批量绑定和筛选均不改变 `label_id`、导入批次、导入统计或 WhatsApp 群状态。
8. 前后端相关测试、类型检查和构建全部通过。

## 12. 已确认口径与未确认项

已确认：

- 单分组模型；每个群最多属于一个运营分组。
- 允许未分组和批量取消分组。
- 使用独立 `group_folder + group_link.folder_id`，不复用 WS 导入分组。
- 首期只实现群组列表筛选、批量分组和分组管理。

未确认项：无。实施过程中若发现迁移编号已被并发分支占用，只调整 Flyway 版本号，不改变本设计的数据结构和业务语义。
