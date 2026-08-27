# 独立设计合理性复核报告（2026-08-26）

> 依据：[independent-design-review.md](independent-design-review.md) 四阶段流程
> 评审时基线：armada `1.0.3-snapshot @ e1ee3821`；wheel-saas-pure-web `1.0.3-snapshot @ b70a01ae`；armada-protocol `1.0.3-snapshot @ b5191a5d`；whatsapp-server-feature-android-zhuan `1.0.3-snapshot @ 5716baff`。四仓 upstream 均为 `origin/1.0.3-snapshot`。
> 授权范围：仅本地只读检查与本地测试；未部署、未 SSH、未访问真库、未操作真实 WhatsApp、未修改代码。

## 结论摘要

- **原始预想（一句话复述）**：负责人只给业务想法、取舍和授权，AI 负责澄清、跨四仓实施、测试环境验收、失败自动修复和证据交付，人从"流程粘合剂"变成"闸口决策者"，且长任务不依赖某个会话在线。
- **总体判断**：`PARTIALLY_ALIGNED`
- **最重要的理由（5 条）**：
  1. **验收一半方向正确且实现质量高**：staging-accept P0（可恢复 Runner、fail-closed 版本核对、BLOCKED≠FAIL 分类、证据校验）与评审者独立基准（见附录 A）几乎重合，本地全部测试可复现通过。
  2. **需求一半完全没动**：原始预想的第一痛点（想法→一次性决策包→范围锁定）零实现——GOV-002/003 在本地是 30 行和 21 行的旧文件，不含 D0~D3、决策包、scope_hash 中的任何一项。
  3. **自动返修回路不存在**：当前体系是"自动发现+报告"，不是"发现→修复→重跑→再验收"。README 流程图里的 `AI repair loop` 只有箭头，没有实现。42k Kafka 积压被发现后，仍是"后续应另开修复"——由人排期。
  4. **这套防假完成的系统自己在产生假完成声明**：backlog 把 GOV-001 标为 `VERIFIED`，但根工作区没有 AGENTS.md，armada/AGENTS.md 也不含 Android 路由；变更记录勾选 [x] 的前端/Web/Android 修复所引用的三个 commit（`0bec41d…` / `1401790…` / `c66ca97…`）在本地三仓的对象库里**不存在**，本地代码里对应缺陷（验证码等待、initRouter 永久 pending、jest 30 的失效参数）**仍然在**。
  5. **实施顺序与文档自己的排序倒置**：文档说先做 GOV/UI/WEB 六个小 PR、第四步才做只读骨架；实际先建了最重的部分（约 17,400 行 Runner/采集/评估代码），最便宜、最直接省人的止血项反而没落地。

## 目标—设计追踪矩阵

| 原始目标 | 当前设计如何满足 | 证据 | 差距 | 建议 |
|---|---|---|---|---|
| 从不完整想法开始，AI 查事实后一次性提问 | requirements-governance.md 设计了四轮静默分析、D0~D3、决策包 | 设计文档完整；`.harness/skills/request-analysis/SKILL.md` 仅 30 行无任何相关内容 | **设计与实现零交集** | 作为第一优先级实施（见"下一步"切片 1） |
| 不停在"代码写完"，继续测试环境验收 | staging-accept 六层验收 + profiles | `go test ./...` PASS；11 个 Python 套件 OK；shell 契约 PASS | test1 端到端仍 BLOCKED（secret、Android 未部署、smoke.spec.ts 本地不存在） | 保留，先打通最后一公里而非加新 profile |
| 失败后 AI 自行修复、重跑、归并 | 仅有失败聚类和 reason code 设计 | 无任何返修代码；变更记录中 Kafka FAIL 转人工排期 | **缺失核心回路** | 建有界返修切片（见切片 3） |
| 长等待不依赖会话在线 | Go daemon + SQLite + systemd + resume | `runner.go`、`runner_regression_test.go`（kill/resume 测试）本地通过 | test1 上的 crash-resume 是文档声明，本地无法复核 | 保留，方向正确 |
| 结论由证据支持、防实现者自证 | 独立验证者角色 + checksum + schema | `evidence_regression_test.go` 篡改检测通过 | 独立验证者从未实际运行过一次；schema 与 Runner 实际输出（summary.json）是两套格式 | schema 收缩对齐实际输出 |
| 防错版本 | 四仓 expected full SHA fail-closed | `plans/test1-quick.json` builds 字段 + `runtime-manifest.py` 测试 OK | plan 里的 frontend SHA `0bec41d…` 本地仓库不存在——版本门禁自身引用了不可验证的版本 | 候选 SHA 必须来自可 fetch 验证的分支 |
| 增加 AI 不增加人的管理负担 | ai-validation-fleet.md 多账号角色/重复矩阵 | 纯设计，无实现 | 角色体系（6 角色、3 账号车道、100~1000 seeds）本身是管理负担，违反原始预想"不建需要负责人日常调度的平台" | 收缩为"1 实现者 + 1 独立验证者"，其余后置 |

