# test1 拉群板块问题池与验收记录（2026-09-03）

## 结论

本轮结论为 **不通过 / BLOCKED**。当前已有现场数据足以确认普通拉群存在状态循环和审批无法自动恢复；
普通建群存在长期卡住及父子状态不一致；速拉群历史任务存在大量无法接到 Outbox 的失败轮次；前端无法展示
`taskId -> executionId/itemId -> commandId -> 协议结果` 全链。专用建群营销表当前无任务，不能把静态代码检查
冒充 test1 真群验收。

13:02 至 13:20 的首轮只做只读查询、代码审计和本地测试。18:49 起追加已登录的 Android
普通拉群金丝雀检查；19:18 创建 `taskId=199`，19:28:33 手动启动，随后完成 1 群、1 个内部目标的真实协议链。
目标实际入群成功，但群在协议成功后立即收到 `CHAT_SUSPENDED` 健康事件，execution 以 `GROUP_BANNED`
失败收口；本轮没有继续扩大到第二个群，也没有发营销消息、创建群或部署。

## 证据边界

- test1 观察时间：2026-09-03 13:02 至 13:20、18:49 至 19:53 CST。
- 数据库会话显式开启 `READ ONLY`；最新 Flyway 为 `178 account operation restriction manual clear`。
- test1 后端镜像：`sha256:c22d3622...e32e4d8b2`，创建时间 2026-09-01 16:58:25 CST；镜像没有
  `org.opencontainers.image.revision`，因此无法把运行镜像精确绑定到 Git commit。
- 13:02 至 13:20 的浏览器会话停在登录页；18:49 至 19:35 的追加检查已取得真实登录态，并交叉核对页面与 DB；
  19:53 的最终资源和协议链检查为数据库 `READ ONLY` 快照。
- `group_creation_marketing_task` 当前有效行数为 0；因此专用建群营销没有可验收的真实 `taskId`。

## 已证实问题池

### GRP-001 P0：普通拉群 UNKNOWN 状态循环

`[test1任务ID=193] [窗口=2026-09-03 13:15:25~13:20:28 CST] [业务=普通拉群] [协议=Web] [页面=/task/pull]`

页面现象：手动刷新可在“执行中”和“等待管理员/UNKNOWN”之间反复跳；页面本身没有自动轮询，停留时又可能
只显示最后一次快照，掩盖循环。

- 全链：`taskId=193 -> executionId=427 -> actionId=42600 -> commandId=pull-task-manager-supplement-entry:42600 -> 协议成员复核=UNKNOWN`。
- 该 command 是补充管理员的 synthetic 直调命令，没有 Outbox，故持久化全链先天断裂。
- 61 个样本中状态切换 41 次：26 次 `EXECUTING`、35 次 `WAIT_RESOURCE/UNKNOWN`；version 从
  `139905` 增至 `140186`。
- action 行、command 数始终为 `2/2`，本窗口没有新增踩链接或拉人命令；但
  `last_business_executed_at` 在无新协议动作时仍持续刷新，导致“最近业务时间”失真。
- 静态根因：已完成的成员缺席复核被永久复用；资源恢复只排除 `JOIN_FAILED`，把 membership `UNKNOWN`
  当成可用管理员，随即重新激活同一 execution。

期望：同一不确定事实应稳定停在可解释、可操作状态，或按明确退避/人工核对收敛；不得每秒反复改写状态和时间。

### GRP-002 P0：等待审批只会暂停，无法自动恢复

`[test1任务ID=194] [窗口=2026-09-03 13:15:25~13:20:28 CST] [业务=普通拉群] [协议=Web（父任务为混合协议）] [页面=/task/pull]`

页面现象：execution 持续显示“等待审批”；外部批准后页面不会自动刷新，后端也没有自动唤醒路径。

- 全链：`taskId=194 -> executionId=429 -> actionId=42602 -> commandId=cmd_2b9b29acc3584b0c9423d5e4d2d3c01f -> Web Outbox=SENT -> 协议结果=PENDING_APPROVAL`。
- 61 个样本中 status、reason、version、action 数全部不变；从 2026-09-01 22:20:13 CST 至本窗口结束已等待约
  39 小时。
- 调度 claim 不接受 APPROVAL 等待类型；UNKNOWN 协调器不处理 `PENDING_APPROVAL`；后续 `JOINED`
  回调的开放态也不包含 `PENDING_APPROVAL`。
