# 通讯录 LID 超链设计稿审查

日期：2026-09-09。审查对象：[当前设计稿](/Users/daishuaishuai/IdeaProjects/armada/docs/superpowers/specs/2026-09-09-contact-lid-hyperlink-design.md)，并对照最初交接提示词。

结论：Kafka 入口、页面配置、协议层实际控速的方向已明确；以下八项仍需补齐，当前稿还不能直接作为实现契约。此次只审查文档并静态核对本地代码，未运行测试、访问远端或修改业务代码。建议均未自动视为用户确认。

## 1. P1：停发缺少协议层生效机制

设计稿第 146 行要求同时阻止新命令、排队消息和准备好的消息，却没有停止命令、执行版本和确认事件。当前 `ContactTaskServiceImpl.action` 只更新 Armada 的任务状态；发出去的命令已进入协议账号队列，修改任务表不会自动撤销这些消息。

建议明确任务执行版本及 pause/stop 控制契约：控制消息仍经 Kafka，但到达协议端后必须及时使旧执行版本失效，不能排在全部待发业务消息后面。协议在真实派发前原子核验版本及停止状态，停止时唤醒间隔等待并阻止后续派发，回报停止已生效。人工停止从协议确认生效起保证不再产生新派发；协议本地检测到断线/限流/封禁应直接关闭该账号发送，再上报 Armada。恢复后旧版本积压消息不得重新获得发送权。

证据：[任务动作实现](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/impl/ContactTaskServiceImpl.java:173)、[协议队列](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_account_dispatcher.go:109)。

## 2. P1：commandId 幂等没有明确有效期和重放边界

设计稿第 148 行以 commandId 持久化保证不重复发送，但协议现有状态保存在有 TTL 的 Redis key 中。key 仍存在时，超时 PROCESSING 可收敛为 UNKNOWN；key 过期后，相同命令再次到达会通过 SET NX 获得新的首次发送权。这两个场景必须区分。

建议确定命令可执行期限和最大重放窗口，保证去重状态覆盖全部允许重放的时间；过期命令直接拒绝派发。不能查询到必要历史事实时应返回不可执行/结果不明，不能把“未找到记录”直接等同于“从未发送”。明确 socket 派发与结果落盘之间发生崩溃时的处理：保留 UNKNOWN，允许晚到回执澄清，禁止再次发信。结果事件传输重试与真实发信重试分别定义。

证据：[状态 TTL 及首次领取](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_state.go:130)、[UNKNOWN 收敛](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_state.go:179)。

## 3. P1：完整快照检查不足以保证目标集一致

设计稿第 118 行只要求 SUCCESS 和新鲜度。当前 sink 每收到一片就 upsert 正式 `account_contact`，之后才检查是否完整；计数按 cutoff 时间而非 snapshotId 聚合。新快照不完整时，旧行虽然没有删除，部分字段和水位已经被覆盖。若同步与任务展开交错执行，仅在展开开始时检查一次状态不足以保证后续多次查询读取同一完整版本。旧快照迟到还需要明确拒绝规则。

建议用 snapshotId/版本明确区分收集中和已发布快照，按分片序号去重并核验完整性，完整后原子发布；任务固定读取同一已发布版本。具体采用暂存后事务替换还是保留多版本由实现评估，不预先要求新建多张表。也可选择同步期间禁止展开并采用正确的账号级数据库互斥，但必须保证旧快照与新分片不会混读。空完整快照、重复分片、缺片、迟到旧版本分别验收。

证据：[先 upsert 后判完整](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSnapshotSink.java:79)、[快照状态覆盖更新](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/AccountContactSyncMapper.xml:39)。

## 4. P1：只列状态，没有定义任务何时完成

设计稿第 152–154 行要求分层，但未给出执行终态、回执状态与任务完成的规则。若任务等所有消息已读才完成，会长期挂起；若收到服务器 ACK 就当送达，又会延续原有统计问题。UNKNOWN 若一直被当作在途，也会使任务永远无法结束。

建议执行结果与回执分别存储。任务是否完成由“是否还存在可以继续派发的工作、是否还有需要超时收敛的执行”决定；送达/已读继续异步更新，不阻塞任务完成。UNKNOWN 结束自动执行，但保留待核对标识。明确成功、失败、结果不明、取消/跳过与总量的统计关系，以及 READ 是否隐含 DELIVERED、迟到成功能否澄清 UNKNOWN。旧 `success_message_num` 不能未经数据迁移就统一改标成送达数。

