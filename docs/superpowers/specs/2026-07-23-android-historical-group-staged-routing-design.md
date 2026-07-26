# Android 历史群全链路分批协议路由设计

## 1. 背景

Armada 通过 `account.protocol_id` 区分 Web/Baileys 与 Android Zhuan 账号，运行时以持久化字段为唯一协议事实。历史群管理当前已经允许正常在线的 Android 账号出现在操作账号下拉框，并且 Android 首次完整群快照已经能够捕获 `account_group_baseline`。因此 Android 账号可以看到数据库中的历史群，但实时刷新、详情和后续操作仍固定调用 Web 协议适配器，导致状态加载失败。

本设计的最终目标是把 Android 历史群从只展示 baseline 扩展到完整闭环，但按可独立验收的批次逐步实现，避免一次修改读取、成员操作、拉手和营销四条链路。

## 2. 已确认范围

最终需要打通以下 Android 历史群能力：

1. 操作账号刷新历史群状态、查看详情、成员列表和邀请链接。
2. 操作账号提升管理员、取消管理员和踢出成员。
3. Android 拉手踩邀请链接进群、保存联系人和批量添加成员。
4. Android A 账号发送历史群营销模板并完成结果闭环。

当前先实施第一批，只完成代码和自动化测试：

- Android 操作账号刷新历史群状态；
- Android 操作账号查看单群详情与完整成员；
- Android 操作账号读取当前邀请链接；
- Web 行为和现有外部 API 保持不变。

当前不部署、不执行 SSH、不修改远程环境，也不使用真实 WhatsApp 账号或群进行验收。

## 3. 历史群口径

历史群范围继续以账号首次完整上线快照保存的 `account_group_baseline.baseline_group_jids` 为唯一事实，不因 Android 接入改变。

设：

- `baseline`：账号首次完整上线时已经存在的群，即历史群范围；
- `current`：账号当前仍然参加的全部群。

集合语义固定为：

- `baseline ∩ current`：目前仍在的历史群，需要读取实时摘要；
- `baseline - current`：已经退出的历史群，标记为已退出；
- `current - baseline`：账号导入后新加入的群，不进入历史群页面。

示例：

```text
baseline = A、B、C
current  = B、C、D

仍在的历史群 = B、C
已退出的历史群 = A
导入后新进群 = D，不进入历史群页面
```

历史群刷新不得覆盖 baseline，不得把导入后新群扩入历史群范围，也不得把协议查询失败误判为已经退出。

## 4. 当前事实与缺口

| 能力 | Web 当前状态 | Android 当前状态 | 缺口类型 |
|---|---|---|---|
| 首次 baseline 捕获 | 已支持 | 已通过 `account.groups_reported` 支持完整快照 | 无缺口 |
| baseline 静态列表 | 已支持 | 已支持，因为只读 Armada 数据库 | 无缺口 |
| 当前参与群轻量列表 | 已支持 | Zhuan 有群列表能力，Armada 尚未路由 | 接入缺口 |
| baseline 交集 metadata 摘要 | 已支持有界批量查询 | Zhuan 有单群 IQ 能力，缺稳定批量 HTTP 契约 | 契约与接入缺口 |
| 单群详情与成员 | 已支持 | Zhuan 已有成员接口，需补充稳定状态字段并映射 | 契约与接入缺口 |
| 邀请链接 | 已支持 | Zhuan 已有二维码/邀请链接接口，Armada 尚未路由 | 接入缺口 |
| 升降管理员、踢人 | Web 专用端口 | Zhuan 已有原生接口 | 后续第二批 |
| 拉手进群 | 已按协议路由 | Android join 已支持 | 无基础能力缺口 |
| 联系人保存 | 已按协议路由 | Android contact backend 已支持 | 无基础能力缺口 |
| 批量 ADD | Web 专用端口 | Zhuan 已有成员添加接口 | 后续第三批 |
| 历史营销消息与回执 | Web 可执行 | Android 命令与 execution/member correlation 已支持，但 Armada 仍主动拒绝 | 后续第四批 |

