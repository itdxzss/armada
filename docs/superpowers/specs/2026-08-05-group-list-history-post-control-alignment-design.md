# 群组列表历史群与上控后群对齐设计

## 背景

当前租户端同时存在两套相邻但不统一的群数据入口：

- `/api/group-links` 面向统一群组池，承载导入链接、任务群、自建群和账号同步发现的群。
- `/api/historical-groups` 按账号组的首次 baseline JSON 聚合历史群，并在打开详情时实时读取 metadata。

账号首次上线时会拍摄 `account_group_baseline`。baseline 自身只固化群 JID、当时可取得的群名和数量；同一份 `account.groups_reported` 还会刷新统一群组池和账号群关系。当前 Web/Baileys 实时群回报只实际携带 JID 和群名，Android 当前群响应可携带更多字段，因此存量历史群的群人数、创建者、建群时间、邀请链接和完整成员经常不完整。

新群组列表需要对齐 `/Users/daishuaishuai/IdeaProjects/前端文件8-3/index.html` 中的“历史群组筛选”和“群组列表”。原型中的批量进群、新建群、批量设置等未来按钮不在本次范围。

## 已确认产品口径

- 列表以租户级群 JID 聚合，一个群只展示一行，不按账号群关系重复展示。
- “历史群”表示该 JID 曾出现在租户任一账号首次上线 baseline 中。
- “上控后群”表示某个已拍 baseline 的账号在 baseline 之后新增观察到该群。
- 两种属性允许重叠，同一个群可以同时显示“历史群”和“上控后群”。
- 两个标签都是固化事实，只允许从 `0` 变为 `1`；账号退群、被踢或后续快照缺失都不清除标签。
- 历史群详情需要后台异步补全，包括协议当前可读取的群 metadata、邀请链接、管理员和完整成员快照。
- 历史筛选保留大洲、国家、群龄和成员数；大洲字段不得移除。
- 建群时间只接受 WhatsApp 协议返回的 `creation`。不得使用首次发现时间、首次上线时间、baseline 时间或系统自建群成功时间兜底。
- 邀请链接读取失败只表示链接未知，不得据此把群判定为封禁或不可用。
- 当前列表已有的批量分组、批量删除和群详情操作保留。

## 目标与非目标

### 目标

1. 在统一群组池上固化历史群、上控后群两类可重叠标签。
2. 将 baseline 中只有 JID 的历史群安全收编到统一群组池。
3. 异步补全群详情并持久化完整当前成员快照，列表查询不逐行调用协议层。
4. 扩展现有 `/api/group-links`，支持原型要求的筛选和列表字段。
5. 对齐 Vue 群组列表的筛选区、历史筛选抽屉和表格列，同时保持现有操作能力。
6. Web、Android、历史数据和协议能力缺失时都采用明确、可恢复的降级语义。

### 非目标

- 不实现原型中标为未来能力的批量进群、新建社群、新建普群和批量群设置。
- 不把历史群与上控后群设计成互斥类型。
- 不在数据库迁移期间调用 WhatsApp 或任何远程协议接口。
- 不用关联账号国家替代群创建者国家。
- 不用任何本地业务时间估算 WhatsApp 建群时间。
- 不改变已有任务对 `origin`、`membership_state` 和 `sync_protocol_mask` 的语义。

## 方案选择

### 标签计算

方案一是在每次列表查询时展开所有 baseline JSON，再与当前 membership 做差集。它不需要新字段，但 SQL 复杂、组合筛选代价高，且退群后“上控后”事实容易因关系变化而丢失。

方案二是使用单个互斥枚举表示历史群或上控后群。它查询简单，但无法表达一个群被账号 A 拍入历史、又在账号 B 上控后加入的真实重叠场景。

采用方案三：在 `group_link` 上增加两个单调布尔字段。事件与快照负责增量固化，迁移脚本负责存量回填。查询简单，且能准确表达重叠。

### 详情获取

方案一是在列表分页时逐群实时查询 metadata。数据最实时，但会把页面时延和 WhatsApp 可用性绑定，并产生请求放大。

方案二是只在用户打开详情时查询。实现成本低，但列表需要的成员数、管理员、地区和建群时间长期为空。