证据：[当前完成条件](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskLifecycleWorker.java:88)、[当前成功回写](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java:85)。

## 5. P2：联系人地址与联系人身份被过早合并为同一概念

设计稿第 108–110 行直接倾向以一个 contact_jid 替代手机号身份，并认为比独立 lid 列更符合单一事实来源。这个论据过强：PN 和 LID 是不同地址事实，可能同时存在；保存可信映射并不天然属于重复存储。

JID 唯一键只能保证同一地址不重复，不能保证同一联系人不会以 PN/LID 两个地址各出现一次。必须先确定混合快照中哪种地址用于发送、可信映射如何保留，以及只有 PN 的历史联系人是否仍参与任务。建议将“规范收件地址”和“可选真实手机号/映射”职责写清，再确定最小 schema。LID-only 不填伪手机号的结论仍成立。

证据：[现有归一化器](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/account/contact/service/AccountContactNormalizer.java:32)、[任务展开快照](/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskExpansionService.java:150)。

## 6. P2：协议控速仍缺命令契约和精度验收

设计稿第 132–138 行把随机区间采样放到协议端，但当前 wire 只有单值 sendIntervalMs；不能仅写“复用现有字段”就完成区间传递。此外，同账号两个任务分别配置 3 秒、10 秒，任务交替发送时采用哪一个间隔尚未定义。运行中是否允许改间隔、已入队消息采用哪个配置版本也未定义。

建议明确区间参数的单位、范围、缺省处理和执行版本，或明确首版只接收已经确定的单条间隔值；无论谁提供数值，实际等待和计时始终由协议层完成。为避免新旧协议把缺失值回落成 500ms，发布顺序和功能开启条件需与 wire 契约一起确定。同账号跨任务规则可选择互斥执行或统一间隔合并策略，需要在设计中落定。

精度验收不能只验证 gap >= 配置：配置 5 秒实际每分钟发一条也会通过。建议在队列持续有可发送消息、网络写入和准备可控的测试中，规定派发延迟容差并统计 actualGapMs/latenessMs；异常耗时单独归因。生产计时点须追到实际网络写入口，不能把 SendBuilder 入队时间或调度回调返回时间直接当作已发出。

证据：[当前命令字段](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_command.go:55)、[现有派发记时](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/armada/message_account_scheduler.go:219)、[peer 提交入口](/Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan/internal/service/node/prepared_peer_send.go:172)。

## 7. P2：按实际派发统计日额度，却只定义了上游预占

设计稿第 144–146 行存在时间归属缺口。23:59 在 Armada 预占并入 Kafka、00:01 才实际发出的消息应算哪天没有定义。若按入队日预占却按派发日展示，实际当日额度可能被绕过；排队取消、结果不明及跨日残余预占也缺少释放规则。

建议保留日额度独立于发送间隔，但给额度凭据绑定账号、业务日期和有效期。协议临近实际派发时校验额度资格；跨日失效的旧凭据先暂停该条或重新取得新日资格，不能直接使用前一天的额度。不得为了额度检查让 Armada 按秒驱动发信。每日 50 的数值仍为产品待确认项。

## 8. P2：端到端验收顺序与依赖不一致

设计稿第 161 行要求在协议阶段完成正式链路单条 LID 卡片送达，但下一步才修改 Armada 的可空手机号、任务展开和通讯录回执。当前 Armada 不能正常生成并完整回写这种业务行，协议阶段不能宣称通讯录任务端到端验收通过。

建议分开验证层次：先冻结跨仓命令/事件契约，协议层做准备、派发和回执的局部验证；Armada 完成最小同步—展开—投递—回写链路后，才做正式单条端到端验收；再补完整页面与小批量验证。兼容接收端先就绪，再启用新格式生产者。若前一阶段直接构造 Kafka 测试命令，报告只能称协议集成验证，不能称业务全链路验收。

## 处理建议

先补第 1–4 项的执行与数据一致性约束，再明确第 5–7 项的契约，最后重排第 8 项的验收步骤。产品范围只需用户决定联系人范围、模板/按钮、配置默认值及业务停止范围；版本号、原子发布、幂等窗口等技术方案应由实现者给出具体设计与证据，不应全部转成“待用户拍板”。
