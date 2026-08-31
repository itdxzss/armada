# D1 当前交付体系与交付状态审计

- 任务编号：`D1`
- 审计日期：`2026-08-30`
- 审计方式：只读源码审计
- 工作区：`/Users/daishuaishuai/IdeaProjects`

## 0. 结论标记与判定口径

- **Observed**：由本次审计读取到的当前源码、配置、测试入口或脚本直接证明。对 README、设计文档和历史 change 的引用只证明“文档这样要求或这样声称”，不证明代码或环境已经实现。
- **Inferred**：由多项 Observed 事实推导出的风险、影响或建议，未通过执行验证。
- **Unknown**：在禁止运行构建/测试、禁止连接远程与环境的约束下，现有证据不能确认。
- **Observed**：本文严格区分四个层次：`代码已存在`、`本地已验证`、`环境已验证`、`尚未验证`。本次没有运行会生成构建产物、缓存、数据库或测试证据的命令，因此“本地已验证”不因测试文件存在而成立。
- **Observed**：本次检索使用 `rg`、`rg --files --hidden`、`git rev-parse` 和只读文件查看；没有修改业务代码，没有提交、推送、部署，也没有连接远程、SSH、数据库、Kafka 或 WhatsApp 环境。

## 1. 执行摘要

- **Inferred**：当前交付体系应定义为“若干真实工具已经存在，但尚未组成可机械裁决的端到端交付链”，不能定义为“完整交付体系已可用”。
- **Observed**：已经存在的真实工具包括：四仓各自的测试入口和构建入口、Armada 的 test1/perf2 多组件部署脚本、只读深检、持久化 `staging-accept` Runner、test1 quick wrapper、1h/6h/24h 被动 soak wrapper、前端只读 Playwright smoke、专项 perf2 负载工具，以及生产离线包安装/健康检查/回滚脚本。
- **Observed**：需求准入、D0～D3 决策、`READY` 范围锁定、验收合同、`scope_hash`、`versions.lock`、独立验证、canary、通用 integration、可交付裁决和发布观察，大部分仍是规划文档要求或人工 change 记录；当前仓库内没有把这些要求变成统一机器门禁的实现。
- **Observed**：当前 Runner 的 Plan 只有 `profile/environment/safety/four builds/stages`，没有 `changeId`、`scopeHash`、验收合同引用、制品摘要、部署记录、required profiles 或发布状态；现有验收报告 schema 也没有被 Runner 代码消费。
- **Inferred**：最大 P0 风险是“测的需求、构建的代码、部署的制品、test1 实际运行版本、Runner 的 PASS、最终交付结论”没有被同一条不可变证据链绑定，因而无法机械排除测错范围、测错版本或把局部 PASS 当成业务交付完成。
- **Observed**：部署链会执行编译/打包和健康检查，但不会机械执行四仓各自已有的单测、类型检查、静态检查或完整集成测试；后端 test1 构建明确使用 `maven.test.skip=true`，生产打包使用 `-DskipTests`，前端/Web/Android 部署入口也只构建或验活。
- **Observed**：当前 Runner 的顶层 `PASS` 表示“该 Plan 中所有进程最终退出 0”。核心 Runner 不限制某个 profile 必须有哪些业务阶段；一个仅运行本地进程 smoke 的计划也可得到 `PASS`。因此该词不能直接展示为“验收通过”或“可交付”。
- **Observed**：test1 quick 有真实实现，并在最终 `evaluate-quick` 汇总逻辑结果；但中间 wrapper 遇到逻辑 `FAIL/BLOCKED` 时会返回进程码 0 继续收集证据，因此核心 Runner 的中间 stage 表可显示 `PASS`，而真正的逻辑失败只在 wrapper 结果文件和最终汇总中体现。
- **Observed**：仓库内只有 `test1-quick` 与 `test1-soak-{1h,6h,24h}` 可执行计划；在当前 `staging-accept` 非测试代码和 `armada-deploy` 中未检索到通用 `integration`、`release-canary`、`traffic-short`、`perf-real-canary` 或发布观察执行链。
- **Unknown**：本次无法确认 Runner 是否已安装并持续运行在 test1、固定 wrapper 是否齐全、当前 test1 运行的四个版本、是否有真实 quick/soak 成功证据、是否存在仓库外 CI/分支保护或生产观察平台。

### 最重要的三项裁决

| 优先级 | 裁决 | 标签 |
|---|---|---|
| P0 | 不得从当前 Runner `PASS`、部署脚本 `SUCCESS/部署完成` 或历史 change 的 `LOCAL_VERIFIED` 推导“可交付”。 | Observed + Inferred |
| P0 | 在 `change_id/scope_hash → versions.lock → build artifacts → deployment.json/runtime manifest → Runner evidence → policy decision` 闭环完成前，当前体系没有机械的 `DELIVERABLE/ACCEPTED` 门禁。 | Observed + Inferred |
| P0 | `canary → 通用 integration → 必要 perf/soak → 发布观察` 仍缺少当前仓库内的完整执行与聚合实现；因此完整交付和发布观察均为尚未验证。 | Observed |

## 2. 覆盖范围与未覆盖范围

### 2.1 已覆盖

- **Observed**：读取了工作区 `AGENTS.md`、`armada/AGENTS.md`、前端和 Android 项目 `AGENTS.md`；`armada-protocol` 与 `armada-deploy` 未发现独立项目级 `AGENTS.md`。
- **Observed**：覆盖 `armada/docs/ai-delivery-system/` 的总览、需求治理、测试环境验收、验证车队、实现 backlog、原始目标、验收 schema 与样例。
- **Observed**：覆盖 `armada/.agents/skills/request-analysis/SKILL.md`、`armada/.harness/changes/_TEMPLATE.md`、changes 目录结构及一个包含准入/决策/验收合同的历史样例。历史记录只用于判断当前记录机制的形态，不用于证明当前代码或环境已验证。
- **Observed**：使用 `rg --files` 发现四仓测试文件和脚本，并审计其统一入口、构建入口、Dockerfile、中央部署入口、健康检查和回滚入口；没有逐一评价每个业务测试断言，因为 D1 只审计交付体系。
- **Observed**：覆盖 `armada/armada-deploy/staging-accept` 的 Plan 模型、CLI、Runner、持久化/恢复、证据、quick、soak、UI smoke、版本 manifest、深检和可观测性聚合入口。
- **Observed**：覆盖 `armada/armada-deploy/deploy-test.sh`、模块化部署库、专项 perf2 工具、生产离线打包、安装、健康检查和回滚脚本。
- **Observed**：使用 `rg --files --hidden` 检索四仓常见 CI 配置路径；在指定四仓的跟踪文件中未发现 GitHub Actions、GitLab CI、Jenkins、CircleCI 或 Azure Pipelines 配置。

### 2.2 未覆盖

- **Unknown**：没有运行任何单测、集成测试、构建、Docker 构建、Playwright、Runner 或部署脚本；因此本文不确认当前 HEAD 的测试通过率和构建可用性。
- **Unknown**：没有读取 test1/perf2/生产主机、systemd、Runner 状态目录、部署制品、云监控或环境日志；因此不确认环境安装、当前部署版本、健康状态、告警状态或历史验收结果。
- **Unknown**：没有访问代码托管平台，仓库外 CI、分支保护、审批规则和外部发布系统是否存在不能确认。
- **Observed**：没有分析具体竞品能力、具体业务功能是否正确，也没有审计业务数据或迁移结果；只检查迁移/回滚是否被交付门禁引用。
- **Observed**：没有输出或读取任何凭据值、手机号、JID、消息正文、代理密码或业务原始数据。