旧变更记录中“Android worker 不识别 historical correlation”的结论已经过期。当前 Go worker 已解析并回传 `historicalExecutionId`、`historicalMemberId` 和 `source=historical_group_pull`；后续营销批次应以当前代码为事实，不能继续沿用旧限制。

## 5. 方案选择

采用能力级协议路由：历史群业务层只处理 baseline、状态计算和权限门禁；统一协议 Port 根据 `ProtocolAccountRef.backend()` 选择 Web 或 Android backend。

```text
HistoricalGroupService
  -> 固定账号群列表/摘要 Port
       -> Routing Port
            -> Web backend -> armada-protocol
            -> Android backend -> Zhuan HTTP
  -> 固定账号群 metadata Port
       -> Routing Port
            -> Web backend
            -> Android backend
  -> 固定账号群邀请链接 Port
       -> Routing Port
            -> Web backend
            -> Android backend
```

协议实现差异只存在于 backend：

- Web 使用 `ProtocolAccountRef.protocolAccountId()` 请求 `armada-protocol`；
- Android 使用 `ProtocolAccountRef.wsPhone()` 请求 Zhuan；
- 路由依据只读取 `ProtocolAccountRef.backend()`；
- 历史群 Service、Controller 和前端 DTO 不出现 Android 分支。

### 5.1 不采用历史群专用 Android 大接口

不在 Zhuan 增加“历史群列表”或“历史群详情”等业务命名。历史群属于 Armada 业务概念，Android 只提供通用群列表、metadata 和邀请链接能力，避免以后普通群管理再建第二套协议接口。

### 5.2 不采用数据库快照代替手动刷新

现有 Android `account.groups_reported` 适合建立 baseline 和维护本地当前关系，但页面“刷新”要求本次请求的实时结果。直接读取 `account_group_membership` 会产生最终一致性延迟，也缺少实时角色、禁言状态和邀请链接，不能替代协议查询。

### 5.3 不在业务层判断协议类型

不在 `HistoricalGroupServiceImpl`、拉人 Worker 或营销 Service 中新增新的 `if (ANDROID)` 执行分支。业务层传统一账号引用，协议防腐层负责路由、请求格式和原始响应解析。

## 6. 分批实施

### 6.1 第一批：固定操作账号只读闭环

范围：

- 当前参与群轻量列表；
- baseline 交集 metadata 摘要；
- 单群完整 metadata 与成员；
- 单群当前邀请链接；
- Web/Android 相同业务事实产生相同页面模型。

不包含成员修改、拉手、ADD 和营销发送。

### 6.2 第二批：操作账号成员管理

把 `GroupParticipantPort` 改为接收包含 `ProtocolAccountRef` 的统一成员变更命令，增加 Web/Android backend 和 Routing Port，支持：

- `PROMOTE`：提升管理员；
- `DEMOTE`：取消管理员；
- `REMOVE`：踢出成员。

群主保护、本人保护、当前角色校验、逐成员部分成功和操作后回读继续由现有 Armada 业务层统一处理。

### 6.3 第三批：Android 拉手

在成员 ADD 已具备 Android backend 后：

- 拉手选号从 Web 专用查询恢复为协议无关的在线正常随机选号；
- `GroupJoinPort` 继续复用现有 Web/Android 路由；
- `ContactPort` 继续复用现有 Web/Android 路由；
- `GroupParticipantPort` 的 `ADD` 按拉手 backend 路由；
- Android 返回 `PENDING_APPROVAL` 时保持失败/等待确认语义，不在 Armada 猜测已经入群；
- 单个联系人或成员失败继续按现有逐成员规则记录。

### 6.4 第四批：Android A 账号营销

移除 `HistoricalGroupMarketingServiceImpl` 中过期的 Android 本地拒绝，统一调用现有 `MessageSendPort`。保持：

