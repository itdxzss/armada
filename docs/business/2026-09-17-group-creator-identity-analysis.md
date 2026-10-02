# 历史群创建者身份解析修复分析

日期：2026-09-17（分析基线）。2026-09-18 已按用户后续指示完成主仓库实现、本地相关验证及 test1/perf2 后端与 Android 协议部署；尚未提交，历史群补齐业务验收待做。本文第 2–3 节保留修改前诊断；当前实现与验证见 [变更记录](../../.harness/changes/2026-09-17-group-creator-identity-analysis.md)。

用户要求：先分析创建者身份如何解析。目标为第二套 perf2 已定位的历史群问题。沿用“创建信息”的含义，以协议明确的建群人身份为依据，不把现任管理员或不相关的群主成员替换成创建者。

## 1. 已知事实与未确认项

现场证据见 [排查记录](../operations/evidence/perf2-group-creator-20260917/diagnosis.md) 和 [上报摘录](../operations/evidence/perf2-group-creator-20260917/profile-evidence.json)。这是本次会话已有取证，不是本轮重新查询的线上状态。

- 账号 2601 首次完整同步 67 群，44 条资料事件有 creatorPhone，23 条没有。
- 随后的 11 条手动刷新资料事件只有 2 条有 creatorPhone；截图中的空白群在首次和刷新两次上报均缺号码。
- 这些样本的后端空值直接来自协议事件，不是前端遗漏、国家字典失败或创建者写库异常。
- 历史事件没有原始 creator/creator_pn，因此不知道这 23 群分别属于“根本没有身份”“只有 LID”“LID 有可用缓存但代码未查”中的哪类，不能预估全部补齐。
- 四次补充实时详情查询返回 iq processor closed / 账号不在线，未取得新 metadata。不能把后来的掉线解释为首次字段缺失。
- 老格式 JID 的 8 个有号码样本中，2 个号码前缀与现有 creatorPhone 不一致。这证明不能无条件按前缀覆盖，但不能仅凭不一致判断哪一个是真正原创建者。

## 2. 当前解析链

Android `internal/service/node/nodes/iq.go` 的单群与全量响应解析器都读取 `creator`、`creator_pn`，保存到 `entity.GroupInfo.Creator/CreatorPN`；成员保存 Jid、PhoneNumber、Type。同一响应中明确成对的 LID/PN 会进入现有映射缓存。

`internal/armada/groups_fetcher.go:182` 的 `reportedGroupProfile` 负责生成资料事件，创建者号码由 `creatorPhone`（约 446 行）解析：

1. Creator 是 PN 或裸数字时直接取号。
2. 否则取 CreatorPN。
3. 否则遍历 Owner=true 的成员，取第一个可用号码。
4. 都不满足就返回空，事件因 omitempty 不携带 creatorPhone。

Owner 的来源同时包含 superadmin 角色和与 Creator/CreatorPN 匹配的成员，第三步没有区分这两种依据。

该映射有四个生产调用点：首次/全量群同步、单群快照刷新、群快照协调器、新建普群。集中修复可覆盖这些调用点，但不能改变它们的成员完整性、权限、baseline 或任务状态语义。

后端 `GroupProfileReportedSinkAdapter.writeCreator` 遇到 null 直接跳过；非空交给 `GroupCreatorCompatibilityWriter` 写号码、解析国家及洲。这解释了“建群时间存在、另外三项一起空”。

## 3. 已确认的代码缺口

### 3.1 创建者路径没有使用现成的 LID 反查

`internal/service/jabber/store.go:69` 已有 `GetJIDByLID`；同文件有反向 `GetLidByJID`。`groups_fetcher.go:297` 的 `normalizeIdentityPhone` 也已使用此能力，但仅用于其他身份判断，创建者函数没有调用。

所以“creator 是 LID，原创建者不在当前成员中，但本地已有该 LID 的可信 PN 映射”目前仍会缺失，或错误落到另一位 superadmin。

这只是已确认的可修复分支，不证明现场全部 23 群都具有可用映射。

### 3.2 superadmin 兜底缺少与创建者的同一人证明

当前实现只要某成员 Owner=true 就可返回号码；相关旧测试甚至没有提供 Creator LID 与该成员 PN 的对应证据。需要改为精确匹配 Creator 的身份，避免不同 superadmin、顺序变化或多个候选改变创建者。