采用方案三：baseline 完成后创建可持久化、可重试的异步补全任务；账号上线、群变更和手动刷新负责后续触发。页面始终先读数据库快照。

## 总体架构

```text
账号首次或后续群回报
  -> Armada 校验租户、账号和协议绑定
  -> 登记/复用 tenant + group JID 对应的 group_link
  -> 固化 is_historical / is_post_control
  -> 刷新 account_group_membership
  -> 按群去重创建 metadata 同步任务

metadata 同步任务
  -> 选择当前仍在群的在线账号（管理员优先）
  -> 查询群 metadata 与完整 participants
  -> 管理员可用时独立查询邀请链接
  -> 原子写入群预览和当前成员快照
  -> 严格解析创建者国家并固化国家、大洲

GET /api/group-links
  -> 全部筛选 SQL 下推
  -> 每个 tenant + group JID 返回一行
  -> Vue 主筛选、历史筛选抽屉和列表统一消费
```

`armada-protocol` 只负责 Web/Baileys 协议数据完整、事件触发和稳定响应；`armada` 负责分类、任务、持久化、查询和权限；`wheel-saas-pure-web` 负责筛选状态与展示。协议差异不得泄漏到前端。

## 数据模型

### `group_link` 分类字段

新增：

- `is_historical TINYINT(1) NOT NULL DEFAULT 0`
- `is_post_control TINYINT(1) NOT NULL DEFAULT 0`

所有更新使用单调语义：目标值为 `1` 时写入，任何路径都不得把已有 `1` 改回 `0`。分别增加租户、软删、标签和主键组成的查询索引，支持历史、上控后及稳定分页。

字段属于租户级群入口。一个账号的 baseline 或新增事实成立，即可提升该租户群入口的对应字段。

### `country` 大洲主数据

在全局国家主数据增加 `continent_code`，仅使用以下稳定代码：

- `ASIA`
- `EUROPE`
- `NORTH_AMERICA`
- `SOUTH_AMERICA`
- `AFRICA`
- `OCEANIA`

全部会暴露给群组地区筛选的国家必须映射到其中一个代码。国家选项接口返回 `continentCode`，前端据此联动过滤国家选项。

### `group_link_preview` 详情字段

复用现有字段：

- `invite_code`
- `wa_subject`
- `member_size`
- `owner_phone`
- `announce_only`
- `group_created_at`
- `avatar_url`
- `last_preview_at`

新增协议详情字段：

- `wa_description VARCHAR(1024) NULL`
- `admin_only_edit_info TINYINT(1) NULL`
- `member_add_mode TINYINT(1) NULL`
- `join_approval_mode TINYINT(1) NULL`
- `ephemeral_duration_seconds INT NULL`
- `creator_country_iso2 VARCHAR(2) NULL`
- `creator_continent_code VARCHAR(24) NULL`

`owner_phone` 只接受已确认 PN。LID、未知身份或无效号码不得截取数字后写入。国家使用现有严格手机号识别能力解析；无法确认时国家和大洲都为空。

`group_created_at` 的唯一来源是协议 `creation`，单位为 Unix 秒。系统自建群也不得用本地建群成功时间补值。

### 当前成员快照

新增 `whatsapp_group_member_snapshot`，每行表示一次成功 metadata 快照中的当前成员：

- `tenant_id`
- `group_link_id`
- `group_jid`
- `participant_jid`
- `phone`
- `role`
- `is_admin`
- `is_owner`
- `snapshot_at`
- `created_at`
- `updated_at`

唯一键为 `(tenant_id, group_link_id, participant_jid)`；增加 `(tenant_id, group_link_id, is_admin)` 索引供管理员列表查询。该表只表达“最后一次成功快照中的当前成员”，不替代现有 `whatsapp_group_member_join_fact` 和 `whatsapp_group_departed_member` 事件事实表。

写入前按稳定协议层返回的规范化 participant JID 去重；同一身份重复出现时，群主优先于管理员、管理员优先于普通成员。`is_owner=1` 时 `is_admin` 也必须为 `1`。无法得到稳定 participant JID 的条目使本次成员响应不完整，整次快照不得替换旧数据。

完整 metadata 在事务外读取成功后，事务内先替换群预览，再删除该群旧成员并批量插入新成员。任一步骤失败则整个数据库事务回滚，旧快照仍可用。部分响应、超时或协议错误不得清空旧成员。

