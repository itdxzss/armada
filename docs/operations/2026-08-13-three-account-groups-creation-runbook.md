# 2026-08-13 三账号分组建群操作记录

## 1. 目的与范围

本记录用于第一套测试环境（test1）后续批量建群。它同时保存：

- 固定业务规则：哪些分组互存联系人、谁负责建群、成员如何选择、谁需要管理员权限、群设置目标；
- 执行快照：启动前冻结的账号 ID、随机结果、群 JID、提权和设置结果；
- 启动门禁：任何未覆盖、不可用或当前协议无法表达的要求，必须显式处理，不能静默跳过。

本文只记录 Armada 账号 ID，不记录手机号、协议账号 ID、凭据或 API key。

## 2. 分组定义

| 角色 | 分组 ID | 分组名称 | 用途 |
|---|---:|---|---|
| 建群号 | 148 | 8-13建群号 | 每个纳入执行的账号负责创建群 |
| 次管理 | 149 | 8-13建群号次管理（辅助） | 每群随机选择 4 个，入群后全部提升为管理员 |
| 印度辅助成员 | 110 | 印度辅助建群（6段个人首次）不能建群。 | 不负责建群；每个群必须全量选择 |

三个分组均属于 tenant `1`。后续只能按分组 ID 执行，名称用于人工复核。

## 3. 执行方式（已确认）

### 3.1 总体方式

本次采用 `DIRECT_PROTOCOL_HTTP`：从 test1 后端主机运行一次性受控编排脚本，按账号协议直接调用 Web/Baileys 或 Android Zhuan 的 HTTP 接口。

本次方式明确为：

- 不走 Armada 前端页面；
- 不调用现有普通建群任务创建接口；
- 不写入普通建群任务表或手工修改业务数据库；
- 数据库只执行 `SELECT`，用于按 Armada 账号 ID 冻结手机号、协议账号 ID、在线状态和分组归属；
- 手机号、协议账号 ID、数据库密码和 API key 只在脚本进程内使用，不写入日志或台账；
- 每个联系人、建群、提权和设置请求均记录脱敏结果，最终输出 JSONL 台账。

选择直接调协议接口的原因是：当前普通建群任务只有一个成员分组和固定成员数，无法表达“分组 149 随机 4 个 + 分组 110 全量”。直接接口编排可以先冻结逐群成员名单，再严格按名单执行。

### 3.2 协议路由

接口必须按**执行账号**的协议选择，不能按目标成员协议选择：

- Web 建群号：调用 Web 协议 master；请求必须带执行账号 `accountId`，由 master 转发到持有该账号 socket 的 worker；
- Android 建群号：调用 Android Zhuan 原生接口，路由键为执行账号手机号；
- Web 接口鉴权使用 test1 容器运行时配置；Android base URL 同样读取运行时配置；文档和账本不得写真实配置值。

同一计划群内严格串行。协议写请求原则上只发送一次；发生超时、连接中断或响应无法解析时，先调用只读接口按英文群名、群 JID、成员和 metadata 对账，确认没有副作用后才允许补偿，禁止直接重放建群请求。

### 3.3 直接接口清单

#### 联系人互存

| 执行账号协议 | 接口 | 关键请求 | 成功判定 |
|---|---|---|---|
| Web | `POST /v1/contacts/{targetJid}/save` | body 包含执行账号 `accountId` 和联系人对象 | HTTP 2xx；接口内部先补齐 App State key，再保存联系人 |
| Android | `POST /ws/v1/contacts/add/{actorPhone}` | `Numbers=[targetPhone]` | HTTP 2xx 且协议 `Code=0` |

联系人写请求由 `armada-deploy/tools/account-contact-matrix.sh` 调度：同一执行账号严格串行，
不同账号并发；Android 与 Web 使用独立并发池，默认上限分别为 `6` 和 `2`；每个执行账号
完成一条后独立随机冷却 `1–3` 秒。HTTP 429 只暂停对应执行账号并指数退避，其他账号继续推进。
按 actor/target Armada 账号 ID 逐条记录成功和失败。

