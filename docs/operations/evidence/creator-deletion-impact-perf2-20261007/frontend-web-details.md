# Armada creator deletion：前端 / Web 协议 / 账号管理影响审计

本子审计只读当前源码，使用隔离本机 H2 合成数据复现；未访问 perf2、test1 或其他现场，未改业务代码、未部署、未写真实 DB/Redis、未执行协议业务动作。所有“已确认”均指源码或 H2 可复现行为；现场数量、部署覆盖由主审计合并。

## 1. 工作区与事实层级

- `wheel-saas-pure-web`：`/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web`，分支 `1.0.3-snapshot`，HEAD `c3df6e2cffca1fb00223b33b9e80ae84428570f6`。逐仓读 AGENTS 与 `.harness/agents/owner.md`；现有修改 [account-batch-operation.ts](/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/account-batch-operation.ts)、同名 test、[useAccountListPage.ts](/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/composables/useAccountListPage.ts)，未触碰。
- `armada-protocol`：`/Users/daishuaishuai/IdeaProjects/armada-protocol`，分支 `1.0.3-snapshot`，HEAD `5f8a4e51e08d7e3fdeab698e2c44c9872ad0ee90`，工作区干净，无 AGENTS.md（rg --files 已确认）。HEAD 是审批字段解析修复，前一提交 `280592f` 是管理员独立删除证明。
- 只读后端 `armada` HEAD `c9a59b242f18ba843db942751f4c08e308f11af9`，读 AGENTS、owner、request-analysis SKILL、数据模型规则；未提交 lifecycle/outbox 补丁仅作当前工作区事实，与 HEAD 分开。本文管理查询/导入/导出/群回调所引文件本轮没有在途修改。
- 阅读用户指定旧报告作为线索，旧部署 SHA 不作为当前生效证明。
- 以下 backend 路径前缀 `B = /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/`；frontend `F = /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/`；Web `W = /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/`。主报告应展开为绝对链接。

## 2. 业务回答

除上下线外，确认会影响：新建群/管理员/拉手/站台/超链/历史群选择候选；已注销账号在页面仍显示正常、统计仍计正常离线、甚至旧在线快照仍匹配“可调用”；迁移分组遇一个 DELETING/DELETED 可整批以“账号不存在”失败；群入退/成员角色变更、群快照结算、风控和账号类型事件因公共身份查询过滤而丢弃/拒绝；自动群同步在 RESERVED 就停止。另有反向缺口：feed 新选号未统一排除；旧同步普通建群/群资料 HTTP 入口绕过 outbox，RESERVED 可过 backend 身份查找；账号导出并移除可以导出 DELETED 的旧凭据。

没有证据表明这些全部发生于当前 perf2。并非所有查询、回调都被挡：列表/本地 WS 号码导出/任务进度查询、已抵达通讯录快照、feed 结果结算不受同一门禁过滤；Web 独立管理员 deletion-proof 正常使用未受限的管理员连接。

## 3. 业务操作 × account_creator_deletion 阶段

