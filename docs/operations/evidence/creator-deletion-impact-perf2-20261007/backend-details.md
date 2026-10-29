# Armada 后端 creator deletion 全面只读审计

审计基线：/Users/daishuaishuai/IdeaProjects/armada，分支 1.0.3-snapshot，HEAD c9a59b24（2026-10-06）。逐项当前源码核验。读取 AGENTS、owner/rules/request-analysis；历史报告仅用于定位。未修改业务代码、未远程写。隔离 H2 用例 15 断言通过：`/private/tmp/creator-backend-audit/BackendAudit.java`、`/private/tmp/creator-backend-audit/result.log`。真实当前 src/main/resources Mapper XML、生产 MyBatisConfig 租户插件、Spring事务；classpath依赖已缓存，H2内存库，无网络。H2不是InnoDB，未声称MySQL锁/死锁实测。

## 基线与未提交补丁

工作区未提交业务文件：AccountBatchLifecycleServiceImpl、AccountOnlineCommandServiceImpl、ProtocolCommandOutboxServiceImpl；新增 ProtocolAccountCommandRejectedException。补丁把上下线注销拒绝收集为账号ID、上层排除后重试正常号。下述公共候选/任务收口/状态回调/营销漏洞在 HEAD 和工作区一致；Outbox 非上下线分支依旧抛普通 BusinessException。部署事实请由现场根审计的运行JAR/类/XML核验补齐，不把git状态当部署状态。

## 状态模型（当前源码）

- AccountCreatorDeletionServiceImpl:48-73 reserve：先锁全局同手机号全部账号别名、锁账号，要求Android主设备+在线，检查其他依赖，插 account_creator_deletion RESERVED。identityHash 仅规范手机号SHA256（:92-98），不含credential代次/租户/backend；全局唯一（AccountCreatorDeletionMapper.xml:31-35，migration唯一键）。
- PullTaskCreatorDeletionTransactionService:54-82 同事务插任务账本 status=0 RESERVED，冻结 creator accountId/phone/protocol/createOperation，派生注销operation `ptcd_tenant_<tenant>_execution_<execution>`。此时账号账本operation_id仍NULL。
- RESERVED真实效果：全部公共候选排除；通用findActive身份仍可见；Outbox仅同tenant/pullTaskId/groupExecutionId放行。此时Android是否存在记录须独立查证，不能把预留叫永久注销。
- claimSubmission:132-165 先重验当前父任务/执行租约、独立管理号/群资料证据，beginDeletion:104-120再检查依赖并将账号账本DELETING、绑定operation，desired_login_state=OFFLINE；同事务任务账本SUBMITTED+submitted_at+deadline（30分钟）。这个事务在实际HTTP DELETE之前提交。
- DispatchService:31-48 最终持有父任务/执行行锁并覆盖有界HTTP POST；若STOP/lease/version失效直接返回 UNKNOWN/TASK_STOPPED_OR_LEASE_LOST，未发仍按UNKNOWN。
- Processor:47-68 首次仅发一次；:71-82恢复只query原operation，accepted后由独立管理号observe。
- record:171-225：匹配ACCEPTED→ACCEPTED；完整独立证据→COMPLETE并Account.completeDeletion→DELETED；FAILED/REJECTED/NOT_SENT都归FAILED；其他/空→UNKNOWN。未知/失败/过30分钟/管理号变化均manualPaused=1；FAILED prepare:112-114永远人工暂停，UNKNOWN恢复只query不重发。
- Account完成只markOffline(desired/login/source/invalidated)，releaseCreator只释放本执行role4；不释放代理、不清wa关系、不改变account_state（Mapper:114-124）。没有任何releaseReservation/安全未发送解除入口。
- 平台软删（account.deleted_at）、账号状态(account_state)、在线(login_state)、期望状态(desired_login_state)、WA永久注销、logout/device解绑/退群彼此独立，当前实现没有统一投影。

## 已确认缺陷与最小方案

### B1 P1：终态任务/单执行/失败不收口RESERVED，账号和同号别名长期无法复用（当前源码确定；现场根审计确认样本）