这里不能一并改管理员权限与 OWNER 角色判定。创建者解析应独立选择同一人，成员角色仍按现有权限事实处理，权限语义另立问题分析。

### 3.3 Android HTTP 契约丢失创建者身份

- Go `api/service/group.go:124` 返回字段 `Creator`，不返回 CreatorPN。
- Java `AndroidNativeFixedAccountGroupMetadataAdapter.java:106` 却读取 `Owner`。
- 当前 `GroupMetadataResult` 只有 ownerJid，没有独立的创建者确认号码。
- 另一条 HTTP 全量群适配 `AndroidAccountParticipatingGroupMapper.resolveOwner` 只匹配 creator 与成员，未利用 creator_pn。

这是真实的跨仓契约缺口，但不能用它解释前述 Kafka 资料事件：截图手动刷新的实测路径是 Kafka，已经在协议端缺号码。

### 3.4 其他写入路径会将未知视为可清空值

`GroupMetadataSnapshotServiceImpl.preview` 在号码/国家为空时仍设置 OwnerPhoneObserved=true、CreatorCountryObserved=true。Mapper 在时间允许时可以把已有号码/国家覆盖为空。

`AccountGroupMembershipSnapshotServiceImpl.ownerPhoneObservation` 也把仅有 LID 视为“观察到了空号码”。应把无法解析与明确删除区分开；创建者本次不可见不等于创建者不存在。

另外，当前 Mapper 是按观察时间覆盖，不能像 Web 注释那样宣称“创建者有值后永不覆盖”。同一业务字段的注释、写入规则需要对齐。

## 4. 推荐解析顺序

| 输入证据 | 处理 | 结果来源 |
|---|---|---|
| Creator 本身为合法 PN | 规范化 JID 后取手机号 | CREATOR_PN |
| Creator 为 LID，响应明确给出 CreatorPN | 使用该显式配对；检查类型与冲突 | EXPLICIT_CREATOR_PN |
| Creator 为 LID，当前成员有相同 LID 且携带 PN | 只取匹配同一人的 PN，不要求此刻仍标 superadmin | MATCHED_PARTICIPANT |
| Creator 为 LID，成员中没有号码 | 用现有 GetJIDByLID 查询，并用反向映射校验一致性 | KNOWN_LID_MAPPING |
| 平台自身成功建群，有准确群 JID 与执行账号记录 | 可从既有建群成功事实补证，不能套用到导入的历史群 | CONTROLLED_CREATION |
| Creator 缺失、映射缺失或候选冲突 | 返回未解析原因，不生成号码 | UNRESOLVED / CONFLICT |

同一响应中 Creator PN 与 CreatorPN 两个明确来源冲突时，不简单选第一个；保留既有事实并记录冲突。显式响应配对与旧缓存冲突时，以本次配对作为当前解析证据，记录缓存冲突，不顺手改写全局缓存或迁移身份。

JID 要通过已有 parser 与 ToNonAD 规范化，验证 server 和纯数字 user；不能直接截 `@` 前的所有数字，不能把 `:device`、`@lid`、群 JID 或不明裸身份当手机号。国家服务继续复用现有号码校验与国家主数据；号码国家是号码归属口径，不表示地理位置。

只有 superadmin、只有当前管理员、只有数字型群 ID、无法关联的手机号，都不足以证明原创建者。老群 `号码-时间@g.us` 前缀暂不作为默认回填来源；如要支持，需要独立验证格式与语义，并记录推断来源。

## 5. 复用映射时的边界

现有 Redis 映射使用协议环境前缀，键形如 `whatsapp:mapping:lid:<user>`，并有 PN→LID 反向键。默认有效期为 30 天。映射由已有协议观察建立，当前缓存没有逐条来源和观察时间，不能宣称是永久、绝对可信的创建记录。

- 只查响应中明确的 creator LID，不扫 Redis、不查全通讯录、不新增逐群在线 usync。
- 一次同步按 LID 去重并缓存查询结果，避免同一创建者反复查询；查询失败和正常未命中分开计数。
- 复用当前环境映射，不能跨 test1/perf2 取数据；Java 不直接访问协议 Redis。
- 双向缺失或冲突保持未解析，记录原因；映射故障不能使整次群同步失败。
- 不直接复用吞掉全部错误的 bool 返回值做诊断结果，需要保留 MAPPING_MISSING、MAPPING_UNAVAILABLE、MAPPING_CONFLICT 等不同原因。
- Redis 读取需要有可验证的超时边界；当前底层使用全局 context，接入前须检查客户端超时，不把一串缓存故障放大成全量同步长时间阻塞。