### metadata 同步任务

新增 `group_metadata_sync_task`，每个租户群入口最多一行，保存：

- `status`：`PENDING/RUNNING/RETRY_WAIT/SUCCEEDED/DEFERRED/FAILED`
- `trigger_source`
- `attempt_count`
- `next_run_at`
- `lease_until`
- `last_started_at`
- `last_success_at`
- `last_error_code`
- `last_error_message`
- `created_at/updated_at`

唯一键为 `(tenant_id, group_link_id)`，待执行索引覆盖 `status/next_run_at/lease_until`。重复触发只推进同一行，不产生任务风暴；租约超时后允许其他实例恢复。列表中的 `metadataSyncStatus` 和 `metadataSyncedAt` 来自该表。

## 标签写入规则

### 首次 baseline

账号仍为 `PENDING` 时收到完整首次群回报：

1. 固化 baseline JID 和可用群名。
2. 为本次群逐个登记或复用 `group_link`。
3. 将这些群的 `is_historical` 提升为 `1`。
4. 刷新账号群关系和已有轻量预览。
5. 为去重后的群入口创建 metadata 同步任务。

首次 baseline 中的群不得因为同一事务随后建立了 membership 而被误标为上控后群。

### baseline 后的完整快照

账号状态为 `CAPTURED` 时，对每个当前群 JID 与该账号 baseline 做差集：

- JID 在 baseline 中：保证 `is_historical=1`。
- JID 不在 baseline 中：提升 `is_post_control=1`。

完整快照继续负责把缺失账号群关系标记为不在群，但不清除任何群分类标签。

### 精确自身成员关系事件

`account.group_membership_changed` 的 `add` 事件仅在以下条件全部满足时提升 `is_post_control=1`：

- 账号 baseline 状态为 `CAPTURED`；
- 事件 JID 不在该账号 baseline；
- 事件事实时间晚于 baseline 捕获时间。

`remove/leave` 只更新账号群关系，不清除标签。账号仍为 `PENDING` 或 `DISABLED` 时不根据精确事件猜测上控后分类，后续完整快照负责校准。

### 重叠与租户聚合

若群 A 在账号 1 的 baseline 中，同时又是账号 2 baseline 后新增群，则租户级 `group_link` 同时为：

```text
is_historical = 1
is_post_control = 1
```

任何账号退出该群都不改变这两个事实。

## metadata 与建群时间来源

### Web/Baileys

`sock.groupMetadata(groupJid)` 返回的 `creation` 是 WhatsApp 建群时间。协议 HTTP metadata 响应已经暴露 `creation`，但当前 Java `HttpGroupMetadataAdapter` 和稳定 `GroupMetadataResult` 未接收该字段；本次需要补齐稳定模型、适配器和持久化链路。

当前 Web `account.groups_reported` 和批量群列表刻意只上报 JID、群名，保持轻量列表不携带 participants。本次不把完整成员塞回群列表事件，而是由独立 metadata 同步任务逐群、限速读取。

### Android

Android 当前参与群响应已经支持 `creation`、creator 和 participants。Android metadata 稳定结果需要与 Web 对齐可持久化字段；协议不返回的字段保持空，不伪造默认值。

### 严格时间规则

- 非空 `creation` 必须是合理的 Unix 秒并早于或等于当前时间；异常值按未知处理。
- 新事实时间不早于当前成功快照时才允许覆盖 `group_created_at`。
- 协议后续明确返回空值时不清除已确认的 `group_created_at`。
- 系统自建、首次发现、首次加入、任务创建和本地写入时间均不能作为替代来源。

## metadata 同步与刷新

### 触发来源

- 首次 baseline 捕获完成。
- baseline 后新群被完整快照或精确 `add` 事件发现。
- 群成员增减、成员角色变化或群资料变化事件。
- 有 deferred 任务的账号重新上线。
- 用户对单群执行“获取最新群信息”。

触发按租户和群入口去重，并设置短时间防抖。群成员连续变更只在稳定窗口后读取一次完整 metadata。

### 执行账号选择

1. 当前仍在群、账号状态正常、协议连接有效且在线的管理员。
2. 若无管理员，选择满足同样在线条件的普通成员读取可读 metadata。
3. 无可用账号时任务进入 `DEFERRED`，账号上线或关系变化后重新排队。