脚本先以 `--mode dry-run` 只读冻结账号集合并写 `BATCH_SNAPSHOT`/`PREFLIGHT`；正式执行必须
以 `--mode live --yes` 复用同一远端 JSONL 账本。分组成员、readiness 策略或账本中的冻结分组
任一发生变化时拒绝恢复，避免续跑时扩大任务范围。成功 checkpoint 的唯一键为
`direction + actorAccountId + targetAccountId`，中断恢复自动跳过已成功组合。

#### 建群与提权

| 操作 | Web/Baileys | Android Zhuan |
|---|---|---|
| 创建群 | `POST /v1/groups/create`，body 包含 `accountId`、英文 `subject`、冻结的 `participants`、`announceOnly=false` | `POST /ws/v1/groups/create/{creatorPhone}`，body 包含英文 `subject` 和冻结的 `participants` |
| 提升 4 个次管理 | `POST /v1/groups/{groupJid}/participants/promote`，body 包含 `accountId`、4 个 participants 和超时参数 | 对 4 人逐个调用 `POST /ws/v1/groups/admin/set/{creatorPhone}`，`state=true` |
| 读取成员 | `GET /v1/groups/{groupJid}/participants?accountId=...` | `POST /ws/v1/groups/members/{creatorPhone}` |
| 读取群状态 | `GET /v1/groups/{groupJid}/metadata?accountId=...` | `GET /ws/v1/groups/list/{creatorPhone}`，必要时结合成员接口 |
| 修改群名（仅补偿） | `POST /v1/groups/{groupJid}/subject` | `POST /ws/v1/groups/settings/name/{creatorPhone}` |

participants 必须来自建群前冻结的账号快照：分组 149 的 4 个随机账号 + 分组 110 的全量账号。不得在发送请求时临时重新选号。

#### 群设置

| 设置 | Web/Baileys 直接接口 | Android Zhuan 直接接口 | 备注 |
|---|---|---|---|
| 允许发送新消息 | `POST /v1/groups/{groupJid}/settings/announcement`，`mode=not_announcement` | `POST /ws/v1/groups/settings/sendmessage/{creatorPhone}`，`state=true` | 两端支持 |
| 允许添加其他成员 | `POST /v1/groups/{groupJid}/settings/member-add-mode`，`mode=all_member_add` | `POST /ws/v1/groups/settings/join-mode/{creatorPhone}`，`state=true` | 两端支持 |
| 关闭批准新成员 | `POST /v1/groups/{groupJid}/settings/join-approval`，`mode=off` | 当前 Armada Android adapter 明确标记不支持；历史批次曾记录候选 `/ws/v1/groups/settings/approval/{creatorPhone}`，执行前必须对部署版本做能力验证 | Android 未验证前不能开始完整批次 |
| 邀请链接可用性检查 | `GET /v1/groups/{groupJid}/invite-code?accountId=...` | `POST /ws/v1/groups/qrcode/{creatorPhone}` | 只能验证链接存在/可取得，不等于修改“通过链接邀请”权限 |
| 发送/查看消息记录 | 无对应稳定接口 | 无对应稳定接口 | 不得伪装成限时消息设置 |

Web 群设置完成后以 metadata 中的 `announce=false`、`memberAddMode=true`、`joinApprovalMode=false` 验证。Android 必须按原生群列表/成员响应的真实字段验证；缺字段时记 `UNVERIFIED`，不能只凭写接口 `Code=0` 判为整群成功。

### 3.4 编排顺序与账本

一次正式 operation 的顺序固定为：

1. `PREFLIGHT`：只读冻结三个分组和账号状态；
2. `CONTACT_148_149`：补齐冻结快照中的双向联系人；
3. `CONTACT_148_110`：完成冻结快照中的双向联系人；
4. `FREEZE_GROUP_PLAN`：为每个建群号生成英文群名、随机 4 个次管理和全量辅助成员；
5. `GROUP_CREATE`：按建群号协议直接调用建群接口；
6. `PROMOTE_ADMINS`：提升并读回确认 4/4 管理员；
7. `APPLY_SETTINGS`：逐项调用支持的群设置接口；
8. `READBACK_VERIFY`：读取成员和 metadata，严格验证实际状态；
9. `RECORD_RESULT`：写入脱敏 JSONL 台账。

