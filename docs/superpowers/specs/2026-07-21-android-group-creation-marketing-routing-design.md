# Android 建群营销完整协议路由设计

## 1. 背景

Armada 通过 `account.protocol_id` 区分 Web/Baileys 与 Android Zhuan 账号。JSON 号导入后归属 `WEB`，六段号导入后归属 `ANDROID`；运行时以已经持久化的 `protocol_id` 为唯一协议事实，不重新解析导入文件格式。

当前建群营销已经在营销消息阶段构造 `ProtocolAccountRef`，并通过 `MessageSendPort` 按账号协议后端发送 Web 或 Android 消息。但建群前后的三个同步能力仍固定使用 Web HTTP executor：

- 联系人预保存；
- 创建群并加入初始成员；
- 发送前查询群成员数。

候选账号 SQL 没有限制协议类型，因此 Android 在线账号可以被选中，却会携带 Android 协议账号信息调用 Web master，导致建群失败。Android Zhuan 当前源码已经提供联系人添加、建群、群成员查询、群发言权限设置以及营销消息发送能力，本次需要在 Armada 防腐层完成完整接入。

## 2. 目标

1. 同一个账号分组可以同时包含 Web 和 Android 账号，每个建群营销执行项按当前账号的 `protocol_id` 选择协议后端。
2. Android 完整执行联系人预保存、创建群并加入初始成员、关闭普通成员发言、群成员数快照和营销消息发送。
3. Web 现有建群营销行为、请求契约、best-effort 语义和失败重试不得回退。
4. 换号后按替换账号的协议事实重新路由，允许 Web 与 Android 账号互相替换。
5. 业务 Worker 只依赖统一协议命令与 Port，不出现 `if (ANDROID)`，不拼接 Android URL 或解析 Android 原生响应。
6. 不新增数据库结构，不要求前端传协议类型，不根据账号文件格式在运行时猜测协议。

## 3. 非目标

- 不修改建群营销任务、执行项和营销回执状态机。
- 不新增 Android Kafka 建群命令；建群相关能力复用 Zhuan 现有同步 HTTP 接口。
- 不修改 Android Go 服务已有路由或响应结构。
- 不在本次扩展历史群拉人等明确限制为 Web 的业务范围。
- 不在本次部署或操作共享测试环境；真实联调必须另行确认目标环境和测试账号。

## 4. 方案选择

采用能力级协议路由：统一 Port 接收包含 `ProtocolAccountRef` 的命令，由 Routing Port 根据 `account.backend()` 选择 Web 或 Android backend。

不采用以下方案：

- Worker 内直接判断 Android：会把协议 URL、字段和响应语义泄漏到营销业务域。
- 按账号分组固定协议：无法安全支持混合分组和跨协议换号。
- 建群营销专用大网关：会把联系人、建群、群成员三个独立能力重新耦合成一个业务专用协议客户端。

## 5. 总体架构

```text
GroupCreationMarketingWorker
  -> ProtocolAccountRef(accountId, backend, protocolAccountId, wsPhone)
  -> ContactPort.save(ContactSaveCommand)
       -> RoutingContactPort
            -> Web contact backend
            -> Android contact backend
  -> GroupCreatePort.create(GroupCreateCommand)
       -> RoutingGroupCreatePort
            -> Web group-create backend
            -> Android group-create backend
  -> GroupMemberListPort.list(GroupMemberListQuery)
       -> RoutingGroupMemberListPort
            -> Web member-list backend
            -> Android member-list backend
  -> MessageSendPort.enqueue(MessageSendCommand)
       -> existing Web/Android message routing
```

`ProtocolAccountRef` 继续承载四项账号事实：Armada 账号 ID、协议后端、Web 协议账号 ID 和 Android `wsPhone`。路由只读取其中的 `backend`；Web backend 使用 `protocolAccountId`，Android backend 使用 `wsPhone`。

## 6. 统一命令与 Port

### 6.1 建群

新增 `GroupCreateCommand`：

- `ProtocolAccountRef account`
- `String subject`
- `List<String> participants`
- `boolean announceOnly`
- `String operationId`