| 操作 | RESERVED（未提交可能） | DELETING（已进入提交/未知保护） | DELETED | 同号别名 / 其他协议 / 其他租户 | 无关账号 |
|---|---|---|---|---|---|
| 账号列表、按账号/登录态筛选、数量统计 | 显示旧状态，无生命周期字段 | 同左 | 同左，旧 NORMAL 可被算正常离线 | 当前租户列表仍可见，业务资格却因全球号码保护消失 | 正常 |
| callable=true | 只看 NORMAL/ONLINE/营销占用，可能误显示可调用 | 同左 | 同左（需旧ONLINE残留） | 同左 | 正常 |
| 普通新任务公共候选 | 全部排除（合理排他） | 全部排除（合理） | 全部排除（必要） | 全号码范围，不区分代次 | 剩余正常候选可用 |
| 固定 ID / phones 身份查询 | 返回 | 隐藏，伪装不存在 | 隐藏，伪装不存在 | 同号码即使 Web / 跨租户也隐藏 | 正常 |
| 迁移账号分组 | 可过生命周期查，但已有任务分组占用仍会禁止 | 一个账号缺失可整批失败 | 同左 | 同左 | 与受限账号混批可能被连带回滚 |
| WS 号码导出（纯本地字段） | 可用 | 可用 | 可用 | 按租户输出，不泄露其他租户 | 正常 |
| 导出账号原凭据并移除 | 离线且无业务占用时可用；并未看注销账本 | 同左 | 同左，错误放行旧注销身份凭据 | 账本不拦此入口 | 正常 |
| 平台软删除 | 看 account_state + dispatched_at；与注销无直接门禁 | 同左 | 若仍NORMAL则无法删，混批全拒 | 同左 | 与不满足账号混批全拒 |
| 导入 / 同号重新导入 | 本地写入未查注销账本；活跃同号会撞原唯一键 | 同左 | 旧账号软删除后可写新ID，但全球同号门禁继续生效 | 未识别代次，换协议也不解除 | 正常 |
| 群列表定时同步候选 | 不再选号，停止刷新 | 停止 | 停止 | 同号码受影响 | 正常 |
| 群入退/增删/角色/身份事件 | 公共lookup可返回，按原逻辑落库 | 被lookup过滤，丢弃 | 被lookup过滤，丢弃 | 同号码观察者也可能被过滤 | 如果事实由被过滤观察者上报，同群其他成员事实也丢 |
| 群快照结算 | 可以过绑定查询 | 抛绑定不一致 | 同左 | 同左 | 已排队但切状态后到达结果存在风险 |
| 通讯录快照回调 | 可继续落库 | 可继续落库 | 可继续落库 | 租户隔离，无全球lookup | 正常 |
| feed 发送结果回调 | 可继续按task-account结算 | 同左 | 同左 | 按任务明细关联 | 正常 |
| Web直接REST/Kafka worker既有命令 | Web本地无注销账本，只看socket online；不受backend SQL预留约束 | 同左：若绕后端且socket仍在线可执行（现场未证实） | 同左 | Web同号与Android账本没有本地桥接 | 正常 |
| 本任务管理员独立 deletion-proof | 使用管理员身份查询，无创号lookup依赖 | 可继续 | 可继续完成证明 | 管理员若恰好是另一条被保护同号，则另受backend选择影响 | 正常 |

本任务/其他任务差异在 outbox 的 taskId/executionId 绑定上，本文不重写主审计的命令矩阵。这里的 `selectActiveById/Ids/ByProtocolAccountId` 没有“本任务回调”豁免；DELETING/DELETED 一律隐藏。

## 4. 发现详情

### FW-1 [P1] 身份查询误当派单资格，群事件与快照结果丢失

**触发**：`account_creator_deletion` 已 DELETING/DELETED；前一阶段已发查询或仍有网络事件抵达，或同号码别名的连接上报。

**预期**：禁止新增业务，同时以 tenant/account/protocol identity + command/attempt 水位核验可信旧结果，继续收敛成员事实、任务结果和资源状态。

**实际链**：

1. [AccountMapper.xml:9-12](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml:9) notCreatorDeleting 通过 account_id **或全局规范化号码**排除；445-450、645-659 是 ID/协议ID/批ID入口。
2. [AccountProtocolLookupServiceImpl.java:50-55,221-232](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountProtocolLookupServiceImpl.java:50) 返回空。
3. [ProtocolGroupDepartureSinkImpl.java:46-51](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/ProtocolGroupDepartureSinkImpl.java:46) 因账号不可见 return，63-64 的退群事实/成员缓存不更新。
4. [ProtocolGroupJoinSinkImpl.java:61-66](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/ProtocolGroupJoinSinkImpl.java:61) return，68-94 的全部参与者事实、受控账号进群关系、成员缓存和新群营销通知不执行。
5. [ProtocolGroupParticipantChangedSinkAdapter.java:99-105](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/ProtocolGroupParticipantChangedSinkAdapter.java:99) return，107-111 的 ADD/REMOVE/PROMOTE/DEMOTE/MODIFY 均丢弃。
6. [GroupSnapshotResultReportedSinkAdapter.java:100-104,158-173](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/GroupSnapshotResultReportedSinkAdapter.java:100) 在定位并结算当前 command 之前抛“群快照结算账号协议绑定不一致”。这是拒绝结果结算，不是无线上证据即可说永远卡死；后续超时/重选需合并调度审计。
7. [ProtocolRiskEventSinkAdapter.java:129-138](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/ProtocolRiskEventSinkAdapter.java:129) 将同类事件报账号绑定不存在；[AccountTypeVerificationServiceImpl.java:46-50](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountTypeVerificationServiceImpl.java:46) 忽略类型检测事件。