- Android 消息命令携带 `historicalExecutionId`、`historicalMemberId` 和 `source`；
- Go worker 先发布带关联字段的 `message.send_result_reported`，再提交输入 offset；
- Armada 独立历史群结果处理器按 execution/member 回写；
- Web 与 Android 均不做发送前在线、在群、管理员或发言权限预检；
- 单账号失败不阻断其他 A 账号，不自动重试。

## 7. 第一批详细设计

### 7.1 固定账号群列表与摘要端口

现有 `AccountParticipatingGroupPort` 的固定账号方法已经接收 `ProtocolAccountRef`，但实现固定注入 Web executor；另一个 `listBatch(List<String>)` 方法只有协议账号 ID，没有 backend，不能安全用于混合协议路由。

第一批将能力边界拆清：

- 固定账号实时读取 Port 只保留 `listCurrent(account)` 和 `summarize(account, groupJids, concurrency)`；
- backend 接口暴露 `backend()` 及同名读取能力；
- Routing Port 按 `account.backend()` 分发；
- 现有无 backend 的 Web 批量查群能力保留为独立 Web-only 端口，不进入固定账号路由。

Web backend 继续调用：

- `GET /v1/accounts/{accountId}/groups`；
- `POST /v1/accounts/{accountId}/groups/metadata-summaries`。

Android backend 使用 `wsPhone` 调用第 8 节的 Zhuan 通用接口，并映射到现有稳定模型：

- `AccountParticipatingGroupResult.Group`；
- `AccountGroupMetadataSummaryResult`。

### 7.2 固定账号 metadata 端口

固定账号 metadata 查询必须携带完整 `ProtocolAccountRef`，不能只传 `protocolAccountId`。第一批新增通用的 `FixedAccountGroupMetadataPort`、对应 backend 接口和 Routing Port，稳定结果继续使用 `GroupMetadataResult`，因此历史群业务层无需识别 Android 原始字段。

现有只接收字符串账号句柄的 `GroupMetadataPort` 继续服务普通群详情，不在第一批迁移。`HistoricalGroupProtocolPorts` 改为依赖新的固定账号端口。Web fixed-account backend 委托现有 Web metadata adapter，Android backend 调用 Zhuan。这样既不扩张普通群资料修改、权限设置和限时消息等能力，也不通过猜测 backend 或把 Android `wsPhone` 填进 Web `protocolAccountId` 来兼容。普通群详情后续若需要 Android 读取，应在其执行账号模型具备完整 `ProtocolAccountRef` 后单独迁移。

Android metadata mapper 输出：

- 规范 `groupJid`；
- 群名称；
- `announce`；
- 当前可读的 `memberAddMode`、`joinApprovalMode` 等字段；不可读字段保持 `null`；
- 完整成员列表；
- 成员的规范 JID、号码、管理员和群主标记。

稳定 metadata 结果同时携带协议能力 `participantMutationSupported`。第一批 Web backend 返回
`true`，Android backend 返回 `false`；详情页的成员修改门禁读取该能力，而不是依据协议枚举写
Android 分支。这样 Android 只读详情接通后不会因为“当前账号是管理员且邀请链接可用”而误开放
升管、降管和踢人按钮，第二批接入 Android 成员修改 backend 后再把该能力切为 `true`。

Android 当前未提供的能力字段不得伪造为 `false`。未知值必须保持 `null`，避免页面把“协议未返回”误解释为“功能关闭”。

### 7.3 固定账号邀请链接端口

`GroupInvitePort` 已接收 `ProtocolAccountRef`，第一批增加 backend 接口和 Routing Port：

- Web backend 保持现有 invite-code 请求；
- Android backend 调用 Zhuan 当前群二维码接口；
- Android `Data` 返回完整 `https://chat.whatsapp.com/{code}` 时，adapter 校验格式并映射为 `GroupInviteResult`；
- 空链接、非 WhatsApp 邀请链接或无法识别的响应不能返回成功。