## 3. 当前实际流程恢复

### 3.1 当前链路形态

```text
[文档/人工]
需求输入 → 需求决策 → 范围锁定
                  │
                  │ 没有 change/scope 到构建与 Runner 的机器绑定
                  ▼
[真实但非统一门禁的仓库工具]
开发 → 可选的仓库测试 → 构建 → deploy-test 部署/健康检查
                                  │
                                  │ 不自动生成或更新 Runner 版本事实
                                  ▼
[真实但独立触发的 Runner 工具]
test1 quick → 被动 soak
      │            │
      └── 无当前通用 integration/canary/交付聚合/发布观察 ──┐
                                                            ▼
[文档/人工裁决] 可交付/交付

[另一路真实工具] 生产打包 → 安装/健康检查 → 可手工回滚
                                      └── 无发布观察状态机
```

- **Inferred**：这是一组“工具岛”，不是用户给出的完整线性流程。断点分别位于：范围锁定到候选版本、构建到测试证据、部署到 runtime manifest、quick 到 integration/canary、验收到可交付、生产发布到观察完成。

### 3.2 能力矩阵

| 阶段 | 当前真实载体 | 代码已存在 | 机械门禁 | 本地已验证（本次） | 环境已验证（本次） | 当前能安全声明的状态 | 标签 |
|---|---|---:|---|---|---|---|---|
| 需求输入 | `request-analysis` 指令 + 通用 change 模板 | 是，规则文件 | 否；必填字段、owner、容量与成功信号不做机器校验 | 不适用 | 不适用 | 只能说“已记录/待分析” | Observed |
| 需求决策 | 治理文档中的四轮分析、D0～D3、decision pack；历史样例有人工文件 | 仅文档/样例 | 否；无决策分类器、签认校验或重开机制 | 未验证 | 未验证 | 只能说“有人工作过/有文档”，不能说决策闭环 | Observed |
| 范围锁定 | 治理文档 + 单个历史 `acceptance-contract.md` | 仅文档/人工记录 | 否；无模板校验器，Runner 不接收 `scopeHash` | 未验证 | 未验证 | 不能机械声明 `READY` | Observed |
| 开发 | 四仓代码、项目规则、各自测试 | 是 | 局部；无跨仓 conductor 或统一依赖闭包门禁 | 未验证 | 未验证 | `IMPLEMENTING` | Observed |
| 单测/本地集成 | Maven、Node、Playwright、Go 测试入口和大量测试文件 | 是 | 否；部署不要求这些结果，未发现仓内统一 CI | 未运行 | 不适用 | 只有实际证据齐全时才可叫“本地检查通过” | Observed |
| 构建 | Maven/Vite/TypeScript/Docker/Go build 脚本 | 是 | 有“命令成功”门禁；没有“先测试通过”门禁 | 未运行 | 不适用 | “制品构建成功”，不能叫“候选已验” | Observed |
| test1 部署 | `deploy-test.sh --full/--all/...`、远端构建/同步、健康检查 | 是 | 有部署过程/健康检查；无验收合同、版本锁、Runner 自动衔接 | 未运行 | 未验证 | 只能按组件和环境说“部署命令成功/健康检查成功” | Observed |
| quick | 持久 Runner + deep check + runtime version + UI smoke + start/peak/end 观测 + 汇总 | 是 | 有 profile 内门禁；核心 Runner 不知道业务合同/required profiles | 未运行 | 未验证 | 最多为“该版本锁的 test1 quick 通过” | Observed |
| canary | 设计文档/schema 枚举 | 未发现可执行 plan/wrapper | 无 | 未验证 | 未验证 | 不得声明 canary 通过 | Observed |
| 通用 integration | 设计文档/schema 枚举；四仓有局部集成测试 | 未发现 test1 通用 profile | 无统一门禁 | 未运行 | 未验证 | 不得声明“集成通过” | Observed |
| 压测 | `perf2_loadtest` 是专项、独立编排；不是通用四仓 candidate profile | 是，专项 | 专项内有 preflight/恢复/报告；未绑定 change/scope/Runner | 未运行 | 未验证 | 只能声明某专项 profile 结果 | Observed |
| soak | test1 1h/6h/24h wrapper，定时 verify/observe/evaluate | 是 | 有被动观测门禁；无主动负载、无 quick 父子关联、无交付聚合 | 未运行 | 未验证 | 只能声明某个 soak profile 结果 | Observed |
| 交付 | 设计中的独立验证/证据综合/`ACCEPTED` | 未发现执行器或状态聚合器 | 无 | 未验证 | 未验证 | 不得声明“可交付/已验收” | Observed |
| 发布 | 生产离线包、install、health、rollback | 是，覆盖 app/protocol 包 | 有安装/进程/HTTP 健康门禁；测试被跳过，未接可交付裁决 | 未运行 | 未验证 | 只能声明“发布脚本完成/健康检查通过” | Observed |
| 发布观察 | 设计状态 `OBSERVING/RELEASED_OBSERVED` | 未发现当前执行状态机 | 无 | 未验证 | 未验证 | 不得声明“已发布观察完成” | Observed |

## 4. 哪些是真实工具，哪些只是文档要求，哪些没有机械门禁

### 4.1 已有真实工具

- **Observed**：Runner 会严格解析 Plan、拒绝未知字段、要求四个完整 commit、只接受 `safety=read-only`、要求绝对命令路径、限制 timeout，并拒绝明显 secret-like argv；这是可执行的输入门禁。
- **Observed**：Runner 使用 SQLite 持久化、严格串行执行、超时/取消进程组、异常恢复、显式 resume、attempt 日志、`summary.json/report.md/checksums.sha256`，并校验日志与证据清单；这是耐久执行和证据完整性的真实内核。
- **Observed**：runtime preflight 会校验 test1 manifest 的环境、时间新鲜度、四组件、Android 角色集合、候选 commit 与 `observedCommit`，并核对制品 identity 格式；这是版本不匹配 fail-closed 的真实检查器。
- **Observed**：quick wrapper 绑定候选 manifest，执行深检、runtime version、只读 UI smoke、Kafka/Redis/宿主/Web 流量观测，并在最后聚合 `PASS/FAIL/BLOCKED`。
- **Observed**：前端 smoke 只允许 GET/HEAD/OPTIONS 和精确登录 POST，阻断其他写请求，采集脱敏 console/network/步骤证据并对页面/API 失败做断言。
- **Observed**：soak wrapper 在 start/peak/end 重做深检和版本检查，要求固定 CloudWatch 信号集合并聚合 Kafka、Redis、宿主、Web/Android 流量证据；其 1h/6h/24h 中间阶段本质是等待，不产生负载。
- **Observed**：`deploy-test.sh` 能按组件或 `--full` 构建、同步、重启、健康检查；`--check` 是独立只读模式，不能与部署参数组合。
- **Observed**：生产离线包能打包 app/protocol，安装脚本切换 `current` symlink、启动 compose 并做健康检查；回滚脚本能切换到另一个已存在 release 并重新 `compose up -d`。

### 4.2 仅为文档要求或人工样例