**范围**：除该账号自身外，事件观察者上报的同群其他成员事实也可丢；不代表另一位健康观察者的独立报告也不能补齐。

**证据**：下面 H2 真Mapper证明 DELETING/DELETED 身份查询为空；以上 sink 分支源码直接确定。尚无本轮现场丢事件样本。

**最小修复**：拆“身份存在性/可信结果归属查询”与“可派单查询”。回调用含软删/生命周期事实的专用绑定查询，保留租户+账号ID+协议身份/代次+command/attempt校验；派单继续严禁。退群/清理终态允许单调收敛，不用简单放开 account_state 的任意更新。

### FW-2 [P1/P2] DELETED 旧凭据可以通过“导出账号并移除”交付

**触发**：注销身份在 ledger 为 DELETED；本地账号仍未软删、login_state=OFFLINE、dispatched_at为空、分组已释放且有唯一原始导入材料。

**预期**：原凭据备份/审计应明确失效，交付可复用账号的业务不可把永久注销旧身份作正常账号导出。

**实际链**：[AccountExportService.java:67-85](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/AccountExportService.java:67) candidates → validateSelection → archive.build → reserve；160-176 只检查离线、占用、EXPORTED。[AccountExportMapper.xml:5-13,34-62](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountExportMapper.xml:5) 不看 account_creator_deletion；reserve 将 NORMAL 改 EXPORTED，下载 99-107 不追加注销限制，complete 119-129 可软删凭据并释放代理。生命周期账本仍DELETED，新机器/同号重导入依旧受保护或远端失效。

**证据**：H2真实Mapper已跑 `DELETED candidates=1, reserve=1, account_state=EXPORTED`。没有真实导出、没有读取真实凭据。

**最小修复**：导出交付候选与最终条件更新显式拒绝所有注销保护身份；如果需要失效材料备份，另用明示只读审计导出，不混同可交付账号。取消导出恢复旧状态还应保留终态单调性，避免恢复NORMAL。

### FW-3 [P2] 页面状态/统计/“可调用”与实际资格不一致；管理混批仍可失败

**触发**：任意保护账本，尤其DELETED完成后旧 NORMAL/ONLINE/OFFLINE 残留。

**链**：

- [AccountMapper.xml:853-906](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml:853) filter 不包含生命周期；877-888 callable=true只看 NORMAL+ONLINE+营销占用。
- 909-916 countPage、926-988 selectPage、1084-1100 statsSummary 不投影生命周期、不修正计数；1004-1040 batch预览亦无生命周期跳过项。
- [account.ts:243-258,352-359](/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/api/account.ts:243) 只有account/login/risk/occupancy，没有creator状态；[AccountListTable.vue:212-242,245-253](/Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue:212) 只展示旧状态与普通业务限制，缺少注销说明。
- 迁组 [AccountServiceImpl.java:282-284](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java:282) 依赖已过滤selectActiveByIds；一条DELETING/DELETED使整批“部分账号不存在或已删除”失败。其余正常账号未迁移。
- 平台删除 [AccountServiceImpl.java:358-391](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java:358) 全批校验 state ∈ EXPORTED/BANNED/UNBOUND/LOGIN_REPLACED 与未dispatch；DELETED+NORMAL不符合，永远注销与平台软删除概念未衔接。