`GroupCreatePort` 改为只接收 `GroupCreateCommand`，删除旧的字符串参数方法，不保留兼容重载。`GroupCreateBackend` 暴露 `backend()` 和 `create(command)`，`RoutingGroupCreatePort` 负责后端注册唯一性、缺失后端错误和实际分发。

现有 `HttpGroupCreateAdapter` 转为 Web backend，仍调用 `/v1/groups/create`，请求和响应保持不变。`GroupOperationServiceImpl` 同步改为从 `Account` 生成完整账号引用，因此直接建群接口也会按账号协议正确路由；这属于统一建群能力修正，不新增对外 API。

### 6.2 联系人保存

新增 `ContactSaveCommand`：

- `ProtocolAccountRef account`
- `String contact`
- `String name`
- `String operationId`

`ContactPort` 改为接收统一命令。现有 Web adapter 转为 Web backend；新增 Android backend。历史群拉人已经持有 `ProtocolAccountRef` 且显式拒绝非 Web 拉手，只需把现有 Web 账号引用传入新命令，业务范围不变。

Worker 仍按当前并发模型逐个提交联系人预保存。Android backend 每次向批量接口发送一个号码，避免把异步执行、失败采样和并发控制下沉到协议 adapter。

### 6.3 群成员读取

现有 `GroupParticipantPort` 同时承担成员读取和成员变更，但 Android 建群营销只需要只读快照。为保持能力边界，新增只读的 `GroupMemberListPort`、`GroupMemberListQuery` 和对应 backend；从 `GroupParticipantPort` 删除无人复用的 `listParticipants` 方法，保留 Web 成员变更能力。

`GroupMemberListQuery` 包含：

- `ProtocolAccountRef account`
- `String groupJid`
- `String operationId`

Web backend 复用现有成员查询 HTTP 契约；Android backend 调用 Zhuan 原生成员接口。Worker 改为依赖新的只读 Port。

## 7. Android 原生 HTTP 契约

Android 请求使用已经绑定 `ProtocolBackend.ANDROID` base URL、API key 和超时的 `ProtocolHttpExecutor`。

### 7.1 联系人预保存

```http
POST /ws/v1/contacts/add/{wsPhone}
Content-Type: application/json

{
  "Numbers": ["919000000002"]
}
```

`Code == 0` 表示同步联系人请求成功。业务失败通常仍为 HTTP 200，必须解码响应 envelope，不得只检查 HTTP 状态。

### 7.2 创建群

```http
POST /ws/v1/groups/create/{wsPhone}
Content-Type: application/json

{
  "subject": "活动群-1",
  "participants": ["919000000002@s.whatsapp.net"]
}
```

成功响应从 `Data.GroupId` 读取群 ID，并统一补全 `@g.us`。`Data.Participants` 中的 `phone` 和 `type` 转换为 Armada 的逐成员结果。

对请求成员逐项比对返回成员：

- 返回列表中存在该号码：`status=OK`；
- 返回列表中缺失该号码：`status=UNKNOWN`，并令 `partial=true`；
- Android 原始 `type` 保存到 `rawStatus`，便于排障。

响应成功但缺少合法 `GroupId` 时抛 `ANDROID_RESPONSE_UNRECOGNIZED`，不得返回空 JID 成功。

### 7.3 关闭普通成员发言

当 `announceOnly=true` 且建群已经成功时调用：

```http
POST /ws/v1/groups/settings/sendmessage/{wsPhone}
Content-Type: application/json

{
  "group_id": "120363000000000000@g.us",
  "state": false
}
```

Zhuan 的 `state=false` 表示只有管理员可以发送消息。该调用是 best effort：成功建群后，权限调用的 HTTP、协议或解析异常只写 warn 日志，不推翻 `GroupCreateResult`，不触发换号，不阻断营销消息。

### 7.4 群成员查询

```http
POST /ws/v1/groups/members/{wsPhone}
Content-Type: application/json

{
  "group_id": "120363000000000000@g.us"
}
```