触发：reserve成功后群资料失败/手工结束/单执行终止/封群失败，尚未claimSubmission。预期：取消未派发动作后可证明未发送的reservation应撤销排他占用，同时保留审计。实际：整任务end只abandon/cancel/releasePullers；单执行end与failure相同；账号生命周期无解除API/SQL。

因果链：创建前reserve→任一后续步骤失败或结束→pull角色仅释放PULLER→account_creator_deletion仍RESERVED→AccountMapper.notCreatorReserved排除全部公用选号→owner不再运行而同号所有其他业务仍被outbox挡住。

证据：
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardLifecycleServiceImpl.java:136（endTask 136-147）, :271（只PULLER）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/service/impl/PullTaskStandardExecutionLifecycleServiceImpl.java:130（单执行end）, :176（封群终止）, :325（只PULLER）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupExecutionFailureServiceImpl.java:63（失败）, :78（只PULLER）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountCreatorDeletionMapper.xml:102（仅begin/complete/markOffline/releaseCreator至124；无reservation释放）

最小修复：终态共用service内CAS释放“账号RESERVED+operation null+任务账本RESERVED+submitted null+没有删除已发证据”的reservation，记录RELEASED/CANCELLED审计状态或独立审计后移除active占用；不要删除历史。先锁父任务/执行并取消未派发动作，禁止旧worker再claim。用户暂停非终态时保持owner绑定，允许必要查询和有绑定的恢复。已SUBMITTED/DELETING不能以任务终态为理由解除。

### B2 P1：注销结果收敛依赖业务任务仍执行，STOP/租约竞争可永久留下DELETING

触发：claimSubmission已经提交，POST已发/可能发/最终STOP栅栏决定未发，随后结束或租约过期。预期：不再新发，但原删除结果仍可审计、对账和收尾。实际：record首先current()要求父任务EXECUTING、执行EXECUTING、manualPaused0、原version/lockOwner/未过期；否则LOST；结束任务没有独立ledger reconciliation。该分支在源码确定，现场未观察完整竞态。

证据：
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/scheduler/PullTaskCreatorDeletionTransactionService.java:174（current不满足即LOST）, :272（运行/租约条件）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/scheduler/PullTaskCreatorDeletionDispatchService.java:40（STOP返回UNKNOWN，POST48）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/scheduler/PullTaskCreatorDeletionProcessor.java:71（query只在业务processor恢复时跑）

最小修复：将“停止新删除发送”和“记录/查询旧operation结果”分开；结果CAS以frozen tenant/account generation/operation为边界，不以业务执行租约为必要条件；独立扫描未收敛ledger并仅query。最终STOP在确定未发时记录可证NOT_SENT，不能自造UNKNOWN；但claim后进程crash且无协议记录不能仅凭not-found断言未发。

### B3 P1：明确未发送/拒绝被永久隔离，没安全恢复协议

record:189-197把NOT_SENT/REJECTED/FAILED都FAILED，账号保持DELETING；prepare:112-114禁止后续query/发送，账号候选和身份查询永久屏蔽。未知继续保护合理；明确未发且稳定operation/taskBinding的解除缺失为可恢复性缺陷。

证据：/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/scheduler/PullTaskCreatorDeletionTransactionService.java:189, :112。

最小修复：按协议证据分“未发送且可解除”“服务器明确拒绝且已完成本地清理”“已发送未知必须保护”；后台与协议带operation+身份代次的原子解除握手，人工选择是否终止旧任务或有新attempt重试。不能删Redis作为恢复。

### B4 P1：候选/占用规则不对称，feed、普通营销与拉群营销漏排除RESERVED，反向堵住原任务注销

H2已复现15断言中的关键9项：feed callable候选仍返回RESERVED；普通营销target candidate仍返回；MarketingAccountOccupancyMapper真实insert成功；新增feed/营销依赖后原beginDeletion=false；group-pull builder/marketer候选都返回RESERVED；发送复查甚至DELETING/DELETED仍返回NORMAL旧状态。

