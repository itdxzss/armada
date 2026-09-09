# 拉群板块功能测试与压测用例

> 状态：`ACTIVE_MATRIX / SIM_LOCAL_LOAD_PASS / H2_OUTBOX_CHAIN_PASS / SIM_INTEGRATION_PENDING / LIVE_HAPPY_PATH_BLOCKED`
>
> 套件：`group-task-test-load-v2`
>
> 说明：除第 1.2 节记录的 test1 `#199` 风险路径外，其余仍须按新 runId 执行；`#199` 不代表正常链通过。

## 1. 统一执行口径

每次执行必须生成唯一 `runId` 和 `environment`，并把表内 `{{<caseId>_<protocol>_taskId}}` 替换为该环境的真实任务 ID；
只有 `PF-L*` 强制是 test1 taskId，`PF-S*` 使用隔离环境 taskId 并在 test1 任务 ID 栏填 `N/A/NOT_TEST1`。
Web、Android“各一轮”必须拥有两个独立 runId/taskId；混合协议则使用一个明确包含两种 backend 的 taskId。每一条问题池
记录至少包含；空载基线 `PF-B00-*` 是唯一没有任务 ID 的例外，必须填 `N/A/NO_TASK_BY_DESIGN`：

```text
caseId + runId + environment + 环境任务ID + test1任务ID或N/A + 精确CST时间窗 + 普通拉群/速拉群/建群营销
+ Web/Android/混合 + 页面现象 + expected/actual + executionId/itemId
+ actionId/attemptId + commandId + 协议event/result + 副作用账本 + 结论
```

统一规则：

- 正向 E2E 的 `T0` 是任务提交/启动时刻；阶段异常用例的 `T0` 是首个目标 command 进入 SENT、故障注入或人工操作
  时刻。主观察窗为 `[T0-30s,T0+4m30s]`，窗口结束后允许再观察最多两个已配置业务超时周期。
- `NOT_RUN`、`BLOCKED`、`FAIL`、`PASS` 必须严格区分。没有真实 `taskId` 和四平面证据不能记 PASS。
- 页面文案可与本文不同，但必须展示真实阶段、阻塞/失败原因、协议后端、最后业务动作时间和下一步。
- DB 查询默认只读；故障只能由隔离协议桩或已授权注入器制造，不直接改业务状态。
- 物理副作用类型：`G` 建群、`A` 加人/拉人/踩链接、`P` 提权、`M` 禁言、`C` 保存联系人、`S` 营销发送、`L` 退群。
- `REPL` 表示替换 execution 数，是状态机计数而非物理协议副作用；它单独做 expected/actual/duplicate 对账。
- 每例从 fixture 生成 `exp(X)`，协议账本记录 `act(X)`，并计算 `dup(X)=max(0,act(X)-exp(X))`。请求或结果事件
  可以重复，但 `dup(G/A/P/M/C/S/L)` 必须全部为 0；本文不以 `G=0` 之类写法表示正常流程的实际动作数。
- 每条用例都要核对页面、API/DB、Outbox/Kafka、协议实况；缺任一平面则为 `BLOCKED/EVIDENCE_GAP`。

所有表格行为“用例索引 + 核心 oracle”。实际执行前必须用第 5 节模板展开为单独 run card，补齐明确注入层/触发时刻、
DB 字段和值、协议请求与结果次数。单条 PASS 的统一机械条件为：页面达到该行预期，DB 状态和父子计数满足该行约束，
协议默认 `act=exp`；Stop/UNKNOWN 行可显式给出允许范围，但必须通过协议事实把实际值核清且 `dup(all)=0`；本例全链
关联率 100%，且本例要求释放的资源均已释放。任一条件缺证据即 BLOCKED。
表中的 `T0~T0+5m` 是 `[T0-30s,T0+4m30s]` 的简写。

历史 taskId 只用于说明回归来源，不能直接作为新套件结果。根据前序只读侦察，`PL-R06`、`PL-A01` 等目前属于
“预计红灯”用例；其价值是把修复目标固定下来，而不是在执行前假定通过。

### 1.1 需求覆盖索引

| 需求点 | 覆盖用例 |
|---|---|
| 普通拉群：管理员资源 | `PL-R01~R07` |
| 普通拉群：邀请码失效 | `PL-I01~I03` |
| 普通拉群：入群审批 | `PL-A01~A03` |
| 普通拉群：提权、拉手进群、联系人、批量拉人 | `PL-P01~P02`、`PL-J01~J04` |
| 普通拉群：异常换群 | `PL-S01A~S01D` |
| 普通拉群：任务收口 | `PL-S02~S05` |
| 速拉群/建群营销：换号 | `FG-E01~E02`、`CM-E00`、`MK-I01M` |
| 速拉群/建群营销：联系人预保存、建群、禁言 | `CM-F01~F03`、`CM-E01~E08`、`FG-I01` |
| 速拉群/建群营销：发送、UNKNOWN、重复/乱序回执 | `MK-I01A~I03`、`MK-A02A~A02B` |
| 父任务汇总 | `CM-A01`、`FG-A01` |
| 结束后资源释放 | `FG-S01~S04`、`CM-S01~S04N`、`MK-S02` |
| 全链与三方一致 | `PL-T01~T03`、`MK-T01` |
| Web、Android、混合与压测 | `PL/FG/CM` 正向例、`PF-*` |

### 1.2 当前执行基线与分层口径

test1 `#199 / execution 449 / 2026-09-03 19:28~19:35 CST / 普通拉群 / Android` 已产生一次真实结果：目标先
入群成功，随后独立 `CHAT_SUSPENDED` 群健康事件使 execution 以 `GROUP_BANNED` 失败；5 个重复/迟到风险事件未产生新
command 或重复副作用。父任务生命周期为 COMPLETED，但子项业务失败；页面在人工查询前仍显示进行中，封禁群的当前
邀请码也没有同步失效。

本地 H2 最新结果：`PL-R01/R02` 通过；`PL-R06`、`PL-A01`、`PL-I01/I02` 和 `PL-S04` 确认红灯。JOIN 的
`TIMEOUT/UNKNOWN` 回调没有 groupJid 时，复核分支会绕过 Outbox 直接再次调用 join，无法用第二个
commandId 追踪，且存在重复踩链接风险。PENDING_APPROVAL 能正确暂停，但 APPROVAL 不在调度领取集合中，
连续 5 个未来调度周期都未自动恢复或执行成员复核。
Web/Android 的 `INVITE_REVOKED` 回调携带已知 JID 时，后续只查成员而不读取已存在的新邀请码，
执行行在资源等待和执行中之间循环，不能完成唯一一次恢复。
同 commandId 成功后的 3 次迟到失败不会覆盖成功事实；但 execution 终态后的首个 SENT 成功回调会整体回滚并抛异常，
协议事实不能留存审计。

该结果归入 `PL-S01C` 风险回归，结论为 `FAIL`，不是 `PL-F02` 的正常 Android PASS。其完整时间线和资源残留见
`docs/operations/2026-09-03-group-task-test1-acceptance-pool.md`。封控解除前：

- 所有 test1 真协议正常链及故障用例均为 `BLOCKED/RISK_WINDOW`，不得通过更换真群重复试错；同 caseId 的
  `stateful-sim` run 可在对应 W0 oracle 冻结后执行；