**证据**：H2所有3阶段 countPage、statsOffline、batchPreview计入；旧ONLINE时callable=true也计入；DELETING/DELETED activeByIds排除。前端没有新字段为源码事实，不把截图当线上证明。

**最小修复**：API增加只读生命周期/有效禁用原因（保留 account_state/login_state 的事实语义）；callable与业务候选统一策略；列表保留显示但禁用不允许操作并解释。迁组属本地管理不应调用“可派单身份查询”，选择明确政策；平台软删对已证明DELETED增加安全状态路径并确保清理，不让用户手改账号状态。批量操作按操作需要明确逐项结果，避免一条受限条目伤整批。

### FW-4 [P1] RESERVED 保护口径不对称：旧同步建群/群资料直连绕过outbox，Web无下层门禁

**触发**：后台已写 RESERVED，账号网络仍ONLINE，调用旧同步API或已持有协议账号引用的直接HTTP路径。

**实际链**：

- [GroupOperationServiceImpl.java:39-53,65-89](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/GroupOperationServiceImpl.java:39) 建群只使用selectActiveById+本地ONLINE；RESERVED不会被notCreatorDeleting过滤。
- [RoutingGroupCreatePort.java:48-60](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupCreatePort.java:48) 直接调用backend；[AndroidNativeGroupCreateAdapter.java:66-74](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupCreateAdapter.java:66) 原生HTTP，[HttpGroupCreateAdapter.java:41-52](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupCreateAdapter.java:41) WebHTTP；未进入outbox creatorDeletionCommandBlocked。
- [GroupLinkServiceImpl.java:435-456,645-655](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/GroupLinkServiceImpl.java:435) 资料/协议操作依赖同样入口，RESERVED可解析。
- [account-manager.ts:812-821](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts:812) getSocket只查ONLINE/socket；[messages.ts:76-81](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/messages.ts:76) 发送直接取socket；[worker-consumer.ts:892-943](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/commands/worker-consumer.ts:892) 已入协议命令同样取socket发送。全仓注销字符串搜索只有deletion-proof，不存在跨层SQL/Android注销ledger门禁。
- [index.ts:28-48](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/index.ts:28) API key/owner guard仍存在；这是注销策略缺口，不是无鉴权漏洞。

**范围/判定**：公共新任务候选排除是合理限制；允许同任务前置操作也合理，但这个同步入口没有task/execution绑定，从而无法证明操作者是获准本任务。Android真正是否阻断依赖其进程/Redis gate产生时点，须与Android审计合并，不能仅凭Backend判定真实建群已成功。Web直接层无策略是源码证据，现场未执行。

**最小修复**：让直接业务端口与outbox统一调用操作分类+作用域binding校验，所有新业务需有效资格；本任务必要前置动作带明确task/execution。Web同号保护要么统一业务层入口并禁止无上下文直连，要么传播identity generation/tombstone到协议层；不能依赖所有调用方自觉走outbox。

### FW-5 [P1/P2] 号码永久全局封锁缺少身份代次；重新导入可以“成功落库但不可用”

**链/证据**：

- [AccountImportServiceImpl.java:210-220](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountImportServiceImpl.java:210) 只尝试writeOne，重复报库内已存在；[AccountImportRowWriter.java:74-98](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountImportRowWriter.java:74) 新建 account/state/credential，无注销查询。
- `B/resources/db/migration/V005__account.sql:28` 原同租户活跃号码唯一键意味着重新导入不是凭据更新，活跃记录返回duplicate；软删后才可另建身份行。
- [AccountMapper.xml:5-12](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml:5)、`V212__account_creator_deletion_identity_index.sql:6` 只规范化号码，无新注册代次、凭据注册ID/identity key比较。
- [MyBatisConfig.java:35-37](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java:35) 将ledger设全局tenant过滤豁免，符合防同号别名规避的意图；不是自动属于跨租户数据泄露。
- H2构造租户1 Android DELETED + 租户2同号Web，后者本地列表count=1而activeIdentityCount=0。