## 6. 最小实施范围与写入规则

建议先做 Android 协议 + Armada 两仓修复，Web 有同类风险但不是本次 perf2 样本的发生路径。后续若要求全协议一致，再把 Web 的 ownerPn、精确成员匹配与账号自身 lidMapping store 对齐；不借本次分析自动扩大部署范围。

Android 把解析放到可供事件和 HTTP 复用的小型身份解析组件，输入现有 GroupInfo 与可注入的映射查询。已有多条真实调用路径，复用有实际价值。保留 Creator/CreatorPN 原始身份，输出 Phone、Source、Reason，不改变成员角色。

HTTP 修正 Creator/Owner 契约并传递 CreatorPN 或明确的 CreatorPhone；后端内部结果保留原始 creator 身份和确认号码的区别，不能用“把 ownerJid 改成另一个 PN”掩盖它。目前 GroupMetadataResult 构造点分布于 9 个生产/测试文件，需同步修改和回归。

第一阶段不要求 Flyway、迁移旧表或新增前端字段：继续使用现有创建者兼容列，解析来源/失败原因先做结构化诊断。若要在页面展示未知原因、跨进程延迟重解析或自动仲裁不同来源，才需要单独设计持久化来源与原始身份；现有库列无法支撑这些能力，不能假装已有。

所有兼容写入口统一遵守：

1. 未解析：不清空已有号码、国家、洲，不提升创建者观察时间。
2. 已解析且旧值为空：写号码，并根据此号码计算国家和洲。
3. 与旧号码相同：允许补齐国家和洲，保持现有乱序保护。
4. 与旧号码不同：第一阶段记录冲突、保留旧值，不以“最后一次上报”自动认定谁是原创建者。已有错误值的纠正需要有直接来源证据，另做有范围的数据修复。
5. 国家解析失败：号码可以独立成立；若发生经证据确认的号码纠正，不能让旧号码对应的国家留在新号码上。三项一致性应作为整体校验。

## 7. 验证与上线方案（尚未执行）

必须覆盖的场景：PN 直接返回；LID+creator_pn；LID 精确匹配普通成员；创建者已不在成员中但映射命中；映射未命中/故障/冲突；不相关 superadmin；多个候选；带设备后缀的 JID；非法群 JID/裸身份；两个明确来源冲突；HTTP Creator 字段及 CreatorPN 透传；空观察不擦除；旧事件不覆盖；不同租户同群不串写；不同号码不自动替换；国家失败不丢已确认号码。

协议使用现有 Go 单测与 miniredis 路径；后端使用 H2 真 Mapper/租户插件，MySQL 专有 UPSERT 另加适配验证。成员角色、完整快照、baseline、邀请链接与任务回执只做相关回归，不改变其行为。分析阶段没有新增或运行这些测试，不能宣称验证通过。

部署顺序建议：先后端落实未知不覆盖及兼容接收，再协议输出修正后的解析结果。部署与数据补齐分开验收；协议修好不代表旧空值会自动更新。

perf2 首先对已定位账号、少量目标群做有界刷新，逐条对照原始身份、解析来源、事件、数据库、页面。只有 creatorPhone 增加并有证据，才能计为补齐；缺失输入的群继续保留未知。普通重连不是可靠的补齐触发，不能靠反复上下线验证。

回滚按各仓独立版本回滚，不触及账号或群成员。第一阶段无 schema 变更；已写入的真实数据不随二进制回滚自动撤销。任何纠正写入应保留目标清单与前值，不能用全表清空作为回滚。

## 8. 分析结论

可确定修复的内容是：接入现有可信 LID 映射、移除未证明同一人的群主兜底、修正 HTTP 身份字段契约、统一未知不覆盖。无法确定的内容是现场 23 个缺失群有多少可恢复；这依赖尚未取得的原始 creator 身份与可用映射。应先补上述确定缺口并观察来源分布，再决定是否需要更深的恢复能力。