历史群详情仍将 metadata 与邀请链接作为两个独立读取结果：metadata 成功但邀请链接失败时继续展示成员，只把链接及后续写操作标记为不可用。

### 7.4 历史群 Service 数据流

第一批不修改历史群对外 API。

刷新流程：

1. 根据 `accountId` 从账号域取得 `ProtocolAccountRef`，完成租户、软删除和协议身份校验。
2. 从数据库读取 baseline；空 baseline 直接返回空列表。
3. 通过固定账号群列表 Routing Port 读取 `current`。
4. 计算 `baseline ∩ current` 和 `baseline - current`。
5. 仅对交集调用摘要 Port，并发参数继续使用 8。
6. 按 baseline 原顺序组装页面结果；协议返回的非 baseline 群永不进入响应。

详情流程：

1. 先验证目标 JID 属于 baseline，再调用任何协议能力。
2. 使用同一个固定操作账号分别读取 metadata 和邀请链接。
3. 根据成员列表中的本人身份计算 `OWNER / ADMIN / MEMBER`。
4. 结合 `announceOnly` 与群异常状态计算现有发言状态。
5. 结合 `participantMutationSupported`、管理员身份和邀请链接计算成员操作门禁。
6. 返回与 Web 相同的 `HistoricalGroupDetailVO`。

### 7.5 Web/Android 等价语义

Web 和 Android 只允许在原始请求与响应映射上不同。以下业务规则必须共用现有实现：

- baseline 范围与原顺序；
- 当前仍在群、已退出、未校验和获取失败状态；
- 群主、管理员、普通成员分类；
- 普通群、仅管理员发言和异常发言状态；
- 成员本人、群主保护及后续操作门禁；
- 详情 metadata 与邀请链接的独立失败展示。

测试应以相同稳定协议结果驱动 Web/Android backend，断言最终历史群 VO 等价，而不是复制两套业务断言。

## 8. Android Zhuan 第一批 HTTP 契约

### 8.1 轻量当前群列表

复用现有接口并增加向后兼容的可选查询参数：

```http
GET /ws/v1/groups/list/{wsPhone}?includeParticipants=false
```

规则：

- 参数缺失时继续沿用原行为，避免影响未知调用方；
- Armada 固定传 `false`；
- `false` 时内部调用 `GetAllGroup(false)`，不读取所有群参与者；
- 成功零群必须返回非 `null` 空数组；
- 账号离线或 IQ 查询失败返回失败 envelope，不能返回成功空列表。

Android adapter 只读取群 JID 和群名称，不依赖列表接口中的参与者、角色或禁言字段。

### 8.2 metadata summaries

新增通用群 metadata 摘要接口：

```http
POST /ws/v1/groups/metadata-summaries/{wsPhone}
Content-Type: application/json

{
  "groupJids": ["120363000000000000@g.us"],
  "concurrency": 8
}
```

输入约束与 Web 对齐：

- JID 去空白、去重并保留首次顺序；
- 1 至 500 个群；
- `concurrency` 为 1 至 16，默认 8；
- 只允许当前在线的目标账号执行。

稳定逐群结果与 Web 字段语义一致：

```json
{
  "Code": 0,
  "Data": {
    "total": 1,
    "succeeded": 1,
    "failed": 0,
    "results": [
      {
        "groupJid": "120363000000000000@g.us",
        "success": true,
        "error": null,
        "subject": "历史群",
        "memberSize": 120,
        "selfRole": "ADMIN",
        "announceOnly": true,
        "stateAbnormal": false
      }
    ]
  },
  "Msg": ""
}
```

实现使用固定数量 worker 对单群 `GetGroupMember` IQ 做有界并发，逐项写入原输入索引：