- **Observed**：`docs/ai-delivery-system/README.md` 自身标记为“规划基线”，并把需求决策系统、完整自动验收、canary 和 AI 验证车队描述为分阶段产出。
- **Observed**：四轮需求分析、准入原因码、D0～D3、一次性决策包、Definition of Ready、`scope_hash`、四维状态和七类需求交付物都存在于治理文档；当前 `request-analysis` skill 仍只有事实对账、影响分析和关键歧义询问。
- **Observed**：backlog 把 `request-analysis` 升级和 `_TEMPLATE` 增加 `change_id/scope_hash/owner/Given-When-Then/profiles/四仓版本` 列为待实施工作；当前模板仍只有“进行中/已完成/已部署”。
- **Observed**：changes 中只找到一组 `intake-summary.md`、`decision-pack.md`、`acceptance-contract.md`；没有找到 `versions.lock` 或 `deployment.json`。该样例证明人工可以写出合同，不证明模板或机器门禁已经普及。
- **Observed**：`acceptance-report.schema.json` 描述了 change/scope、profiles、四仓 candidate、stages、signals、evidence 和 summary，但 scoped source search 只在文档中找到其引用，Runner 不生成或校验该 schema。
- **Observed**：独立验证者、对抗评审者、资源租约、真实 canary、父子 run、通用性能/故障注入和 `GREEN/RED/YELLOW` 放行均是设计目标；当前 Runner 模型没有这些实体。

### 4.3 没有机械门禁的关键转换

| 转换 | 当前缺失的机械条件 | 标签 |
|---|---|---|
| `INTAKE → READY` | 必填需求字段、D2/D3 签认、验收合同与 `scope_hash` 校验 | Observed |
| `READY → IMPLEMENTING` | WIP 容量、四仓适用性和依赖闭包校验 | Observed |
| `IMPLEMENTING → 本地检查通过` | 四仓统一命令集、精确 HEAD、测试日志和制品绑定 | Observed |
| `本地检查通过 → test1 待验` | 机器生成 `versions.lock`、制品 digest、迁移/config hash、授权和回滚计划 | Observed |
| `test1 待验 → 已部署` | deploy 自动写 `deployment.json/runtime-manifest-source` 并以实际制品身份对账 | Observed |
| `已部署 → quick 通过` | 当前可手工运行，但 deploy 不自动排队 Runner，Runner 不消费 change/scope | Observed |
| `quick → integration/canary` | required profile policy、资源租约、可执行通用 plan/wrapper | Observed |
| `profiles → 可交付` | 合同覆盖率、所有 required profiles、独立复核、无开放 P0/P1 的统一 policy engine | Observed |
| `可交付 → 已发布` | 发布脚本校验可交付签名/证据、确切 release candidate | Observed |
| `已发布 → 观察完成` | 观察窗口、指标阈值、abort/rollback trigger 与最终状态机 | Observed |

## 5. 状态名称审计与建议状态模型

### 5.1 当前容易误解的名称

| 当前名称 | 为什么会误解业务人员 | 应替换/限定 | 标签 |
|---|---|---|---|
| `已完成` | 通用模板没有说明是代码、测试、环境还是业务完成 | 禁用；只使用后文精确状态 | Observed + Inferred |
| `LOCAL_VERIFIED` / “本地候选” | 容易被理解为已经具备 test1 验收资格；当前又没有统一本地门禁 | `LOCAL_CHECKS_PASSED` 与 `TEST1_PENDING_VALIDATION` 分开 | Inferred |
| `已部署` | 部署脚本允许只发一个组件，且不携带 candidate/scope；“已部署”不说明环境和版本 | `TEST1_DEPLOYED(candidate-id)`，并要求四仓适用性与实际版本匹配 | Observed + Inferred |
| 部署 `SUCCESS` / “部署完成” | 当前只表示选中组件构建/同步/健康检查成功，不能证明业务链路、quick 或 integration | 展示为“部署步骤成功”，不得提升交付状态 | Observed |
| Runner `PASS` | 核心只按 Plan 内进程退出码聚合，任意合法短 Plan 都可 PASS | `RUN_EXECUTION_PASS`；另由 policy 产生 profile/交付结论 | Observed + Inferred |
| `quick PASS` | quick 只验证版本、健康、只读 UI 和观测，不验证完整业务合同、canary、性能或长跑 | `TEST1_QUICK_PASSED`，始终带 candidate/profile/run-id | Observed |
| `STAGING_VERIFIED` | 未绑定 acceptance contract 的 required profiles 时，不知道到底验了什么 | 使用各 profile 事实 + 派生 `TEST1_REQUIRED_PROFILES_PASSED` | Inferred |
| `GREEN` | 设计文档中是独立验证后的放行色，但当前 Runner 不会产生该语义 | 在 policy engine 落地前不用作业务状态 | Observed + Inferred |
| `已发布观察` | 可同时被理解为“正在观察”或“观察已经完成” | 拆成 `RELEASED_OBSERVING` 和 `RELEASED_OBSERVED` | Inferred |

### 5.2 精确区分七类业务可见状态

| 中文状态 | 机器状态建议 | 精确定义 | 进入条件 | 退出条件 | 不能代表 | 标签 |
|---|---|---|---|---|---|---|
| 本地检查通过 | `LOCAL_CHECKS_PASSED` | 针对同一 `scope_hash` 和精确四仓适用版本，合同要求的本地单测、静态检查、集成测试与构建全部留下可校验证据 | 合同已锁；适用仓版本精确；工作树/制品来源可追；required local commands 全部通过；证据哈希完整 | 生成 `TEST1_PENDING_VALIDATION`；或任何源码、依赖锁、迁移、配置、合同变化后退回 `IMPLEMENTING` | 未部署、未 quick、未集成、不可交付 | Inferred（建议） |
| test1 待验 | `TEST1_PENDING_VALIDATION` | 一组不可变、已经满足本地门禁、被明确指定要部署并在 test1 验证的版本集合；它是“待进入环境验证”的版本，不是“环境正在运行的版本” | 见 5.3 的全部进入条件 | 成功对账后进入 `TEST1_DEPLOYED`；部署失败进入 `TEST1_DEPLOY_FAILED`；环境/授权缺失进入 `TEST1_BLOCKED`；任一版本或 scope 改变则作废并退回 | 未部署、环境健康、quick 通过、可交付 | Inferred（建议） |
| 已部署 | `TEST1_DEPLOYED` | test1 对所有适用组件的实际 runtime artifact identity 与 `versions.lock` 一致，并完成规定 readiness/health；状态必须包含环境、candidate-id 和 deployment-id | 部署记录完成；runtime manifest 由部署事实产生；四仓/角色完整；制品 digest 和 commit 匹配；迁移/配置结果按合同记录 | 启动 quick；若环境漂移则进入 `TEST1_VERSION_DRIFT/BLOCKED`；部署回滚则回到前一 candidate 的部署状态 | quick、业务集成、canary、可交付 | Inferred（建议） |
| quick 通过 | `TEST1_QUICK_PASSED` | 同一已部署 candidate 的合同指定 quick required stages 全部执行，版本、健康、只读 UI 和基础观测通过 | `TEST1_DEPLOYED`；Plan 与合同/版本锁绑定；required stage 清单校验；逻辑结果与核心报告一致 | 进入 integration/canary/soak；候选或环境版本变化立即失效 | 完整业务行为、真实协议、性能、长跑、可交付 | Inferred（建议） |
| 集成通过 | `TEST1_INTEGRATION_PASSED` | 对 acceptance contract 的适用 Given/When/Then，在隔离 test1 数据上完成 API/DB/Kafka/Redis/事件投影/恢复对账并覆盖负向、幂等和权限用例 | quick 对同一 candidate 通过；测试数据/资源租约可用；合同 case 与 evidence 一一关联 | 若合同要求 canary/perf/soak，则继续；否则进入可交付聚合；任何 required case 失败则 FAIL/BLOCKED | canary、性能、发布观察 | Inferred（建议） |
| 可交付 | `DELIVERABLE` | 同一 scope/candidate 的所有 required local、quick、integration、canary、perf/soak（按适用性）均通过，证据完整、独立复核通过、回滚与观测计划就绪，且无未裁决阻断项 | policy engine 对合同覆盖、版本、部署、证据、findings、授权、回滚和 required profiles 机械求值为通过 | 生产发布后进入 `RELEASED_OBSERVING`；任一 candidate/scope/证据变化立即失效 | 已发布或观察完成 | Inferred（建议） |
| 已发布观察 | `RELEASED_OBSERVING` / `RELEASED_OBSERVED` | 前者表示精确 candidate 已发布且观察窗口正在进行；后者表示规定窗口已完成、阈值未越界、无未处理 rollback trigger | 仅 `DELIVERABLE` 可发布；发布记录与 runtime identity 匹配；观察规则与 owner 已建立 | 观察成功转 `RELEASED_OBSERVED`；异常转 `ROLLBACK_REQUIRED/ROLLED_BACK` | `RELEASED_OBSERVING` 不能统计为观察完成 | Inferred（建议） |