每个写步骤成功后立即追加 checkpoint。脚本中断恢复时从最后 checkpoint 继续；建群步骤是否完成必须先按群名和群 JID 对账，不能重复创建同名群。

台账中的执行方式字段固定记录：

- `executionMethod=DIRECT_PROTOCOL_HTTP`；
- `protocolRoute=WEB_MASTER` 或 `ANDROID_ZHUAN`；
- `operationId`、`itemNo`、`creatorAccountId`、冻结成员账号 ID、接口动作标签、HTTP/协议码、`groupJid`、验证结果和时间；
- 不记录完整手机号、协议账号 ID、邀请码、邀请链接或任何凭据。

## 4. 已确认的业务规则

### 4.1 联系人互存

必须完成以下双向联系人保存：

1. 建群号（148）与次管理（149）双向互存；
2. 建群号（148）与印度辅助成员（110）双向互存；
3. 次管理（149）与印度辅助成员（110）之间没有互存要求。

联系人保存必须按账号所属协议路由，单次操作间隔随机 `1–3` 秒。失败必须逐条记录 Armada actor/target 账号 ID、方向、协议、HTTP/业务码、失败分类和时间；报告不得保存手机号或凭据。

联系人阶段的严格完成口径是：冻结快照中的每一对账号，两个方向都明确成功。不可用账号不能被当作成功，也不能静默排除。

### 4.2 每个群的成员组成

每个计划群由一个建群号创建，初始目标成员为：

- 次管理分组随机抽取 `4` 个账号；
- 印度辅助成员分组全量账号；
- 建群号由 WhatsApp 自动成为群主，不重复放进 participants。

随机抽取规则：

- 单个群内 4 个次管理账号不得重复；
- 不同群之间允许重复抽到同一个次管理账号；
- 必须在发出建群请求前冻结并记录 4 个账号 ID；
- 同一计划群失败重试时沿用原冻结名单，禁止重新随机；
- 使用可复现的随机种子，建议格式为 `<operationId>:<itemNo>`。

“第三组全部选中”的严格口径是执行启动时冻结分组 110 的全部有效账号。若任何账号不可用，预检应阻止该计划群启动或等待账号恢复，不能把“当前在线子集”冒充全量。

### 4.3 建群后动作

建群成功并取得 `groupJid` 后：

1. 对该群冻结的 4 个次管理账号逐个提升管理员；
2. 读取实时群成员信息，确认 4 个账号均已在群且均为管理员；
3. 应用并验证群设置；
4. 将 `groupJid`、实际成员、管理员及设置结果写入执行台账。

任何提权或设置动作发生“结果未知”时，不允许盲目重放；应先读取实时群 metadata，再决定补偿动作。

## 5. 群设置目标与协议映射

| 用户要求 | 目标值 | 当前协议映射 | 执行口径 |
|---|---|---|---|
| 允许发送新消息 | 开启 | `sendMessagesAllowed=true` → `not_announcement` | 应用后读取 metadata，确认 `announce=false` |
| 允许添加其他成员 | 开启 | `addMembersAllowed=true` → `all_member_add` | 应用后读取 metadata，确认 `memberAddMode=true` |
| 允许发送/查看消息记录 | 开启 | 当前普通建群 DTO 和 Baileys 群设置接口没有对应开关 | **未映射，执行前必须补协议能力或确认由 WhatsApp 默认行为满足；不得标记为已设置** |
| 允许通过邀请链接 | 开启 | 当前可获取/吊销邀请链接，但 Baileys 7.x 未暴露同名成员权限开关 | **未映射；若口径只是“链接可用”，可在建群后获取 invite code 并做验证；若是独立成员权限，需要补协议能力** |
| 关闭“批准新成员” | 关闭 | `joinApprovalEnabled=false` → `groupJoinApprovalMode(..., 'off')` | 应用后读取 metadata，确认 `joinApprovalMode=false` |
| 群名使用英文 | 英文 | `groupNameTemplate` / `groupUpdateSubject` | 名称必须只含英文、数字、空格和安全分隔符；确切模板与起始序号执行前冻结 |