## 复杂度与收益

| 模块 | 收益 | 建设成本 | 运行/人工成本 | 首期保留？ |
|---|---|---|---|---|
| staging-accept Go Runner（P0 串行、无 DAG） | 高：解决"AI 自己等、自己停、自己留证" | 已付出（4,310 行，测试完备） | 低（systemd + SQLite，自维护小） | **是** |
| observability 采集/评估（collect/evaluate/kafka/redis） | 高：已真实抓到 42k lag 这种人工最贵的排查 | 已付出（约 13,100 行 Python/Shell/JS） | 中：13k 行自研脚本是长期维护面，需警惕继续膨胀 | 是，冻结范围 |
| acceptance-report.schema.json（1,155 行） | 低（当前无消费者，Runner 输出的是另一套 summary.json） | 已付出 | 每次演进要双向同步两套格式 | 收缩或降级为示例 |
| requirements-governance 全套（准入字段/原因码/四视图/会议包） | 潜在高，但全量实施会把负责人变成流程管理员 | 未付出 | 高（流程本身要人喂养） | **只取核心 30%**：决策包 + scope_hash + 模板 |
| ai-validation-fleet（角色/车道/重复矩阵/故障注入） | 未证明 | 未付出 | 高（多账号协调正是原始预想要消灭的东西） | **后置** |
| WA 资源池/canary/traffic/soak 真实执行 | 高但受外部条件制约 | 部分付出 | 中 | 按风险触发，不默认 |

## 建议的目标架构

与现有设计**相同并应保留**的部分：Runner 与 AI 会话解耦（耐久程序管等待、AI 管判断、人管决策）、fail-closed 版本核对、BLOCKED/FAIL/PASS 三分、证据落盘+校验——这些与独立基准一致，是正确内核。

与现有设计**不同**的部分及迁移路径：

1. **把"三层大体系"收缩为"一条纵向管线"**。不需要"需求治理系统 + 验收控制面 + AI 验证车队"三个建制；需要的是每条需求走同一个档案目录：`intent → 决策包 → scope.lock → 实施 → quick 验收 → (失败→有界返修→重跑) → 证据 → 签收`。需求治理收缩为一个升级过的 SKILL.md + 模板；车队收缩为"实现者 + 1 个无实现上下文的验证者"。
2. **补上返修回路**：Runner FAIL 时产出失败簇文件 → 唤起一个修复 AI 会话（读档案，不读实现者对话）→ 修补 → 重跑 quick → 最多 N 轮，超界准确升级。这是原始预想里"减少人救火"的关键，比任何新 profile 都优先。
3. **完成声明必须机器可证**：backlog/变更记录中的 `VERIFIED`/[x] 必须附带本地可复现命令；候选 SHA 必须存在于可 fetch 的分支。当前三个"幽灵 commit"应立即澄清（另一台机器的工作区？未推送？记录错误？）。

## 当前进度真值表