**判定**：禁止旧身份改ID/改协议绕过永久保护是必要；明确新注册能否放行在当前模型无法表示。不能以任意“凭据更新时间变化”解除，因为同旧凭据重导入也会变时间。

**最小修复**：引入可信注册代次/凭据身份指纹，并仅通过被授权且有新注册/新identity证明的受控流程切代；保留旧代次tombstone及旧command fencing。导入阶段立即给出脱敏明确冲突或“待身份复核”，不要先显示成功可用。跨租户不返回另一租户/task数据，只返回泛化的身份受保护原因。

### FW-6 [P2] 候选有漏口，feed新选号与互加联系人当前项资格不一致

- [FeedTaskAccountCandidateMapper.xml:5-73](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/feed/FeedTaskAccountCandidateMapper.xml:5) 新候选没有creator ledger判断；即使 callable=true也只看普通状态与营销占用。
- [AccountMutualContactMapper.xml:7-12](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMutualContactMapper.xml:7) 新候选正确排除全部保护阶段；`account:14-17` 既有任务逐项账号读取不排除，最终仍要依赖outbox/协议。此点不直接证明发送放行。
- feed [FeedTaskWorker.java:116-147,166-195](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/feed/task/scheduler/FeedTaskWorker.java:116) 读sendable事实，先标SENDING再messageSendPort.enqueue；逐项拒绝可markFailed，但异常是否导致整轮回滚须合并消息端口分析。
- 普通建群任务新选候选 [NormalGroupCreationServiceImpl.java:399-403](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/normalcreation/service/impl/NormalGroupCreationServiceImpl.java:399) → findOnlineStrictByGroupId → AccountMapper.selectOnlineByGroupId有notCreatorReserved，合理排除。不要将旧同步API与普通建群任务混称一个入口。

**最小修复**：候选、已有任务派发、重试统一资格原因；保留结果处理。避免新增一个通用全局锁。

## 5. 明确不受同一错误门禁影响 / 未证明的路径

- 本地账号列表、统计、历史进度读取没有被禁；问题是呈现不完整而非查询不可用。
- [AccountMapper.xml:706-719](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml:706) WS号码导出只读当前租户非软删账号字段；H2证明3阶段均可用。
- [AccountMapper.xml:670-679](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml:670) 批量登录态投影不查ledger，因此营销详情可显示旧在线，不能以这个字段证明可派单。
- 通讯录已到达快照 [AccountContactSnapshotSink.java:79-113](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSnapshotSink.java:79) 通过专用 [AccountContactSyncMapper.xml:29-30](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountContactSyncMapper.xml:29) 锁账号id；该SQL不查注销，不属于群回调丢弃路径。
- feed回执 [FeedTaskSendResultSink.java:38-79](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/feed/task/service/FeedTaskSendResultSink.java:38) 按feed_task_account更新，不查公共AccountMapper。
- Web删除证明 [account-deletion-proof.ts:14-49](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/account-deletion-proof.ts:14) 使用独立manager socket、fresh原始群IQ、registration proof、同socket二次校验。没有Web永久删号能力，不需creator本人在线；正常deletion proof不因为creator后台隐藏必然失败。
- [group-member-link-mode.ts:85-108](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/group-member-link-mode.ts:85) deletion严格解析只在requireDeletionProof=true启用；普通群资料查询不会强制deletionProof解析。审批空节点行为属独立parser问题，不能并入注销因果。
- Web媒体下载 [messages.ts:274-360](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/routes/messages.ts:274) 属直接Web socket路径；无新增注销保护，业务后端是否存在该入口使用未完整确认。Android媒体下载由协议审计覆盖。
- 账号凭据更新：当前账户管理导入链是新建并去重，未发现页面显式“覆写已有account_credential”入口；Web persistCurrentAuthCreds是协议自身持久化，[account-manager.ts:824-834](/Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer/src/worker/account-manager.ts:824) 未加注销检查。不能笼统说凭据更新被阻断。
- 不声明消息ACK全部被滤。feed已证反例，其他消息ACK主审计需沿实际correlation sink定论。