- 旧设计明确“不实现获批后的自动唤醒”，与本次新验收条件直接冲突。

期望：审批通过事件或成员事实应原子地把该行唤醒一次；重复/迟到批准不得重复踩链接。

### GRP-003 P0：邀请码失效恢复尚无“已部署并有效”的验收证据

`[test1任务ID=194] [窗口=2026-09-01 22:18:00~22:23:00 CST] [业务=普通拉群] [协议=Web（父任务为混合协议）] [页面=/task/pull]`

页面现象：execution 最终为链接失效；前端状态选项遗漏 `GROUP_INVALID`，对应状态可能回退显示为 `-`。

- 全链：`taskId=194 -> executionId=428 -> actionId=42601 -> commandId=cmd_d8375aa234624ea9b67accbf443da9bb -> Web Outbox=SENT -> 协议结果=INVITE_REVOKED`。
- 当前 `group_link_preview` 有 JID 和邀请码，但当前码与冻结码相同，没有可观察的替代码；所以终态既可能是“恢复逻辑
  已执行但未取得新码”，也可能是部署路径未命中，不能仅凭终态二选一。
- 当前分支的恢复器支持 Web 完整 URL、Android 纯 code，并只重试一次；但 `knownGroupJid` 分支会优先做成员
  查询、绕过刷新器，存在组合缺口。
- 变更记录当时标记未部署；当前镜像时间晚于源码提交，但镜像 revision 为空，任务窗口内也没有可用恢复日志标记。

期望：新建一条同时满足“已知 JID、旧码失效、当前码已轮换、管理员未在群”的 test1 任务，保留首次失败、码观测、
唯一一次重试和最终结果四段证据；在此之前该项不能签字通过。

### GRP-004 P0：普通建群联系人阶段长期卡住

`[test1任务ID=127] [窗口=2026-08-14 02:18:00~02:23:00 CST] [业务=建群（建群营销前置基线，非专用建群营销表）] [协议=Web+Android] [页面=/group/list]`

页面现象：新建普群流程保持“运行中 / 正在准备联系人”，8 个群均不推进，页面没有可解释的全链错误。

- 父任务：`RUNNING`，8/8 item，成功 0、失败 0；父任务最后更新时间 02:20:44 CST。
- item 146~153 全部 `RUNNING/PREPARING_CONTACTS/CONTACT_PREPARE/SENT`；4 个 Web、4 个 Android。
- 每个 item 冻结 58 个成员和双向联系人命令，共 116 个 commandId；8 个 item 的 matching Outbox 均为 0。
- 样本链：`taskId=127 -> itemId=146 -> memberRowId=1972 -> creator commandId=cmd_0e48274950be4374ad5baa35444ecc41 / member commandId=cmd_08507639453c49df852cb198249332aa -> Outbox=不存在 -> creator保存=FAILED、member保存=PENDING`。
- 截至 2026-09-03 仍为 RUNNING，已卡约 20 天；这不是五分钟内允许的短暂等待。

期望：CONTACT_PREPARE 必须有超时、失败原因和恢复/终止出口；父任务汇总应随子状态更新。

### GRP-005 P0：普通建群父任务 FAILED、子项 RESULT_UNKNOWN，command 无 Outbox

`[test1任务ID=140] [窗口=2026-08-17 21:29:00~21:34:00 CST] [业务=建群（建群营销前置基线，非专用建群营销表）] [协议=Web] [页面=/group/list]`

页面现象：父级显示失败，但子项仍是“结果不确定 / 建群中”；页面、父汇总和协议链语义不一致。

- 全链：`taskId=140 -> itemId=168 -> commandId=cmd_e2a0ed30e09e4206aa317dd1db61d14e -> Outbox=不存在 -> 协议结果=PROTOCOL_RESULT_UNCONFIRMED`。
- 父任务 `FAILED`、`failed_count=1`；子项 `RESULT_UNKNOWN/CREATING_GROUP`，没有明确的人工核对或超时收敛。

期望：不确定结果不得伪装为确定失败；必须保留可核对状态、协议事实和资源占用，确认后再汇总。

### GRP-006 P1：速拉群营销失败轮次与协议 Outbox 断链

`[test1任务ID=54] [窗口=2026-07-27 22:10:00~22:15:00 CST] [业务=速拉群/建群营销] [协议=Android] [页面=/task/group-pull-marketing]`

