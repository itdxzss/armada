# 变更记录：拉群板块功能测试与压测方案

- 日期 / 分支 / worktree：2026-09-03 / 1.0.3-snapshot / 主工作区
- 需求来源：用户要求先形成测试方案与测试用例，再执行功能测试和压测
- 状态：本地 simulator core/load PASS；普通拉群 Web/Android/混合 H2 Outbox 纵向链 PASS；Armada 性能集成待办；test1 真协议车道 HOLD

## 目标（一句话）

为普通拉群、速拉群和建群营销形成可评审、可执行、可留证且具有真实 WhatsApp 安全边界的测试与压测套件。

## 缺口拆解 / 任务清单

- [x] 对齐两条业务链、现有调度参数和安全边界。
- [x] 输出功能测试与压测总体方案。
- [x] 输出普通拉群测试用例矩阵。
- [x] 输出速拉群 / 建群营销测试用例矩阵。
- [x] 完成普通拉群、速拉群 / 建群营销及压测三路交叉评审，并修正状态机混用、真协议预算、注入门禁、
  副作用账本、Tfence 和负载可复现性问题。
- [x] 执行 test1 Android 单群金丝雀 `#199`，完成五分钟窗口、迟到事件和资源对账。
- [x] 将 `#199` 重新分类为 GROUP_BANNED 风险回归，不作为 Android 正常链 PASS。
- [x] 将执行策略升级为“有状态协议模拟器 -> 专用性能环境 -> 解封后 test1 金丝雀”三层。
- [x] W0-01 将 `#199` 脱敏为可执行 replay fixture；其 expected 仍待产品/协议/测试签字后转为冻结 oracle。
- [ ] W0 冻结父子终态、UNKNOWN、审批、邀请码、换号和资源隔离 oracle。
- [x] W1 首切片完成 test-only stateful simulator 内核、物理副作用账本、父子聚合和 fail-closed CLI。
- [x] W1 局部接入生产 Outbox service/Mapper、普通拉群调度协调器和真实结果服务的 H2 Web/Android/混合正常链。
- [ ] W1 后续接入 Web/Android simulator adapter、Kafka broker/consumer、故障 DSL 和部署态 Armada API。
- [x] W4 首切片完成 simulator-only open-loop/并发生成器及副作用指标。
- [ ] W4 后续接公开 API、全链采集和安全 fence。
- [ ] W5 在专用性能环境跑正常链、故障、爬坡和排空。
- [ ] 收到 `LOCKDOWN_CLEARED` 后再准备全新 test1 L1 夹具。

## 关键设计决策

- test1 只承担小流量真实协议验证，不承担容量极限或破坏性压力测试。
- 当前真实群操作门禁关闭；不再通过换群寻找“可用真群”。
- 大规模、重复/乱序和故障接管压力先在有状态协议模拟器/专用性能环境执行。
- Crypto Loopback 只校准 Signal/Noise/ACK 成本，不承担群成员、角色、审批、邀请码、封禁和父子汇总验证。
- 代码审计、历史数据和单元测试都不能替代本套件的真实执行结果。
- 每条用例强制绑定 taskId、约五分钟窗口、协议后端、页面现象及完整 command/result 证据。
- 重复副作用必须由协议物理动作账本证明，不能只看数据库未重复计数。

## 验证（evidence-before-done）

总体仍未通过。已执行的 `#199 / execution 449 / Android` 中，目标真实入群后群被报告
`CHAT_SUSPENDED/GROUP_BANNED`：8 个唯一 command、retry=0、重复/迟到风险事件未派生新动作，调度占用已释放；但父
COMPLETED/子 FAILED、页面需人工查询才刷新、封禁群邀请码仍健康及协议成员残留均未通过。因此它只作为风险路径证据，
正常 Web/Android/混合及容量结论保持 `BLOCKED/NOT_RUN`。

