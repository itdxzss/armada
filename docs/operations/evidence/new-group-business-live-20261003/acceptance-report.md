# NEW_GROUP 真实业务验收续验记录

2026-10-03，Asia/Shanghai。结论：**第一套 test1 受阻；第二套 perf2 失败。两套均未通过完整新建拉群业务验收。**

本报告承接同日 `../new-group-business-acceptance-20261003/` 的源码、本地测试与初次预检。用户随后明确指定 A8-XA748.txt、A8-XA747.txt，并要求每次拉1–3人，受控料子缺口已解除；前一报告的“没有料子、未创建任务”是当时状态，不是本次最终状态。

## 本次实际任务

| 项目 | test1 第一套 | perf2 第二套 |
|---|---|---|
| 结论 | 受阻：建群当刻Web账号STALE，创建结果UNKNOWN，执行自动暂停 | 失败：资料核验通过后卡在管理员入群回写 |
| taskId / executionId | 218 / 659 | 52 / 257 |
| 群名 | NG验收-test1-20261003-1233 | NG验收-perf2-20261003-1230 |
| 实际建群人 | 1609 / 尾6650 / Web | 999 / 尾2815 / Android |
| 实际次管理员 | 1610 / 尾2107 / Web | 1038 / 尾4355 / Android |
| 实际拉手 | 尚未选号；候选220组1792/尾7120、1795/尾5205，Android | 尚未选号；候选664组821/尾7358，Android |
| groupJid | 数据库NULL，尚无可确认的真实群JID | 120363411384572796@g.us |
| 创建操作标识 | ptgc:1:659 | ptgc:1:257 |
| 资料commandId | 未产生 | cmd_5827c8d52eb14a5c9a51a1dac1c78e3c |
| 资料协议结果 | 未执行 | SUCCESS，原始事件日志可对应 |
| profile_verified_at | NULL | 1791002326522，即12:38:46.522 |
| profile_verified_command_id | NULL | 与上述资料commandId相同 |
| 首笔料子命令时间/结果 | 不存在 / 未执行 | 不存在 / 未执行 |
| 数据库任务/执行状态 | 父任务EXECUTING；执行manual_paused=1，GROUP_CREATE/步骤2 | EXECUTING；MANAGER_JOIN，manual_paused=0，异常后30秒退避 |
| 料子 | 6条全部待执行，0次尝试；实群结果未确认 | 6条全部待执行，真实群中0/6 |
| 最终真实成员 | 无可确认JID，无法完成逐群成员验收 | 仅999群主(superadmin)、1038普通成员；次管理员尚未提权 |

角色来自本次执行表，候选不冒充实际选号。由于流程提前停止，两套均未形成可核验的三个实际角色账号闭环。Android成功创建了本次真实群；Web仅执行到建群结果未知，**不能宣称Web与Android均完成真实建群覆盖**。

## 料子与表单

两个原文件各201条有效且文件内不重复。延续用户最初“少量验收”的范围，各取前6条：XA748用于test1，XA747用于perf2；原文件未修改，未执行全量201条。选取行号、原文件及验收副本SHA256、号码尾号见 `materials-manifest.json`。完整号码样本不放入本证据目录。

任务通过真实租户页面填写、上传与提交；先关闭自动启动，核对落库配置和当前账号状态后点击启动，没有直接插库。两套均为1群、拉手1、初始站台0、每次附带站台0、前期固定次数0、每次1–3人、间隔10–15秒、群资料开启且BEFORE_PULL、禁止文件名作为群名，关闭无关营销。配置原文见 `expected-form.json` 和两套数据库快照。

简介包含真实换行，实际填写为：

test1：
```text
NEW_GROUP 真实业务验收｜test1｜2026-10-03 12:33
群名称和简介回读匹配并持久化后，才允许拉入受控料子。
```

perf2：
```text
NEW_GROUP 真实业务验收｜perf2｜2026-10-03 12:30
群名称和简介回读匹配并持久化后，才允许拉入受控料子。
```

perf2最终真实回读的群名、简介（包括中文与换行）和群JID全部匹配。test1未取得群资料回读结果，不能以数据库表单值代替真实群资料。

## 账号重新核验

test1在12:29–12:30重新查询数据库限制、各业务占用及协议在线；提交后又于12:40:18–25读取两Web账号ONLINE/wsOpen、两Android候选明确在线，并于12:41:23完成最终外部任务占用复查。155组“沙特建群号”用于建群人及次管，源码按已选择ID排除确保两账号不同；拉手220组“926”独立。最终执行确认实际角色1609与1610，尚未选拉手。

原候选148/149组仍受普通建群任务127等占用，219组受进群任务79计划用途占用，未使用或释放。没有因为数据库显示在线就忽略暂停、计划任务占用。

perf2于12:36:32–42刷新数据库、限制及跨业务占用，12:36:49通过Android只读接口确认999、1038、821在线；最终预启动快照12:37:38。组658、659、664各一账号，无其他活跃/暂停占用；本任务52自身WAIT_START引用单独排除。实际执行只选出999、1038。