### 5.3 “test1 待验版本”的精确定义、进入与退出

- **Inferred（建议定义）**：`test1 待验版本` 是“为一个已锁定 `scope_hash` 生成的、不可变且唯一排队的四仓适用版本与制品集合；已经满足本地交付前置条件，等待 test1 部署和环境验收；其名称本身明确不声称已经部署”。

**进入条件（必须全部满足）**

1. **Inferred**：需求处于 `READY`，业务 owner、技术 owner、非目标、验收 cases、required profiles、回滚和成功信号已签认，且 `scope_hash` 已生成。
2. **Inferred**：四仓分别标记 `CHANGED / VERIFIED_NOT_CHANGED / NOT_APPLICABLE`；所有适用仓记录完整 commit，不允许仅记录分支名。
3. **Inferred**：required 本地测试、静态检查和构建均对这些精确 commit 通过，证据包记录命令、工具链、锁文件、结果与 SHA-256；本次审计没有确认任何当前候选满足此条件。
4. **Inferred**：`versions.lock` 同时绑定 `change_id`、`scope_hash`、四仓适用性、commit、dirty 状态、artifact/image digest、迁移集合 hash、部署配置 hash、required profiles 和生成时间。
5. **Inferred**：test1 部署计划、回滚目标、环境互斥锁、owner 和 D3 授权已就绪；尚未授权时应停在 `TEST1_AUTHORIZATION_PENDING`，不要提前叫“待验版本”。
6. **Inferred**：同一 test1 同时只有一个正式 candidate；任何制品或版本改变都会产生新 candidate-id，不能原地覆盖旧 lock。

**退出条件**

- **Inferred**：只有部署完成且实际 runtime manifest 对所有适用组件/角色与 `versions.lock` 完全匹配，才进入 `TEST1_DEPLOYED`。
- **Inferred**：部署命令失败进入 `TEST1_DEPLOY_FAILED`；环境、证据或资源不可得进入 `TEST1_BLOCKED`；二者都不能伪装成仍在排队。
- **Inferred**：源码、依赖锁、迁移、配置、制品、acceptance contract 或 `scope_hash` 任一改变，当前待验版本立即 `INVALIDATED`，回到 `IMPLEMENTING → LOCAL_CHECKS_PASSED` 重新生成版本锁。
- **Inferred**：取消或业务范围撤回进入 `CANCELLED`，保留旧版本锁和证据索引但不得复用其 PASS。

## 6. 需求模板、验收合同、版本锁、证据包与 Runner 的关系

### 6.1 应有的单向关系

```text
需求模板
  └─ 形成 intake + owner + success signal
      └─ 决策包解决 D2/D3
          └─ 验收合同冻结“要交付什么”并生成 scope_hash
              └─ 版本锁冻结“用哪些代码/制品交付这个 scope”
                  ├─ 部署器产生 deployment.json + runtime manifest
                  └─ Runner 消费 contract refs + versions.lock + profile plan
                        └─ 证据包记录“实际执行了什么、看到了什么”
                            └─ Policy/独立验证根据合同产生状态迁移
```

| 对象 | 唯一职责 | 必须引用 | 不能替代 | 当前实现 | 标签 |
|---|---|---|---|---|---|
| 需求模板 | 标准化问题、用户、证据、成功信号、owner、优先级和容量取舍 | `change_id` | 验收标准、代码版本、运行证据 | 当前模板字段不足且无校验 | Observed + Inferred |
| 决策包 | 记录 D2 产品取舍、D3 授权和默认安全行为 | `change_id`、事实证据 | 验收合同或环境授权本身 | 仅文档规范与历史人工样例 | Observed |
| 验收合同 | 定义业务纵切、非目标、cases、适用组件、required profiles、回滚/观测 | `change_id`、owners；输出 `scope_hash` | 版本锁、部署记录或测试证据 | 仅文档规范与单个历史人工样例 | Observed |
| 版本锁 | 把一个 `scope_hash` 绑定到精确 commit、制品 digest、迁移/config hash 和 profiles | `change_id`、`scope_hash` | runtime 实际版本 | 当前不存在 `versions.lock` 文件；Runner Plan 只有四个 commit | Observed |
| 部署记录/runtime manifest | 证明哪个环境在什么时间实际运行哪些制品与角色 | `candidate-id`、`versions.lock hash`、环境 | quick/integration 结果 | 有 runtime manifest 校验器，但 deploy 未发现自动生成/更新衔接 | Observed |
| Runner | 有界执行 profile、采集状态/日志/证据并 fail-closed | contract case refs、version lock、deployment/runtime identity、plan | 产品签认、独立复核、最终交付裁决 | 耐久执行已存在；contract/scope/release 绑定缺失 | Observed |
| 证据包 | 追加式记录 plan、stage、attempt、观测、报告、文件 hash 与脱敏结果 | change/scope/candidate/deployment/run IDs | 验收合同或 policy 决策 | 当前 Runner 有校验和证据目录，但 summary 缺 change/scope/contract coverage | Observed |
| Policy/独立验证 | 对 required profiles、cases、findings、发布条件进行最终聚合 | 上述全部对象 | Runner 的进程状态 | 当前未发现执行实现 | Observed |

### 6.2 当前关系的实际断点

- **Observed**：Runner README 明确说明 Plan 中四个 revision 是声明值，不是 Runner 自动观察到的部署版本。
- **Observed**：quick/soak 通过固定 root-owned `runtime-manifest-source.json` 复制运行事实，再做版本对比；但在 `staging-accept` 之外的部署代码中未找到更新该 source 的调用。
- **Observed**：`runtime-artifact-observer.py` 会计算文件摘要，但 `observedCommit` 来自调用者传入的 `--*-commit`，不是从制品内部不可伪造地恢复；因此“制品摘要”和“commit 归属”仍依赖受信调用链。
- **Observed**：当前四个 test1 Plan 写死相同四仓 commit；审计时四仓 HEAD 前缀分别为 `6c2c749d`、`162e6282`、`3f28e8c`、`415e6ff1`，Plan 前缀为 `5c301a16`、`0bec41d0`、`068d5c25`、`421830fd`，四项均不同。
- **Unknown**：这些 Plan 是否有意锁定了 test1 当前旧 candidate、是否已在环境执行以及 runtime source 是否与其匹配，本次不能确认。
- **Observed**：没有检索到 Plan 生成器；当前 JSON 更像手工固定计划。候选更新、scope 更新与旧 run 失效没有统一自动入口。

