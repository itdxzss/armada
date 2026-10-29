# perf2 task 52 / execution 257：ManagerJoin 独立只读复核

结论：根因成立。当前源码在“次管理员已在群、没有踩链接命令”这个 NEW_GROUP 正常分支中，把刚插入的 PENDING 动作当作 SUBMITTED 动作回写，真实 Mapper 更新 0 行，导致事务回滚。现场失败发生在资料核验之后、管理员提权及料子拉人之前；不是资料回读不一致，也不是协议入群请求失败。源码 HEAD 为 `0a900d181e1259440ad6e18289bb90574959e738`，与本次前期检查一致；未修改源码、未连接远程、未改变任务状态。

## 源码与现场因果链

1. `PullTaskGroupCreateTransactionService.java:159–172` 将次管理员加入建群请求初始成员；`199–200,617–629` 把明确成功的初始成员持久化为 IN_GROUP。`455–500` 在资料凭证存在后登记群，并转入 MANAGER_JOIN。这里本来就没有 JOIN_BY_LINK 命令。
2. `PullTaskManagerJoinTransactionService.java:159–179` 先查询 JOIN_BY_LINK；没有则 `insertJoinAction`。`333–349` 和 `PullTaskAccountActionMapper.java:41–44` 明确插入 PENDING。随后 IN_GROUP 分支直接调用 `complete`，没有执行 `submit` / `markSubmitted`。
3. `complete` 先按租约/版本把阶段转到 MANAGER_ADMIN（`116–121,366–376`），随后 `writeActionResult` 调用 `writeBackResult`（`404–416`）。后者只允许前置态 SUBMITTED（`PullTaskAccountActionMapper.java:118–128`），真实 SQL 对 `action_status IN (expectedStatuses)` 做 CAS（`PullTaskAccountActionMapper.xml:58–68`），所以 PENDING 行更新必为 0。
4. `writeActionResult:416` 抛出“管理员踩链接结果回写失败”。虽然 `complete` 是类内调用，外层 `prepare:72` 已由 Spring 开启事务，因此动作插入和阶段推进一起回滚。`PullTaskExecutionDispatchCoordinator.java:205–215` 只释放租约并退避，不自动暂停或终止；默认间隔为 `PullTaskExecutionDispatchProperties.java:24` 的 30,000 ms。只要角色/动作状态不变且调度仍启用，就会反复走同一失败分支。

源码路径均相对 `/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/`：Java 在 `main/java/com/armada/task/{scheduler,mapper}/`，XML 在 `main/resources/mapper/task/`。

父代理已采集的现场证据与以上逻辑一致（本复核只读取本地证据）：

- [最终数据库快照](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-live-20261003/perf2/task-52-blocked-final.tsv:9)：execution 257 仍在 MANAGER_JOIN，未暂停；groupJid 为 `120363411384572796@g.us`，资料凭证已存在。角色 1420 / 账号 1038 / 尾号 4355 / ANDROID 为 IN_GROUP；动作列表只有资料动作 4253，没有持久化的 JOIN_BY_LINK，符合插入后回滚。
- [首次异常及调用栈](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-live-20261003/perf2/task-52-backend-focused.log:3)：2026-10-03 04:38:51.690 UTC，精确经过 `prepareExisting:179 → complete:121 → writeActionResult:416`。此前资料命令 `cmd_5827c8d52eb14a5c9a51a1dac1c78e3c` 收到 SUCCESS。
- [重试时序](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-live-20261003/perf2/task-52-retry-timeline.json)：04:38:51 至 04:43:52 UTC 共 11 次同错，约每 30 秒重试。
- [最终真实群资料/成员回读](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-live-20261003/perf2/task-52-readback-final.json)：群名及中文换行简介匹配；群中只有建群账号 999（尾号 2815，superadmin）和次管理员账号 1038（普通成员），料子 0/6。数据库第一笔料子命令为空，拉手尚未实际选中。因此不能证明完整拉群业务通过，也不能声称已实测“核验后拉料子”的正向执行时序。

## 影响范围