所有账号状态都是当时的验收证据，不应沿用为下次恢复时的在线结论。

## perf2：真实时序与失败因果

| 北京时间 | 证据事实 |
|---|---|
| 12:38:00.551 | task52启动 |
| 12:38:01.529 | 建群结果中的joined_at记录时间，已确认groupJid及初始角色IN_GROUP事实 |
| 12:38:06.767 | 资料动作4253/attempt1提交 |
| 12:38:06.834 | group.profile.apply Outbox发送，同一commandId |
| 12:38:08.560 | 资料协议结果时间；12:38:09.588后端收到原始SUCCESS事件 |
| 12:38:37.728 | 第一次匹配核验凭证的记录时间（12:38:41快照确认已持久化） |
| 12:38:41.673 | 中间数据库快照：凭证已存在，第一笔料子命令仍NULL，6料子未消费 |
| 12:38:46.522 | 步骤6再次匹配回读的核验记录时间，同一commandId |
| 12:38:51.690 | 首次“管理员踩链接结果回写失败” |
| 12:43:21.637 | 最终业务数据库快照仍在MANAGER_JOIN，无料子命令 |
| 12:43:37.942 | 独立最终真实资料/成员回读：群名、简介、JID匹配，仅2个角色成员，0/6料子 |
| 至12:43:52 | 已抓到11次同错，约30秒一次；12:47页面仍显示进行中 |

步骤4(APPLY_PROFILE)与步骤6(APPLY_BEFORE_PULL_SETTINGS)都会回读资料并写本次核验时间，因此37.728更新为46.522。两次均为动作4253、attempt1、同一资料commandId；Outbox只有一条，retry_count=0，没有重发资料命令。步骤7登记群只检查凭证。此次协议结果确为SUCCESS，不是线上UNKNOWN场景。

表中数据库字段时间是业务记录的时间值，不等同于精确事务提交时刻；中间数据库快照另外证明了已提交事实。这里只能证明**已有匹配核验凭证时尚未下发任何料子命令**。由于首笔料子命令始终未产生，`proof_lead_ms=NULL`，无法完成用户要求的“核验时间严格早于实际第一笔料子命令”的正向链路验收，也无法实测批次1–3人与10–15秒间隔。初始次管理员随建群入群属于角色成员，不算提前拉料子。

直接缺陷：NEW_GROUP次管理员已是IN_GROUP且不存在JOIN_BY_LINK动作；`PullTaskManagerJoinTransactionService.prepareExisting`先插入PENDING动作，随即调用complete回写成功；`PullTaskAccountActionMapper.writeBackResult`只接受SUBMITTED前态，真实SQL更新0行并抛异常。外层事务把动作插入、阶段推进一起回滚，调度器只退避，不自动暂停，故反复停在MANAGER_JOIN。

这是共享Java状态机条件性缺陷，不限Android。正常新群初始次管成功入群、仍在线可用且无JOIN动作时都会落入此分支；已有群只有满足“本地IN_GROUP、仍在MANAGER_JOIN、动作缺失或非SUBMITTED”的组合才受影响，不能泛称所有已有群都失败。

最小修复建议：在创建JOIN动作之前，单独处理已确认IN_GROUP且JID有效的事实推进，保留租约/版本CAS，推进到MANAGER_ADMIN；不伪造已下发的踩链接命令，不覆盖旧动作的真实协议事实，不全局放宽Mapper的PENDING回写。补真实Mapper/H2连续测试贯穿建群登记→管理员入群阶段。精确源码行号、测试为何漏过和完整影响分析见 `review.md`。本次未实施修复。

## test1：未确认现场

12:45:33启动；协议日志12:45:33.967记录建群请求转发至worker w2，12:45:36.867返回HTTP503、`ACCOUNT_UNAVAILABLE`，details为`account_not_online`、`state=STALE`，traceId为`945e02ffb85b48caa8cf6785a21209ba`。建群人1609在调用当刻不可用，不能把12:40的在线检查当作后续一直在线。执行659在创建WhatsApp群步骤进入 `GROUP_CREATE_RESULT_UNCONFIRMED`，`manual_paused=1`，groupJid和两列profile proof均NULL。资料动作、拉人调用和料子Outbox均未产生。页面明确显示“建群结果未知，已停止自动重建”。创建操作标识为 `ptgc:1:659`，未恢复、重放创建或新建替代任务。

12:52:45对实际创建者1609的只读真实参与群列表查询返回12个群，没有发现本次唯一群名。该次列表缺少匹配项不足以证明WhatsApp绝对未建群，仍保持UNKNOWN；无已确认JID，无法继续真实资料及成员核验。12:55:05原操作Redis幂等记录仍为PROCESSING、has_result=false，未取得确认结果。最终日志与幂等查询结论见 `test1/test1-live-report.txt`。