页面现象：任务最终显示完成且资源已释放，但详情不展示 executionId、attemptId、commandId；运营无法从页面识别
五分钟内重复产生的失败轮次。

- 样本链 1：`taskId=54 -> executionId=12 -> targetId=405 -> attemptId=67159 -> commandId=cmd_4ca55c030b6b413fa5b77fbac778c7e3 -> Outbox=不存在 -> IMAGE_ASSET_NOT_FOUND`。
- 样本链 2：`taskId=54 -> executionId=14 -> targetId=406 -> attemptId=67160 -> commandId=cmd_34812714dd624f79bdce7ce06c763a12 -> Outbox=不存在 -> IMAGE_ASSET_NOT_FOUND`。
- 本窗口两个 target 各产生 10 个不同 round/command，共 20 个失败 command、0 个 Outbox；累计分别为
  216 和 215 个不同 command，全部在协议发布前失败。
- 这是周期营销轮次，不据此声称“协议重复发送”；恰恰因为 Outbox 为 0，本样本的协议副作用为 0。问题在于
  预检失败仍持续生成新轮次、页面隐藏链路、父任务汇总无法解释成本和失败历史。

期望：素材不可用应在任务/轮次前置校验并可见地阻断；每个失败 round 仍须可从页面追到 attempt 和失败原因。

### GRP-007 P1：任务完成后仍保留 TIMEOUT/UNKNOWN，页面无法解释最终收敛事实

`[test1任务ID=198] [窗口=2026-09-02 15:21:00~15:26:00 CST] [业务=普通拉群] [协议=Web+Android] [页面=/task/pull]`

页面现象：父任务和 execution 显示完成，但动作明细仍可见 TIMEOUT/UNKNOWN；页面没有 commandId、原始回执或
“已由成员事实收敛”的关联说明。

- 全链：`taskId=198 -> executionId=448 -> actionId=42837 -> commandId=cmd_635eb0ea7fb741ccb7f83cfdab8c6f9b -> Web Outbox=SENT -> action=UNKNOWN/TIMEOUT -> execution=COMPLETED`。
- 任务 4 个 execution 全部终态、拉手全部释放；任务整体包含 Web 和 Android。
- 该状态组合可能是业务事实已由旁路复核收敛、action 保留原始审计结果，但当前页面无法表达这层关系，因此不满足
  前端、数据库、协议结果一致且可解释的验收要求。

期望：保留原始 UNKNOWN 的同时展示最终裁决事实、裁决来源和时间，避免把审计历史误读为未收敛。

### GRP-008 P0：Web 页面本身不能完成全链验收

`[test1任务ID=197] [窗口=2026-09-02 14:42:00~14:47:00 CST] [业务=普通拉群] [协议=Web+Android] [页面=/task/pull]`

页面现象：任务能显示完成和 execution 行，但不展示 commandId、原始协议结果或协议后端，无法在 UI 内证明混合协议
和零重复副作用。

- DB 正向基线：task 197 `COMPLETED`，4 个 execution、0 个非终态、0 个未释放拉手；40 个 action command
  一一对应，协议后端包含 Web 和 Android。
- 前端 API/抽屉只展示 execution、角色、action/call/member 摘要，未暴露或未渲染 commandId 与原始协议结果。
- 普通建群 API 的 `creatorProtocolBackend` 在页面映射时被丢弃；建群营销 API 虽返回 commandId，详情抽屉仍隐藏。

期望：至少提供统一诊断抽屉或可下载证据，完整展示 task、execution/item、attempt/action、command、backend、
原始结果、最终裁决和资源释放。

### GRP-009 P1：群链接刷新后列表链接与 current invite 指针不一致，但列表链接仍有效

`[test1任务ID=199（下游验证；群链接刷新任务ID=220）] [窗口=2026-09-03 18:49:08~18:58:40 CST] [业务=普通拉群前置] [协议=Android] [页面=/group/list -> /task/pull]`

页面现象：对唯一勾选的专用两人测试群执行“批量刷新群链接”，结果弹窗显示“成功 / 邀请链接已更新”；但刷新后
页面列表链接对应的 `group_link.link_url` 仍不等于 `group_profile.current_invite_id` 指向的邀请码。后续直接打开列表链接，
WhatsApp 仍能正确展示目标测试群的“群聊邀请”页，因此“字段不一致”不能再推导为“列表链接失效”。