- 属于共享 Java 状态机，Web / Android 均受该条件影响，与哪种协议完成建群无关。NEW_GROUP 次管理员初始加群成功、可用且在线、进入 MANAGER_JOIN、无既有 JOIN_BY_LINK 动作，是正常预期路径，因此影响这一大类新建群执行。初始加群未被确认、账号离线等走其他路径，不能笼统称所有新群无条件失败。
- 已有群模式也存在条件性影响：数据库角色已标 IN_GROUP、执行仍停在 MANAGER_JOIN、对应动作缺失或为非 SUBMITTED 时，也走同一分支；分支没有 creationMode 判断。仅仅“WhatsApp 上原本已在群”，但本地角色尚未确认，不足以触发本缺陷；新选管理员通常仍正常提交 JOIN。
- IN_GROUP 分支先于后续动作状态处理，因此对应动作已是 SUCCESS / UNKNOWN / FAILED 等非 SUBMITTED 状态时，普通 `writeBackResult` 也无法更新。无需重复踩链接、重复建群或释放占用来解决这一程序缺陷。

## 为什么现有测试没有揭示

- 确实已有针对该分支的 `PullTaskManagerJoinTransactionServiceTest#managerAlreadyInNewlyCreatedGroupAdvancesWithoutSubmittingJoinCommand`（`test/java/com/armada/task/scheduler/…:190–226`）：输入正是 IN_GROUP + 无 JOIN 动作。但 `209–210` 直接把 `writeBackResult(...SUCCESS...)` mock 为 1，绕过了真实 SQL 的 SUBMITTED 前置条件，所以用例会绿。
- `PullTaskGroupCreateTransactionIntegrationTest#persistsRolesResultsInviteAndRegistrationBeforeManagerJoin`（同目录 `100–187`）用真实 Mapper 验证建群及资料步骤，却在“已到 MANAGER_JOIN”处结束，没有继续调用 ManagerJoin。`PullTaskManagerJoinTransactionIntegrationTest:98–184` 唯一参数化方法覆盖首次选择或替换管理员的正常 SUBMITTED 路径，未覆盖预先 IN_GROUP 且无动作的组合。
- 原先保存的 [135 项结果](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-acceptance-20261003/backend/test-results.json) **不包含** ManagerJoinTransactionServiceTest。本复核因新发现补跑该现有类，JDK 17.0.19 + Byte Buddy，离线 Maven，**11 项通过、0 失败/错误/跳过，退出码 0**；上述误模拟分支也通过。结果只证明现有 Mockito 用例通过，不代表真实 Mapper 通过。[日志](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-live-20261003/manager-join-existing-tests.log)、[命令与结果](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-live-20261003/manager-join-existing-tests.json)、[JUnit XML](/Users/daishuaishuai/IdeaProjects/armada/docs/operations/evidence/new-group-business-live-20261003/manager-join-existing-tests.xml)。没有新增测试或修改代码，也没有重复前期已通过的测试。

## 最小修复建议（未实施）

在 `prepareExisting` 中把“已确认 IN_GROUP 且 groupJid 非空”作为独立的事实推进分支，放在创建 JOIN_BY_LINK 动作之前：保留可用账号检查、租约/版本 CAS，直接推进到 MANAGER_ADMIN，不伪造已下发的踩链接命令或协议成功；若有旧动作，保留其真实命令/结果事实。groupJid 缺失时走现有等待/未确认路径。不要全局放宽 `writeBackResult` 接受 PENDING，否则会改变其他异步结果入口的前置态保护。

补充一条真实 Spring 事务 + Mapper/H2 的连续回归：建群成功初始 manager IN_GROUP → 资料凭证 → registerGroup → ManagerJoin，无既有 JOIN 动作，应推进 MANAGER_ADMIN、无 JOIN outbox、同一 groupJid/资料凭证不变。再覆盖 IN_GROUP + 既有终态/UNKNOWN 动作，确认不伪造协议成功且普通 SUBMITTED 回调的迟到结果保护仍成立。修复部署须另行授权；修复后应在同一任务/同一群继续完整业务验收，而不是用新建群掩盖现场。