## 7. P0 / P1 / P2 问题

### P0-01：合同、候选、部署事实和 Runner 证据没有端到端机器绑定

- **Observed**：治理文档要求 `scope_hash`，验证车队文档要求 `versions.lock/deployment.json` 并规定 candidate 改变后旧验收失效；当前 Plan/summary 模型没有这些字段，changes 中也没有 `versions.lock/deployment.json`。
- **Observed**：部署脚本与 `runtime-manifest-source.json`/Runner 没有代码引用关系，制品观察工具的 commit 归属由调用参数声明。
- **Inferred**：在可信操作员误配、旧 Plan 被复用、局部部署或制品/commit 关联错误时，当前机制不能从一条机器链证明“这个 PASS 正在验这个 scope 的这些制品”。
- **Inferred（最小修复）**：先实现单一 candidate manifest：`change_id + scope_hash + versions.lock hash + 四仓适用性/commit + artifact digest + migration/config hash + required profiles`；deploy 原子生成 deployment/runtime manifest；Runner 入队和每次 resume 都比较三者，任一变化即 `BLOCKED/VERSION_MISMATCH`。

### P0-02：`PASS` 语义可形成业务假绿

- **Observed**：核心 Runner 只要求 1～64 个任意 stage，并在全部 stage 进程退出 0 后把 run 记为 `PASS`；`examples/local-smoke.json` 仅运行两个本地 smoke 进程也满足该模型。
- **Observed**：核心 report 标题为 “Staging acceptance report”，直接显示 `Outcome: PASS` 和 stage `PASS`，但不显示 acceptance contract、required profile coverage 或 quick wrapper 的业务逻辑 stage 结果。
- **Observed**：quick wrapper 为了继续取证，会把多数中间逻辑 `FAIL/BLOCKED` 以进程退出 0 返回；最终 `evaluate-quick` 会再读结果文件并使顶层失败，但核心中间 stage 表仍可能显示 `PASS`。
- **Observed**：文档 schema 的样例把只有一个 version-provenance stage、空 signals 的报告标成 `PASS`，同时 nextAction 又要求继续 quick 其余阶段；schema 对 PASS 仅机械要求版本匹配、证据完整/脱敏和无人决策，没有约束某 profile 的 required stage 集合。
- **Inferred**：如果业务人员只看 Runner 顶层或中间表、如果误用了短 Plan、或外部系统直接消费 schema outcome，就可能把“进程执行成功/版本核对成功”误当成“quick/验收/可交付成功”。
- **Inferred（最小修复）**：核心状态改名 `RUN_EXECUTION_PASS`；report 必须显示 `scope/candidate/profile` 与“非交付结论”提示；profile registry 机械规定 required stages；wrapper 逻辑结果进入核心 stage 模型；最终 `DELIVERABLE` 只能由独立 policy 聚合产生。

### P0-03：没有当前可执行的完整可交付与发布观察门禁

- **Observed**：在当前非测试代码中未找到通用 integration、release-canary、traffic-short、perf-real-canary 或发布观察实现；仅有 quick 和被动 soak Plan。
- **Observed**：生产打包会跳过后端测试，安装仅验证容器和 HTTP 健康；生产回滚脚本存在，但没有“只有 DELIVERABLE 才能发布”、观察窗口、阈值、自动 rollback trigger 或 `RELEASED_OBSERVED` 状态聚合。
- **Inferred**：当前任何“可交付”“已发布观察完成”结论都只能是人工判断，不能由仓库内机械证据推导。
- **Inferred（最小修复）**：先实现统一 policy ledger 和 release observation，而不是先增加更多 profile；未实现的 profile 在合同要求时必须返回 `BLOCKED/CAPABILITY_MISSING`。

### P1-01：测试存在，但构建/部署不会机械要求测试证据

- **Observed**：后端有 Spring/H2/Testcontainers 测试依赖；前端有 unit/E2E/typecheck/lint；Web 协议有 unit/CLI/lint；Android 有大量 Go tests 且项目规则要求 vet/build/test。
- **Observed**：test1 后端构建明确跳过测试源码编译和运行；前端只执行 build；Web 本地/远端只 build；Android Dockerfile 只 `go build`；生产后端也跳过测试运行。
- **Observed**：四仓未发现常见 CI 配置，部署入口也没有接收“已通过本地门禁的证据 hash”。
- **Unknown**：仓库外是否有 CI/分支保护强制测试。
- **Inferred（最小修复）**：为四仓提供只读/不自动修复的 `verify-delivery` 入口，生成机器报告；candidate 生成器只接受四份同版本验证证据，deploy 只接受该 candidate。

### P1-02：需求治理文档与当前 skill/template 漂移

- **Observed**：规划要求四轮分析、原因码、D0～D3、一次性决策、`scope_hash`、owner、Given/When/Then 和 profiles；当前 skill/template 没有这些字段和门禁。
- **Observed**：通用模板仍允许手工写 `已完成/已部署`，且没有环境、candidate、证据或 profile 限定。
- **Inferred**：业务输入质量和范围锁定依赖执行者自觉，同一需求可能越过 `READY` 条件直接开发或被过早标记完成。
- **Inferred（最小修复）**：升级模板后增加一个离线 schema/linter；缺 owner、合同、scope hash、适用性或 required profiles 时不能生成 `READY` 或 candidate。

### P1-03：quick、soak 与压测的语义边界容易被合并

- **Observed**：quick 是只读版本/健康/UI/可观测性检查，不产生业务状态；soak 的长时段是 sleep + 重复 verify/observe，没有主动负载；perf2 工具是一个独立专项，存在显式执行模式和恢复逻辑，但不属于 Runner candidate chain。
- **Inferred**：把“quick + soak + perf2 专项”汇总成“集成/压测已通过”会超出真实证据。
- **Inferred（最小修复）**：所有结果保持 profile-scoped；只有合同明确列出的 profile 可以参与交付聚合，并记录 workload、baseline、window、candidate 和适用性。

### P1-04：静态 Plan 与部署事实更新流程不明确

- **Observed**：四个 test1 Plan 的四仓 SHA 相同且与审计时四仓 HEAD 均不相同；未发现 Plan 生成器或 deploy 后自动更新 runtime source 的代码。
- **Unknown**：环境中是否有仓库外生成器或运维步骤。
- **Inferred**：手工维护 Plan 和 root-owned runtime source 会增加旧候选复用、部分更新和交接遗漏风险。
- **Inferred（最小修复）**：只允许由 versions.lock 生成 Plan；部署器产出 runtime manifest；禁止手改候选字段，旧 Plan 只读归档。

### P2-01：状态命名不统一