当前源码解释：Web在线守卫在调用groupCreate之前抛出ACCOUNT_UNAVAILABLE，Java ProtocolErrorCode没有对应枚举，ProtocolHttpExecutor:323–333将其归为UNKNOWN；IdempotentGroupCreatePort只把ACCOUNT_NOT_ONLINE等明确前置拒绝认作确定未创建，因此本次保留PROCESSING并暂停。最小修复建议只对明确发生在建群调用之前的ACCOUNT_UNAVAILABLE + account_not_online做精确映射，并补HTTP错误映射/幂等回归；不能泛化所有503或UNKNOWN。当前原操作仍需对账，不删除幂等键、不自动重建。该建议未实施。

12:56:52最终快照确认仍保持上述状态。安全恢复方向应是先完成原操作幂等状态/协议结果对账，确认实际JID后沿原执行处理；不能为通过验收直接重建群。没有证据时不把UNKNOWN改写为失败或成功。

## 源码、本地测试、真实业务分别结论

当前源码规则：NEW_GROUP后端独立校验trim群名1–100、简介1–1024，强制资料开启/BEFORE_PULL；建群请求携带群名及次管/初始站台，随后独立设置简介；真实metadata匹配后以CAS持久化核验凭证，实际batch-add入口再次检查两列凭证。旧链接模式资料可选。源码引用、版本及既有负向矩阵见前一轮 `../new-group-business-acceptance-20261003/backend/backend-report.txt`。

本次验收全过程已运行：后端原16类135项通过；新发现后补跑既有ManagerJoin Mockito类11项通过，共17类146项；前端56项通过；Web57项通过；Android22个顶层、33个子测试通过。均无失败/错误/跳过，未跑全仓回归，未新增或修改测试。补跑结果见 `manager-join-existing-tests.{json,log,xml}`，前期其余结果在前一证据目录。

新增实证覆盖缺口：`managerAlreadyInNewlyCreatedGroupAdvancesWithoutSubmittingJoinCommand`已覆盖IN_GROUP+无JOIN动作输入，但直接mock `writeBackResult=1`，掩盖真实SQL条件；真实H2建群测试结束于MANAGER_JOIN，未继续这一阶段。因此这些测试全绿不能证明本次真实业务可通过。

| 负向/兼容要求 | 当前证据 | 剩余边界 |
|---|---|---|
| 空白、超长资料拒绝 | 原前后端负向测试通过；本轮perf2完整页面有效料子/角色下，空白群名、空白简介均阻止创建 | 精确100/1024正向边界专项未覆盖；超长真实页面提交未单独测试 |
| 绕过前端仍拦截 | 创建事务调用validator；H2非法必填开关不落库 | 空白/超长完整HTTP入口专项缺失，未用共享环境造非法任务 |
| 设置成功但回读简介不匹配不拉料 | 既有H2验证同JID、暂停、pull_call=0 | 未在共享环境制造不匹配 |
| 查询失败/超时保留同群暂停 | Processor异常传未确认；H2超时同JID暂停 | 缺异常一路贯穿H2的单条集成；test1是建群结果未知，不冒充资料查询超时案例 |
| 缺核验凭证实际拉人入口拦截 | H2三种缺凭证变体，无料子提交；有凭证对照放行 | 未篡改真实proof列 |
| 旧命令、旧尝试不能推进 | command/attempt/version/租约/tenant及事务回滚用例通过 | 未篡改真实回执 |
| UNKNOWN依真实回读判定，不改协议结果 | 既有H2匹配写proof且action保持UNKNOWN | 本轮perf2实际为SUCCESS，无线上UNKNOWN资料样本 |
| 已有群链接资料可选 | 源码、validator/writer和兼容测试通过，页面旧模式仍可选 | 双空资料完整HTTP专项、真实旧群任务未跑 |

## 证据与现场边界

- `perf2/task-52-report.json`、`task-52-running-1.tsv`、`task-52-blocked-final.tsv`、`task-52-readback-final.json`、`task-52-backend-focused.log`：资料命令→核验凭证→零料子→管理员回写故障。
- `test1/`：账号刷新、创建前后数据库快照、UNKNOWN暂停、只读协议及幂等排查。
- `ui/`：真实页面截图与脱敏详情，表单操作及负向阻止创建记录。
- `materials-manifest.json`、`expected-form.json`：指定文件/抽样/表单与任务ID对应。
- `review.md`、`manager-join-existing-tests.*`：独立源码复核与新增针对性现有测试结果。

数据库只持久化核验时间/commandId，没有保存首次metadata完整响应，因此报告不声称已获得首次回读原始响应全文；独立最终实时回读内容另存。原始协议结果与业务判断始终分开。HTTP200、入队、ACK、页面状态和本地测试均未单独作为业务成功依据。

本次没有改业务源码、测试或部署，没有清理旧任务、释放占用、批量改账号、篡改真实回执或中断共享服务。test1保留自动暂停现场；perf2保留运行中退避现场，未擅自恢复、暂停、结束或重建任务。后续恢复前仍须重新检查账号在线和占用。