从 `Data.Participants` 读取成员，兼容 `phone`、`phone_number`、`phoneNumber` 和 `jid` 身份字段，归一化为完整用户 JID。Worker 只取列表数量写入 `send_member_count`；查询失败沿用现有降级，计数字段保持空值并继续发送。

## 8. Android client 与响应映射

扩展 `AndroidNativeClient` 和 `HttpAndroidNativeClient`，新增：

- `saveContacts(wsPhone, numbers)`
- `createGroup(wsPhone, subject, participants)`
- `setGroupAnnouncement(wsPhone, groupJid, membersCanSend)`

现有 `members(wsPhone, groupJid)` 继续复用。

新增聚焦的响应 mapper：

- 建群 mapper：解析 `GroupId`、成员回执和 `partial`；
- 群成员 mapper：解析 `Participants` 并归一化成员身份。

Android 进群结果确认与建群营销成员快照应复用同一个成员节点解析规则，避免两套字段兼容列表逐渐漂移。

## 9. 数据流

1. 创建任务时，候选账号继续按在线、正常、未风控和未禁言条件筛选，不过滤协议。
2. Worker 抢占执行项后重新读取当前账号事实，构造一次 `ProtocolAccountRef`。
3. 联系人预保存命令、建群命令和成员查询命令都使用同一个账号引用。
4. 建群失败沿用现有换号服务；换号后重新读取替换账号的 `protocol_id`、`protocol_account_id` 和 `ws_phone`，下一轮自然路由到新后端。
5. 建群成功后写入统一 `groupJid`，营销消息命令继续使用现有 `MessageSendPort`。
6. Android 消息进入现有 Android message topic，由 Zhuan consumer 发送并回传结果；Web 消息保持 master topic 路径。

任务表和执行项表不新增 `protocol_backend` 快照。执行时的账号主表事实是路由来源，执行项已有账号 ID、手机号和协议账号 ID 快照继续用于展示与关联。

## 10. 错误语义

Android 原生 envelope 继续由 `AndroidResponseDecoder` 识别 `Code`、`Data`、`Msg` 和 Gin `error`。建群相关 adapter 将错误附加 `backend=ANDROID`、统一 operation 和业务 `operationId`。

| Android 现象 | Armada 协议错误 | 建群营销行为 |
|---|---|---|
| 账号不存在、不在线或离线 | `ACCOUNT_NOT_ONLINE` | `GROUP_CREATE_FAILED`，换号 |
| timeout / time out | `TIMEOUT` | `GROUP_CREATE_FAILED`，换号 |
| 消息包含 `rate-overlimit` 或原始码为 `429` | `ACCOUNT_REACHOUT_RESTRICTED` | 标记建群受限，换号 |
| Gin 参数校验失败 | `BAD_REQUEST` | `GROUP_CREATE_FAILED`，换号并保留诊断 |
| 成功响应缺少群 ID 或结构未知 | `ANDROID_RESPONSE_UNRECOGNIZED` | `GROUP_CREATE_FAILED`，换号 |
| 联系人保存失败 | 对应协议错误 | 记录摘要和 warn，继续建群 |
| 关闭发言失败 | 对应协议错误 | 只记 warn，保留建群成功 |
| 群成员查询失败 | 对应协议错误 | 人数快照为空，继续营销 |

日志只记录 Armada 账号 ID、operationId、群 JID、错误码和响应结构摘要，不记录营销正文、私钥、API key 或完整原始响应。

## 11. 事务与并发

- 账号检查和执行项抢占仍在短事务中完成。
- 联系人、建群、权限设置和群成员查询仍在数据库事务外执行。
- 联系人预保存继续使用现有固定大小守护线程池；Android adapter 不再创建额外线程。
- 建群成功后的营销命令与执行项状态写入仍保持当前本地事务原子性。
- Android 权限设置同步发起但失败被 adapter 吞并记录，确保“群已创建”不会因后置设置失败被当成可重试建群失败，从而重复创建群。

## 12. Spring 装配

`ProtocolConfiguration` 注册：

- Web/Android `GroupCreateBackend` 与 `RoutingGroupCreatePort`；
- Web/Android `ContactBackend` 与 `RoutingContactPort`；
- Web/Android `GroupMemberListBackend` 与 `RoutingGroupMemberListPort`；
- Android 建群和成员响应 mapper。