邀请链接仅在所选账号当前为管理员时单独读取。邀请读取失败不会回滚已成功的 metadata 和成员快照，只保留旧链接或空值并记录独立错误。

### 并发与新旧保护

- 任务并发默认限制为每租户 3 个、每账号 1 个，允许通过配置下调。
- 同一租户群入口只允许一个有效租约。
- metadata 写入携带观察时间；晚到的旧响应不得覆盖更新快照。
- 成功同步后重置重试次数；协议超时、网络错误和临时服务错误最多执行 4 次，重试间隔依次为 1、5、30 分钟，耗尽后进入 `FAILED`。账号重新上线、群变化或用户手动刷新可把它重新置为 `PENDING` 并开启新一轮尝试。
- 明确无账号在群属于 deferred，不累计为永久协议失败。

## 群组列表接口

继续扩展：

```http
GET /api/group-links
```

不新增平行的历史群列表接口。已有 `/api/historical-groups` 暂时保留给账号组维度的历史群详情和成员操作。

### 查询参数

- `page/pageSize`
- `keyword`：群名、WhatsApp 群名、邀请链接、群 JID、群主号码和管理员号码。
- `folderId`
- `withoutFolder`
- `status`：沿用 `UNCHECKED/AVAILABLE/BANNED/LINK_INVALID/UNAVAILABLE`。
- `groupType`：`ALL/HISTORICAL/POST_CONTROL/BOTH`。
- `availableAdmin`：布尔值；不传表示全部。
- `memberCountMin/memberCountMax`
- `continentCode`
- `countryIso2`
- `ageDaysMin/ageDaysMax`

范围边界均为包含。成员数未知时不命中成员范围；建群时间未知时不命中群龄范围；国家或大洲未知时不命中对应地区筛选。大洲和国家同时传入时按交集查询。

群龄按 `floor((查询时刻 Unix 秒 - group_created_at) / 86400)` 计算完整天数，不受浏览器时区影响。未来时间或非法时间按未知处理。

`groupType` 语义：

| 参数 | 条件 |
|---|---|
| `ALL` 或不传 | 不限制分类 |
| `HISTORICAL` | `is_historical=1`，包含双标签群 |
| `POST_CONTROL` | `is_post_control=1`，包含双标签群 |
| `BOTH` | 两个字段都为 `1` |

查询、计数和分页全部 SQL 下推。一对多成员数据使用 `EXISTS` 或按群预聚合，禁止直接连接产生重复群行。默认排序沿用 `group_link.created_at DESC, group_link.id DESC`。

### 可用管理员

“管理员号码”来自最后一次成功成员快照中当前角色为管理员或群主的全部号码。

“可用管理员”只判断 Armada 当前上控账号，必须同时满足：

- `account_group_membership` 当前为在群状态；
- 当前账号在该群是管理员；
- 账号未删除且业务状态正常；
- 登录状态在线；
- 协议绑定和连接仍有效。

邀请链接、metadata 是否成功和普通外部管理员数量不参与可用管理员判断。

### 响应字段

在现有 `GroupLinkVO` 上增量增加：

- `isHistorical`
- `isPostControl`
- `folderId/folderName`
- `inviteUrl`
- `adminPhones[]`
- `availableAdmin`
- `availableAdminCount`
- `creatorPhone`
- `creatorCountryIso2/creatorCountryName/creatorContinentCode`
- `groupCreatedAt`
- `metadataSyncStatus/metadataSyncedAt/metadataSyncError`

现有 `admin` 字符串暂时保留供旧调用兼容，新群组列表使用 `adminPhones[]`。前端根据 ISO2 或后端国家信息展示国旗，不从任意号码自行猜国家。

## 前端交互

### 主筛选区

主筛选区对齐原型，依次展示：

- 群信息
- 群组分组
- 群类型：全部、历史群、上控后群、双标签群
- 群状态
- 可用管理员
- 群人数最小值与最大值
- 历史群组筛选
- 查询与重置

当前“来源文件、来源、群关系”不再占用主筛选区，但后端参数保持兼容，避免任务和导入页受影响。

### 历史筛选抽屉

使用 Element Plus Drawer 和表单组件，禁止自绘 select、slider 或 drawer。字段包括：