| 工作包 | 判定 | 证据（路径 / 命令 / 实际结果） |
|---|---|---|
| GOV-001 四项目路由 | **CLAIM_UNVERIFIED**（backlog 称 VERIFIED） | `~/ideaProject/AGENTS.md` 不存在；`armada/AGENTS.md:10` 只提前端和 armada-protocol，无 Android 仓 |
| GOV-002 需求技能升级 | **NOT_STARTED** | `.harness/skills/request-analysis/SKILL.md` 共 30 行；`grep "D0\|D1\|D2\|D3\|决策包\|静默"` → 0 命中 |
| GOV-003 变更模板 | **NOT_STARTED** | `.harness/changes/_TEMPLATE.md` 共 21 行，无 scope_hash/验收合同/profiles/状态 |
| UI-001 验证码等待 | **CLAIM_UNVERIFIED**（变更记录勾 [x]） | `wheel-saas-pure-web/e2e/group-marketing.spec.ts:39-43` 仍等待 `/api/public/auth/captcha`；声称的 commit `0bec41d0dcc2…` 经 `git cat-file -t` 本地不存在 |
| UI-002 initRouter 快速失败 | **CLAIM_UNVERIFIED** | `wheel-saas-pure-web/src/router/utils.ts:201` 的 `new Promise(resolve => getAsyncRoutes().then(...))` 无 reject/catch，永久 pending 缺陷仍在 |
| WEB-001 E2E 入口 | **CLAIM_UNVERIFIED** | `armada-protocol/protocol-layer/package.json:18` 仍用 `--testPathPattern`，而 `package.json:46` jest 为 `^30.0.5`（Jest 30 已移除该参数）；声称 commit `1401790117…` 本地不存在 |
| ACC-002 可恢复 Runner | **VERIFIED**（本地能力） | `cd armada-deploy/staging-accept && go test ./...` → `ok ... 3.738s`；`runner_regression_test.go` 含 kill/resume/timeout/cancel 用例 |
| ACC-004 证据与校验 | **VERIFIED**（本地能力） | `evidence.go` + `evidence_regression_test.go` 含 checksum 篡改与脱敏用例，随 go 套件通过 |
| ACC-001 候选 manifest | **PARTIAL** | `python3 scripts/test_runtime_manifest.py` → OK；但 `plans/test1-quick.json` 引用本地不存在的 frontend SHA `0bec41d…` |
| ACC-003/005 adapter 与 quick | **PARTIAL / BLOCKED** | 11 个 Python 套件逐个 `python3 <test>.py` 全 OK；`bash wrappers/ui-smoke.test.sh`、`bash scripts/preflight.test.sh` PASS；但 wrapper 硬编码的 `e2e/smoke.spec.ts` 在本地前端仓不存在，`/etc/staging-accept/ui-smoke.env` 缺失（变更记录自认 BLOCKED） |
| OBS-001/002/003 观测 | **PARTIAL** | `node scripts/observability/kafka.test.mjs`、`redis.test.mjs` PASS；`test_collect.py`/`test_evaluate.py` OK；test1 真实数据（42k lag 等）为文档声明，本授权下不可远程复核 |
| Runner test1 部署/crash-resume | **CLAIM_UNVERIFIED** | 变更记录声明 systemd active、kill-9 恢复通过；本地无法 SSH 验证 |
| WA-001~003 / TRAFFIC / 车队 / 真实 soak | **NOT_STARTED / BLOCKED** | 变更记录自认：canary 未配、Android 三节点 `/ws/v1/traffic/snapshot` 404 未部署、soak 未逐级执行 |

## 必须回答的十个问题

1. 用户要的工作方式：**给想法和授权，收证据和结论，中间不陪跑**。
2. 结论：`PARTIALLY_ALIGNED`。
3. 最有价值的三个机制：① 可恢复 fail-closed Runner；② 四仓候选版本绑定（错版即 BLOCKED）；③ BLOCKED≠FAIL 的原因码分类 + start/peak/end 观测（已真实抓到 42k 积压）。
4. 最可能增加成本的三个机制：① 全量需求治理官僚层（10 准入字段/四视图/会议包）；② 与 Runner 输出脱节的 1,155 行报告 schema；③ 多账号验证车队角色体系。
5. 三层分层**作为文档结构合理，作为建设计划不必要**：staging-accept 与 AI 的边界（确定性执行 vs 判断）正确；需求治理应收缩为技能+模板；车队应折叠为"1+1"。
6. **没有自动修复回路**——目前止于发现与报告，这是与原始预想最大的实质差距。
7. 完成定义方向正确（只计 ACCEPTED），个别处偏严：schema 强制每次 PASS 都要四仓 build 全匹配，而治理文档自己允许 `NOT_APPLICABLE` 组件——应允许单仓变更豁免未涉及组件。
8. quick 默认每次执行；canary/soak/全量观测**按合同声明的风险触发**（现设计即如此，应保留），不默认全跑。
9. 三个月三个切片：见"下一步"。
10. staging-accept 是**合理基座，应保留并收缩**：不扩 DAG/平台/新 profile，优先打通 test1 最后一公里并对齐 schema 与实际输出。