现有普通建群五项设置还包含：

- `editGroupSettingsAllowed`（谁可编辑群资料）；
- `ephemeralDurationSeconds`（消息自动消失时长）。

用户本次未指定这两项。执行前必须明确值，不能将“允许发送/查看消息记录”擅自解释为 `ephemeralDurationSeconds=0`。

## 6. 当前能力缺口

当前 Armada 普通建群任务只支持：一个成员分组 + 固定成员数。它不能直接表达“分组 149 随机 4 个 + 分组 110 全量”这一组合。

后续执行必须满足以下任一方案：

1. 在 Armada 增加组合成员来源并将每群成员快照持久化；或
2. 在操作前生成逐群冻结清单，由专用执行流程按清单建群，并完整记录结果。

不能直接把分组 110 或 149 填入现有 `memberAccountGroupId` 后声称满足本需求。

## 7. test1 当前账号快照

快照时间：`2026-08-13`。账号状态会变化，真正执行前必须重新预检并生成新的冻结快照。

### 7.1 建群号（148）

- 总数：32；当前 READY：30；BLOCKED：2；协议构成：Web 5、Android 27。
- READY 账号 ID：`15, 18, 43, 908, 909, 1003, 1004, 1132, 1133, 1134, 1135, 1545, 1546, 1547, 1548, 1549, 1550, 1551, 1552, 1553, 1554, 1556, 1557, 1558, 1559, 1561, 1564, 1565, 1566, 1567`
- BLOCKED 账号 ID：`903`（account_state=3、login_state=2），`976`（account_state=6、login_state=2）。

### 7.2 次管理（149）

- 总数：9；本次详细快照均为 READY，状态曾在相邻查询中短暂显示 8 个可用，执行前必须再次冻结。
- 账号 ID：`888, 892, 1568, 1569, 1570, 1571, 1572, 1573, 1574`

### 7.3 印度辅助成员（110）

- 总数：59；当前 READY：57；BLOCKED：2；全部为 Android。
- READY 账号 ID：`786, 787, 792, 796, 800, 801, 809, 812, 814, 820, 823, 824, 825, 829, 831, 832, 833, 834, 839, 840, 844, 846, 855, 856, 862, 868, 870, 871, 872, 875, 877, 881, 883, 1006, 1007, 1013, 1014, 1015, 1019, 1020, 1024, 1025, 1026, 1027, 1028, 1032, 1036, 1041, 1042, 1045, 1047, 1048, 1049, 1050, 1052, 1053, 1055`
- BLOCKED 账号 ID：`853`（account_state=3、login_state=2），`857`（account_state=5、login_state=2）。

## 8. 联系人阶段当前事实

### 8.1 分组 148 ↔ 149

- 已执行快照：148 中 30 个可用账号 × 149 中 9 个账号；
- 双向请求总数：`30 × 9 × 2 = 540`；
- 最终成功：540；失败：0；
- Web App State 修复后，上一轮 48 条失败已全部补成功；
- 结果报告位于工作区：`/Users/daishuaishuai/IdeaProjects/armada-failure-reports/`。

严格“分组全量完成”仍未满足：148 的账号 `903`、`976` 当时未纳入，因此它们与 149 的双向联系人关系必须在恢复后补齐。按当前分组规模，完整矩阵应为 `32 × 9 × 2 = 576` 个方向性结果，历史已确认 540 个。

### 8.2 分组 148 ↔ 110