- 单群失败生成 `success=false` 的结果，不终止整批；
- 顶层只在账号离线、参数无效或无法启动查询时失败；
- 本人未出现在成员列表时返回 `selfRole=null`、`stateAbnormal=true` 和稳定错误；
- `announceOnly` 无法确定时保持 `null` 并标记异常；
- 日志只记录账号业务标识、群数量、成功/失败数量、耗时和稳定错误分类，不记录邀请 code、消息正文、凭据或完整原始响应。

### 8.3 单群 metadata/成员

复用：

```http
POST /ws/v1/groups/members/{wsPhone}
Content-Type: application/json

{
  "group_id": "120363000000000000@g.us"
}
```

在现有成功 `Data` 中增量补充：

- `AnnounceOnly`：是否仅管理员可发言；
- `StateAbnormal`：群是否处于明确 suspended/terminated 等异常状态；
- 现有 `Subject`、`GroupId`、`Count` 和 `Participants` 保持不变。

成员继续至少返回 `phone` 和 `type`。Armada mapper 兼容 `phone`、`phone_number`、`phoneNumber` 和 `jid`，并将 `admin/superadmin` 映射成管理员/群主。

### 8.4 邀请链接

复用：

```http
POST /ws/v1/groups/qrcode/{wsPhone}
Content-Type: application/json

{
  "group_id": "120363000000000000@g.us"
}
```

当前成功 `Data` 已返回完整 WhatsApp 邀请链接，不修改响应形状。Armada Android backend 负责验证和映射。

## 9. 错误与降级

Android HTTP 业务失败通常仍返回 HTTP 200，所有 Android backend 必须先使用 `AndroidResponseDecoder` 校验 envelope，再读取 `Data`。

| 失败位置 | 历史群行为 |
|---|---|
| 当前群列表整体失败 | 全部 baseline 保留并标记获取失败，不得标记退出 |
| 单群摘要失败 | 只标记该群摘要异常，其余群继续成功 |
| metadata 失败 | 详情成员不可用，保留 baseline 群名及失败原因 |
| 邀请链接失败 | 成员仍可展示，链接不可用，后续写操作继续禁用 |
| 账号离线 | 映射稳定 `ACCOUNT_NOT_ONLINE`，不伪装空结果 |
| 协议超时 | 映射 `TIMEOUT`，本批不自动重试 |
| 响应结构无法识别 | 映射 `ANDROID_RESPONSE_UNRECOGNIZED` |
| backend 未注册 | Routing Port 抛 `UNSUPPORTED_BACKEND` |

协议错误可以携带 backend 和 operation 上下文，但不能把 API key、代理凭据、完整原始响应或邀请 code 写入日志或租户响应。

## 10. 数据、API 与仓库影响

### 10.1 数据库

不新增表、列或索引，不修改 baseline 和历史群执行状态机，不需要 Flyway。

### 10.2 前端

第一批不修改历史群对外 API、DTO 和页面调用链。Android 账号已经能出现在正常在线账号选择器中，接入后同一页面自然获得实时能力。

协议类型标签可作为后续独立 UX 优化，不是第一批功能前提。

### 10.3 `armada-protocol`

第一批不修改。Web backend 继续使用现有路由和响应契约。

### 10.4 `armada`

主要影响：

- 固定账号群列表/摘要 Port 与 Routing backend；
- 固定账号群 metadata Port 与 Routing backend；
- 群邀请链接 Routing backend；
- `AndroidNativeClient` 的群列表、摘要和邀请链接方法；
- Android 响应 mapper；
- 稳定 metadata 的群异常状态与成员修改能力标记；
- Spring 装配和对应测试；
- 历史群 Service 调用统一 Port 的聚焦调整。

### 10.5 `whatsapp-server-feature-android-zhuan`

主要影响：

- 群列表轻量查询参数；
- 通用 metadata summaries DTO、Service、Controller 和路由；
- 单群成员响应的增量状态字段；
- Swagger/路由契约测试和服务单测。