- **Observed**：当前同时存在模板中文状态、README 单一状态、治理文档四维状态、Runner `PASS/FAIL`、部署 `SUCCESS`、验证车队 `GREEN/RED/YELLOW` 与日报 `GREEN/RED/BLOCKED`。
- **Inferred**：不同层级同名或近义状态会在日报、change 和 Runner 报告中丢失范围，导致业务将组件成功或 profile 成功理解为交付成功。
- **Inferred（最小修复）**：采用第 5 节的精确状态，并让所有展示强制带 `environment/candidate/profile/run`；颜色只能作为派生显示，不能是事实源。

### P2-02：构建可复现性存在漂移点

- **Observed**：前端 `package.json` 固定 pnpm 版本，但 Dockerfile 使用 `pnpm@latest`；Web 协议 Dockerfile 使用 `npm install` 而非 `npm ci`；Android `go.mod` 声明 Go 1.25.1，Dockerfile 使用 Go 1.26。
- **Inferred**：相同 commit 在不同时间/入口可能得到不同工具链或依赖解析结果，削弱版本锁的含义。
- **Inferred（最小修复）**：所有 candidate build 固定工具链 digest、包管理器版本和 frozen/ci 安装模式，并把它们写入 versions.lock 与制品 provenance。

### P2-03：Runner 目标环境运维门禁尚未由当前代码证明

- **Observed**：Runner README 明确说现有 deploy-test 白名单不会自动安装它、P0 无自动保留期，并要求在目标 Linux 验证 CGO 构建、systemd 自启和 kill 后子进程组清理。
- **Unknown**：这些检查是否已经在 test1 完成，磁盘告警和证据保留是否存在。
- **Inferred（最小修复）**：增加一个不接触业务数据的 `runner-self-check` profile，输出 binary version、service state、process-group test、disk/retention policy 和 wrapper inventory；未通过不得开始正式 quick/soak。

## 8. 每阶段 Owner、输入、输出、门禁和回滚表

> 下表是基于当前缺口给出的最小责任模型；“Owner”指唯一 accountable owner，不等同于所有执行者。建议项均为 Inferred；“当前机械性”来自 Observed 源码。

| 阶段 | Owner（建议） | 输入 | 输出 | 必须门禁 | 失败/变更时回滚或退回 | 当前机械性 | 标签 |
|---|---|---|---|---|---|---|---|
| 需求输入 | 业务提出人 | 原始问题、受影响用户、现状证据、成功信号、优先级 | `intake-summary`、`change_id`、准入状态 | 必填字段与 owner 完整；可查事实不问人 | `HOLD/REJECT`，补齐后重新准入 | 无；skill 仅指导 | Inferred + Observed |
| 需求决策 | D2 为业务 owner；D3 为技术/环境授权人 | intake、事实对账、选项和影响 | 一次性 decision pack、决策日志 | 阻断 D2 全确认；D3 的授权时点/安全默认值明确 | 重开具体决策，不暗改范围 | 无；仅文档/样例 | Inferred + Observed |
| 范围锁定 | Delivery owner；业务/技术 owner 签认 | decision pack、cases、非目标、适用组件/profiles | acceptance contract、`scope_hash`、`READY` | DoR 全项、双 owner、机器 schema | 任何范围变化生成新 hash，旧合同作废 | 无统一校验 | Inferred + Observed |
| 开发 | 各仓 repo owner | 锁定合同、依赖图、仓级任务 | 代码、测试、迁移、回滚材料 | 不跨 scope；依赖和接口契约闭包 | 回到 `IMPLEMENTING`；禁止保留旧 local/test1 PASS | 仅项目规则/人工 | Inferred + Observed |
| 单测/本地集成 | 各仓 repo owner | 精确 commit、合同 cases、测试环境夹具 | repo verification report、日志 hash | required test/typecheck/vet/build 全通过；不使用会自动改代码的 lint 作为只读门禁 | 修复后重跑；版本变化使旧证据失效 | 工具存在，未接 deploy | Inferred + Observed |
| 构建 | Build owner | 已通过的 repo evidence、锁文件、工具链 | 四仓适用制品、digest、provenance | 必须消费同 commit 的验证证据；可复现工具链 | 丢弃制品，回到本地门禁 | 只检查构建成功 | Inferred + Observed |
| test1 待验锁定 | Delivery owner | 合同、四仓验证报告、制品、授权/窗口 | `versions.lock`、candidate-id、部署/回滚计划 | 第 5.3 节全部条件；环境唯一 candidate lock | `INVALIDATED/BLOCKED/CANCELLED` | 当前无 | Inferred + Observed |
| test1 部署 | Test environment release owner | versions.lock、部署计划、rollback target | `deployment.json`、runtime manifest、health/readiness evidence、`TEST1_DEPLOYED` | 所有适用制品与 runtime identity 匹配；部分部署不能提升 candidate 状态 | 自动/人工切回上一 versions.lock 并重新核对；当前 test1 自动回滚未知 | 部署/health 有；candidate 绑定无 | Inferred + Observed + Unknown |
| quick | Runner service owner；独立验证人复核 | deployed candidate、quick profile、固定 wrapper、只读资源 | quick summary、完整 evidence、`TEST1_QUICK_PASSED/FAIL/BLOCKED` | profile required stages、逻辑结果、版本和证据完整 | 环境保持部署；失败回开发或环境修复，新 candidate 重跑 | 真实但未接合同 | Inferred + Observed |
| test1 integration | Contract verification owner | quick PASS、隔离数据、acceptance cases | case coverage、API/DB/event/recovery evidence、`TEST1_INTEGRATION_PASSED` | 所有 required cases 一一有证据，无静默 skip | 清理隔离数据；失败回开发，阻塞回资源/环境 | 当前无通用 profile | Inferred + Observed |
| canary | Protocol validation owner；D3 授权人批准 | integration/quick、资源租约、安全信封、abort/cleanup | Web/Android 分通道 canary 证据 | 资源独占、动作/并发/时长上限、correlation、cleanup | 立即停止动作、隔离/冷却资源、保全证据；不自动重试真实外部错误 | 仅文档/schema | Inferred + Observed |
| 压测/soak | Performance/observability owner | 同 candidate、workload、baseline、窗口、阈值 | perf/soak summary、趋势、恢复证据 | candidate 不漂移；collector 连续；abort threshold；合同要求的时长完整 | 停负载、继续恢复观察；必要时回滚部署 | soak 被动工具、perf2 专项；无统一聚合 | Inferred + Observed |
| 可交付裁决 | 独立 verifier / Delivery owner | 合同、versions.lock、所有 required run、findings、rollback/observe plan | `DELIVERABLE` 或精确 FAIL/BLOCKED | policy 机械求值；实现者不能自证；无未决 P0/P1 | 保持非交付；新 candidate 重新全链 | 当前无 | Inferred + Observed |
| 生产发布 | Release owner | `DELIVERABLE` candidate、release package、变更/回滚窗口 | release record、生产 runtime identity、`RELEASED_OBSERVING` | 发布物与 candidate 匹配；安装健康；观察规则已启动 | 使用已验证上一 release 回滚并记录；迁移按合同补偿 | 安装/health/rollback 有；交付前置无 | Inferred + Observed |
| 发布观察 | On-call / Release observation owner | release record、成功信号、阈值、窗口 | `RELEASED_OBSERVED` 或 `ROLLBACK_REQUIRED/ROLLED_BACK` | 完整窗口、信号连续、无越界、证据归档 | 触发回滚/降级，继续观察恢复窗口 | 当前未发现状态机 | Inferred + Observed |

