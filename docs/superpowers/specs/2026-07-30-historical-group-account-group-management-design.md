# 历史群账号组维度管理设计

## 背景

历史群管理当前以单个操作账号为入口：用户先选择账号组，再选择一个在线账号，页面只展示该账号历史基线中的群，详情、成员操作和拉群任务也始终携带这个账号 ID。这个模型无法覆盖同一账号组内多个账号共同拥有的历史群，也会让用户在群级操作前做一次没有业务价值的账号选择。

本次将页面和接口改成账号组下的群维度。账号只是后台执行实时同步和群操作时选择的资源，不再由前端指定。

## 已确认的产品口径

- 选择账号组后立即分页展示该组所有账号历史基线的群并集。
- 初次列表允许使用已保存的旧数据；缺失值显示 `--`，不展示“未校验”标签或独立分区。
- 点击“加载群列表”后，后台同步请求该账号组内所有在线正常账号的 WhatsApp 群列表，聚合、持久化并返回最新分页结果。
- 同步完成后，只展示该账号组内至少有一个群主或管理员账号的群；只有普通成员的群不展示。
- 群主或管理员暂时离线时群仍展示，操作置灰；账号重新上线后可继续操作。
- 详情和所有写操作都由后台从当前账号组中自动选择在线、仍在群内的群主或管理员，前端不传 `accountId`。
- 同一群的角色聚合按群主、管理员、普通成员的优先级取最高角色。
- 发言状态按“可发言优先”聚合；有任一可发言管理员就不因为其他账号不可发言而显示不可发言。
- 关联账号列只显示一个账号，悬停展示全部号码，不扩展每个账号的角色和在线状态明细。
- 暂不引入异步任务、进度条或分批后台刷新；采用同步请求聚合方案。

## 页面结构与列顺序

顶部仅保留账号组选择和“加载群列表”按钮，移除操作账号下拉框。账号组改变后重置页码并请求历史列表；加载按钮只要求已选账号组。

列表按群一行展示，固定顺序为：

1. 群名称
2. 关联账号
3. 群链接
4. 国家
5. 群组创建时间
6. 当前关系
7. 自身角色
8. 发言状态
9. 群人数
10. 操作
11. 完整群 JID

群链接由邀请码格式化为 `https://chat.whatsapp.com/{inviteCode}`。国家根据群创建者 WhatsApp 号码匹配启用国家中最长的电话区号。创建者或创建时间未知时显示 `--`。

## 两阶段数据流

### 阶段一：历史基线列表

`GET /api/historical-groups` 接收 `accountGroupId + page + pageSize`。数据库直接分页账号组内所有账号基线 JID 的去重并集，再批量补充：

- 已保存的群名称、邀请码、创建者和创建时间；
- 该群关联的账号号码；
- 已保存的当前关系、最高自身角色和发言状态；
- 当前是否存在可执行的在线群主/管理员。

历史基线决定初次列表范围。为避免 Java 全量加载后切页，去重和分页必须在 SQL 中完成；页内账号和群状态可以使用第二次批量 SQL 装配。

### 阶段二：实时加载

`POST /api/historical-groups/refresh` 只接收 `accountGroupId`。后台按账号组读取在线、正常且协议身份完整的账号，逐账号请求 WhatsApp 群列表。单账号失败不终止其他账号同步，响应保留可识别的失败摘要。

每个账号结果先写入现有账号组基线、群关系和群预览模型，然后按规范化 group JID 聚合：

- 关联账号去重；
- 群名称和成员数优先使用本次有效值；
- 群主优先于管理员，管理员优先于普通成员；
- 发言状态按可发言优先；
- 只保留至少有一个群主/管理员关系的群；
- 每个唯一群选择一个在线群主/管理员请求一次邀请链接；邀请链接失败只影响该字段，沿用旧值或空值；
- 创建者号码和创建时间写入群预览，供后续离线列表展示。

刷新完成后返回与列表相同的分页结构。刷新请求使用当前页参数时返回当前页；如果接口保持只传账号组，则前端刷新成功后再调用一次列表接口。实现时优先保持刷新 DTO 只含账号组，由页面复用标准分页查询。

## API 合同

### 历史群列表

```http
GET /api/historical-groups?accountGroupId=12&page=1&pageSize=20
```

返回 `PageResult<HistoricalGroupItemVO>`，每行至少包含：

- `groupJid`、`groupName`
- `accountPhones`
- `inviteLink`
- `countryName`、`countryIso2`、`countryFlag`
- `groupCreatedAt`
- `membershipState`、`selfRole`、`speechState`
- `memberCount`
- `operable`、`disabledReason`

### 实时加载

```http
POST /api/historical-groups/refresh
Content-Type: application/json

{"accountGroupId": 12}
```