- 群所属大洲
- 群所属国家
- 群龄最小/最大天数及快捷区间
- 成员数最小/最大值及快捷区间

选择大洲后，国家选项仅展示该大洲国家。打开抽屉时复制已应用条件为草稿；直接关闭不改变列表。清空只清空草稿；“应用筛选”保存后关闭但不自动请求；“查询”保存、关闭、回到第一页并立即请求。

历史抽屉条件生效时自动发送 `groupType=HISTORICAL`，按钮展示激活状态和摘要。主页面重置同时清除大洲、国家、群龄、成员范围和历史类型。

主筛选的群人数和历史抽屉的成员范围共用同一份筛选状态；抽屉应用值时覆盖主筛选中的成员范围，主筛选修改后再次打开抽屉也必须回显相同值。群类型选择“上控后群”或“双标签群”后，历史抽屉已应用的地区和群龄条件仍保留为草稿但不参与请求；再次通过历史抽屉查询时，群类型切回“历史群”。

### 列表列

按原型展示：

1. WS 群组名称，名称下方显示“历史群”“上控后群”标签。
2. 分组名称，空值显示“未分组”。
3. 群人数。
4. 当前有效邀请链接并支持复制。
5. 当前全部管理员号码。
6. 群状态。
7. 可用管理员勾叉状态。
8. 建群信息：国家、创建者号码和 WhatsApp 建群时间。
9. 群组 JID 并支持复制。
10. 现有操作项。

双标签群同时展示两个标签。metadata 同步中显示“同步中”；字段未知时显示 `-`，失败原因通过 tooltip 或详情展示。

现有 `GroupMemberDrawer` 优先读取数据库成员快照。用户显式刷新时创建单群 metadata 同步任务，刷新完成后再读取数据库，列表打开和分页过程不批量调用协议层。

## 存量迁移

### 历史标签与群入口

按租户展开所有有效账号的 `account_group_baseline.baseline_group_jids`，按 JID 去重：

- 已存在活跃 `group_link`：提升 `is_historical=1`。
- 完全不存在群入口：使用 `wa://group/{jid}`、baseline 群名和 `origin=ACCOUNT_SYNC` 创建入口，再标记历史群。
- 已存在软删除入口：允许写入标签但不得由迁移脚本复活，避免撤销用户手工删除。

### 上控后标签

仅使用 baseline 状态为 `CAPTURED` 的账号。其 membership JID 不在该账号 baseline 时，提升对应租户群入口的 `is_post_control=1`。`DISABLED` 或无可信 baseline 的账号不参与回填，对应群允许两个标签都为空。

### 详情补全

Flyway 只创建结构和做本地、幂等、分批回填，不发远程请求。应用部署后扫描当前仍有可用账号访问的历史群，创建 metadata 同步任务。旧历史群无访问账号时保持稀疏数据和 deferred 状态，直到后续账号重新上线或重新入群。

所有迁移和回填严格依赖租户上下文或显式 `tenant_id`，不得跨租户按 JID 合并。

## 错误与降级

- metadata 失败：保留最后成功预览和成员快照，更新任务状态及安全错误摘要。
- 邀请链接失败：不影响 metadata 成功，不清空仍可能有效的旧链接，除非协议明确报告链接已重置或失效。
- 创建者未知或 LID 无法映射 PN：创建者国家、大洲为空，不回退关联账号国家。
- `creation` 缺失或非法：建群时间为空，不使用任何本地时间兜底。
- 成员快照部分返回：整次视为失败，不替换旧成员。
- 群内没有我方账号：任务 deferred，列表和历史标签仍保留。
- 旧协议不认识新增字段：按空值兼容；后端不得因单个详情字段缺失拒绝整个群列表事件。
- 单群异常不得阻断同账号其他群或同租户其他任务。

## 可观测性与数据安全

- 日志记录租户、Armada 账号 ID、群入口 ID、群 JID 的安全摘要、触发来源、耗时、结果和错误码。
- 不在日志中输出完整成员数组、完整邀请链接或批量手机号。
- 指标至少覆盖任务待执行数、成功数、失败数、deferred 数、重试数、metadata 耗时和成员快照大小。
- 成员快照沿用租户数据访问权限；前端接口继续受 `tenant:group_link:view` 及现有详情操作权限保护。