完整因果链：R已排他→业务独立mapper漏过滤→新任务/执行占用→发送走公共outbox被拒→原任务注销前hasOtherDependencies又等新业务→互相等待/运营无法完成。不是“所有已占用正常业务都突然注销”：reserve本来会阻止已有活动依赖；缺陷发生在预留之后又增依赖及不参与身份锁的竞态。

证据：
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/feed/FeedTaskAccountCandidateMapper.xml:5（15-24只基础有效和本任务去重；无生命周期）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/feed/task/service/FeedTaskExpansionService.java:26（27选、40落新account）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountFilterSelectionMapper.xml:11（发送前只有NORMAL/导出校验，无deletion或在线）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/marketing/MarketingTaskMapper.xml:1248（固定群候选）, :1302（动态账号候选）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/service/impl/MarketingTaskServiceImpl.java:153（159-168目标创建与占用）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/marketing/MarketingAccountOccupancyMapper.xml:59（insertAvailableTaskAccounts无门禁）, :6（tryOccupyTaskAccount同样无门禁）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/marketing/GroupPullMarketingMapper.xml:517（builder）, :555（marketer）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingAllocator.java:96（候选）、:102（占用）、:124（新execution）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountCreatorDeletionMapper.xml:51（营销依赖）, :63（feed依赖）, :68（grouppull依赖）

额外同类源码确认：JoinTaskServiceImpl:258直接使用请求selectedAccounts生成任务，无reservation复核；JoinTaskDispatchTransactionService:109 findActiveRefs对R可见，:149批量outbox被拦。GroupScriptCandidateMapper.xml:4-38不排除R，script执行findOnlineProtocolRefs也对R可见，最终消息outbox拦。contacts新候选走AccountHyperlinkCandidateService排除R，但已有指定账号的发送复查复用AccountFilterSelectionMapper缺口。需分别标记：H2已测feed/marketing/grouppull；join/script只代码链确认，未现场触发。

最小修复：候选、领取占用、下发前最终复核共用按操作语义的资格谓词；所有会新增依赖的领取在同一事务用现有identity锁+CAS检查排他。不能只给页面加禁选，也不能只扩大注销前依赖清单。

### B5 P1：混批影响仍存在于非上下线：正常账号营销/feed/进群整轮回滚

触发：一个受限账号混入同批命令（B4已证明候选会漏）；预期逐账号确定拒绝、其他正常号继续；实际 outbox对非上下线直接BusinessException→AndroidMessageSendBackend不转换per-item拒绝→routing不捕获→外层整轮@Transactional回滚。feed下一次1秒扫描重试，普通营销/进群类似，造成无关号持续等待（路径确定，现场规模待证）。

证据：
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/service/impl/ProtocolCommandOutboxServiceImpl.java:820（非上下线825抛错；832只上下线有typed exception）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java:107
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingMessageSendPort.java:61
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/feed/task/scheduler/FeedTaskWorker.java:65（整轮事务）, :178（批次enqueue）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/feed/task/scheduler/FeedTaskScheduler.java:48（默认1秒）, :72（仅日志捕获）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundWorker.java:91（整轮事务）, :591（enqueue）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/task/scheduler/JoinTaskDispatchTransactionService.java:94（租户批事务）, :149（批次enqueue）

最小修复：保留 outbox 原子语义，业务构造前逐账号确定拒绝并终结/延期对应item，正常项一起入队；不要catch后在已rollback-only事务中继续。补“受限+正常”H2事务测试，不能只测上下线。

### B6 P1：RESERVED又未进入Android门禁时，直接协议业务错误放行

并非所有业务都走outbox。拉群营销上述独立选号后，worker直接GroupCreatePort；兼容单次建群GroupOperationServiceImpl按selectActiveById（R仍可见）+在线，直接GroupCreatePort。Routing与AndroidNativeGroupCreateAdapter不查询删除账本。R仅后台预留、协议未Begin时这些路径可继续实际建群；也可能使原任务最终注销掉一个刚在别的业务创建新群的账号。尚未现场发真实命令，错误放行是当前调用链证明。

证据：
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java:349
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/group/service/impl/GroupOperationServiceImpl.java:45（解析）, :48（直调用）, :69（active查询）
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/routing/RoutingGroupCreatePort.java:44
- /Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupCreateAdapter.java:71