成功表示已完成本次可用账号的同步和持久化，不表示每个账号、每个邀请链接都成功。无在线正常账号时返回明确业务错误，不清空旧历史群。

### 详情与成员操作

```http
GET /api/historical-groups/detail?accountGroupId=12&groupJid=...
POST /api/historical-groups/participants/promote
POST /api/historical-groups/participants/demote
POST /api/historical-groups/participants/remove
```

成员操作请求体统一为 `accountGroupId + groupJid + participantPhones`。每次请求在事务外的协议调用前重新选择在线群主/管理员；没有合格账号时返回可读错误，不回退到普通成员。

### 拉群任务

创建请求将 `operationAccountId` 替换为 `sourceAccountGroupId`，保留目标侧已有的 `pullerAccountGroupId`。后台在创建或启动执行时选择来源群的在线群主/管理员，并将实际账号 ID 写入执行记录用于审计和后续稳定执行。查询最近一次执行使用 `sourceAccountGroupId + groupJid`。

## Android 协议合同

Android `GetAllGroupService` 已能从 WhatsApp 群列表解析并返回 `creator` 和 `creation`；邀请链接仍是每群单独调用 `GetGroupCodeService`，不能假设群列表响应自带链接。

`announce` 已在协议解析层存在，但当前 JSON 被忽略。本次将它以 `announce_only` 暴露给后端，使后端能区分管理员可发言和普通成员禁言。`creation` 以 WhatsApp 返回的 Unix 秒时间戳传输，后端统一转换为 `Long` 保存，不做字符串时区推断。

## 数据模型

复用 `group_link_preview` 存储群级缓存，新增可空字段：

```sql
group_created_at BIGINT NULL
```

现有 `owner_phone` 保存创建者号码，`invite_code` 保存邀请码，`announce_only` 保存仅管理员发言。迁移使用下一可用 Flyway 版本并通过 `information_schema` 守卫重复执行场景。

不把实际邀请链接写入 `group_link.link_url`。该字段是现有群实体唯一身份的一部分；展示链接始终由 `group_link_preview.invite_code` 派生。

账号组、账号、历史基线和成员关系继续使用现有表。实时同步必须通过现有租户上下文写入，所有账号组和账号查询都验证同租户归属。

## 自动执行账号选择

新增历史群专用选择查询，条件包括：

- 指定租户与账号组；
- 指定规范化 group JID；
- `membership_status = IN_GROUP`；
- `is_admin = true`（群主同样满足可管理资格，并由角色字段优先）；
- 账号正常、在线且协议身份完整。

排序按群主优先、管理员次之，再按最近群关系确认时间和账号 ID 保证稳定。该查询不直接替换其他群管理模块已有的“在线成员即可”选择器，避免扩大行为变化。

## 失败与一致性策略

- 初次列表只读已保存数据，不发协议请求。
- 实时加载不因单账号或单群邀请链接失败回滚全部已成功同步的数据。
- 某账号成功返回完整群列表后，才能据此更新该账号的离群状态；失败账号不得把旧关系标记为离群。
- 同一群不同账号返回名称、创建者或创建时间不一致时，优先使用非空且本次最新观察值；创建者和创建时间已有值时不被空值覆盖。
- 刷新后普通成员群从列表范围排除，但历史基线不删除，以便账号后续升为管理员时重新出现。
- 离线管理员群的 `operable=false`，详情和写操作也必须在后端二次校验，不能只依赖前端置灰。

## 测试与验收

- Android：群列表 JSON 包含 `creator`、`creation`、`announce_only`，解析群公告模式的测试通过。
- 后端协议映射：Android/Web 群结果都能映射创建者、创建时间和发言模式。
- 数据库：迁移、预览 upsert、账号组历史 JID 去重分页及页内账号批量查询有真实 Mapper 或 SQL 形状测试。
- Service：初次列表不触发协议调用；刷新遍历在线账号、隔离失败、过滤普通成员群、每群只取一次邀请链接；角色和发言状态优先级正确。
- 路由：详情、成员操作和拉群创建不接受前端账号 ID，只选择当前账号组内在线管理员。
- 前端：选组即加载、刷新请求合同、分页、列顺序、账号 tooltip、离线置灰、详情和拉群参数均有测试。
- 完成后运行三项目相关测试、后端 Mapper XML 校验、前端 typecheck/build，以及 Android `gofmt`、`go vet ./...`、`go build ./...`、`go test ./...`。

## 非目标

- 不增加异步刷新任务、进度查询、WebSocket 推送或刷新进度条。
- 不展示每个关联账号的角色、在线状态或失败状态详情。
- 不通过普通成员账号执行任何群管理操作。
- 不自动定时刷新历史群。
- 不操作远程数据库、环境或部署。