## 发布与兼容顺序

1. Armada 数据库结构、兼容读取和新任务框架先发布；新增协议字段均按可空处理。
2. Armada Protocol 保持 Web metadata 已有的 `creation/owner/description` 字段契约，并发布群变更触发补充；Armada 稳定模型开始消费这些字段。
3. 执行本地标签、群入口和地区主数据回填，再启动受限速的详情补全任务。
4. 前端切换到扩展后的统一群组列表接口和新筛选交互。
5. 核对任务、导入链接、历史群详情和拉群候选等现有消费者后，再考虑淘汰旧 `admin` 字符串或旧历史列表入口；本次不删除。

回滚应用版本时，新字段和新表保留无害；两个标签已经固化为事实，不执行反向清零。异步任务可通过开关停止领取，不影响现有群列表基础查询。

## 测试与验收

### 协议层

- Web metadata 继续正确返回 `creation` Unix 秒、owner、description、群设置和完整成员角色，兼容性测试防止字段再次丢失。
- Web 轻量群列表仍不携带 participants，不扩大实时群快照载荷。
- Android `creation`、creator 和成员角色正确映射；缺失字段保持空。
- 任何路径都不生成本地建群时间作为 `creation`。
- 群成员或群资料变化能触发按群防抖的同步信号。

### 后端分类

- 首次 baseline 群只标记历史，不误标上控后。
- baseline 后新群通过精确 add 立即标记上控后，并由完整快照兜底。
- 历史群后来被其他账号新增观察时变成双标签。
- remove、leave、被踢和完整快照缺失不清除标签。
- `PENDING/DISABLED` 账号不生成不可靠的上控后标签。
- 并发 baseline、精确事件和快照下标签仍只从 `0` 到 `1`。

### 后端详情

- 完整 metadata 成功时原子替换成员快照并刷新预览。
- metadata、数据库或部分成员响应失败时保留旧快照。
- 普通成员能同步可读 metadata，但没有邀请链接。
- 无当前在群账号时进入 deferred，账号上线后可恢复。
- Web 和 Android 的合法 `creation` 写入 `group_created_at`；缺失时保持空。
- 系统自建群在协议无 `creation` 时仍保持空。
- 已确认 PN 可严格解析国家和大洲；LID、无效号码和未知国家均为空。

### SQL 与 API

- 每个租户群 JID 只返回一行，不因成员或账号连接产生重复。
- `HISTORICAL`、`POST_CONTROL` 和 `BOTH` 与双标签真值表一致。
- 大洲、国家同时筛选按交集；未知地区不命中。
- 群龄、成员数范围为包含边界；未知值不命中。
- 可用管理员只计算当前在线、正常、在群管理员。
- 计数查询和数据查询使用相同条件，翻页总数稳定。
- 原有来源、关系、导入和任务调用仍能解析已有响应字段。

### 前端

- 主筛选布局、历史筛选抽屉和表格列与原型目标一致。
- 大洲联动国家，清空、应用、查询、关闭和主重置行为符合本设计。
- 历史抽屉查询自动附加 `groupType=HISTORICAL`。
- 双标签、同步中、失败、未知值和可用管理员状态正确展示。
- 搜索、重置、抽屉查询和页大小变化回到第一页。
- 分页和打开列表不会产生逐群协议请求。

## 跨仓修改范围

### `armada`

- Flyway 表结构与幂等回填。
- baseline、membership 事件和完整快照分类写入。
- metadata 同步任务、成员快照和协议稳定结果扩展。
- `/api/group-links` 查询、响应、地区解析与索引。
- 后端单元测试、Mapper 数据库测试和接口测试。

### `armada-protocol`

- 固化 Web metadata 已有字段契约，保证 `creation`、owner、description 和完整成员角色不被裁剪。
- 群成员、群资料变化的防抖同步触发。
- 不扩大轻量 `account.groups_reported` 的成员载荷。
- 路由与 worker 测试。

### `wheel-saas-pure-web`

- `src/api/group.ts` 查询和响应类型扩展。
- 群组列表 composable 的统一筛选状态。
- Element Plus 历史筛选 Drawer。
- 表格列、标签、建群信息和同步状态展示。
- 组件、composable 与 API 映射测试。