合理对照：独立普通建群正式任务NormalGroupCreationServiceImpl:401使用findOnlineStrictByGroupId→公共候选排R，不能把直接建群兼容缺口说成正式任务全部失效。本任务建群本来需要直通，因此修复须给直接端口带可信用途/owner binding，而非一概拦R。

### B7 P1：状态与群结果回调被身份门禁吞掉/拒绝，消息结果有条件受影响

具体路径：
- AccountStateEventServiceImpl:133 activeById隐去→:135“账号不存在”return false；AccountStateMapper.xml:113/124/149相关更新也notCreatorDeleting。禁重新在线合理，但OFFLINE/设备解绑/终态事实也不再收敛。
- ProtocolGroupJoinSinkImpl:61、ProtocolGroupDepartureSinkImpl:46、ProtocolGroupParticipantChangedSinkAdapter:99均findActiveRef隐去后return，延迟进退群/管理员变更不入库。
- GroupSnapshotResultReportedSinkAdapter:103→validateAccountBinding:159→173抛“群快照结算账号协议绑定不一致”，使快照任务本来的结果结算失败。
- GroupProfileReportedSinkAdapter:257、GroupParticipantObservationServiceImpl:141/178通过selectActiveByWsPhones做账号-成员匹配，DELETING/DELETED账号不再绑定/校准账号关系；全群资料不等于全部被丢。
- ProtocolRiskEventSinkAdapter:38仅有识别的risk code才resolveCanonicalAccount:133-135；ProtocolMessageEventConsumer:84/104先风险落库，再消息ACK/发送结果sink，因此带RATE_LIMITED/ACCOUNT_REACHOUT_RESTRICTED/CHAT_SUSPENDED这三种enum信号的回执可提前抛错，业务结果没执行。普通成功ACK/无riskcode结果本身按command/attempt结算，未见统一删除门禁；不能写成所有ACK都丢。

所有路径为源码确认，现场当前没有DELETING/DELETED不能声称正在丢。最小修复：拆“身份定位/历史回调绑定”与“是否可派新业务”。回调以租户、账号代次、协议路由、command/operation与时间水位校验，允许收敛终态/清理；仅拒让旧事件复活已注销身份。

### B8 P2：只读/结果对账与清理被统一命令门禁拦；确定拒绝被包装成Kafka未知后重试

ProtocolCommandOutboxMapper.xml:419-425/AccountMapper.xml:1179-1185完全不读取command_type：R缺任务绑定即blocked，D/X全部blocked。群健康/账号群同步/快照（OutboxService:343/372/396）也无owner binding；定时群同步候选在AccountMapper.xml:795已排R（不报错但停止刷新）；直接本任务group profile getMetadata在PullTaskGroupCreateProcessor:77不经过outbox、R阶段可用，因此当前ex305审批字段缺陷不可归因此门禁。

超链UNKNOWN reconciliation的replay:OutboxMapper.xml:16-27会把Android原消息改message.send_result.query并PENDING；Publisher:254同一门禁照挡。Publisher:279-282将BusinessException包装成ProtocolException.unknown(:663-664)，Dispatcher:255的确定BusinessException分支抓不到，按retry直到默认3次/30秒（Properties:21/24），制造错误Kafka失败/未知分类。正常运行hasOtherDependencies本应挡已有active/inflight超链，故该组合属竞态/历史异常/直接路径后出现的条件风险，不虚构现场超链故障。

最小修复：命令分为新副作用、同owner前置、停止/清理、持久结果查询、实时只读；只读是否可依赖真实连接另定，但持久结果query不能被拒。确定注销拒绝保留类型和reason，不包Kafka unknown；per-item terminal rejection要回写业务item，而非无效重试。

### B9 P2：手机号永久身份无代次，新凭据/真新注册无法区分

AccountCreatorDeletionServiceImpl:92 identityHash=phone SHA256；AccountMapper.xml:5-12/Outbox XML:421对同手机号别名全局保护，跨租户及Web/Android同号都命中；这是防同身份绕过的必要边界。但没有credential generation/registration proof字段，永久DELETED影响后来明确新注册；仅改protocolAccountId或重导凭据不能解除。当前已证设计缺口，不等于现场发生误伤。

