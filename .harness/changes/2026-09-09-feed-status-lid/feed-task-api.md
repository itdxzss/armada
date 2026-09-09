# 接口协议

> 本文由 `.harness/wiki/parse_endpoints.py` + `.harness/wiki/format_api.py` 从 armada controller 生成。
> 如接口签名或 Javadoc 变化,请重新运行生成脚本。

---

# 二、租户业务 API
> 租户侧业务接口;租户上下文由 armada 后端统一处理。

## Feed Task API（FeedTaskController）

### GET /api/feed-tasks

分页查询动态发布任务。

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| query | query | FeedTaskQuery | 否 | - | 对象字段平铺为查询参数 |

- 权限：暂无接口级权限注解
- 响应 `data`：`分页（FeedTaskVO）`

### POST /api/feed-tasks

创建动态发布任务。

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| request | query | FeedTaskFormDTO | 否 | - | 对象字段平铺为查询参数 |
| linkPreviewImage | part | MultipartFile | 否 | - | multipart 文件块 |
| principal | query | AuthPrincipal | 否 | - | 对象字段平铺为查询参数 |

- 权限：暂无接口级权限注解
- 响应 `data`：`FeedTaskVO`

### GET /api/feed-tasks/{id}

查询动态发布任务详情。

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| id | path | Long | 是 | - |  |

- 权限：暂无接口级权限注解
- 响应 `data`：`FeedTaskVO`

### PUT /api/feed-tasks/{id}

编辑未开始的动态发布任务。

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| id | path | Long | 是 | - |  |
| request | query | FeedTaskFormDTO | 否 | - | 对象字段平铺为查询参数 |
| linkPreviewImage | part | MultipartFile | 否 | - | multipart 文件块 |

- 权限：暂无接口级权限注解
- 响应 `data`：`FeedTaskVO`

### POST /api/feed-tasks/{id}/action

执行动作：start / pause / resume / stop。

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| id | path | Long | 是 | - |  |

- 权限：暂无接口级权限注解
- 请求体：`FeedTaskActionRequest`
- 响应 `data`：`FeedTaskVO`

### GET /api/feed-tasks/{id}/data

分页查询任务账号发布明细。

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| id | path | Long | 是 | - |  |
| accountPhone | query | String | 否 | - |  |
| page | query | Integer | 否 | - |  |
| pageSize | query | Integer | 否 | - |  |

- 权限：暂无接口级权限注解
- 响应 `data`：`分页（FeedTaskAccountVO）`

### POST /api/feed-tasks/{id}/data/{accountRowId}/audience/refresh

准备当前任务账号的候选受众，不重发已终态的消息。

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| id | path | Long | 是 | - |  |
| accountRowId | path | Long | 是 | - |  |

- 权限：暂无接口级权限注解
- 响应 `data`：`com.armada.account.contact.model.StatusAudienceView`