- `2026-08-13T05:03:27Z` 已在 test1 以严格模式完成只读 dry-run；未发送联系人写请求；
- 冻结规模：148 为 `30/32 READY`，110 为 `57/59 READY`；
- 严格矩阵：`32 × 59 × 2 = 3776` 个方向性结果；当前 READY 子集为 `3420` 条；
- 预检结果：`BLOCKED`，阻塞账号为 148 的 `903`、`976`，110 的 `853`、`857`；
- 远端恢复账本：`/tmp/armada-contact-matrix-148-110-20260813T050324Z.jsonl`；
- 本地脱敏副本：`docs/operations/2026-08-13-contact-148-110-dry-run.jsonl`；
- 正式执行必须等待 4 个账号恢复并复用上述远端账本；若分组成员发生变化，应作废该账本并重新 dry-run。

#### 2026-08-13 实际执行结果

- 执行环境与方式：test1，`DIRECT_PROTOCOL_HTTP`；同账号串行，Android/Web 独立并发池上限 `6/2`；
- 初始 ready-only 冻结为 148 的 30 个账号和 110 的 57 个账号；执行过程中 110 的账号 `883`
  变为 `account_state=3 / FORBIDDEN`，后续冻结将其排除；
- 最终稳定冻结集合为 148 的 30 个账号 × 110 的 56 个账号，双向矩阵 `3360` 个组合；
- 最终覆盖：唯一成功 `3360/3360`，缺失 `0`，最后一轮限流 `0`，远端无残留调度进程；
- 完整分组按 `32 × 59 × 2` 计算仍为 `3776` 个组合；合并两本执行账本后唯一成功 `3390`，
  仍缺 `386`，来自执行期间未纳入或后来不可用的账号；
- 最终只读状态：110 不可用 `853、857、883`；148 不可用 `903、976、1003`。其中 `883`
  在本次 0 秒实验期间新增 `FORBIDDEN`，`1003` 为待上线；
- 0 秒间隔实验窗口：`2026-08-13T05:31:52Z` 至 `05:47:35Z`，共 `1423` 次尝试：
  `1383` 成功、`39` 条 Android 确定失败、`1` 次临时失败、`0` 限流；后段失败率超过预设 `5%`
  回退阈值，且账号 `883` 出现 `FORBIDDEN`，因此已恢复 `1–3` 秒间隔；
- 不能证明 `883` 的封禁由 0 秒间隔直接导致，但时间上发生于实验期间，后续批次不得默认使用 0 秒；
- 本地脱敏账本：
  `/Users/daishuaishuai/IdeaProjects/armada-failure-reports/armada-contact-matrix-148-110-ready-20260813T051500Z.jsonl`
  和
  `/Users/daishuaishuai/IdeaProjects/armada-failure-reports/armada-contact-matrix-148-110-ready-20260813T054836Z.jsonl`。

## 9. 启动门禁

以下条件全部满足后才允许开始批量建群：

- [ ] 148 ↔ 149 冻结快照双向联系人全部成功；
- [ ] 148 ↔ 110 冻结快照双向联系人全部成功；
- [ ] 所有计划建群的 148 账号在线、正常且协议身份完整；
- [ ] 分组 149 至少有 4 个在线、正常账号，并冻结每群随机 4 人；
- [ ] 分组 110 全量账号在线、正常且协议身份完整；
- [ ] 每个群的成员总数未超过当前 WhatsApp/协议限制；
- [ ] 英文群名模板、起始序号和每个建群账号创建群数已确认；
- [ ] `editGroupSettingsAllowed` 和 `ephemeralDurationSeconds` 已确认；
- [ ] “发送/查看消息记录”的准确 WhatsApp 开关及实现方式已确认；
- [ ] “允许通过邀请链接”的口径是“链接可用”还是独立成员权限，且实现方式已确认；
- [ ] Android 部署版本已验证是否存在并支持关闭批准新成员的原生接口；不支持时不得把 Android 群标为整套设置成功；
- [ ] 已生成 operation ID、可复现随机种子和逐群冻结清单；
- [ ] 已确认失败、未知结果和限流的重试/冷却策略。

## 10. 逐群执行台账模板

每个计划群一行；如字段内容较长，可将成员快照放入同 operation ID 的 JSONL 文件，并在本表记录文件与校验值。