最小修复：保留phone全局闸门，新增可验证registration generation/credential fingerprint+frozen operation绑定，只有独立新注册证明+旧operation完全收敛才切新generation；不能见到新凭据就视新身份（旧token重导不算）。跨租户错误只给本租户“身份受保护”，不返回别的tenant/task详情。当前门禁只返回bool，未发现直接泄漏他租户详情；相同route ID分支没有tenant限制，任意路由ID复用也可能超范围，需要部署数据核实。

### B10 P2：新增锁扩大阻塞范围（风险，未证明线上死锁）

- 每个outbox入队都锁同手机号全租户账号别名，无论该号是否正在注销（OutboxService:813-814；Mapper:427-431）。单别名组ORDER BY id是正确的，但多命令按输入顺序各锁一个身份，A/B与B/A跨事务可能逆序；当前未实测InnoDB死锁。
- hasOtherDependencies:43-87的多个EXISTS子查询FOR UPDATE锁所有匹配活动依赖；activeScriptBindings:90-95把特定phone账号与同租户全部active script tasks及group行JOIN，先FOR UPDATE后Java parse JSON，哪怕脚本完全未引用该号也先锁，可能拖慢无关剧本。这个锁范围源码可确认，延迟程度未知。
- ClaimSubmission与STOP共用父任务锁，最终POST持锁HTTP是有意防STOP之后发注销（Dispatch:14/29/31-48），不要简单删除这道栅栏。最小优化是有界网络deadline+清楚标记dispatch intent/lease，并统一排序所有身份再锁；占用方都遵循同一账号锁后，可避免逐依赖扩展锁作为并发唯一保障。无需新全局/分布式锁。

## 业务操作 x 阶段矩阵（后端当前源码）

标记：✓可用；P合理保护；M误拦/隐藏；B错误放行；R仅条件风险/待现场证实。

|业务|RESERVED本任务|RESERVED其他任务/同号别名|DELETING/UNKNOWN|DELETED|无关账号|
|---|---|---|---|---|---|
|公共候选：新建群角色、管理员/拉手/站台/补资源、超链、contacts新选号、互加联系人、正式普通建群|冻结creator专用可用；普通候选P排除|P排除|P排除|P排除|✓；同分组没有剩余资源就等待|
|本任务建群/资料/管理提权前置outbox|带tenant/task/execution✓|P拒|P拒新动作|P拒|✓|
|本任务资料核验getMetadata/邀请码、独立管理员注销证明|R阶段直查询✓；管理号独立✓|普通直查询未统一后台策略|协议可能挡creator，独立manager查询✓|creator不应用于live业务|✓|
|feed新候选、feed/contact发送复查|非该任务用途|B候选可入；outbox后挡→新占用反阻注销|B旧NORMAL可入；outbox挡|B复查旧NORMAL可入；outbox挡|M同消息批可被回滚|
|普通营销target/占用|非该任务用途|B可创建/占用；消息outbox挡|B仍online NORMAL时可见；消息挡|offline后多数候选不选；direct sendability仍可见|M同轮可回滚|
|拉群营销builder/marketer|非该任务用途|B自有候选漏排除、直接建群/联系人等可走HTTP|底层协议挡；后台已可能占资源|底层协议挡|同组配额/资源等待R|
|进群任务指定账号|非该任务用途|B任务可存；批outbox M整批失败|M按ACCOUNT_NOT_FOUND结项|M按ACCOUNT_NOT_FOUND结项|M同租户派发批可回滚|
|剧本营销|非该任务用途|候选/online lookup仍可见，消息outbox挡|lookup按不存在/离线|lookup按不存在/离线|取决每步事务；未证明整剧本所有号停|
|历史群营销/拉人|非该任务用途|固定phone查询R可见，发消息outbox挡；随机Web拉手公共候选P排除|lookup隐去|lookup隐去|单业务调用形状决定；未实测|
|独立兼容单次建群|非该任务用途|B selectActiveById+online→直HTTP可走|M报账号不存在|M报账号不存在|✓|
|定时账号群同步|R也被候选排除M（无法刷新当前业务事实）|M|P停止live sync|P|✓|
|手动群快照/健康outbox|缺owner绑定M|M|P新查询可能合理；结果收敛不合理|P不使用已注销连接|混批有M风险|
|状态/群事件/快照结果|✓身份可见|✓身份可见|M隐去/拒结算|M历史事件不收敛|✓|
|普通ACK/发送结果无riskcode|✓|✓|✓按command/attempt|✓按command/attempt|✓|
|带riskcode ACK/结果|✓|✓|M风险sink抛错使后续业务结果不执行|M同左|✓|
|持久注销结果query|尚无协议记录须区别not-found|独立原operation核查|✓直接port不走账号lookup/outbox；但受业务调度存活约束M|可读取历史|✓|
|超链原消息结果query replay|R条件风险：会被publisher统一挡|同左|M条件风险|M条件风险|✓|
|本地列表/count/状态和号码导出/业务历史|✓，无生命周期投影会“看着可用”|同左|多为✓但少数getPhones缺号M|旧NORMAL造成误展示M|✓|
|账号人工转组|R一般能走；仍受原有group locks|R能走但不解除reservation|M账号被隐去→整批不存在|M同左|混批被连带M|
|终态回收|Mreservation不释放|受持续占用影响|必须保护未知，但M无人独立对账|仅creator role释放；其他资源需显式收口|同组/批间接受影响|