- 刷新任务 220：总数 1、成功 1、失败 0，完成时间 18:49:13 CST。
- 18:52:49 CST 只读复核：群与当前 invite 均健康且未封禁，current pointer 一致，但列表链接与当前邀请码不一致。
- 扩展扫描 660 个名称或备注明确含 ARMADA/CANARY/TEST/测试的测试资产，一致数量为 0；这说明两个字段的数据口径
  普遍不同，但不能证明任一链接无效。
- 目标、管理员组、拉手组、唯一 Android promoter、群成员和占用门禁均通过；列表链接又经 WhatsApp 页面实时验证，
  因此后续允许用该链接创建并启动 `taskId=199`。
- `taskId=199` 已证明该列表链接能完成管理员入群、拉手入群和真实拉人；此前“旧链必然失效、因此不能建单”的判断撤回。

期望：明确 `group_link.link_url` 与 `current_invite_id` 的产品语义；若允许并存多个有效入口，页面和诊断工具应显示各自
来源、有效性与更新时间；若只允许一个当前入口，则刷新成功必须原子更新任务实际消费的链接。

### GRP-010 P0：Android 正常拉人成功后群立即封控，父 COMPLETED 与子 FAILED 同时成立

`[test1任务ID=199] [窗口=2026-09-03 19:28:33~19:33:50 CST；19:53:05补充核验] [业务=普通拉群] [协议=Android] [页面=/task/pull]`

页面现象：刷新后的任务列表显示“已完成、1/1、有效成功率 100%、异常群组 1”；execution 明细显示“执行失败 / 群已被封禁”，
逐成员结果显示唯一目标“成功 / 入群成功”。这些是三个不同维度的真实事实，但页面没有明确解释“父任务已完成”并不等于
“所有群执行成功”。

- 启动前 19:28:06 `READ ONLY` 门禁：`WAIT_START`、唯一 `executionId=449`、1 个目标；action、pull-call、wave、
  attempt、Outbox 均为 0；群和邀请码健康，无外部普通/营销占用或调度锁；管理资源 4/4 ready、拉手资源 7/8 ready，
  候选全部为 Android。
- 19:28:33 只点击一次启动。7 个准备动作（双向保存联系人、管理员踩链接、提权、放开加人、关闭审批、邀请拉手）
  全部 `SUCCESS`；唯一 pull-call 为 `WRITTEN_BACK`，目标 material 与显式 attempt 均为 `SUCCESS`，成员事实确认目标实际在群。
- 全链为 `taskId=199 -> executionId=449 -> 7 action + 1 pull-call -> 8 个唯一 commandId -> 8 条 Android Outbox=SENT -> 协议结果`；
  8/8 commandId 非空且一一对应，Outbox retry=0，没有同一业务事实的第二条 Outbox。
- `GROUP_BANNED` 首源不是上述业务 command 的失败回调，而是独立的 Android `group.health_reported` /
  `GROUP_HEALTH` / `CHAT_SUSPENDED` 实时事件；首个协议发生时间 19:29:16.773，Armada 最先接收时间
  19:29:17.793，execution 于 19:29:19.035 失败收口。共收到 5 条同源风险事件，包含终态后的迟到事件，
  但没有新增动作、拉人或提权。
- 数据库已在 19:29:19 终态收口后，详情刷新能看到 execution 失败，但返回列表仍一度保持“进行中、0/1、无异常”
  并保留暂停/结束按钮；约 19:35 手动点击列表“查询”后才更新为“已完成、1/1、异常群组 1”。因此最终数据可对齐，
  但前端快照不会自动及时收敛。
- 19:33:50、19:37:39 与 19:53:05 复核：7 action、1 call、8 Outbox 数量保持不变；0 open、0 dead、0 retry、0 UNKNOWN、
  0 重复副作用；puller 已释放，群占用和调度锁均为 0，三个角色账号仍健康在线。
- 调度资源虽已释放，但协议现场仍保留 promoter、manager、puller 和目标的在群事实；同时 group profile 已是
  `banned=1/CHAT_SUSPENDED`，current invite 仍是 `health=1/banned=0`。这不等于调度占用泄漏，但属于需要明确清理/隔离
  规则的终态残留，尤其不能让该 invite 再被新任务选中。