| operationId | executionMethod | protocolRoute | itemNo | creatorAccountId | creatorProtocol | groupSubject | adminAccountIds（4个） | helperSnapshotCount | helperSnapshotRef | contactGate | createAction | groupJid | createStatus | promoteStatus（4/4） | sendMessages | addMembers | historyPermission | inviteLinkPermission | joinApprovalOff | metadataVerifiedAt | finalStatus | failureReason |
|---|---|---|---:|---:|---|---|---|---:|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 待生成 | DIRECT_PROTOCOL_HTTP | 待按协议路由 | 1 | 待冻结 | 待冻结 | 待确认英文模板 | 待随机冻结 | 待冻结 | 待生成 | PENDING | GROUP_CREATE |  | PENDING | PENDING | PENDING | PENDING | UNSUPPORTED/待确认 | UNSUPPORTED/待确认 | PENDING |  | PENDING |  |

推荐终态：

- `SUCCESS`：群创建成功、成员快照匹配、4/4 次管理已确认管理员、所有已支持设置经 metadata 验证，且两项待确认权限已有明确验收口径；
- `FAILED`：协议返回确定失败且实时状态确认无相应副作用；
- `UNKNOWN`：响应丢失、超时或实时状态无法确认，必须人工/程序核验后再处理；
- `BLOCKED`：联系人门禁、账号状态、全量成员或权限能力未满足，尚未发出建群请求。

## 11. 执行前仍需确认

1. 每个建群号创建几个群；
2. 英文群名的确切模板和起始编号；
3. 建群号建群后是否继续留在群内；
4. 谁可编辑群资料；
5. 消失消息时长；
6. “允许发送/查看消息记录”的准确产品含义；
7. “允许通过邀请链接”是要求邀请链接可用，还是 WhatsApp 新版中的独立成员权限。

## 12. 单群试建结果（operation `probe-20260813-001`）

### 12.1 冻结计划

- 环境 / 方式：test1 / `DIRECT_PROTOCOL_HTTP`；
- 群名：`Armada Test Group 001`；
- 建群号：Armada 账号 `15`，Web；
- 随机次管理：Armada 账号 `892、1569、1570、1574`；
- 印度辅助成员：分组 110 全量 `59` 个；
- 目标人数：建群号 1 + 次管理 4 + 辅助成员 59 = `64`；
- 远端脱敏账本：`/tmp/armada-single-group-probe-probe-20260813-001.jsonl`。

### 12.2 创建与设置结果

- 群 JID：`120363430965743376@g.us`；
- 协议 create 通知人数：`64`；
- 首次 metadata（2026-08-13 14:24:00 CST）：`64/64`，计划成员缺失 `0`；
- 随机次管理：`4/4` 已确认管理员；
- 英文群名：已确认；
- 允许发送新消息：已确认，`announce=false`；
- 允许添加其他成员：已确认，`memberAddMode=true`；
- 关闭批准新成员：已确认，`joinApprovalMode=false`；
- 邀请链接：已确认可取得；不等于存在独立“允许通过链接加入”权限开关；
- 发送/查看消息记录：协议无独立能力，记录为 `UNSUPPORTED`。

### 12.3 稳定性复核异常

首次验收后约 93 秒，协议于 2026-08-13 14:25:33 CST 收到该群一次独立成员变更：
`participantAction=add`、`participantCount=1`、`hasAuthor=false`。后续多次 metadata 均为 `65` 人。

只读对账结果：

- 63 个冻结计划成员全部仍在群，缺失 `0`；
- 建群号匹配 1 人；
- 额外 1 人为普通 LID 参与者且带手机号映射；
- 该手机号不匹配 tenant 1 账号库中的任何 Armada 账号；
- 没有证据证明该成员来自建群请求，也没有足够证据确认其加入入口。

因此本次试建终态记录为 `PARTIAL`，不是整群失败：创建、计划成员、管理员和已支持设置均正确，
但群内存在 1 个计划外成员。当前保留现场，未执行踢人。批量建群前需要决定是否移除该成员，
并建议把创建后的稳定观察窗口和“计划外成员数必须为 0”加入硬门禁。