## 9. Unknown 与最便宜的下一步验证

| Unknown | 为什么未知 | 最便宜且安全的下一步 | 期望输出 | 标签 |
|---|---|---|---|---|
| 当前四仓 HEAD 的本地测试/构建是否通过 | 本次禁止写构建产物和缓存，未运行 | 在一次性干净 worktree/CI 中运行各仓只读门禁，输出脱敏机器报告；不要直接进入 deploy | 四份与完整 commit 绑定的验证报告 | Unknown |
| 仓库外 CI/分支保护是否存在 | 本次未连接代码托管平台 | 由有权限人员只读导出 required checks/branch rules；不需要源码或凭据值 | 规则清单与检查名称 | Unknown |
| Runner 是否已安装并稳定运行在 test1 Linux | 禁止远程/systemd 访问 | 获授权后执行最小只读 self-check：binary version、service active、wrapper inventory、磁盘策略；不读取业务日志 | `runner-self-check.json` | Unknown |
| 当前 test1 实际运行哪四仓制品 | 未读环境/runtime source | 由 deploy 端生成脱敏 runtime manifest，仅返回别名、commit 和 digest；Runner 与 versions.lock 比较 | 四仓/Android roles 完整匹配结果 | Unknown |
| `runtime-manifest-source.json` 由谁、何时更新 | 仓库内未找到 deploy 调用，环境流程未查 | 先在本地追踪/补充唯一生成入口；环境验证只检查文件 owner、mtime、hash 与 deployment-id，不输出原始内容 | 可审计生成链 | Unknown |
| 是否存在真实 quick/soak PASS 证据 | 未读取 Runner 状态目录 | 获授权后只运行 `staging-accept status/report`，只取最新候选的脱敏 summary/checksum；不要导出原始 stage logs | run-id、candidate、profile、outcome、manifest hash | Unknown |
| test1 部署失败后是否有可靠回滚 | test1 部署代码未发现自动 rollback；运维流程可能在仓库外 | 使用本地 fake transport/dry-run 验证“上一 versions.lock → redeploy → runtime match”，不要触碰环境 | 回滚步骤与验证断言 | Unknown |
| 通用 integration/canary 是否由外部系统提供 | 当前仓库未发现执行实现 | 先查外部流水线配置/任务定义；若没有，明确 `CAPABILITY_MISSING`，不要以文档 profile 枚举代替 | 工具位置或正式缺口 | Unknown |
| 生产发布后的观察/告警是否自动关联 release | 仓库只见安装/健康/回滚 | 只读检查发布平台/监控是否保存 release-id、candidate、窗口和 rollback trigger | release observation contract | Unknown |
| 历史 change 中的本地/环境验证是否仍适用于当前 HEAD | 历史记录可陈旧，且本次未重放 | 对所需当前 candidate 重跑；不复用旧 PASS，只引用其测试设计 | 当前版本新 run | Unknown |

## 10. 文件与行号证据

### 10.1 规划性质、目标状态与治理合同

- **Observed**：`armada/docs/ai-delivery-system/README.md:1-6` 将体系标记为“规划基线”；`:41-70` 描述目标闭环和独立验证；`:88-104` 定义统一状态；`:134-190` 把能力分阶段建设；`:208-217` 仍称下一实施切片。
- **Observed**：`armada/docs/ai-delivery-system/requirements-governance.md:24-60` 定义准入字段、结果和原因码；`:62-104` 定义四轮分析与 D0～D3；`:163-190` 要求验收合同、`scope_hash` 和 DoR；`:194-204` 要求范围变化生成新 hash；`:206-217` 定义产品/实施/验证/发布四维状态；`:219-231` 规定需求阶段七类交付物。
- **Observed**：`armada/docs/ai-delivery-system/implementation-backlog.md:47-60` 将 skill/template 升级列为工作包；`:95-151` 规划 candidate manifest、Runner、四 adapter 与 report；`:311-339` 仍列推荐 PR 与“缺答案只能 LOCAL_VERIFIED”。
- **Observed**：`armada/docs/ai-delivery-system/staging-acceptance.md:5-22` 明确各 profile 不能互相替代；`:99-125` 明确 CLI 是“待实现契约”；`:509-546` 定义 PASS/FAIL/BLOCKED 和 required stage 聚合；`:568-611` 定义完整证据包与 Runner 仍需执行的语义校验。
- **Observed**：`armada/docs/ai-delivery-system/ai-validation-fleet.md:35-81` 规划 contract、versions.lock、deployment、validation/evidence 目录并规定 candidate 变化使旧验收失效；`:83-97` 定义 Runner 持久职责；`:207-212` 定义放行色；`:242-252` 规定 WIP/环境互斥。
- **Observed**：`armada/docs/ai-delivery-system/original-intent.md:44-55` 明确文档、代码、本地单测、health 或部分部署都不算全流程完成。

### 10.2 当前需求 skill、模板与 change 结构

- **Observed**：`armada/.agents/skills/request-analysis/SKILL.md:10-24` 只有事实源、对账、影响和歧义确认步骤；`:26-30` 要求结论标明事实/推断/未确认，但没有 D0～D3、准入 schema 或 scope gate。
- **Observed**：`armada/.harness/changes/_TEMPLATE.md:1-21` 只有来源、`进行中/已完成/已部署`、任务、决策、验证、部署和遗留，没有 change/scope/owners/profiles/version lock。
- **Observed**：`armada/.harness/changes/README.md:1-17` 把 changes 定义为持久化历史记录和人工复制模板流程。
- **Observed**：`armada/.harness/changes/2026-08-26-group-canonical-first-classification/intake-summary.md:3-9` 展示人工四维状态；`:19-36` 展示人工准入；`:45` 把 test1 Runner 留待授权。
- **Observed**：同目录 `acceptance-contract.md:1-7` 有人工 scope hash；`:91-115` 列四仓状态、required profiles 和 D3 状态。`summary.md:1-8` 标记历史 `LOCAL_VERIFIED`；`:27` 仍未运行 test1；`:66-73` 明确未部署/仍有环境阻塞。其历史测试数字不作为本审计的当前验证证据。

### 10.3 Runner、版本和证据