## 最小修复优先序与边界

1. P0/P1先定义并落“安全未发送reservation释放”“结果账本独立收敛”，覆盖整任务、单执行、失败、超时、重试耗尽、重启。先用当前2755/2757现场证据分类；不自动清已发未知。父任务暂停继续保留业务owner，允许任务内有绑定的恢复。
2. 补候选/领取/直接端口门禁的对称性（feed、普通营销、拉群营销、进群指定账号、剧本），依旧禁止DELETED新业务；补混批逐账号隔离。
3. 拆身份查询与业务可用性；让OFFLINE、群进退、snapshot settle、ACK/risk结果正确收敛；完成注销统一状态/代理/账号角色/连接/关系，但群退出事实不能凭假设写。
4. 区分停止、只读、持久结果查询与新副作用；publisher保留明确拒绝，终止无效重试；有owner绑定才允许R本任务动作。
5. identity generation与锁序优化；执行状态/任务详情/账号列表显示统一的可用性和原因。

历史治理：只在未来修复获授权后执行。候选安全释放需同时满足：A lifecycle RESERVED且operation null；T RESERVED且submitted null；父任务/执行已终态或明确终止owner；所有该owner pending/locked命令已取消，dispatching与旧worker已收口；协议持久记录证明没有已发operation/unknown/accepted任务绑定。软删除或“查不到Redis”都不是充分证据。NOT_SENT/REJECTED需协议结果绑定验证及两端握手，UNKNOWN/SUBMITTED/ACCEPTED/DELETING继续隔离和仅query。

回滚：只回滚行为实现，不清生命周期审计/已发intent；不能回到无门禁版本。部署范围后端+Android为主；页面增加字段/错误原因时带前端；Web仅修同号映射/共享端口相关，按核实需要部署。仅perf2后续授权，其他环境不动。

业务验收必须分层：isolated H2边界；本地协议持久结果/重启迁移；perf2受限+正常混批；R owner全部前置/核验；任务end前后无发注销释放；已发未知停止后独立对账；明确NOT_SENT/REJECTED解除；DELETED不得重登/新发但ACK/群结果收口；同号跨租户/异协议/新凭据generation；并发STOP与POST后回写。账号数、HTTP200、KafkaACK都不代表业务完结。


## 本任务 NEW_GROUP 前置/后续逐链核验（避免泛称自身被挡）