- 19:53:05 修正后的诊断器在真实 test1 编译执行通过：`runtime=running`、`configBad=0`、`terminalMissing=0`、
  `terminalExcess=0`；异常只剩 `groupBanned=1` 与父完成/子失败对应的 `terminalShapeBad=1`。
- 数据库父任务为 `COMPLETED`，唯一 execution 为 `FAILED/GROUP_BANNED`。若 `COMPLETED` 只表示“所有 execution 已终止”，
  当前聚合可解释；但列表应明确区分“处理完毕”和“业务成功”，否则 100% 容易被误读为正常验收通过。

结论：单目标拉人和幂等/释放门禁通过，但正常 Android E2E 因群封控不通过；本轮按止损策略不再扩大到第二个群。

## 两路静态审计的阻断项

以下问题已由代码路径确认，但 test1 当前没有满足条件的真实任务；它们是待执行问题池，不计为已完成的真群验收。

### 普通拉群

1. `PENDING_APPROVAL` 自动恢复功能不存在；见 GRP-002。
2. `knownGroupJid + INVITE_REVOKED` 会走成员复核并绕过邀请码刷新，缺组合回归。
3. 任务/单群结束后的首个迟到 manager-join 或 puller-invite 回调会先改事实、再因唤醒终态 execution 失败而
   整体回滚，违背“终态不复活但迟到事实可收敛”。
4. manager-join 路由丢失 `attemptNo`；同 command 多 attempt 无法精确隔离。
5. 批量拉人 `UNKNOWN -> FAILED -> SUCCESS` 的最终值依赖回调顺序；SQL 没有 occurredAt 单调保护。
6. 正向防线：UNKNOWN 复核路径明确不重放拉人、提权等真实副作用；任务 193 的五分钟采样也未新增 command。

### 速拉群 / 建群营销

1. Android 对同一 command 的 `SEND_RESULT_UNKNOWN` 明确不重发；Armada 却把 `success=false` 统一当确定失败，
   换号并生成新 commandId，存在真实消息已发送后再次触达的 P0 风险。
2. 建群营销换号后仍使用固定 item operationId；幂等层可能回放旧账号创建的旧群，新账号不在群却继续营销；
   幂等记录过期后又可能创建第二个群。
3. 专用建群营销 scheduler 只扫描 PENDING；`GROUP_CREATING`、`MARKETING_SENDING` 无 watchdog/租约，进程崩溃
   或丢回调可永久卡住。
4. Stop 只改 task/item，不能取消专用 Outbox；已认领 worker 在外部动作前不重查父任务，页面停止后仍可能建群/发消息。
5. Android 禁言失败是 best effort 但仍返回建群成功；Web 把 announceOnly 作为建群请求字段，跨协议成功语义不一致。
6. 联系人“预保存”只代表异步提交成功，建群不等待真实保存结果；后台失败仍可能被摘要成成功。
7. 速拉群除建群外的加人、提权、禁言、联系人保存、退群缺少稳定 operationId；租约接管可能重做外部动作。
8. 释放流程会无限等待 status=PENDING 的营销 attempt，缺回调时账号/群链接可能长期 `RELEASING`。

## 待创建的 test1 验收任务

只有在明确授权真实账号、群、联系人和营销触达后才能执行。创建后必须把“待创建”替换为数据库真实 taskId。

| 用例 | test1任务ID | 约5分钟窗口 | 业务 / 协议 | 必须观察的页面现象与出口 |
|---|---|---|---|---|
| PL-CANARY-NORMAL | 199 / execution 449 | 19:28:33~19:33:50（19:53补核） | 普通拉群 / Android | 1 个目标真实入群；随后群被 `CHAT_SUSPENDED`，execution 失败、父任务完成；零重试/重复，资源释放；E2E 不通过 |
| GCM-UNKNOWN | 待创建 | T0~T0+5m | 建群营销 / Android | 消息实际提交后丢回执；只能显示待核对，不换号、不生成新 command、不二次触达 |
| GCM-STUCK | 待创建 | T0~T0+5m | 建群营销 / Web、Android | 在认领后和 Outbox 发送后中断；必须自动恢复或明确超时，不永久停在建群中/发送中 |
| GCM-SWITCH | 待创建 | T0~T0+5m | 建群营销 / Web->Android、Android->Web | 换号后只能有一个有效群，新账号必须真正在群，营销只能一次 |
| GCM-MUTE | 待创建 | T0~T0+5m | 建群营销 / Web、Android | Android 禁言失败不得显示完整成功；页面、DB 与群实况一致 |
| GCM-CONTACT | 待创建 | T0~T0+5m | 建群营销 / Web、Android | 联系人保存失败必须阻断或真实显示异步失败，不能把“已提交”当“已保存” |
| GCM-STOP | 待创建 | T0~T0+5m | 建群营销 / Web、Android | 状态 2/3 停止后不得再建群/发消息；Outbox 取消，账号和群资源释放 |
| GPM-LEASE | 待创建 | T0~T0+5m | 速拉群 / Web、Android | 各外部阶段超时并触发租约接管；最终收敛且协议动作零重复 |
| MIX-TRACE | 待创建 | T0~T0+5m | 两路线 / 混合 | Web 建群+Android 营销及反向组合，全链一键可追踪且三方一致 |