- **Observed**：`armada/armada-deploy/staging-accept/model.go:20-63` 定义 Runner/Stage 状态和 Plan 字段；`:149-211` 只校验 read-only、四个 full SHA、1～64 stages、绝对命令、secret-like argv 与 timeout。
- **Observed**：`armada/armada-deploy/staging-accept/examples/local-smoke.json:1-23` 证明一个只含两个本地 smoke 命令的合法 Plan。
- **Observed**：`armada/armada-deploy/staging-accept/runner.go:121-218` 按进程 stage 串行执行，非 PASS 终止，全部 PASS 即把 run 记为 PASS。
- **Observed**：`armada/armada-deploy/staging-accept/evidence.go:144-186` 生成 summary/checksum/report；`:189-255` 校验 checksum 与持久状态；`:595-623` 报告只展示 Runner Outcome、环境、profile、声明 builds 和核心 stage 表。
- **Observed**：`armada/armada-deploy/staging-accept/README.md:3-20` 给出 P0 边界；`:44-45` 明确 Plan revisions 是声明值；`:113-119` 说明 resume/checksum 语义；`:121-155` 说明独立安装、无自动保留期和目标 Linux 尚需验证。
- **Observed**：`armada/armada-deploy/staging-accept/scripts/preflight-manifest-check.py:73-127` 校验制品 identity/observedCommit/角色；`:152-226` 校验环境、新鲜度、四组件和候选版本匹配。
- **Observed**：`armada/armada-deploy/staging-accept/scripts/runtime-artifact-observer.py:34-53` 计算制品 digest；`:84-105` 显示 commit 来自命令行参数；`:108-150` 组合 manifest。
- **Observed**：`armada/armada-deploy/staging-accept/wrappers/runtime-observer-client.py:17-51` 固定 root-owned source；`:66-128` 只校验安全读取和顶层形态；`:220-240` 复制到当前 run。
- **Observed**：`armada/armada-deploy/staging-accept/plans/test1-quick.json:1-12` 固定四仓 builds；`:12-83` 列 quick 十阶段。`plans/test1-soak-1h.json:1-23` 展示被动 soak 的 verify/observe/wait/evaluate 结构；6h/24h 使用同类结构。
- **Observed**：`armada/armada-deploy/staging-accept/wrappers/test1-quick.py:35-46` 定义 quick stages；`:135-174` 对多数逻辑失败返回 0 继续；`:278-346` 执行 deep check/runtime versions/UI smoke；`:409-524` 最终聚合逻辑结果并写 quick summary。
- **Observed**：`armada/armada-deploy/staging-accept/wrappers/test1-soak.py:283-307` 的长时阶段使用 sleep；`:309-351` 在各窗口复核版本/环境；`:481-584` 聚合观测并写 soak summary。

### 10.4 验收 schema 的静态缺口

- **Observed**：`armada/docs/ai-delivery-system/acceptance-report.schema.json:8-24` 要求 change/scope/profile/candidate/stages/signals/evidence；`:45-60` 枚举计划 profiles；`:116-157` 对 PASS 只增加 build/evidence/decision 约束；`:356-375` 要求四 builds；`:1029-1094` 定义 evidence hash/index。
- **Observed**：`armada/docs/ai-delivery-system/acceptance-report.example.json:1-10` 顶层写 `PASS`；`:57-104` 只有 version-provenance stage 且 signals 为空；`:125-140` 又明确只是版本样例并要求继续 quick 其余阶段。

### 10.5 四仓测试、构建与部署

- **Observed**：`armada/armada-api/pom.xml:100-120` 有 Spring/H2/Testcontainers 测试依赖；`:123-165` 有编译/Boot 插件和一个专项 MySQL IT profile。
- **Observed**：`wheel-saas-pure-web/package.json:6-28` 有 unit、E2E、typecheck、lint 和 build 脚本；`:173` 固定 pnpm；`Dockerfile:1-13` 使用 `pnpm@latest`、安装依赖并只 build。
- **Observed**：`armada-protocol/protocol-layer/package.json:8-25` 有 build、unit/CLI test、lint 和 Docker 命令；`deploy/Dockerfile:1-12` 使用 `npm install` 并只 build。
- **Observed**：`whatsapp-server-feature-android-zhuan/go.mod:1-3` 声明 Go 1.25.1；`deploy/Dockerfile:1-18` 使用 Go 1.26 并只构建二进制；`deploy/coordinator/deploy.sh:40-92` 和 `deploy/node/deploy.sh:43-103` 只构建/迁移/启动/health。
- **Observed**：`armada/armada-deploy/lib/armada.sh:38-58` 后端以 `maven.test.skip=true` 构建、前端只 build；`lib/protocol.sh:20-64,159-205` 协议层本地/远端只 build、reload、health；`lib/zhuan.sh:127-147,193-210,291-300` Android 侧同步、build、migrate、restart、health。
- **Observed**：`armada/armada-deploy/deploy-test.sh:243-264` 定义部署 scope；`:347-367` 明确分支与四仓来源；`:767-885` 执行 build/deploy/health 并输出组件 success/部署完成。
- **Observed**：`armada/armada-deploy/lib/deep-check.sh:158-220` 检查各组件/Kafka，其中无预期元数据时 Kafka exact check 会 skip；`:223-253` 跨组件只做 readiness/HTTP 路由和 health。
- **Observed**：`armada/armada-deploy/deploy-test.test.sh:1568-1640` 有大量部署脚本测试函数的手工调用入口；其存在不证明本次已运行，也不构成 deploy 前置条件。
- **Observed**：`wheel-saas-pure-web/e2e/smoke.spec.ts:49-100` 关闭原生 trace 并脱敏；`:320-409` 登录并遍历只读页面、阻断写、检查 API/console；`e2e/support/smoke-policy.ts:16-31` 定义只读白名单和失败条件。

### 10.6 压测、生产发布与回滚

- **Observed**：`armada/armada-deploy/tools/perf2_loadtest/orchestrator.py:42-88` 定义专项状态；`:90-226` 构建、preflight、监控、可选 resume、reconcile 和报告，证明它是专项真实工具，但不是 staging Runner 的 profile。
- **Observed**：`armada/armada-deploy/package-prod.sh:189-208` 后端跳过测试、前端只 build；`:224-238` 构建 app/protocol 镜像；`:246-255` 把 install/rollback/status/logs 放入发布包。
- **Observed**：`armada/armada-deploy/prod/scripts/install.sh:93-115` 安装 release 并切 `current`；`:117-166` 只做容器/HTTP health 后宣布安装完成。
- **Observed**：`armada/armada-deploy/prod/scripts/rollback.sh:20-50` 查找另一个已存在 release、切换 symlink 并重新启动 compose；没有发布观察或 rollback trigger。

## 11. 最小落地顺序

1. **Inferred**：先统一状态词并立即停止使用无范围的“已完成”“PASS”“SUCCESS”作为业务交付结论；将“本地候选”正式替换为第 5.3 节的 `test1 待验版本`。
2. **Inferred**：升级需求模板/skill，并用本地 schema/linter 机械生成 `change_id`、验收合同和 `scope_hash`。
3. **Inferred**：建立唯一 `versions.lock` 和 candidate-id；四仓本地验证报告、制品 digest、迁移/config hash 必须绑定同一 candidate。
4. **Inferred**：让 deploy 消费 candidate 并原子产出 deployment/runtime manifest；Runner 不再接受手填的四仓 builds。
5. **Inferred**：把 wrapper 逻辑 outcome 纳入核心 stage 模型，增加 profile registry 和 required-stage 校验；Runner PASS 改为执行层状态。
6. **Inferred**：实现 `quick → integration →（按合同）canary/perf/soak → independent policy → DELIVERABLE` 聚合；未实现的能力明确 BLOCKED。
7. **Inferred**：生产发布只接受 `DELIVERABLE` candidate，并实现 `RELEASED_OBSERVING → RELEASED_OBSERVED/ROLLED_BACK` 的观察状态机与证据。

## 12. 本次审计的验证层级声明

| 层级 | 本次结论 | 标签 |
|---|---|---|
| 代码已存在 | 上文标记为“真实工具”的源码、配置、测试入口和脚本均在当前工作区可见 | Observed |
| 本地已验证 | 没有运行测试或构建；不能确认当前 HEAD 通过 | Unknown |
| 环境已验证 | 没有连接 test1/perf2/生产或 Runner 状态目录；不能确认 | Unknown |
| 可交付 | 当前仓库没有可机械推导该状态的完整门禁；本次不作此声明 | Observed + Inferred |
| 已发布观察 | 当前仓库没有完整观察状态机；本次不作此声明 | Observed + Unknown |