- 测试方案：`docs/superpowers/specs/2026-09-03-group-task-test-and-load-test-plan.md`
- 测试用例：`docs/superpowers/specs/2026-09-03-group-task-test-cases.md`
- 真实验收池：`docs/operations/2026-09-03-group-task-test1-acceptance-pool.md`
- 模拟器：`armada-deploy/tools/group_task_simulator/README.md`
- `PYTHONPATH=armada-deploy/tools python3 -m unittest armada-deploy/tools/tests/test_group_task_simulator.py`：13 tests PASS。
- 本地无节流混合协议突发：100/300/500 execution，max in-flight=10/30/50；共 10,800 command、3,600 次重试，
  duplicate mutation=0、terminal completeness=100%。
- 本地开放环：目标 5/20/50/60 command/s，实测 4.996/19.968/49.988/59.853；共 660 command、220 次重试，
  duplicate mutation=0、terminal completeness=100%。
- `#199` H2/真实生命周期回归：5 次 `CHAT_SUSPENDED` 只终止一次，SUCCESS 目标事实保留，8 条 SENT command 不新增，
  拉手释放、父任务完成、人工链接不创建 retry。
- `PYTHONPATH=armada-deploy/tools python3 -m unittest discover -s armada-deploy/tools/tests -p 'test_*.py'`：63 tests PASS。
- Java H2 Outbox/管理员资源/审批等待/邀请码恢复/回调冲突/取消/父子生命周期聚焦回归：33 tests PASS；本机 JBR 25 通过 ByteBuddy agent 与 experimental
  兼容参数运行，业务断言 0 failure / 0 error。Web/Android/混合各产生 11 个唯一 command，全部经生产 Mapper 状态
  SQL 收敛到 SENT 后才接收回调，且与 action/call/material 聚合记录一一对应；每个 profile 的 12 条结果均即时重复并在
  终态后迟到重放，Outbox 数量和 execution 版本不变；Android topic 与混合 backend 路由已断言；正常收口后执行租约、
  群链接占用键和拉手账号占用键均为空。
- `PL-R01/R02` H2 PASS：管理员持续不足 5 个恢复周期状态稳定且 0 command，补入后只恢复一次并创建 1 条 JOIN Outbox。
- `PL-R06` H2 FAIL：JOIN `TIMEOUT/UNKNOWN` 且无 groupJid 时复核分支绕过 Outbox 直接调用 join，存在重复踩链接及
  command 全链断裂风险；33 tests 中对应项为 characterization test，绿色只表示缺陷被稳定复现。
- `PL-A01` H2 FAIL：PENDING_APPROVAL 能暂停，但 APPROVAL 不在调度器恢复领取集合中；连续 5 个未来
  调度周期均 `claimed=0`，没有成员复核入口。该项为 characterization test。
- `PL-I01/I02` H2 FAIL：Web/Android 均在已知 JID 的失效码恢复中循环成员查询，即使存在新码也不刷新；
  6 轮调度中状态反复跳转，新 Outbox 为 0。两个参数化项为 characterization test。
- 同 commandId 的 JOIN SUCCESS 后迟到失败重放 3 次均不覆盖成功事实，PASS。
- `PL-S04` H2 FAIL：execution 终态后首个 SENT 成功回调会整体回滚并抛异常；终态不复活且无新命令，
  但 action/membership 协议事实无法留存审计，对应项为 characterization test。
- `bash armada-deploy/tools/group-task-simulator.test.sh`：PASS；两个新增 shell 文件 `bash -n` 通过。
- `PL-S01C-TASK-199` CLI replay：`passed=true`，8 command、8 mutation、5 risk deliveries、1 risk transition、
  0 duplicate mutation、0 new command after terminal。
- 本轮只运行本地 synthetic fixture；未访问 test1，未触发 WhatsApp/Meta 网络或真实群动作。
- 本地负载结果详见 `docs/operations/2026-09-03-group-task-local-simulator-load-result.md`，不得解释为 Armada 容量。

## 部署

- 无部署。

## 遗留 / 跟进

- W0 先冻结 UNKNOWN/冲突回调、父 lifecycle/outcome、审批、换号及 release/retain/quarantine 规则。
- 当前 perf2 工具只会恢复暂停营销任务，缺少本轮三业务 open-loop 生成器与业务 oracle。
- 等待专用性能环境；test1 另行等待解封口令、全新账号/群/联系人、四级预算和紧急停派门禁。