## 6. 隔离 H2 证据

文件：`/private/tmp/creator-deletion-frontend-h2/CreatorDeletionManagementProbe.java`
输出：`/private/tmp/creator-deletion-frontend-h2/result.txt`

使用 JDK17、本地已有测试依赖、`JdbcDataSource jdbc:h2:mem`、**直接加载当前源码** AccountMapper.xml/AccountExportMapper.xml、真实MyBatisPlus租户插件；无mock SQL。合成2个租户/3个账户/同号Web别名，不含现场真实号码或凭据。

```
phase=RESERVED countPage=2 statsOffline=2 batchPreviewMatched=2 activeIdentityCount=2 phoneExportCount=2 staleOnlineCallableCount=1
phase=DELETING countPage=2 statsOffline=2 batchPreviewMatched=2 activeIdentityCount=1 phoneExportCount=2 staleOnlineCallableCount=1
phase=DELETED countPage=2 statsOffline=2 batchPreviewMatched=2 activeIdentityCount=1 phoneExportCount=2 staleOnlineCallableCount=1
DELETED export candidates=1 export.reserve=1 account_state=EXPORTED (actual production Mapper + tenant plugin)
samePhoneDifferentTenantDifferentProtocol listCount=1 activeIdentityCount=0
PASS: synthetic in-memory H2; no live database or protocol accessed
```

只跑该适配风险的检查。未跑全站前端/Web所有测试；无需以无关全量通过证明这次状态策略正确。

## 7. 当前补丁与建议部署/验收边界

前端未提交补丁：account-batch-operation.ts将整批success提示改为按accepted/failed/skipped warning/error，脱敏batchErrors；useAccountListPage.ts批量和单下线改用新反馈。这是反馈改进，不解决列表状态、callable、导出或回调；旧报告说已部署必须由本轮制品hash重证。

本子审计建议最小实施顺序：

1. 后端拆身份/资格查询，继续接收可信历史事实并禁止新派单；补群回调/快照结算真实Mapper+sink测试。
2. 同步账号终态与只读生命周期显示，修callable/preview与导出交付；对单已注销+健康账号混批做验收。
3. 统一同步HTTP业务端口与outbox策略；正向验证本任务RESERVED的建群/资料/接管仍可，反向验证非本任务/无binding拒绝。
4. 身份代次与重新导入明确契约；同号旧凭据不放行，新注册只有可信证明切代。
5. 历史治理必须先主审计区分RESERVED未发送与UNKNOWN真实发送；本审计不建议一律清账本/Redis。

部署至少后端+前端；若Web也承担同身份底层fencing，增加Web协议。只允许后续明确指令的perf2部署，不能顺带test1/Athena/WBPro。回滚不要移除永久注销保护，优先回退具体展示/接口行为并保留tombstone。真实业务验收需另行授权，本轮没有执行。


## 补充：导出查询与身份哈希精确边界

- 身份 hash 实际只为 SHA-256(normalized phone)，无协议/凭据代次：[AccountCreatorDeletionServiceImpl.java:92-96](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/service/impl/AccountCreatorDeletionServiceImpl.java:92)。本报告 FW-5 的代次结论有直接函数依据。
- 营销任务群成员导出不一定整项失败：[MarketingTaskWhatsAppMemberProvider.java:471-481](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/export/service/impl/MarketingTaskWhatsAppMemberProvider.java:471) 经findActiveProtocolRefs过滤DELETING/DELETED观察账号；181-205对完整本地缓存直接返回，缺缓存时跳过不可见候选；229-258最后降级数据库缓存或空成员。因此实际影响为“无法用这个观察账号补齐实时成员，可能使用旧/不完整/空数据”，若还有健康观察者则可补齐，不应笼统说营销导出被禁止。此查询通过metadataPort直接HTTP，RESERVED则仍能通过（与outbox不同）。