## 验收出口矩阵

| 出口 | 结果 | 证据 |
|---|---|---|
| 不允许无原因卡住或反复跳状态 | FAIL | #193 五分钟 41 次切换；#127 卡约 20 天 |
| 等待审批可自动恢复 | FAIL | #194 execution 429；代码不存在恢复路径 |
| 邀请码失效恢复已部署且有效 | FAIL / NOT PROVEN | #194/428 无替代码；刷新任务 220 的列表链接虽与 current pointer 不同但实测有效，不能覆盖“失效后恢复”用例 |
| 重试零重复副作用 | PARTIAL PASS / BLOCKED | #199 到 T+24m：8 个唯一业务 command、0 retry、0 重复；5 条同源迟到风险事件未触发业务重放；营销 UNKNOWN 风险仍未验收 |
| 任务结束无账号/群链接泄漏 | PARTIAL PASS / BLOCKED | #199 调度 puller、群占用和锁均释放，但角色/目标仍在封禁群且 invite 仍标健康；专用建群营销仍无真实数据 |
| 前端、数据库、协议结果一致 | FAIL | #199 父“完成/100%”缺少语义解释，且 group 已封而 invite 仍健康；#140/#198 的历史矛盾仍在 |
| taskId -> executionId -> commandId -> 协议结果 | FAIL | 页面普遍不展示 commandId/原始回执；synthetic 直调和普通建群 command 无 Outbox |
| Web、Android、混合协议完整通过 | FAIL | #199 跑通 Android 拉人但群随即封控，不能签正常 E2E；Web、混合和专用建群营销仍无本轮完整真群任务 |

## 本轮验证记录

- `bash armada-deploy/tools/pull-task-diagnose.test.sh`：PASS。
- Web 拉群相关定向测试：13 suites / 60 tests / 60 passed / 0 failed；但主要是状态映射和源码契约，未覆盖上述真群边界。
- 本机没有 Java 17 和 Go，后端/Android 聚焦测试未执行；这是环境缺失，不是测试失败，也不能算通过。
- `deploy-test.sh --env test1 --all --dry-run` 被本机缺 Java 17 阻断。
- `deploy-test.sh --env test1 --check` 还被本地缺少 Android fleet 配置阻断；本轮未部署。
- 首轮 test1 登录态不可用，未执行已登录 UI smoke；当时浏览器只确认站点落到“登录 | 第一套环境”。
- 追加轮次已完成已登录 UI smoke；群链接刷新任务 220 后列表链接与 current invite 指针仍不一致，但列表链接经
  WhatsApp 页面直接验证有效，并被 #199 实际消费成功。
- Android 普通拉群金丝雀 #199 按 1 群、1 目标、1 管理、1 拉手、并发 1、自动启动关闭配置；启动前零动作门禁通过，
  只启动一次。目标真实入群，随后群被 `CHAT_SUSPENDED`；T+5m/T+24m 均确认零重试、零重复副作用和资源释放。

## 修复优先级

1. 先修普通拉群 #193 循环和审批自动恢复。
2. 统一 UNKNOWN 语义：UNKNOWN 不能换新 command 自动重做真实副作用。
3. 补建群营销 2/3 状态 watchdog、Stop/Outbox 栅栏及换号后的建群幂等模型。
4. 补全前端全链、协议后端、最终裁决和资源释放视图。
5. 再创建受控 test1 任务执行 Web、Android、混合协议真群验收。