## 应保留、修改和删除

**保留**：staging-accept Go Runner 全部 P0 边界；observability 采集与评估脚本；BLOCKED 原因码体系；版本 fail-closed 门禁；"只计 ACCEPTED"口径；profiles 按风险分层的原则。

**修改**：
1. 变更记录与 backlog 中所有 `VERIFIED`/[x] 补可复现证据或降级为 `CLAIM`；
2. 澄清三个幽灵 commit 的来源并把真实修复落到 1.0.3-snapshot 可见的分支；
3. schema 收缩到 Runner 实际输出；
4. 允许 manifest 声明 `NOT_APPLICABLE` 组件；
5. requirements-governance 砍到决策包 + scope_hash + 模板三件套。

**删除/后置**：ai-validation-fleet 的多账号车道与重复矩阵（后置到单需求闭环被证明省人之后）；perf-simulated/perf-real-canary 建设（后置）；会议包/四视图生成（后置）；报告 schema 的跨字段语义校验层（暂删）。

## 下一步（三个可独立验收的切片）

1. **需求侧一次性决策包**（改 `SKILL.md`、`owner.md`、`_TEMPLATE.md`，用一个历史高歧义需求重放验收）——消灭"逐题问人"，这是原始预想第一痛点且成本最低。不做：任何准入原因码自动化、四视图生成。
2. **test1 quick 真正跑绿**（在真实分支落地 UI-001/002、WEB-001、smoke.spec.ts，配 secret alias，quick 端到端 GREEN，一个独立会话只读报告出复核意见）——消灭"手工点页面+查中间件+整理证据"。不做：canary、soak、traffic。
3. **有界自动返修回路**（quick FAIL → 失败簇文件 → 修复会话 → 重跑，≤2 轮后准确升级）——消灭"失败后人工救火"。不做：跨仓并行返修、夜间无人返修真实 WhatsApp 链路。

## 评审结语

这套体系最大的风险不是技术方案，而是它正在重复它要消灭的病症——**文档宣布的完成快于可验证的完成**。在把 GOV-001 的 `VERIFIED` 和三个幽灵 commit 澄清之前，建议把"任何完成声明必须附本地可复现命令"作为这套体系自己的第一条放行门禁。

---

## 附录 A：第一阶段独立基准（阅读现有设计前写就，未回改）

> 本附录在只读取 original-intent.md 之后、阅读 README/requirements-governance/staging-acceptance/ai-validation-fleet/implementation-backlog/schema 及任何 staging-accept 实现之前完成，用于防止被现有架构锚定。

### A.1 评审者理解的用户核心预想

用户（技术负责人）想要的不是"更强的写代码助手"，而是**把整条交付链的陪跑成本转移给机器**。他愿意做的只有三类事：给业务想法和取舍、在关键闸口授权、看证据做最终判断。其余一切——查事实、澄清需求、跨四个仓库改代码、部署测试环境、点页面、查 Kafka/Redis/进程、跑真实 WhatsApp 链路、失败后修复重跑、整理证据、跨会话接续——都应该由 AI + 耐久程序完成。

最深的痛不是单点能力，而是**中断与接续**：AI 停在"代码写完"，人要接着跑验收；会话断了，人要重讲上下文；测试要等 6 小时，人要陪着；失败了，人要自己看日志。预想的本质是：**人从"流程的粘合剂"变成"闸口的决策者"**。

### A.2 用户未来每天应如何与 AI 协作

- 早上：用一两句话丢进一个新想法（不写技术规格）。
- 稍后：收到 AI 的一次性澄清包——已查清的事实 + 少量真正需要人拍板的取舍，每个都带推荐选项。人花几分钟回答。
- 之后不再参与，直到收到下列之一：
  - "证据齐了，请签收"（附可短时间读完的结论 + 证据索引）；
  - "被外部条件阻塞"（准确分类：代码失败/环境阻塞/外部阻塞/等待授权）；
  - "需要一次高风险授权"（真库/SSH/部署/真实 WhatsApp 写操作）。