每个 Routing Port 在构造时拒绝同一 `ProtocolBackend` 的重复实现。调用未注册后端时抛 `UNSUPPORTED_BACKEND`，并携带 operation 上下文。

## 13. 兼容性与影响范围

- 前端 API、DTO 和页面不变。
- 数据库表、Mapper SQL 和 Flyway 不变。
- `PROTOCOL_ANDROID_BASE_URL`、API key 和超时沿用现有配置。
- Web 请求路径、body、错误映射和协议 master 路由不变。
- Android Go 仓库不产生代码变更；Armada adapter 严格适配其当前原生契约。
- 直接建群 Service 因统一 `GroupCreatePort` 改造而获得 Android 路由能力；对外响应结构不变。
- 历史群拉人仍在业务层拒绝 Android 拉手，不因 ContactPort 改造扩大范围。

## 14. 测试设计

所有生产代码遵循测试先红后绿。

### 14.1 路由单测

- Web 命令只到 Web backend；
- Android 命令只到 Android backend；
- 后端缺失抛 `UNSUPPORTED_BACKEND`；
- 同一协议重复注册在构造时失败；
- 换号后的账号引用决定新一轮后端，不沿用旧协议。

### 14.2 Android HTTP 契约测试

使用本地 mock HTTP server 验证：

- 四个接口的 method、path 和 JSON 字段完全匹配 Zhuan；
- path 使用纯数字 `wsPhone`，不使用 `protocolAccountId`；
- envelope `Code=0` 正确解析；
- HTTP 200 中的业务失败被识别；
- 空 body、缺少 Code、缺少 GroupId 和非法成员结构不会误判成功。

### 14.3 Android adapter 单测

- 建群成功返回规范群 JID；
- 成员完整时 `partial=false`；
- 成员缺失时生成 `UNKNOWN` 并令 `partial=true`；
- `announceOnly=true` 发起 `state=false` 设置；
- 关闭发言失败仍返回建群成功；
- 离线、限流、超时和未知响应映射到约定错误；
- 群成员多种身份字段正确归一化。

### 14.4 Worker 与业务回归

- Android 候选账号把同一 `ProtocolAccountRef` 传给联系人、建群、成员查询和营销发送；
- Android 建群成功进入 `MARKETING_SENDING`；
- 联系人或成员查询失败不阻断营销；
- 建群失败进入现有换号重试；
- Web 建群营销全部既有用例继续通过；
- 直接建群 Service 对 Web 和 Android 都生成正确命令；
- Spring context 对每个统一 Port 只装配一个 routing bean。

### 14.5 验证命令

先运行新增和受影响的定向测试，再运行：

```bash
cd armada-api
mvn test
```

本次不修改 SQL，无需 DbTest。若本地完整测试受外部依赖限制，必须报告未运行项及原因，不能声称通过。

## 15. 联调与发布

1. 发布前确认测试环境 Android 服务实际部署版本包含四个原生 HTTP 路由和现有 message consumer。
2. 确认 Armada 的 Android base URL、API key、HTTP 超时和 Android message topic 配置指向同一测试环境。
3. 使用专用在线六段号和最小料子创建一条建群营销任务。
4. 验证联系人预存、群创建、普通成员禁言、成员数快照、Android message outbox、Zhuan 消费及最终回执。
5. 再执行一个混合账号分组用例，确认每个执行项独立路由。

真实联调会创建 WhatsApp 群并发送消息，属于外部副作用；执行前必须再次确认目标环境、账号和料子。

本次无数据库迁移。回滚只需回退 Armada 应用版本；Android 服务和现有数据不需要回滚。

## 16. 已确认决策

- Android 建群营销必须与 Web 完整对齐。
- 采用能力级协议路由，不在 Worker 内写协议分支。
- 账号持久化 `protocol_id` 是唯一协议路由事实。
- Android 建群相关能力复用现有同步 HTTP 接口。
- 关闭普通成员发言保持 best-effort，失败不推翻建群成功。
- 不修改数据库、前端和 Android Go 服务。