## 11. 第一批测试设计

所有生产代码按测试先红后绿实现。

### 11.1 Android Go 测试

- 群列表 `includeParticipants=false` 确实调用 `GetAllGroup(false)`；
- 参数缺失保持原默认行为；
- 合法零群返回非 `null` 空数组；
- metadata summaries 参数去重、顺序、数量和并发上限；
- 摘要并发始终有界；
- 单群失败不终止其他群，汇总计数正确；
- 本人角色映射 `OWNER / ADMIN / MEMBER`；
- `announceOnly` 和 `stateAbnormal` 正确输出；
- 成员详情新增字段保持现有响应兼容；
- 离线、IQ 错误和未知响应不伪装成功；
- 路由与 Swagger 契约包含新增接口和参数。

### 11.2 Armada Android HTTP 契约测试

使用本地 mock HTTP server 验证：

- path 中使用纯数字 `wsPhone`，不使用 Web `protocolAccountId`；
- 群列表固定携带轻量参数；
- summaries 请求字段、并发值和响应计数完整；
- 成员与角色多字段兼容映射；
- 邀请链接格式校验；
- HTTP 200 内 `Code != 0` 被识别为失败；
- 空 body、缺少 `Code`、缺少必要 `Data` 字段不会误判成功。

### 11.3 Routing Port 测试

- Web 账号只调用 Web backend；
- Android 账号只调用 Android backend；
- 同一 backend 重复注册在构造时失败；
- 未注册 backend 返回 `UNSUPPORTED_BACKEND`；
- Routing 不修改 backend 返回的稳定结果。

### 11.4 历史群业务等价测试

使用同一组稳定协议结果分别覆盖 Web 和 Android 路由，断言：

- `baseline ∩ current`、`baseline - current` 和非 baseline 新群处理一致；
- 管理员、成员、已退出和获取失败区段一致；
- 群人数、禁言状态和异常状态一致；
- 单群详情、本人角色、成员列表和邀请链接一致；
- Android 第一批详情明确禁用成员修改，且不会调用 Web 成员变更端口；
- 当前列表失败时均不误判退出；
- 单群摘要失败时均不影响其他群；
- Web 全部既有历史群测试保持通过。

### 11.5 验证命令

Android Go 至少执行：

先对实施计划列出的全部改动 Go 文件执行 `gofmt -w`，再运行：

```bash
go vet ./...
go build ./...
go test ./...
```

若全仓既有失败仍存在，必须另外运行本次受影响包的定向测试，并准确记录全仓失败与本次改动的关系，不能声称全仓通过。

Armada 至少执行新增与受影响的聚焦测试，再执行：

```bash
cd armada-api
mvn test
```

本批无数据库改动，不新增 DbTest。若完整测试进入本地未配置的外部依赖，必须记录实际阻断和已经通过的定向范围。

两仓均执行 `git diff --check`，并确认没有覆盖当前工作区内与本任务无关的在途修改。

## 12. 当前非目标

- 当前不实施第二至第四批代码；
- 不改变历史群 baseline 范围；
- 不将导入后新群加入历史群页面；
- 不新增前端协议选择参数；
- 不修改数据库；
- 不修改 `armada-protocol`；
- 不自动重试协议读取；
- 不考虑发布顺序、部署、SSH、远程环境或真实账号验收。

## 13. 已确认决策

- 最终需要完整打通 Android 历史群读取、成员管理、拉手和营销闭环。
- 按四个可独立验收的批次推进，第一批只做固定操作账号只读闭环。
- 历史群集合语义与 Web 完全一致，交集表示“目前仍在的历史群”。
- 采用能力级 Routing Port，不建设 Android 历史群专用大接口。
- Android 与 Web 共用 Armada 历史群业务逻辑，只在协议 backend 和响应 mapper 上不同。
- 当前阶段只完成代码和自动化测试，不考虑发布与远程验证。