- 人回答/授权后流程自动继续。换电脑、换会话、隔天回来，进度不丢。
- 每周看一眼度量：人工介入次数、假完成率、平均无人值守时长。

### A.3 一条需求从原始想法到真正完成的理想状态机

```text
INTAKE（想法入档）
  → FACT_FINDING（AI 自查文档/代码/测试/运行事实）
  → DECISION_PENDING（一次性提交需人取舍的问题，带推荐）
  → SCOPED（范围锁定：验收标准、非目标、回滚路径写入档案）
  → IMPLEMENTING（跨仓库拆解与实施，含本地测试）
  → LOCAL_VERIFIED
  → STAGING_DEPLOY（候选版本部署 + 版本指纹核对）
  → ACCEPTANCE_RUNNING（页面/API/Kafka/Redis/资源/协议链路验收 + 有界 soak）
      ↘ FAILURE_TRIAGE（失败聚类）→ AUTO_FIX（有界返修，回 IMPLEMENTING 或直接重跑）
  → EVIDENCE_READY（独立复核者确认证据完整、非实现者自证）
  → HUMAN_SIGNOFF（负责人短时判断）
  → DONE

任何阶段可进入四个准确的阻塞终态：
CODE_FAILED / ENV_BLOCKED / EXTERNAL_BLOCKED（账号/代理/WhatsApp）/ AWAITING_AUTH
```

关键性质：

- 状态与证据全部落盘在仓库（每需求一个档案目录），**任何新会话仅凭档案即可接班**。
- 返修回路有界（次数/时间预算），超界准确升级而不是无限循环。
- "完成"只能由独立复核 + 人签收产生，实现者无权宣布。

### A.4 职责分工

| 承担者 | 必须承担 |
|---|---|
| 耐久程序（cron/systemd/CI runner，非 AI 会话） | 长等待与 1h/6h/24h 观察、定时采样、超时、断点续跑、把检查点和原始证据写盘、在到点/失败时唤起 AI |
| AI | 事实检索、需求对账、拆解实施、失败定位与有界返修、证据解读、生成可决策结论、接班 |
| 自动测试/脚本（确定性） | 版本指纹核对、页面/API/消息链路探测、Kafka/Redis/CPU/内存采样、证据采集与格式化——凡是可以不用 LLM 判断的都不用 |
| 人 | 业务目标与取舍、范围锁定确认、高风险授权、发布/回滚/风险接受、最终签收 |

原则：**证据采集用确定性脚本，判断用 AI，决策用人**。三者不混。

### A.5 最小可行方案（MVP）与首期不应建设的内容

MVP（一个纵向切片跑通一条真实需求）：

1. **需求档案 + 状态机**：仓库内每需求一个目录（intent → 澄清记录 → scope.lock → 计划 → 证据），一个状态文件。纯文件，无平台。
2. **一次性澄清协议**：AI 先查事实，把需要人的问题合并为一个带推荐项的清单，禁止逐条追问。
3. **验收 Runner（无头、可恢复）**：一个 CLI，输入档案目录，执行部署核对 + 确定性检查 + 有界 soak，检查点落盘，崩溃/换会话后 `--resume` 续跑，产出结构化报告。
4. **独立复核**：另一个不含实现上下文的 AI 会话按档案与证据出具"证据是否支持完成"的意见。
5. **四类阻塞终态与升级模板**：准确停住比含糊完成重要。

首期**不建**：

- 多 agent 编排/调度平台、agent 池、并行车队——先证明单需求闭环省人，再谈并发。
- 常态化真实 WhatsApp 大规模验证——canary 级、按风险触发、每次显式授权。
- 自建 dashboard/Web UI——文件 + 报告 + 终端够用。
- 通用需求治理系统——一个 markdown 模板即可，流程重了人反而变成流程管理员。
- 自动生产发布。

### A.6 成功度量

- 每需求人工介入次数（目标：澄清 1 次 + 授权 N 次 + 签收 1 次，无中途救火）。
- 人工陪跑时长 ≈ 0（soak 期间无人值守）。
- 假完成率 = 0（签收后被推翻的"完成"）。
- 冷接班成功率：新会话仅凭仓库档案继续任务的成功比例。
- 阻塞分类准确率：外部阻塞不被报成功也不被报代码失败。
- 净人工成本 = 节省的协调时间 − 维护这套体系本身的时间，必须为正且可测。