- 正常 Web/Android/混合与 P0 故障先在 `stateful-sim` 执行，任务 ID 填隔离环境真实 taskId，test1 列填
  `N/A/NOT_TEST1`；
- Crypto Loopback 的 PASS 只表示密码学热路径通过，不能填写为本表任一业务用例 PASS；
- `PF-*` 容量与故障风暴只在 `load-sim`/专用性能环境执行。

## 2. 普通拉群用例

### 2.1 主流程与资源场景

| 用例 ID / 优先级 | test1 任务 ID / 约 5 分钟窗口 | 业务 / 协议 | 前置与步骤 | 页面现象 | DB、协议与通过条件 |
|---|---|---|---|---|---|
| `PL-F01 / P0` Web 正常闭环 | `{{PL_F01_W_taskId}}`；`T0~T0+5m` | 普通拉群 / Web | 有效群、足量管理员和拉手、5 个白名单成员；无注入；创建并启动任务 | 状态只向前推进，展示选中资源、当前批次、成功数；最终完成 | execution/action/call/command/result 全链唯一；`act=fixture exp`；`dup(all)=0` |
| `PL-F02 / P0` Android 正常闭环 | `{{PL_F02_A_taskId}}`；`T0~T0+5m` | 普通拉群 / Android | 同 F01，所有群动作走 Android；无注入 | 页面展示 Android，终态和 Web 同语义 | Android 结果可反查 command；成员、角色和计数一致；`act=exp,dup(all)=0` |
| `PL-F03 / P0` 混合协议父任务 | `{{PL_F03_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 同一父任务包含 Web、Android execution，各 1 群、3 个成员；Web 行暂时缺资源，T+120s 恢复 | 仅目标行等待，其他行继续；各行展示真实后端；父任务不提前完成 | command 只到指定后端；子项聚合等于父项；跨协议串线 0；`dup(all)=0` |
| `PL-R01 / P0` 管理员不足稳定等待 | `{{PL_R01_W_taskId}}/{{PL_R01_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层：管理员候选查询；候选为空，连续观察至少 5 个调度周期 | 稳定显示“管理员不足”及原因，不出现“管理员不足→执行中→管理员不足” | 不生成真实群 command、不刷新伪业务时间、版本不空转；`exp(all)=act(all)=0` |
| `PL-R02 / P0` 管理员补充后单次恢复 | `{{PL_R02_W_taskId}}/{{PL_R02_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层：资源池；T0 资源不足，T+2m 加入唯一合格管理员 | 先等待，资源到位后只恢复一次并继续，不反复跳态 | 只绑定一个管理员，只生成当前阶段 command；`act=exp,dup(A/P)=0`，最终收敛 |
| `PL-R03 / P1` 受限/离线/占用资源过滤 | `{{PL_R03_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 候选池仅含离线、受限、已占用账号，随后补一个合格账号 | 明确显示“无可用资源”，恢复后展示真正选中的账号 | 不选中负例账号，不跨任务抢占；占用唯一且终态释放 |
| `PL-R04 / P0` 人工补管理员双入口 | `{{PL_R04_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 注入层：操作入口；两个等待 execution 分别选“账号踩链接”和“当前管理员邀请”，再提权并恢复检查点 | 补充前稳定等待；补充后显示入群、校验、提权，不跳阶段 | 两行各一次入群和提权；全任务 `exp(A)=2,exp(P)=2`；`act=exp,dup(A/P)=0` |
| `PL-R05 / P1` ACCOUNT_NOT_FOUND 后人工补充 | `{{PL_R05_W_taskId}}`；`T0~T0+5m`；历史映射 `#194/exec431` | 普通拉群 / Web | 注入层：join 协议结果；冻结管理员返回 ACCOUNT_NOT_FOUND，观察两分钟后人工选新管理员 | 原账号原因清楚且稳定；人工补充后只恢复一次 | 旧 membership 确定失败且不复用；新账号只执行一次补充链；`dup(A/P)=0` |
| `PL-R06 / P0` UNKNOWN 补管理员循环回归 | `{{PL_R06_W_taskId}}`；`T0~T0+5m`；历史映射 `#193` | 普通拉群 / Web | 注入层：synthetic join 结果与成员复核；action/membership=UNKNOWN，成员快照中无目标 | 稳定停在有原因、可处理状态；状态往返次数 0，不出现“管理员不足→执行中→管理员不足” | UNKNOWN 不得作为可用资源；只允许只读复核，不重踩链接；`dup(A)=0`；预计当前实现红灯 |
| `PL-R07 / P0` 等待管理员时释放拉手再占用 | `{{PL_R07_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 注入层：资源池；先分配拉手，再让管理员不足进入等待；核对释放后补管理员恢复 | 等待时展示管理员不足且拉手不再占用；恢复后只显示本轮重新选中的拉手 | 旧 puller occupancy/lease 已释放，新占用唯一；旧拉手无新 command；`act=exp,dup(all)=0` |

### 2.2 邀请码、审批、提权与拉人

| 用例 ID / 优先级 | test1 任务 ID / 约 5 分钟窗口 | 业务 / 协议 | 前置与步骤 | 页面现象 | DB、协议与通过条件 |
|---|---|---|---|---|---|
| `PL-I01 / P0/预计红灯` Web 失效邀请码恢复 | `{{PL_I01_W_taskId}}`；`T0~T0+5m`；历史映射 `#194/exec428` | 普通拉群 / Web | 注入层：join 响应；已知 JID、旧码失效、当前码已轮换、管理员不在群 | 展示“链接失效→刷新→重试”，只出现一次恢复，不直接判普通失败 | 同一 operationId 保留首次失败、新码观测和至多一次恢复协议尝试；`act(A)=1,dup(A)=0`；当前 knownGroupJid 分支预计红灯 |
| `PL-I02 / P0/预计红灯` Android 失效邀请码恢复 | `{{PL_I02_A_taskId}}`；`T0~T0+5m` | 普通拉群 / Android | 同 I01；注入层为 Android join，验证纯 code 与已知 JID 组合 | 与 Web 同语义，不能因参数格式显示未知状态 | 同一 operationId 下至多一次恢复；code/JID 对应正确群；`act(A)=1,dup(A)=0`；当前 knownGroupJid 分支预计红灯 |
| `PL-I03 / P1` 刷新后仍失效 | `{{PL_I03_W_taskId}}/{{PL_I03_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层：join 与邀请链接查询；首次和唯一恢复尝试都返回失效 | 显示明确终止/人工处理原因，不无限刷新 | 同一 operationId 重试有界；保留两次 attempt 事实；链接按策略释放；`act(A)=0,dup(A)=0` |
| `PL-A01 / P0` 等待审批后自动恢复 | `{{PL_A01_W_taskId}}/{{PL_A01_A_taskId}}`；各 `T0~T0+5m`；历史映射 `#194/exec429` | 普通拉群 / Web、Android 各一轮 | 注入层：join 返回 PENDING_APPROVAL；T+2m 群端批准 | 先暂停并显示审批；批准后两个调度周期内自动恢复一次 | 原 action 与成员批准事实关联；不得再次 join；`dup(A)=0`；预计当前实现红灯 |
| `PL-A02 / P0/BLOCKED` 重复/迟到审批事实 | `{{PL_A02_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 待协议负责人冻结批准事件/成员快照字段与专用注入入口后，投递 3 次并在 Stop 后迟到一次 | 首次有效事实最多唤醒一次；停止后不复活 | 父子计数一次；迟到事实只审计；`dup(A)=0`；接口未冻结前不得执行 |
| `PL-A03 / P0/预计红灯` 管理员入群 attempt/乱序隔离 | `{{PL_A03_<sequence>_<protocol>_taskId}}`；每序列每协议独立 `T0~T0+5m` | 普通拉群 / Web、Android | 注入层：manager-join callback；分别让旧 attempt FAILED 先到、当前 SUCCESS 先到，并重放 SUCCESS×3 | 四个 run 均只推进一次且最终一致，不回退 | 只接受当前 commandId+attemptNo；attemptNo 丢失即 FAIL；不产生新 join；`dup(A)=0` |
| `PL-P01 / P0` 提权成功与确认 | `{{PL_P01_W_taskId}}/{{PL_P01_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入：无；管理员已入群但无角色，执行提权并读真实角色 | “提权中”只出现一次，确认角色后进入下一阶段 | `exp(P)=act(P)=1,dup(P)=0`；command/result 与角色事实一致 |
| `PL-P02 / P0/BLOCKED` 提权 TIMEOUT/UNKNOWN | `{{PL_P02_W_taskId}}/{{PL_P02_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层：提权响应/回调；实际提权后丢响应，再投重复、迟到、冲突结果 | 显示待核对，不按普通失败再提权；不抖动 | 同 operation 不发第二动作，`exp(P)=act(P)=1,dup(P)=0`；冲突结果优先级冻结前不得执行 |
| `PL-J01 / P0` 拉手入群及部分失败 | `{{PL_J01_W_taskId}}/{{PL_J01_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层：puller join 结果；两个拉手一成功一确定失败，补入备用拉手 | 逐个展示结果，失败原因明确，备用只接管失败份额 | 成功拉手不重复入群；membership/call/action 一致；`act=exp,dup(A)=0` |
| `PL-J02 / P0/BLOCKED` 批量拉人 UNKNOWN/乱序 | `{{PL_J02_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 注入层：participant callback；投递 `UNKNOWN→FAILED→SUCCESS`、`SUCCESS→迟到失败`、重复 SUCCESS | 页面不来回覆盖，最终裁决可解释 | 旧 attempt 不改新 attempt且不重发 batch；`dup(A)=0`；同 attempt 冲突 oracle 冻结前不得执行 |
| `PL-J03 / P1` 联系人与拉手双入口 | `{{PL_J03_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 注入层：SAVE_CONTACT；分别覆盖管理员↔拉手、拉手↔驻群号，一个保存确定失败；两个拉手分别踩链接/管理员邀请 | 保留逐关系联系人失败事实，并按冻结的阻断/非阻断规则推进，不能假成功 | 每条关系、join/invite/batch 各有结果；`act=exp,dup(C/A)=0`；业务语义未冻结则 BLOCKED |
| `PL-J04 / P0` 协议生效后进程崩溃接管 | `{{PL_J04_<action>_<protocol>_taskId}}`；每动作每协议独立 `T0~T0+5m` | 普通拉群 / Web、Android | 注入层：join/promote/invite/batch 分别在协议成功后、DB CAS 前；worker A 崩溃，租约后由 B 接管 | 每个独立 run 短暂待核对后只推进一次，不回退、不循环 | action/call/command 唯一；由原回调或成员事实收敛；`act=exp,dup(all)=0` |

### 2.3 换群、停止、收口与追踪

| 用例 ID / 优先级 | test1 任务 ID / 约 5 分钟窗口 | 业务 / 协议 | 前置与步骤 | 页面现象 | DB、协议与通过条件 |
|---|---|---|---|---|---|
| `PL-S01A / P0` 来源文件夹 GROUP_BANNED 换群 | `{{PL_S01A_W_taskId}}/{{PL_S01A_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层：群健康事件；来源文件夹模式返回 GROUP_BANNED，并重复事件 3 次 | 旧 execution 明确终止，只出现一次新尝试和新群 | `exp(REPL)=act(REPL)=1,dup(REPL)=0`；封禁群移出来源集合；`dup(all)=0` |
| `PL-S01B / P0` 来源文件夹 GROUP_UNAVAILABLE 不换群 | `{{PL_S01B_W_taskId}}/{{PL_S01B_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层同 S01A，但返回 GROUP_UNAVAILABLE | 显示明确失败/处理入口，不谎报封禁且不展示换群 | `exp(REPL)=act(REPL)=0`；来源集合不按封禁移动；`dup(all)=0` |
| `PL-S01C / P0/BLOCKED/W0_ORACLE_PENDING` 目标成功后 GROUP_BANNED（#199 回放） | `{{PL_S01C_<protocol>_taskId}}`；`T0~T0+5m`；test1 历史 `#199/exec449` | 普通拉群 / Web、Android 各一轮 | 手工链接模式；目标 ADD 成功后投 `CHAT_SUSPENDED×5`，含终态后迟到事件 | PROPOSED：execution 明确失败；父级区分 lifecycle 完成与 outcome 失败；页面自动收敛且异常数=1 | PROPOSED：目标 mutation=1；execution=`FAILED/GROUP_BANNED`；processed=1、success=0、abnormal=1；终态后新 command=0；群及所有邀请码 quarantine；`dup(all)=0`；W0 签字后解除阻断 |
| `PL-S01D / P1` 来源文件夹成功群归档 | `{{PL_S01D_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 注入：无；来源文件夹任务正常完成，随后重复收口事件 | 页面展示成功群和来源/已使用去向 | 成功群只移动到已使用集合一次；封禁集合不变；重复收口不重复移动；`dup(all)=0` |
| `PL-S02 / P0` Stop 与在途回调竞态 | `{{PL_S02_<phase>_<protocol>_taskId}}`；每阶段每协议独立 `T0~T0+5m` | 普通拉群 / Web、Android | 注入层：execution Stop 与 callback；在等待、提权中、批量拉人中分别停止，随后投迟到结果 | 每个 run 停止后保持终态；已发生事实可见但不重新执行 | Tfence 后新 dispatch/command=0；此前在途动作至多完成一次并落事实；`dup(all)=0` |
| `PL-S03 / P0` 任务终态资源释放 | `{{PL_S03_<outcome>_<protocol>_taskId}}`；每终态每协议独立 `T0~T0+5m` | 普通拉群 / Web、Android、混合 | SUCCESS/FAILED/STOPPED × Web/Android/混合共 9 个独立 run，随后等待释放宽限期 | 每例业务终态和可释放资源结果均可核对，不能只显示任务结束 | 9/9 run 的拉手/链接占用和 worker 租约归零；管理员/驻群号成员事实按业务保留；`dup(all)=0` |
| `PL-S04 / P0/预计红灯` 单群结束后的首个迟到回调 | `{{PL_S04_<action>_<protocol>_taskId}}`；每动作每协议独立 `T0~T0+5m` | 普通拉群 / Web、Android | 注入层：callback；manager join/promote/puller invite/batch 各独立 run 暂存一个 SENT 结果，结束 execution 后释放 | 始终保持已结束；迟到协议事实可审计但不重新执行 | execution 不复活；事实不因终态 CAS 失败回滚；新 command 0；`dup(all)=0`；当前实现预计红灯 |
| `PL-S05 / P0` execution/父任务两层结束幂等 | `{{PL_S05_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 注入层：结束 API 与 callback；先重复结束单群 2 次，再重复结束父任务 2 次 | 单群与父任务各只显示一次终态；其他行按父级策略收口 | PENDING command 栅栏，SENT 只收敛事实；父终态写入一次；拉手/链接/租约归零；`dup(all)=0` |
| `PL-T01 / P0` 主 Outbox 全链双向追踪 | `{{PL_T01_M_taskId}}`；`T0~T0+5m` | 普通拉群 / 混合 | 注入：无；一条成功、一条含确定性重试；从 taskId 下钻，再从 eventId 反查 | 页面若未原生展示，必须由 Network/诊断导出提供 backend、原始结果、最终裁决和释放事实 | `task→execution→action/call→command→outbox→event/result` 关联率 100%；孤儿 0；`dup(all)=0` |
| `PL-T02 / P1` 原始 UNKNOWN 的最终裁决 | `{{PL_T02_W_taskId}}/{{PL_T02_A_taskId}}`；各 `T0~T0+5m` | 普通拉群 / Web、Android 各一轮 | 注入层：协议结果；先产生 UNKNOWN，再由成员事实确认 | 页面区分“原始结果 UNKNOWN”和“当前裁决成功”，不自相矛盾 | 原始 UNKNOWN 保留在事件/日志；派生 action 可收敛；裁决来源/时间/版本齐全；`dup(all)=0` |
| `PL-T03 / P0` 补充管理员 synthetic 链路 | `{{PL_T03_W_taskId}}`；`T0~T0+5m` | 普通拉群 / Web | 注入：无；从 WAIT_RESOURCE 手工补管理员，追踪入群、成员核对和提权 | 页面/Network/诊断导出能看到账号、operation/command、后端、原始结果和裁决 | 允许不走普通 Outbox，但必须有可持久化反查的等价结果链；`task→execution→command→result` 不断，`dup(A/P)=0` |

## 3. 速拉群与建群营销用例

### 3.1 正向流程

| 用例 ID / 优先级 | test1 任务 ID / 约 5 分钟窗口 | 业务 / 协议 | 前置与步骤 | 页面现象 | DB、协议与通过条件 |
|---|---|---|---|---|---|
| `FG-F01 / P0` 速拉群 Web 全阶段 | `{{FG_F01_W_taskId}}`；`T0~T0+5m` | 速拉群 / Web | 注入：无；联系人→建群→加人→提权→禁言→登记→建群号退出→营销→收口 | 阶段严格单向，每阶段展示账号、时间和结果；最终业务与资源均完成 | 全链唯一；`exp` 由角色/成员 fixture 生成，`act=exp,dup(all)=0` |
| `FG-F02 / P0` 速拉群 Android 全阶段 | `{{FG_F02_A_taskId}}`；`T0~T0+5m` | 速拉群 / Android | 注入：无；Android 完成联系人→建群→加人→提权→禁言→登记→建群号退出→营销→收口 | 与 Web 同成功语义，禁言和角色均经协议确认 | 全链唯一，群权限/成员/消息与 DB 一致；`act=fixture exp,dup(all)=0` |
| `FG-F03 / P0` 速拉群混合路由 | `{{FG_F03_M1_taskId}}/{{FG_F03_M2_taskId}}`；各 `T0~T0+5m` | 速拉群 / 混合 | 无注入；Web 建群+Android 营销、Android 建群+Web 营销各一独立任务 | 逐角色显示真实协议，无串号、串群 | 动作走指定后端；每任务 `exp(G)=1,exp(S)=1`，`act=exp,dup(all)=0` |
| `CM-F01 / P0` 建群营销 Web 闭环 | `{{CM_F01_W_taskId}}`；`T0~T0+5m` | 建群营销 / Web | 注入：无；1 item、5 联系人；真实预保存后建群、禁言、发送 | 待执行→准备联系人→建群→禁言/发送→成功，只向前推进 | `exp(G)=1,exp(C)=fixture,exp(M)=1,exp(S)=1`；`act=exp,dup(all)=0` |
| `CM-F02 / P0` 建群营销 Android 闭环 | `{{CM_F02_A_taskId}}`；`T0~T0+5m` | 建群营销 / Android | 注入：无；1 item、5 联系人；真实预保存后建群、禁言、发送，并读取群权限 | 只有禁言已确认后才显示整体成功 | `exp(G)=1,exp(C)=fixture,exp(M)=1,exp(S)=1`；`act=exp,dup(all)=0` |
| `CM-F03 / P0` 建群营销混合父任务 | `{{CM_F03_M_taskId}}`；`T0~T0+5m` | 建群营销 / 混合 | 无注入；同一父任务含 Web、Android item 各一条 | 子项协议与进度独立；父任务 2/2，不能提前完成 | 每 item `exp(G)=1,exp(S)=1`；父计数一次；跨后端串线 0；`dup(all)=0` |

### 3.2 换号、联系人、建群与禁言异常

| 用例 ID / 优先级 | test1 任务 ID / 约 5 分钟窗口 | 业务 / 协议 | 前置与步骤 | 页面现象 | DB、协议与通过条件 |
|---|---|---|---|---|---|
| `FG-E01 / P0/BLOCKED` 换号后群归属 | `{{FG_E01_M_taskId}}`；`T0~T0+5m` | 速拉群 / 混合 | 注入层：账号级确定失败；首账号建群成功后启用备用账号；复用/重建策略须先冻结 | 明确展示所选策略，不能静默换号 | 复用时备用号先入群；重建时旧群登记清理；最终有效群 1、孤儿群 0、`dup(G/S)=0` |
| `FG-E02 / P0` 换号无资源 | `{{FG_E02_W_taskId}}/{{FG_E02_A_taskId}}`；各 `T0~T0+5m` | 速拉群 / Web、Android 各一轮 | 注入层：账号结果/资源池；当前账号确定失败，候选仅离线、占用或受限 | 稳定显示无可用账号和下一步，不在执行中/资源不足间循环 | 重试有界，不选负例账号；新群 0、新发送 0；`dup(all)=0` |
| `CM-E00 / P0/BLOCKED` GCM 跨协议换号 | `{{CM_E00_WA_taskId}}/{{CM_E00_AW_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web→Android、Android→Web | 注入层：建群成功后的账号级确定失败；分别切到另一后端；换号 operation 版本规则须先冻结 | 展示原/新账号、原/新 backend、复用或重建策略，不静默清 groupJid | 固定 item operationId 不得回放旧群给不在群的新号；最终有效群 1、孤儿群 0；`dup(G/S)=0`；预计当前实现红灯 |
| `CM-E01 / P0/BLOCKED` 联系人预保存失败 | `{{CM_E01_W_taskId}}/{{CM_E01_A_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web、Android 各一轮 | 注入层：SAVE_CONTACT；5 人中 2 失败、1 延迟；“全部完成才建群/允许明确降级”须先冻结 | 展示真实成功/失败/待处理数，不能把“已提交”当“已保存” | 逐人结果可查；门禁未满足前 `act(G/S)=0`；`dup(C/G/S)=0` |
| `CM-E02 / P1/BLOCKED` 联系人积压与有界队列 | `{{CM_E02_M_taskId}}`；连续 `5m` 采样窗 | 建群营销 / 混合 | 注入层：联系人协议桩延迟/线程池；20/50 人 item，降低保存吞吐制造积压；依赖 CM-E01 联系人失败策略冻结 | 显示积压、限流或失败；列表可用且无假成功 | item/父状态与真实完成数一致；队列有界且排空期下降；按冻结的阻断/降级策略判建群；`dup(C/G)=0` |
| `CM-E03 / P0/预计红灯` 建群成功后响应丢失 | `{{CM_E03_W_taskId}}/{{CM_E03_A_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web、Android 各一轮 | 注入层：建群成功后、worker 保存结果前；断链并重启原 worker，不假设已有 watchdog | 目标是 UNKNOWN/待核对且不按普通失败再建群；若直接 FAILED 则按实际记 FAIL | operation/idempotency 保留；核对前不营销；`exp(G)=act(G)=1,dup(G/S)=0`；当前实现预计直接失败 |
| `CM-E04 / P0/预计红灯` claim 后崩溃卡 GROUP_CREATING | `{{CM_E04_W_taskId}}/{{CM_E04_A_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web、Android 各一轮 | 注入层：item 由 PENDING claim 为 GROUP_CREATING 后、协议调用前；终止并恢复 worker | 目标是在冻结超时内恢复或带原因转人工核对；不得永久建群中 | 当前无租约假设；记录 status/version/updated_at；5m 无恢复即 FAIL；`act(all)=0,dup(all)=0` |
| `CM-E05 / P0` Android 禁言失败 | `{{CM_E05_A1_taskId}}/{{CM_E05_A2_taskId}}/{{CM_E05_A3_taskId}}`；各 `T0~T0+5m` | 建群营销 / Android | 注入层：mute；建群成功后分别返回失败、超时、异常 | 不得整体成功；展示群 ID 与禁言失败/待核对；未确认不营销 | 分步事实齐全；每例 `exp(G)=act(G)=1,act(S)=0,dup(G/M/S)=0` |
| `CM-E06 / P0` Web/Android 禁言语义一致 | `{{CM_E06_W_taskId}}/{{CM_E06_A_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web、Android 各一轮 | 注入：无；相同业务配置分别创建 1 群并设置仅管理员发言，再读取真实群权限 | 两端只有群端禁言事实确认后才进入发送/成功 | 请求字段可不同，但 item/父终态同语义；每例 `exp(G/M/S)=1,act=exp,dup(all)=0` |
| `CM-E07 / P0/预计红灯` 发送中丢回调 | `{{CM_E07_W_taskId}}/{{CM_E07_A_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web、Android 各一轮 | 注入层：item 已 MARKETING_SENDING、专用 Outbox 已接受后；阻断结果并重启 worker | 目标是在冻结超时内恢复或带 commandId 转人工核对；不得永久发送中 | 不假设已有 watchdog/租约；原 command 可追踪且不新发；5m 无出口即 FAIL；`dup(S)=0` |
| `CM-E08 / P1` 素材预检确定失败 | `{{CM_E08_M_taskId}}`；`T0~T0+5m` | 建群营销 / 混合 | 注入层：协议发布前素材校验；使用 INVALID_ASSET，连续观察调度 | 页面一次性阻断并展示素材原因，不每周期生成新轮次 | 专用 Outbox/物理发送均为 0；失败 item/command 有界可追踪；`act(S)=0,dup(all)=0` |

### 3.3 营销回执、幂等、父汇总与资源收口

| 用例 ID / 优先级 | test1 任务 ID / 约 5 分钟窗口 | 业务 / 协议 | 前置与步骤 | 页面现象 | DB、协议与通过条件 |
|---|---|---|---|---|---|
| `MK-I01A / P0` Android 实际发送后 UNKNOWN | `{{MK_I01A_FG_A_taskId}}/{{MK_I01A_CM_A_taskId}}`；各 `T0~T0+5m` | 两路线 / Android 各一轮 | 注入层：物理发送后、结果保存/发布前；中断后以同 command 重新 claim，触发 SEND_RESULT_UNKNOWN | 显示发送待核对，不得换号或创建新发送 command | 原 command 可核对，父任务不按普通失败收口；`exp(S)=act(S)=1,dup(S)=0` |
| `MK-I01W0 / P0` Web 未接受请求 | `{{MK_I01W0_FG_W_taskId}}/{{MK_I01W0_CM_W_taskId}}`；各 `T0~T0+5m` | 两路线 / Web 各一轮 | 注入层：Web server 接收前；连接失败并由协议侧证明未接受，随后按冻结策略重试 | 页面先显示确定未发送，再展示一次重试及结果 | 旧 command `exp(S)=act(S)=0`；只允许此确定分支创建新 attempt/command；`dup(S)=0` |
| `MK-I01W1 / P0` Web 接受后丢响应 | `{{MK_I01W1_FG_W_taskId}}/{{MK_I01W1_CM_W_taskId}}`；各 `T0~T0+5m` | 两路线 / Web 各一轮 | 注入层：Web 已接受并完成发送后、HTTP 响应前；断链，再做只读核对 | 页面显示待核对，不得把超时当确定未发送后换号 | 原 command `exp(S)=act(S)=1`；不创建新 command；`dup(S)=0` |
| `MK-I01M / P0` UNKNOWN 时跨协议备用号不得接管 | `{{MK_I01M_FG_AW_taskId}}/{{MK_I01M_FG_WA_taskId}}/{{MK_I01M_CM_AW_taskId}}/{{MK_I01M_CM_WA_taskId}}`；各 `T0~T0+5m` | 两路线 / Android→Web、Web→Android | 注入使当前账号物理发送成功但结果丢失，同时预置另一协议在线备用账号并连续调度 | 页面保持原 command 待核对，不显示已换号/新轮次 | 当前号 `exp(S)=act(S)=1`；备用号新 command/发送 0；`dup(S)=0` |
| `MK-I02 / P0/BLOCKED` 重复和冲突回调 | `{{MK_I02_<route>_<protocol>_<sequence>_taskId}}`；每条序列独立 `T0~T0+5m` | 两路线 / Web、Android | 注入层：callback；SUCCESS×3、FAIL×3、SUCCESS→FAIL、FAIL→SUCCESS 四个独立 run/command | 每个 run 只发生一次权威变化，不抖动 | command 只计一次且不触发新发送；`dup(S)=0`；同 attempt 冲突优先级未冻结前不得执行 |
| `MK-I03 / P0` Outbox/Kafka 重投 | `{{MK_I03_<route>_<crashPoint>_A_taskId}}`；每故障点每路线独立 `T0~T0+5m` | 两路线 / 营销发送端 Android | 注入层：物理发送后、事件发布后、offset 提交前；各用独立 task/command 崩溃并重投 3 次 | 每个 run 只展示一个权威结果和重放审计 | command/event 去重；父计数一次；`exp(S)=act(S)=1,dup(S)=0` |
| `FG-I01 / P0/预计红灯` 速拉群全阶段租约接管 | `{{FG_I01_<stage>_W_taskId}}/{{FG_I01_<stage>_A_taskId}}`；每阶段每协议独立 `T0~T0+5m` | 速拉群 / Web、Android | 注入层：联系人/建群/加人/提权/禁言/登记/退群/发送成功后、DB CAS 前；逐阶段制造接管 | 每阶段最多完成一次，无回退和重复完成日志 | 除建群外稳定 operationId 能力未确认；缺注入/幂等能力则 BLOCKED；执行后须 `act=exp,dup(all)=0` |
| `CM-A01 / P0` 建群营销父任务汇总 | `{{CM_A01_M_taskId}}`；`T0~T0+5m` | 建群营销 / 混合 | 注入层：item 结果；matched=4，先得 SUCCESS 2、FAILED 1、MARKETING_SENDING 1，再让末项 SUCCESS | 末项未决时父 RUNNING；结束后显示 PARTIAL_FAILED，列表/详情/导出一致 | 未决时 count=2/1/0；终态 success/failed/abandoned=3/1/0、matched=4、父 status=5；`dup(all)=0` |
| `FG-A01 / P0` 速拉群父任务汇总 | `{{FG_A01_M_taskId}}`；`T0~T0+5m` | 速拉群 / 混合 | 注入层：execution/target/attempt；四个 execution 为 SUCCEEDED、FAILED、EXECUTING/重试、MANUAL_REVIEW | 页面 successGroupCount=1、failedGroupCount=1；未决两项可下钻，父营销任务保持 SENDING，不假完成 | MANUAL_REVIEW 不计成功/失败且阻止资源完全收口；旧 attempt 不重复累计；明确处置后再按实际终态汇总；`dup(all)=0` |
| `MK-A02A / P0/BLOCKED` 确定未发送后的重试 | `{{MK_A02A_<route>_<protocol>_taskId}}`；每路线每协议独立 `T0~T0+5m` | 两路线 / Web、Android | 注入层：协议发布前；旧 command 校验失败，GPM 建新 attempt/command、GCM 按冻结策略建新 command，再投伪旧 SUCCESS；依赖 FG-E01/CM-E00 策略冻结 | 显示一次失败历史和当前成功，旧伪结果不覆盖 | 旧 command `act(S)=0`，新 command `act(S)=1`；父计数一次；`dup(S)=0` |
| `MK-A02B / P0/BLOCKED` 可能已发送后的迟到结果 | `{{MK_A02B_<route>_<protocol>_taskId}}`；每路线每协议独立 `T0~T0+5m` | 两路线 / Web、Android | 注入层：物理发送后结果丢失；保持 UNKNOWN，再投迟到 SUCCESS；UNKNOWN 裁决/版本规则先冻结 | 待核对期间不创建新 attempt/command；迟到事实到达后仅收敛一次 | 原 command `exp(S)=act(S)=1`；无第二 command；父计数一次；`dup(S)=0` |
| `FG-S01 / P0` 速拉群 Stop 竞态 | `{{FG_S01_<stage>_<protocol>_taskId}}`；每阶段每协议独立 `T0~T0+5m` | 速拉群 / Web、Android | 注入层：Stop 与 worker/outbox/callback；在建群、加人、提权、发送等阶段分别停止 | Tfence 后保持停止；已发生事实可见且不重新执行 | Tfence 后新 dispatch/command=0；此前不可撤销动作可完成至多一次并落事实/清理；`dup(all)=0` |
| `CM-S01 / P0` 建群营销 Stop 竞态 | `{{CM_S01_<state>_<protocol>_taskId}}`；每状态每协议独立 `T0~T0+5m` | 建群营销 / Web、Android | 注入层：stopTask 与 claim/outbox/callback；对专用 Outbox PENDING、LOCKED、SENT 分别停止并投迟到结果 | 父任务和全部开放 item 按现有 Stop 语义一次停止，不假设单 item Stop | 按 GCM commandId 对账；Tfence 后新 dispatch/command=0；此前在途动作至多完成一次并清理；父计数不复活、`dup(all)=0` |
| `MK-S02 / P0` 丢回调后的资源释放 | `{{MK_S02_FG_W_taskId}}/{{MK_S02_FG_A_taskId}}`；各 `T0~T0+5m` | 速拉群 / Web、Android | 注入层：发送结果；实际发送后丢 callback，等待超时再停止/释放 | 进入待核对或有界释放，不能无限 RELEASING | attempt 有超时/核对出口；不因释放重发；`exp(S)=act(S)=1,dup(S)=0`，账号/链接/租约泄漏 0 |
| `FG-S03 / P0/BLOCKED` 速拉群三结局资源账 | `{{FG_S03_SUCCESS_taskId}}/{{FG_S03_FAILED_taskId}}/{{FG_S03_SWITCH_taskId}}`；各 `T0~T0+5m` | 速拉群 / 混合 | 注入层：确定失败/换号；分别完成成功、失败、换号成功；换号分支依赖 FG-E01 策略冻结 | 展示业务终态、builder/营销号、群与链接去向 | builder 退群规则、account/group occupancy、link 释放逐项对账；孤儿群/占用/outbox 0；`dup(all)=0` |
| `CM-S03 / P0/BLOCKED` 建群营销三结局资源账 | `{{CM_S03_SUCCESS_taskId}}/{{CM_S03_FAILED_taskId}}/{{CM_S03_SWITCH_taskId}}`；各 `T0~T0+5m` | 建群营销 / 混合 | 注入层：确定失败/换号；分别完成成功、失败、换号成功；换号分支依赖 CM-E00 策略冻结 | 展示 item 终态、账号、实际群和清理去向 | group_jid/link_id、群注册、成员关系、账号占用、专用 outbox 逐项对账；孤儿/占用 0；`dup(all)=0` |
| `FG-S04 / P0/预计红灯` 建群 UNKNOWN 到 MANUAL_REVIEW 的资源保护 | `{{FG_S04_W_taskId}}/{{FG_S04_A_taskId}}`；各 `T0~T0+5m` | 速拉群 / Web、Android 各一轮 | 注入层：建群实际成功后丢结果；进入 MANUAL_REVIEW，再停止/触发释放 | 保持可能存在群的警告和人工核对入口；不得显示已完全释放 | MANUAL_REVIEW 在资源活跃判定中受保护；账号/群锁不提前释放；群登记后再收口；孤儿群 0、`dup(G)=0` |
| `CM-S04E / P0/BLOCKED` UNKNOWN 建群确认存在 | `{{CM_S04E_W_taskId}}/{{CM_S04E_A_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web、Android | 建群实际成功后回执丢失；人工核对能力未提供前 BLOCKED；确认协议群存在 | 核对前保持阻塞，确认后展示并登记真实群 | 原 operation 不重建；`exp(G)=act(G)=1,dup(G)=0`；群资源全链闭合 |
| `CM-S04N / P0/BLOCKED` UNKNOWN 建群确认不存在 | `{{CM_S04N_W_taskId}}/{{CM_S04N_A_taskId}}`；各 `T0~T0+5m` | 建群营销 / Web、Android | 传输结果不确定，事后由协议审计事实确认请求未生效且未创建群；人工核对能力未提供前 BLOCKED | 确认前保持阻塞；确认不存在后显示显式新尝试 | 旧 operation `exp(G)=act(G)=0`；新 operation 版本递增；孤儿群 0、`dup(G)=0` |
| `MK-T01 / P0` 全链与协议反查 | `{{MK_T01_FG_M_taskId}}/{{MK_T01_CM_M_taskId}}`；各 `T0~T0+5m` | 两路线 / 混合 | 注入层：一次确定性可重试失败；分别正向下钻和由 eventId 反查 | 页面若未原生展示，必须由 Network/诊断导出提供全部 ID、backend、原始结果和裁决 | FG：`task→execution→target→attempt→command→outbox→event/result`；CM 中 itemId 代 executionId，并行核对同步建群链 `task→item→groupOperationId→backend→groupResult` 与营销链 `task→item→command→outbox→event/result`；关联 100%、资源可反查、`dup(all)=0` |

## 4. 压测用例

> 容量数字只适用于隔离协议桩或专用性能环境；test1 不用于寻找容量极限。每个 5 分钟采样窗都要回填该窗内
> 的任务 ID 列表或可反查这些 ID 的 runId。

| 用例 ID / 优先级 | test1 任务 ID / 约 5 分钟窗口 | 业务 / 协议 | 负载与注入 | 页面现象 | 指标与通过条件 |
|---|---|---|---|---|---|
| `PF-B00-SIM / P0` 隔离空载基线 | `环境任务ID=N/A`；1 个 `5m` 窗 | 三种业务 / stateful-sim | 0 新任务；冻结隔离环境；验证 egress deny、空凭据、sim 自检和诱捕目标 | 隔离测试页面/API 可用，无任务 | 出公网尝试 0；基础资源稳定；采集器开销可量化；缺一即 BLOCKED |
| `PF-B00-TEST1 / P0` test1 空载基线 | `test1任务ID=N/A`；1 个 `5m` 窗 | 三种业务 / 全部 | 0 新任务；冻结 test1 制品/配置，验证白名单、预算栅栏和本轮 backlog 归属 | test1 页面可用，无本轮任务 | 旧 backlog 可隔离，非本轮对象拒绝测试通过；缺一即 BLOCKED |
| `PF-S01 / P0/BLOCKED/TOOLING_GAP` 协议桩冒烟 | `环境任务ID={{PF_S01_taskIds}}；test1任务ID=N/A`；2 个 `5m` 窗 | 三种业务 / Web、Android、混合 | 从公开任务 API 注入；固定 seed/K；100 活动，目标首 20s 实测约 5 command/s | 列表/详情可打开，状态持续向前 | 小样本非注入错误 0；本轮 backlog <=300s 清空；全链 100%；`dup(all)=0` |
| `PF-S02A / P1/BLOCKED/TOOLING_GAP` 多轮 burst 恢复 | `环境任务ID={{PF_S02A_taskIds}}；test1任务ID=N/A`；2 个 `5m` 窗 | 三种业务 / 40% Web、40% Android、20% 混合 | 从公开任务 API 注入；固定业务 40/30/30；300 活动；首 15s 目标约 20 command/s | 无批量无原因卡住或周期性状态抖动 | 停止输入后 backlog 持续下降并 <=300s 清空；小样本非注入错误 0；`dup(all)=0` |
| `PF-S02B / P1/BLOCKED/TOOLING_GAP` 持续稳态 | `环境任务ID={{PF_S02B_taskIds}}；test1任务ID=N/A`；2 个 `5m` 窗 | 三种业务 / 40% Web、40% Android、20% 混合 | 从公开任务 API open-loop 注入；经 K 换算 task/s，持续目标实测 20 command/s 共 10m | 页面可持续查询，状态单向，backlog 不增长 | CPU P95 <70%；DB pool <80%；oldest <=30s；每操作样本不足 10k 时不判 P99/<0.1% |
| `PF-S03 / P1/BLOCKED/TOOLING_GAP` 调度/Outbox 边界 | `环境任务ID={{PF_S03_taskIds}}；test1任务ID=N/A`；3 个 `5m` 窗 | 三种业务 / 混合 | 从公开任务 API 注入；500 活动；首 10s 目标约 50 command/s；协议延迟 0.3/2/5s | 汇总不溢出，未决项原因明确 | 本轮队列停止输入后 <=300s 清空；死锁/孤儿/父子差异 0；`dup(all)=0` |
| `PF-S04 / P0/BLOCKED/TOOLING_GAP/ORACLE_UNDEFINED` 幂等风暴 | `环境任务ID={{PF_S04_taskIds}}；test1任务ID=N/A`；6 个 `5m` 窗 | 三种业务 / 全部 | 以 PF-S02B 从公开 API 注入；重复 10%、乱序 5%、TIMEOUT/UNKNOWN 5%、丢失 2%；依赖 MK-I02/MK-A02B oracle 冻结 | 状态不抖动；UNKNOWN 有明确出口 | 未知动作不以新 command 盲重试；事件集合乱序结果一致；`dup(all)=0`；依赖未解除前不执行 |
| `PF-S05 / P0/BLOCKED/TOOLING_GAP` Stop/进程故障 | `环境任务ID={{PF_S05_taskIds}}；test1任务ID=N/A`；4 个 `5m` 窗 | 三种业务 / 全部 | 从公开 API 以 0.8×PF-S02B 注入；外部成功后落库前中断 1%，随机 Stop 20% | Stop 后不复活；接管有明确原因 | `Tfence-Tstop<=5s`；Tfence 后新 dispatch/command=0；此前在途动作 `act<=exp,dup(all)=0` 并落事实/清理；活动资源和租约归零 |
| `PF-S06 / P1/BLOCKED/TOOLING_GAP` 爬坡与容量拐点 | `环境任务ID={{PF_S06_taskIds}}；test1任务ID=N/A`；每级 2 个 `5m` 窗，候选档补足 30m | 三种业务 / 全部 | 从公开 API 注入；经 K 换算 task/s；从已通过速率每级 +20%，保持 seed family | 达阈值时限流/降级但不假成功 | 连续 3 个 1m 软阈值窗才停止升级；每级本轮 backlog <=300s 清空；候选档 30m；样本不足不判 P99/<0.1% |
| `PF-L1-PL-W / P0/HOLD` test1 `PL-W` L1 | `{{PF_L1_PL_W_taskId}}`；`T0~T0+5m` | 普通拉群 / Web | 对应普通拉群 Logic P0 全 PASS、修复候选已部署且 LIVE_GATE 覆盖本 lane；1 任务、1 独立群、1 白名单目标 | 完整阶段和 Web 后端可见；自动刷新与人工查询一致 | 个案全断言通过；真实 `act=exp,dup(all)=0`；T+30m/T+24h 风险与资源复核通过 |
| `PF-L1-PL-A / P0/HOLD` test1 `PL-A` L1 | `{{PF_L1_PL_A_taskId}}`；`T0~T0+5m`；历史 `#199` 不得回填 PASS | 普通拉群 / Android | 对应普通拉群 Logic P0 全 PASS，且 `PL-W` L1 干净；使用全新 taskId、群、账号和目标 | 完整阶段和 Android 后端可见；自动刷新与人工查询一致 | 同上一条；不接受统计容错；`#199` 只属于 `PL-S01C` |
| `PF-L1-FG-W / P0/HOLD` test1 `FG-W` L1 | `{{PF_L1_FG_W_taskId}}`；`T0~T0+5m` | 速拉群 / Web | 对应 FG/MK Logic P0 全 PASS，PL-W/PL-A L1 干净且追加授权；1 任务、1 群、<=5 人、<=1 消息 | 速拉全阶段及 Web 角色路由可见 | `act=exp,dup(all)=0`；父子、协议和资源一致；T+30m/T+24h 干净 |
| `PF-L1-FG-A / P0/HOLD` test1 `FG-A` L1 | `{{PF_L1_FG_A_taskId}}`；`T0~T0+5m` | 速拉群 / Android | 对应 FG/MK Logic P0 全 PASS，FG-W L1 干净；使用独立 taskId 和对象 | 速拉全阶段及 Android 角色路由可见 | 同上一条；不接受统计容错 |
| `PF-L1-CM-W / P0/HOLD` test1 `CM-W` L1 | `{{PF_L1_CM_W_taskId}}`；`T0~T0+5m` | 建群营销 / Web | 对应 CM/MK Logic P0 全 PASS，FG-W/FG-A L1 干净且追加授权；1 任务、1 item、1 群、<=5 人、1 消息 | 联系人、建群、禁言、发送和 Web 后端可见 | `act=exp,dup(all)=0`；父子、协议和资源一致；T+30m/T+24h 干净 |
| `PF-L1-CM-A / P0/HOLD` test1 `CM-A` L1 | `{{PF_L1_CM_A_taskId}}`；`T0~T0+5m` | 建群营销 / Android | 对应 CM/MK Logic P0 全 PASS，CM-W L1 干净；使用独立 taskId 和对象 | 联系人、建群、禁言、发送和 Android 后端可见 | 同上一条；不接受统计容错 |
| `PF-L1-MIX / P0/HOLD` test1 mixed lane L1 | `{{PF_L1_MIX_PL_taskId}}/{{PF_L1_MIX_FG_taskId}}/{{PF_L1_MIX_CM_taskId}}`；每例 `T0~T0+5m` | 三种业务 / 混合，各独立任务 | 对应混合 Logic P0 全 PASS，且该业务 Web/Android lane 均已达到 L2；每条 mixed lane 单独追加授权 | 每个角色/item 展示真实 backend，无串线 | 每例单独对账；父子一致、全链 100%、`dup(all)=0`；T+30m/T+24h 干净 |
| `PF-L2 / P1/BLOCKED` test1 lane 累计 3 样本 | `{{PF_L2_<lane>_taskIds}}`；每个 `5m` 窗 | 已获准 lane / 对应协议 | 在该 lane L1 基础上新增 2 个独立任务/群，始终最多 1 个活动；无预算不得执行 | 按 lane 逐任务展示 | 对应 Logic P0 和 L1 通过；累计 3/3 PASS 且各自 T+24h 干净 |
| `PF-L3 / P1/BLOCKED` test1 lane 累计 10 样本 | `{{PF_L3_<lane>_taskIds}}`；每个 `5m` 窗 | 已获准 lane / 对应协议 | 在该 lane L2 基础上新增 7 个独立群；mixed lane 必须已按规则取得资格和授权 | 同 L2 | 累计 10/10 PASS；不得用 test1 探索极限；任一风险退回 L0 |
| `PF-L4 / P1/BLOCKED` 30m 小流量稳定性 | `{{PF_L4_<lane>_taskIds}}`；6 个 `5m` 窗 | 已通过 lane / 对应协议 | 使用该 lane 已授权低水位 30m；不复用触达对象；总/账号/日预算固定 | 每窗页面刷新和汇总稳定 | 对应 Logic P0 仍通过；每窗满足硬阈值；本轮 backlog <=600s 清空；资源归零、无迟到副作用 |

## 5. 单条用例执行记录模板

```text
caseId:
runId:
environment: test1 / stateful-sim / load-sim / dedicated-perf
对应Logic P0 runId（test1必填）:
LIVE_GATE(status/approver/scope/openedAt/expiresAt，test1必填):
套件版本及SHA-256:
候选制品/Git SHA:
环境任务ID:
test1任务ID或N/A:
executionId/itemId:
actionId/targetId/attemptId:
commandId:
协议eventId/result:
约5分钟时间窗（CST）:
业务: 普通拉群 / 速拉群 / 建群营销
协议: Web / Android / 混合
前置数据别名:
注入层/对象/触发时刻/恢复方式:
步骤:
页面现象（期望/实际）:
API（期望/实际）:
DB表/字段/前值/后值:
Outbox/Kafka（期望/实际）:
协议请求次数/原始结果/真实群事实:
期望副作用 G/A/P/M/C/S/L:
实际副作用 G/A/P/M/C/S/L:
重复副作用 G/A/P/M/C/S/L:
资源前/峰值/结束后:
Tstop/Tfence/在途账本（如适用）:
load入口/taskRate/K/实测commandRate/seed（如适用）:
附件及SHA-256:
结论: PASS / FAIL / BLOCKED / NOT_RUN
缺陷ID/阻断原因:
执行人/复核人:
```

## 6. 评审出口

当前矩阵处于 `ACTIVE_MATRIX / SIM_LOCAL_LOAD_PASS / H2_OUTBOX_CHAIN_PASS / SIM_INTEGRATION_PENDING`。本地 simulator
已完成 100/300/500 execution 并发突发和 5/20/50/60 command/s 开放环校验；普通拉群 Web、Android、混合 H2
正常链已用生产 Outbox service/Mapper 各完成 11 条 command 的发送状态和回调收口。由于仍未经过 Armada 公开 API、部署态 scheduler、
真实 Kafka 或服务资源采集，`PF-S01~S06` 保持 `BLOCKED/TOOLING_GAP`。当前只允许继续 W0 规则冻结、
`PF-B00-SIM` 隔离零负载预检和
`PF-B00-TEST1` test1 只读零负载基线；后者不得创建任务或产生协议副作用。各类用例独立解锁：

- Logic 用例：产品确认 UNKNOWN、审批、换号、群封禁和父汇总规则；测试负责人确认范围/时间窗；协议负责人确认
  Web/Android 幂等键、结果优先级和隔离故障点。满足后对应 simulator case 才标 `EXECUTABLE`，不依赖真群预算。
- Perf 用例：Logic P0 通过、W4 工具缺口关闭、性能目标/软阈值和专用压测环境确认后，才解除
  `PF-S01~S06/TOOLING_GAP`；未确认前不发压力。
- Live 用例：在对应 Logic P0 和 W5 通过基础上，另需 test1 窗口、`LOCKDOWN_CLEARED`、有效 LIVE_GATE、监控/急停/
  清理责任人、白名单账号/群/联系人和真实动作预算；缺一保持 `HOLD/BLOCKED`。