1. SELECT_ROLES：PullTaskGroupCreateTransactionService:124-155先生成稳定createOperation，排除manager/stations后reserve，再插creator PROMOTER role4；PullTaskGroupAccountMapper.xml:23-26明确同owner RESERVED且相同account role4豁免。候选来自:687公共online query，会排除已属于另一执行的R，是正确独占。
2. CREATE_GROUP：同文件:177-201从固定creator activeRef读取（R仍可见）并frozenCreatorMatches；Processor:99直接GroupCreatePort，未走outbox，不依赖taskId payload，R阶段应可用。故不能说缺taskId就挡住本任务建群。
3. APPLY_PROFILE：PullTaskGroupProfileDispatcher:105-121固定creator onlineRefs对R可见；:155-158请求明确tenant/task/execution/action；ProtocolPullTaskGroupProfileCommandRequest:47-48/73-74把字段写入Reference；outbox owner match放行。资料核验Processor:77直接metadataPort.getMetadata(fixedCreator,group)，不走outbox；R阶段应可用。
4. INVITE：GroupCreateTransactionService:487-498固定creator activeRef；Processor:124-125直接invitePort.getInvite，无outbox；R阶段应可用。
5. 管理号进群：PullTaskManagerJoinTransactionService:265-269明确tenant/task/execution/action，且actor是独立manager，creator reservation不应挡。
6. 管理员提权：PullTaskManagerAdminTransactionService:112-120通过GroupExecutionAccountSelector的本地群管理员候选，AccountGroupMembershipMapper.xml:268-326没有排R，允许本群creator充当actor；:277-279明确tenant/task/execution/action；owner outbox应放行。Discovery成员查询请求也携带执行行上下文。注意这个独立候选若在别的任务选到R，会在role插入或outbox门禁被挡，可能反复等；不是本任务误拦。
7. 完成提权后Gate.afterManagerAdmin:32-34转CREATOR_DELETE；若开关开启，后续联系人/拉人门槛要求任务ledger COMPLETE。这是需求内合理保护，不能跳过核验。
8. 联系人：PullTaskManagerPullerContactTransactionService:583-587及PullTaskPullerStationContactTransactionService:176-180都明确tenant/task/execution/action，actor是manager/puller/station；不会因creator已注销而全被挡。简化模式直接拉手时跳过部分联系人是模式设计。
9. 恢复/补资源：PullTaskResourceRecoveryTransactionService:170-177从首PROMOTER辨识原creator，:185-188排除creator作为新manager，使用公共经理候选；这保护职责边界合理。只要真正独立manager/puller可用，creator reservation不应挡正常替代。原creator在R意外离线时，本任务再次需要在线的prepare/creatorReady会暂停，而普通上线outbox缺owner被挡、auto-proxy-recovery排全部R；这是已确认恢复入口缺失，不是正常前置payload丢失。
10. 收尾退群：PullTaskCreatorLeaveProcessor:90-94在开启永久注销时直接ADVANCED，**明确跳过旧末尾creator leave**，不会对已删creator再发退群。关闭注销则没有本任务creator reservation，原leave请求ProtocolPullTaskCreatorLeaveCommandRequest:33-35本身也带task/execution。

本轮没有发现 NEW_GROUP 正常前置create/profile/invite/admin/contact因为缺taskId/executionId而被公共门禁误拦；确证影响是异常离线后的恢复/终态未释放、独立查询outbox与延迟回调，以及新增任务漏防。

## 回收基线边界

已用 `git show 2a69df1d:<lifecycle service>` 对照：在2026-10-06新增注销机制之前，整任务/单执行end本来就只releasePullers，creator PROMOTER、manager、station角色历史保留。不能把“所有非puller角色没release”都记作新增注销bug，也不能把普通PROMOTER released_at NULL直接说成全局占用（现场occupancy_key NULL，role4不是puller排他键）。新增机制的明确差异是account_creator_deletion全局identity唯一预留成为新增排他，旧结束流程未接入释放；它把原历史角色残留放大成公共候选/outbox持续阻断。代理绑定在R阶段本来用于在线连接，终态后要不要释放须结合停止连接和既有账号共享策略；不能只因task终态就贸然解绑仍online账号。DELETED完整收口应明确释放该身份网络/代理与必要资源，但群关系只按真实事实校准。
