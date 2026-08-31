# S2 非排除域能力差距矩阵

任务编号：S2
审计日期：2026-08-30
审计方式：四份稳定输入合并去重，并以当前源码、配置、测试源码和迁移复核关键证据
验证边界：只读静态分析；未运行构建、测试、迁移、服务或环境验收

## 0. 判定与评分口径

- **Observed**：当前源码、配置、测试源码、迁移或本地参考构建产物直接证明；不等于本地测试通过或环境可用。
- **Inferred**：由两项及以上 Observed 事实推断；本次没有运行验证。
- **Unknown**：现有证据不能确认，或验证被本任务边界排除。
- **Observed**：“代码已存在”只表示当前静态实现存在。
- **Observed**：“本地已验证”要求本次有真实命令、退出码和结果；本报告没有此类证据。
- **Observed**：“环境已验证”要求指定环境中的实际场景结果；本报告没有此类证据。
- **Unknown**：“尚未验证”表示只有代码、配置、迁移、测试源码或页面证据，尚无本地运行或环境结果。
- **Inferred**：“已实现但只有本地证据”要求在所述边界内存在跨层实现且未发现确定性代码断链；它仍只是静态证据，不表示本地运行通过或业务可用。
- **Inferred**：“部分实现”表示已有实质代码，但存在当前代码直接证明的跨层缺口、契约断链、恢复缺口、语义缺口或关键边界未闭合。
- **Observed**：没有以菜单、路由、类、配置或迁移数量计算完成度。

七维评分均为 1～5 分，分值越高表示：

- 收入/获客价值、客户留存价值、当前客户痛点、与现有架构复用度：价值或复用越高。
- 实现成本、协议/封号风险、验证成本：成本或风险越高。

优先级口径：

- **P0**：安全、数据隔离或现有功能确定性断链。
- **P1**：能够形成完整业务纵切且近期有价值。
- **P2**：需要先验证真实需求、竞品后端行为或产品边界。
- **P3**：当前暂不跟进。

## 1. 执行摘要

1. **Observed**：四份输入已连续两次稳定并完整读取。当前主仓 HEAD 与输入快照一致：armada 为 6c2c749d27cd，前端为 162e6282c201，Web 协议为 3f28e8c50667，Android 协议为 415e6ff16bd3；没有执行 fetch，不能代表远端最新状态。
2. **Observed**：合并去重后得到 38 个能力簇：0 个“已实现并有环境证据”、7 个“已实现但只有本地证据”、18 个“部分实现”、3 个“只有页面/占位”、6 个“明确缺失”、4 个“产品边界未知”。
3. **Observed**：本次没有任何“本地已验证”或“环境已验证”能力。7 个“只有本地证据”均指跨层静态实现证据，不等于测试通过或业务可用。
4. **Observed**：去重后有 7 个 P0：用户私有数据隔离未进入活动运行时；多组写操作继承只读权限；协议管理面鉴权 fail-open 或缺失；通讯录快照只有消费者；通讯录消息跨仓契约不兼容；建群关闭发言不是成功后置条件；建群营销失败重试遗忘已建群事实。
5. **Observed**：最新版 C2 与当前代码共同证明，标准拉群的 settingTiming 已被 BEFORE_PULL/AFTER_PULL 调用点读取；旧迁移中的“只写不读”注释已过期。因此该项不再是 P0。当前缺口是群资料设置失败只留痕、不重发，协议结果缺少失败项时会回退为“群名设置失败”，归入 P1。
6. **Inferred**：关闭 P0 后，近期最有价值的四条 P1 纵切是：账号可靠性与诊断、群任务副作用安全、通讯录安全私聊、持久操作与敏感访问审计。
7. **Observed**：参考系统证据只是 2026-08-26 的编译后前端存档，无 sourcemap、无后端和环境证据。它能证明页面、字段和 API 字符串，不能证明业务真实可用。
8. **Inferred**：Armada 已有一些不应为表面对齐而削弱的设计：分阶段持久化与人工收口、稳定操作标识、租约与资源锁、受理与真实结果分离、代理原子分配与恢复、Web 节点容量和故障迁移、完整快照才清残留。
9. **Unknown**：任何目标环境运行提交、迁移落地、角色授权、网络边界、协议配置、真实成功率、封号率、联系人完整性和竞品后端行为。

## 2. 覆盖范围与未覆盖范围

### 2.1 已覆盖

| 范围 | 结论 |
|---|---|
| C1 账号、设备、代理与节点 | **Observed**：完整读取，并与当前账号、代理、Web/Android 节点关键代码复核。 |
| C2 群与群营销 | **Observed**：完整读取，并与当前群设置、建群、任务、营销和恢复代码复核；settingTiming 冲突以当前代码为准。 |
| C3 通讯录、消息与自动化 | **Observed**：完整读取，并复核快照消费者、协议事件枚举、消息命令/结果契约和任务恢复 SQL。 |
| C4 平台管理与运营 | **Observed**：完整读取，并复核 IAM/RBAC、租户行隔离、用户 owner 迁移、协议管理面和审计现状。 |
| 当前主仓 | **Observed**：armada、wheel-saas-pure-web、armada-protocol、whatsapp-server-feature-android-zhuan。 |
| 隔离候选 worktree | **Observed**：只用于区分“候选代码存在”和“活动分支已包含”；后端候选 0cfc9b70c885，前端候选 800c20b6816d，均未运行。 |
| 本地参考构建 | **Observed**：只用于页面、字段和 API 契约对比，不用于证明竞品后端或环境行为。 |
| 检索方法 | **Observed**：使用 rg、rg --files、sed、nl、git status 和 git rev-parse；历史 change/README 没有覆盖当前代码事实。 |

### 2.2 明确未覆盖

- **Observed**：未连接远程、SSH、数据库、Kafka 或真实 WhatsApp 环境。
- **Observed**：未运行 Maven、pnpm、Node 或 Go 测试/构建，避免写入报告以外的构建产物。
- **Unknown**：目标环境镜像 SHA、配置覆盖、Flyway history、实际权限分配、Ingress/网络策略和外部日志平台。
- **Unknown**：竞品后端、数据库、权限、状态机、幂等、失败恢复及真实协议行为。
- **Unknown**：旧项目、工作区外 L1/L2 后台、商业中台或私有旁车是否提供本报告列为边界未知的能力。
- **Observed**：没有读取或输出凭据、号码、联系人标识、消息正文、代理秘密或原始业务数据。

## 3. 六类汇总与验证层级

### 3.1 六类汇总

| 分类 | 数量 | 判定 |
|---|---:|---|
| 已实现并有环境证据 | 0 | **Observed**：四份输入和本次复核均无环境验收证据。 |
| 已实现但只有本地证据 | 7 | **Observed/Inferred**：有跨层静态实现且未发现所述边界内的确定性断链；本次未运行，不能称为本地已验证。 |
| 部分实现 | 18 | **Observed/Inferred**：有实质代码，但存在跨层缺口、确定性断链、恢复缺口或关键语义未闭合。 |
| 只有页面/占位 | 3 | **Observed**：只有占位页、禁用控件或菜单归组，没有相应业务中心。 |
| 明确缺失 | 6 | **Observed**：在当前审计范围内做了负向检索，未找到对应产品闭环。 |
| 产品边界未知 | 4 | **Unknown**：当前 L3 范围没有承诺，不能因竞品邻接能力存在就判定为应做。 |

### 3.2 验证层级

| 层级 | 当前结论 |
|---|---|
| 代码已存在 | **Observed**：矩阵中的本地证据和部分实现能力均有当前源码、配置、迁移或测试源码证据。 |
| 测试源码已存在 | **Observed**：账号、群、代理、IAM、协议节点和通讯录任务均能找到部分测试源码。 |
| 本地静态核对 | **Observed**：已完成四份矩阵合并、当前 HEAD 核对和关键文件行号复核。 |
| 本地运行已验证 | **Unknown**：本次未运行任何测试或构建。 |
| 候选包已包含 | **Unknown**：未读取发布构件或构建清单。 |
| 环境已验证 | **Unknown**：未连接任何环境。 |
| 业务已可用 | **Unknown**：不存在可由静态代码直接推出的“业务已可用”结论。 |

## 4. 去重后的能力与评分矩阵

说明：

- **Observed**：分类、代码状态和证据引用来自当前代码。
- **Inferred**：优先级与七维分数均为合并后的产品/工程判断。
- 评分列依次为收入、留存、痛点、复用、成本、协议/封号风险、验证成本。

| ID | 能力簇 | 分类 | 验证状态 | 收入 | 留存 | 痛点 | 复用 | 成本 | 风险 | 验证 | 优先级 | 关键结论与证据 |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| M01 | 用户私有数据隔离与前端缓存命名空间 | 部分实现 | 活动分支仅迁移；本地/环境未验证 | 2 | 5 | 5 | 4 | 4 | 1 | 3 | P0-1 | **Observed**：owner/scope 迁移在活动分支，运行时 owner 写入/过滤未闭合；完整实现只在独立 worktree，活动前端仍有固定业务缓存键。E01 |
| M02 | 后端写操作最小权限 | 部分实现 | 代码存在；本地/环境未验证 | 1 | 5 | 5 | 5 | 2 | 2 | 2 | P0-2 | **Observed**：账号、分组、代理、群、成员和营销多组写接口继承 view/list 权限。E02 |
| M03 | Web/Android 协议管理面强鉴权 | 部分实现 | 管理代码存在；环境暴露 Unknown | 1 | 5 | 5 | 4 | 2 | 3 | 3 | P0-3 | **Observed**：Web key 空集时放行；Android 节点状态变更路由无应用鉴权。**Unknown**：实际网络 ACL。E03 |
| M04 | 通讯录快照生产、分片、乱序与同步状态 | 部分实现 | 后端消费存在；生产端缺失 | 4 | 4 | 5 | 4 | 3 | 3 | 3 | P0-4 | **Observed**：后端只消费 account.contacts_reported；两条协议当前无生产实现，快照明细/同步状态也缺少完整水位保护和 FAILED 业务状态写入。E04 |
| M05 | 通讯录消息命令、结果与收件人关联 | 部分实现 | 后端发送存在；两协议不兼容 | 4 | 4 | 5 | 5 | 2 | 3 | 2 | P0-5 | **Observed**：后端下发三个任务关联 ID；Web/Android payload、校验和结果事件均不保留，命令进入错误分支。E05 |
| M06 | 建群关闭普通成员发言的成功后置条件 | 部分实现 | 两协议 best-effort；未环境验证 | 4 | 4 | 5 | 5 | 2 | 5 | 3 | P0-6 | **Observed**：Web 异步忽略失败且测试源码固定此行为；Android 失败仅告警，营销可继续。E06 |
| M07 | 建群营销失败重试保留已建群事实 | 部分实现 | 确定性代码缺口 | 4 | 4 | 5 | 4 | 3 | 5 | 3 | P0-7 | **Observed**：消息失败重试清空群关联并回 PENDING，下一轮可能再次建群。E07 |
| M08 | 标准拉群群资料设置时机、失败分类与恢复 | 部分实现 | 时机已接通；失败恢复不完整 | 3 | 4 | 4 | 5 | 2 | 4 | 2 | P1-B | **Observed**：BEFORE_PULL/AFTER_PULL 已按 settingTiming 分支；失败只留痕不重发，协议未给失败项时回退为群名失败。E08 |
| M09 | 账号导入、批次、生命周期命令与状态收敛 | 部分实现 | 跨层代码存在；最终闭环未验证 | 4 | 5 | 4 | 5 | 2 | 4 | 3 | P1-A | **Observed**：多格式导入、批次明细、批量上下线、抢登和 outbox 受理存在；最终在线闭环 Unknown。E09 |
| M10 | 账号分组、画像、设备事实、分配与诊断 | 部分实现 | 多处代码存在；产品呈现未闭合 | 3 | 5 | 4 | 5 | 3 | 2 | 3 | P1-A | **Observed**：分组拆合、状态统计和诊断后端存在；好友数占位，丰富设备事实未贯穿 DTO/UI，分配字段无可识别写入闭环。E09 |
| M11 | 代理导入、健康、地域分配与失败恢复 | 已实现但只有本地证据 | 跨层静态证据；本地运行/环境未验证 | 3 | 5 | 4 | 5 | 3 | 4 | 3 | P1-A | **Observed**：抽检、原子分配、失败排除、换代理补偿和不可用复检存在；未发现该能力边界内的确定性断链。E10 |
| M12 | Web 节点容量、心跳与故障迁移 | 已实现但只有本地证据 | 协议静态证据；本地运行/环境未验证 | 2 | 5 | 4 | 5 | 3 | 4 | 3 | P1-A | **Observed**：显式 capacity/load、原子满载保护、心跳判死和跨 worker failover 存在。E11 |
| M13 | Android 节点版本、心跳、分配与容量 | 部分实现 | 软均衡存在；硬容量缺失 | 2 | 5 | 4 | 4 | 3 | 4 | 3 | P1-A | **Observed**：有版本、心跳、账号数和最少账号分配；无 capacity 字段或满载拒绝。E11 |
| M14 | 群列表、详情、成员、管理员与群权限 | 已实现但只有本地证据 | 前后端和双协议静态证据；未运行 | 3 | 5 | 4 | 5 | 3 | 4 | 4 | P1-B | **Observed**：成员名册、升降管理员、移除成员及五类权限抽象存在；环境一致性 Unknown。E12 |
| M15 | 同步普通建群与批量普群 | 部分实现 | 状态机存在；幂等/恢复有缺口 | 5 | 4 | 4 | 5 | 3 | 5 | 4 | P1-B | **Observed**：批量普群有客户端幂等键和准入；同步建群使用随机操作 ID，崩溃恢复 mapper 未发现调用者。E13 |
| M16 | 标准拉群与分阶段拉群营销 | 部分实现 | 持久阶段存在；无运行闭环 | 5 | 4 | 4 | 5 | 3 | 5 | 4 | P1-B | **Observed**：稳定操作标识、租约、资源锁、逐阶段失败和 MANUAL_REVIEW 存在；群资料恢复和统一风险预算仍不完整。E13 |
| M17 | 普通群营销、轮次与新群延迟发送 | 已实现但只有本地证据 | attempt/outbox 静态证据；未运行 | 5 | 4 | 4 | 5 | 3 | 5 | 4 | P1-B | **Observed**：时间窗、轮次、新群延迟、暂停/关闭、outbox 受理和结果回写代码存在；真实发送 Unknown。E13 |
| M18 | 通讯录任务 CRUD、调度、恢复、抑制与统计 | 部分实现 | 产品外壳存在；两个 P0 阻断 | 5 | 4 | 5 | 4 | 4 | 5 | 4 | P1-C | **Observed**：任务/账号/收件人模型和页面存在；快照、协议关联、SENDING 超时、退订、跨任务频控和受众试算不闭合。E14、E15 |
| M19 | 持久用户操作与敏感访问审计 | 部分实现 | 技术日志/局部诊断存在 | 2 | 5 | 5 | 5 | 3 | 1 | 3 | P1-D | **Observed**：普通日志、上线诊断和受控下载存在；没有统一 append-only 审计、查询 API/UI 和留存。E16 |
| M20 | L3 IAM、租户行隔离、用户/角色/菜单 | 已实现但只有本地证据 | 跨层静态证据；本地运行/环境未验证 | 1 | 5 | 4 | 5 | 2 | 1 | 2 | P1-D | **Observed**：基础 IAM/RBAC、动态菜单、方法权限和 tenant fail-closed 代码存在；用户级 owner 与方法权限缺口由 M01/M02 单列。E16 |
| M21 | 域内导入导出、渠道成本与局部运营报表 | 已实现但只有本地证据 | 多域静态证据；本地运行/环境未验证 | 3 | 4 | 3 | 5 | 2 | 1 | 2 | P2 | **Observed**：账号、群、IP、营销导出和渠道成本统计存在；没有跨域统一中心或平台 P&L。E17 |
| M22 | 账号/群资产转移与不可逆迁移交付 | 明确缺失 | 负向检索；竞品仅前端契约 | 4 | 4 | 3 | 3 | 5 | 3 | 4 | P2 | **Observed**：当前只有租户内分组迁移和有限导出，未找到冻结、短时码、接收、撤销和迁移任务闭环。E19 |
| M23 | 账号分组标签、融合搜索与密钥轮换 | 明确缺失 | 负向检索；需求 Unknown | 2 | 3 | 2 | 4 | 3 | 1 | 2 | P2 | **Observed**：当前分组只有名称、备注和计数；未找到标签与轮换产品闭环。E09、E19 |
| M24 | 账号通用 CSV、国家/趋势与质量画像 | 部分实现 | schema/局部统计存在；展示不闭合 | 3 | 3 | 3 | 4 | 3 | 1 | 3 | P2 | **Observed**：当前导出用途窄，profile 字段多于列表，国家/日趋势少于参考前端。E09、E19 |
| M25 | 收群验群、群采集与群关注 | 明确缺失 | Armada 负向检索；竞品行为 Unknown | 4 | 3 | 2 | 3 | 4 | 5 | 5 | P2 | **Observed**：当前范围无同名产品闭环；参考构建只有页面/API 字符串。E19 |
| M26 | 群关键词、群脚本、独立群池/封号统计 | 明确缺失 | Armada 负向检索；竞品行为 Unknown | 3 | 3 | 2 | 3 | 4 | 5 | 5 | P2 | **Observed**：当前范围无同名模块；参考构建的后台执行与指标口径 Unknown。E19 |
| M27 | 群 QR、速度档与标准拉群未来定时启动 | 部分实现 | 协议/字段/页面存在但语义未闭合 | 3 | 3 | 2 | 4 | 3 | 4 | 4 | P2 | **Observed**：Android QR 路由只返回邀请 URL；speed 只接受 NORMAL；新版定时页面只保存本地状态，旧创建契约无未来 startAt。E13 |
| M28 | 通讯录 delivery/read、会话、入站与回复 | 明确缺失 | 通用事件局部存在；C3 未接入 | 4 | 4 | 3 | 3 | 5 | 4 | 5 | P2 | **Observed**：C3 只有发送结果状态，无 ACK sink、会话表、回复 API 或回复率。E21 |
| M29 | 通讯录视频、音频和文件任务 | 明确缺失 | 通用协议路由存在；任务未集成 | 3 | 2 | 2 | 4 | 3 | 4 | 4 | P2 | **Observed**：C3 只集成正文和可选图片；通用媒体接口不带任务关联。E21 |
| M30 | 通讯录 AI、剧本与工作流 | 只有页面/占位 | 页面明确建设中 | 3 | 2 | 1 | 2 | 5 | 5 | 5 | P3 | **Observed**：占位页无表单、列表或动作；当前业务源码无 AI/自动回复/工作流引擎。E20 |
| M31 | 账号 IP 分组与已分配服务筛选 | 只有页面/占位 | 控件禁用并标记未接入 | 2 | 2 | 2 | 4 | 2 | 1 | 2 | P2 | **Observed**：前端显式禁用，不能计为筛选能力。E20 |
| M32 | 统一任务、通知与导入导出中心 | 只有页面/占位 | 菜单归组/域内作业，不是统一中心 | 2 | 4 | 3 | 4 | 4 | 1 | 3 | P2 | **Observed**：任务中心只是既有页面归组；未见统一通知、跨域作业、下载历史和保留策略。E17、E20 |
| M33 | 平台共享 IP 池、供应商、采购与自动补货 | 产品边界未知 | schema 有预留；运行语义不闭合 | 3 | 3 | 2 | 3 | 5 | 2 | 4 | P2 | **Observed**：表允许平台池，候选 SQL 只选当前租户；前端仍是手工导入。**Unknown**：是否属 L1/L2。E10、E18 |
| M34 | 统一协议版本、灰度升级与回滚控制 | 部分实现 | 版本/前置检查存在；执行器缺失 | 1 | 4 | 3 | 4 | 4 | 3 | 4 | P2 | **Observed**：Web 可见版本，Android 有版本和升级前置检查；没有统一灰度、升级执行和回滚闭环。E11 |
| M35 | 平台租户生命周期、部门、席位与组织树 | 产品边界未知 | 当前 L3 未实现 | 2 | 3 | 1 | 2 | 5 | 1 | 4 | P3 | **Observed**：当前前端明确为 L3；**Unknown**：L1/L2 路线图。E18 |
| M36 | 钱包、订单、套餐、账单与平台 P&L | 产品边界未知 | 当前范围负向检索 | 3 | 2 | 1 | 1 | 5 | 1 | 5 | P3 | **Observed**：当前范围无通用财务/交易闭环；**Unknown**：是否由商业中台承载。E18 |
| M37 | 开放平台、租户 API key、Webhook 与连接器 | 产品边界未知 | 当前范围负向检索 | 3 | 2 | 1 | 2 | 5 | 2 | 5 | P3 | **Observed**：内部服务 key 和点对点集成不能等同开放平台；产品路线图 Unknown。E18 |
| M38 | 特定第三方渠道集成与令牌保护 | 已实现但只有本地证据 | 点对点静态证据；本地运行/环境未验证 | 3 | 3 | 2 | 4 | 2 | 2 | 3 | P2 | **Observed**：特定渠道配置、探测、令牌加密和不回显代码存在；另一个第三方 key 形态常量的公开/秘密分类 Unknown，报告未回显其值。E22 |

## 5. P0 问题

### P0-1 用户私有数据隔离未进入活动运行时

- **Observed**：V141-V152 已加入 owner/scope schema；账号通用 insert 不写 owner，账号分页没有 SELF/ALL 过滤，多域运行时代码同样未闭合。
- **Observed**：完整 DataScope 与用户维度缓存键只在独立 worktree，不在当前活动主仓提交。
- **Inferred**：同租户普通用户可能读写彼此业务数据；同标签页切换身份可能复用上一身份缓存。
- **Unknown**：环境运行哪个提交、迁移是否已部署、历史空 owner 的正式处置规则。
- **Inferred**：发布门槛应是实际发布提交包含完整运行时、目标测试真实通过、环境确认运行该提交；不能以迁移文件存在代替。

### P0-2 多组写操作继承只读权限

- **Observed**：账号上线/下线/删除、分组 CRUD、代理导入/检测/删除、群设置/成员变更、营销创建/启停等写操作沿用类级 view/list。
- **Inferred**：若角色可单独持有查看权限，只读用户可能触发真实数据或协议副作用。
- **Unknown**：目标环境角色是否将 view 与 edit 永久捆绑；这不消除代码层最小权限缺口。
- **Inferred**：最低修复是逐方法权限矩阵和仅 view/list 主体的参数化 403 测试。

### P0-3 协议管理面鉴权 fail-open 或缺失

- **Observed**：Web API key 默认空且空集直接放行；Android coordinator 的 drain/online/delete 路由没有应用鉴权，并监听全部接口。
- **Observed**：随仓 Android 部署模板同时存在全网卡绑定和仅回环绑定两种形态，不能从模板推断实际环境暴露。
- **Inferred**：按 P0 的安全定义，应用层 fail-open/无鉴权本身需要优先关闭；实际可利用性仍取决于网络边界。
- **Unknown**：Ingress、安全组、NetworkPolicy、服务网格身份和实际 key 配置。
- **Inferred**：生产模式应在无身份配置时 fail-closed；状态变更管理面统一强认证、细粒度授权和不可采样审计。

### P0-4 通讯录快照只有消费者，没有当前协议生产者

- **Observed**：后端专用 consumer、schema 和快照落库代码存在；Web 事件类型没有该事件，Android 只写自身本地联系人表。
- **Observed**：快照联系人和同步状态缺少完整的乱序水位保护，运行代码也未发现写 FAILED 业务状态的路径。
- **Inferred**：若工作区外没有生产者，任务展开会因快照缺失或过期跳过全部账号。
- **Unknown**：实际部署构件是否包含工作区外生产者。
- **Inferred**：修复必须同时定义完整、部分、删除、分页、中断和乱序语义，不能拿 history 计数冒充完整通讯录。

### P0-5 两条协议的通讯录消息 wire contract 与后端不兼容

- **Observed**：后端发送三个任务关联 ID；Web/Android 的 command payload、validator、correlation 和 result event 均缺少这些字段。
- **Inferred**：当前命令会在协议发送前被拒绝，且失败无法按收件人回写，已 claim 行可能停在 SENDING。
- **Observed**：后端 adapter 测试源码只证明序列化，协议 parser 测试未覆盖该 source；单边测试不能证明跨仓兼容。
- **Inferred**：必须以同一匿名化 JSON 测试向量同时喂给 Web/Android parser，并验证成功与失败结果保留关联。

### P0-6 建群关闭发言不是成功后置条件

- **Observed**：Web 在建群成功后异步调用设置且失败只告警；现有测试源码明确断言设置失败仍返回建群成功。
- **Observed**：Android adapter 同样吞掉设置失败；营销链可在未确认关闭发言时继续。
- **Inferred**：任务配置语义与真实群状态可能不一致，并扩大协议/封号风险。
- **Inferred**：最低修复是把“关闭发言已确认”变成下一阶段前置条件，或进入明确可恢复失败/人工处理。

### P0-7 建群营销发送失败会遗忘已建群事实

- **Observed**：发送失败回调进入账号替换重试；SQL 清空群、目标、attempt 和 command 关联并回 PENDING。
- **Inferred**：下一轮可能重复建群，产生遗留群、额外联系人操作和更高封号风险。
- **Inferred**：最低修复是保留外部群事实，独立重试消息发送；不确定结果进入人工收口。

## 6. P1 最小可交付纵切

以下四个 P1 是对矩阵 P1 行的再次去重。每个纵切均以对应 P0 已关闭为前置。

### P1-A 账号上线、代理恢复、分配与诊断闭环

- **Inferred — 最小可交付纵切**：使用脱敏合成账号完成“导入一行 → 本地 outbox 受理 → fake Web/Android 结果 → 状态回写 → 前端区分受理/最终态”；代理探测失败后只换一次代理并写诊断时间线；Android 加硬容量拒绝；Web/Android 统一返回脱敏版本、负载和心跳；接通账号分配写入与现有筛选读模型。
- **Inferred — 依赖**：P0-1 用户归属、P0-2 方法权限、P0-3 协议管理鉴权；现有 outbox、allocator、状态事件、诊断表和节点 registry。
- **Inferred — 验收案例**：
  1. 同一命令重复/乱序结果不重复改终态。
  2. 接口返回 accepted 时 UI 不显示 ONLINE。
  3. 代理失败只执行一次原子换绑，旧代理进入不可用并可复检。
  4. Android 满载节点不再接收账号；无容量返回明确失败。
  5. 分配写入、列表筛选、统计和取消分配口径一致。
  6. 诊断只显示白名单设备事实，不含敏感标识或原始协议数据。
- **Inferred — 不做什么**：不连接真实账号；不做跨租户转移；不采集完整设备指纹；不构建灰度升级执行器。

### P1-B 群任务副作用安全、资料恢复与统一风险预算

- **Inferred — 最小可交付纵切**：在同步/批量建群、标准拉群、分阶段拉群营销和建群营销上统一请求幂等、结果不确定收口、过期任务恢复、单账号风险预算和触顶暂停；保留既有持久阶段与资源锁；群资料失败记录准确失败项，并按批准口径有限重试或转人工。
- **Inferred — 依赖**：P0-1、P0-2、P0-6、P0-7；现有任务状态机、租约、资源锁、结果分类和 settingTiming 调用点。
- **Inferred — 验收案例**：
  1. 客户端重复创建请求只产生一个任务/群副作用。
  2. 关闭发言未确认时营销命令不入队。
  3. 建群成功后消息失败重试不再次建群，原群事实仍可追踪。
  4. BEFORE_PULL/AFTER_PULL 分别按配置执行；不同设置项失败落入准确原因码。
  5. 进程中断后的 expired-processing 可安全拾取或转人工。
  6. 同一限制信号在各工作流触发一致退避、暂停或人工处理。
- **Inferred — 不做什么**：不做大流量真实拉群；不新增采集/脚本等参考模块；不开放 speed 新档位；不把 RESULT_UNKNOWN 自动重试成新群。

### P1-C 通讯录安全私聊最小闭环

- **Inferred — 最小可交付纵切**：完整/部分快照事件进入后端并按水位防乱序；试算同时返回可用账号与预计收件人数；任务关联在 Web/Android 命令和结果中完整保留；加入租户级退订、联系人跨任务冷却、SENDING 超时恢复、配置可见性和可解释失败统计。
- **Inferred — 依赖**：P0-1、P0-4、P0-5；快照完整性契约、协议 parser/result contract、recipient mapper、调度器、私聊后端能力配置和最小抑制表。
- **Inferred — 验收案例**：
  1. 完整、部分、乱序、删除、分页和中断快照用同一合成 fixture 验证。
  2. 快照缺失/过期或能力配置为空时，试算明确为零并解释原因。
  3. Web/Android 均保留任务、任务账号、收件人和轮次关联。
  4. 同一收件人在退订或冷却期内跨任务、跨发送账号均被抑制。
  5. 丢结果后 SENDING 超时进入有限重试或人工失败，不永久卡住。
  6. retryMax 的“重试次数”与实际总尝试次数口径一致。
- **Inferred — 不做什么**：不声称 named contacts 是好友或双向好友；不做 AI/剧本；不做视频/文件；不做 delivery/read/会话回复；不主动对真实账号拉全量通讯录。

### P1-D 持久操作与敏感访问审计

- **Inferred — 最小可交付纵切**：为账号、群、代理、用户/角色/菜单、协议节点状态变更和敏感导出建立 append-only 审计事件；提供租户隔离的查询 API 和最小只读页面。
- **Inferred — 依赖**：P0-1、P0-2、P0-3；可信 actor、trace ID、统一 action/target/result 枚举和保留策略。
- **Inferred — 验收案例**：
  1. 成功、失败、拒绝的高风险动作都恰好写一条事件。
  2. 记录 actor、权限快照、动作、对象类别/ID、结果、时间和 trace，不保存敏感正文或原始数据。
  3. 成功事件不可关闭、不可采样。
  4. 普通用户只能读自身/授权范围，管理员访问有明确审计。
  5. 导出下载和协议节点 drain/online/delete 均可追责。
- **Inferred — 不做什么**：不替换技术日志；不落原始协议包、联系人、消息正文或凭据；不在首切构建完整 SIEM。

## 7. P2 与 P3

### P2：先验证真实需求或竞品行为

- **Inferred**：M21 域内导入导出和局部报表——先确认是否真的需要统一中心。
- **Inferred**：M22 账号/群资产转移和不可逆迁移——先确认代理商交付、合规和撤销边界。
- **Inferred**：M23 分组标签、融合搜索、密钥轮换——先确认轮换语义和使用频率。
- **Inferred**：M24 通用账号 CSV、国家/趋势与质量画像——先确认运营决策所需字段及空值/零值/未采集语义。
- **Inferred**：M25 收群验群、群采集和群关注——先取得竞品后端行为证据与合规边界。
- **Inferred**：M26 群关键词、脚本和独立风险统计——先确认业务收益、指标口径与协议风险。
- **Inferred**：M27 QR、速度档和未来定时启动——先确认真实场景，不把已有字段或本地页面状态当实现。
- **Inferred**：M28 delivery/read、会话和回复——先确认是否要做销售会话闭环，避免直接扩成客服系统。
- **Inferred**：M29 通讯录视频/音频/文件——先验证客户素材需求和两协议限制。
- **Inferred**：M31 两个禁用筛选——先用客服/运营样本确认检索痛点。
- **Inferred**：M32 统一任务/通知/导入导出中心——先定义跨域作业范围和所有者。
- **Inferred**：M33 平台共享 IP 池与供应运营——先确认 L3 fallback 还是 L1/L2 调拨。
- **Inferred**：M34 协议灰度升级与回滚——先确认是否由外部编排平台负责。
- **Inferred**：M38 特定第三方集成——只确认 key 形态常量的公开标识/秘密分类、限制策略和轮换责任，不在工单、报告或测试中传值。

### P3：暂不跟进

- **Inferred**：M30 通讯录 AI/剧本/工作流；当前只有占位且基础发送闭环未成立。
- **Inferred**：M35 平台租户生命周期、部门、席位和组织树；当前项目明确是 L3。
- **Inferred**：M36 钱包、订单、套餐、账单和平台 P&L；没有当前版本范围依据。
- **Inferred**：M37 开放平台、租户 API key、Webhook 和连接器；没有当前路线图证据。
- **Unknown**：若正式产品范围把上述任一项列为本版本验收条件，应重新评分和定级。

## 8. Unknown 与最便宜的下一步验证

| Unknown | 最便宜且安全的下一步 | 预期产出 |
|---|---|---|
| 实际环境运行提交 | **Inferred**：由发布方提供脱敏镜像 label、构建清单或 Git SHA 文件，离线核对，无需 SSH。 | **Observed**：确认候选包包含哪些代码；仍不等于环境功能通过。 |
| V141-V166 等迁移是否应用 | **Inferred**：提供只含 version/description/success 的脱敏 Flyway history。 | **Observed**：确认 schema 阶段，不读取业务数据。 |
| 当前测试是否通过 | **Inferred**：在允许写构建产物的独立任务中运行最小目标集；先权限、owner、群副作用和两协议 contract test。 | **Observed**：真实命令、退出码和失败清单。 |
| 写权限是否真实可达 | **Inferred**：MockMvc 参数化仅 view/list、edit、admin 三类主体。 | **Observed**：每个变更端点的 403/允许矩阵。 |
| 协议管理面是否暴露 | **Inferred**：离线审查脱敏监听、Ingress、NetworkPolicy、服务网格身份和 key 必填配置。 | **Observed**：应用鉴权与网络边界组合。 |
| 是否有工作区外通讯录生产者 | **Inferred**：核对实际部署构件清单/SBOM 和源码归属，不连接服务。 | **Observed**：生产者存在/缺失及版本。 |
| 联系人集合是否完整 | **Inferred**：用合成 full/partial/increment/delete/out-of-order fixture 验证集合语义。 | **Observed**：完整性、水位、清残留和失败状态契约。 |
| 真实好友/双向好友 | **Inferred**：先让协议负责人确认是否存在可靠关系事实；拿不到则产品字段改为 named contacts。 | **Observed**：可用数据源或明确不支持。 |
| 群资料失败恢复口径 | **Inferred**：产品确认八类设置项的可重试/不可重试规则，再用 fake result 测试。 | **Observed**：准确失败码、有限重试和已成功项不重复执行。 |
| 普群 pending/expired recovery 调用者 | **Inferred**：增加静态架构测试或调度器单测，模拟进程中断。 | **Observed**：过期项被安全拾取或转人工。 |
| 竞品模块是否真实可用 | **Inferred**：优先取得授权的 OpenAPI、源码或脱敏离线 HAR；先做 schema/状态机核对。 | **Observed**：后端路由、权限、幂等和恢复证据；否则保持 Unknown。 |
| 封号/限流风险 | **Inferred**：先用非敏感聚合失败码建立统一分类和预算；真实小样本另行审批。 | **Observed**：各工作流一致的退避/熔断策略。 |
| 外部日志平台是否补齐审计 | **Inferred**：查看脱敏路由、字段规范、索引和留存配置。 | **Observed**：actor/action/target/result/trace/retention 覆盖矩阵。 |
| L1/L2 与商业能力边界 | **Inferred**：产品负责人确认一页边界表：本版本、后续、外部系统、不做。 | **Observed**：可重新定级，避免按竞品菜单照抄。 |
| 第三方 key 形态常量分类 | **Inferred**：负责人只确认公开标识/秘密、限制策略和轮换责任，不提供值。 | **Observed**：分类和处置责任。 |

## 9. Armada 内部可证明更强、无需照抄的能力

本节的“更强”只指当前可见静态工程证据更完整；竞品后端和两边环境行为均为 Unknown。

1. **Observed**：分阶段拉群营销把好友准备、建群、加营销号、加料、设管理员、设权限、保存群、退群和收口拆为持久阶段，并有 MANUAL_REVIEW。
   **Inferred**：无需照抄只显示 start/pause/resume/stop 的黑盒页面模型；应保留可定位恢复点。
2. **Observed**：拉群营销使用稳定操作标识、租约和资源锁，结果不确定时不盲目重建。
   **Inferred**：这是降低重复外部副作用的核心设计，应扩展到其他群任务。
3. **Observed**：批量普群有客户端幂等键、租户/用户准入、快照上限、明确失败重试和 RESULT_UNKNOWN。
   **Inferred**：无需为了页面相似度弱化幂等和不确定结果边界。
4. **Observed**：普通营销把本地 outbox 受理与协议真实发送结果分开，新群延迟 attempt 有唯一约束和终态。
   **Inferred**：无需把“请求成功”改写成“消息成功”；这一区分应成为全域标准。
5. **Observed**：代理域有地域优先、原子绑定、失败释放、换代理补偿和不可用复检。
   **Inferred**：当前可见证据比参考代理页面更具工程闭环；应优先验证而不是重写。
6. **Observed**：Web registry 有显式容量、实时负载、心跳判死和跨 worker failover；Android 有版本、心跳和最少账号分配。
   **Inferred**：无需照抄只展示节点数量的运维页面；应补 Android 硬容量并统一可观测口径。
7. **Observed**：群详情具备成员名册、管理员升降级、移除成员和 Web/Android 统一群设置抽象。
   **Inferred**：在 contract test 通过后，这比参考构建可见的只读详情表面更完整。
8. **Observed**：通讯录快照 sink 只在完整快照收齐时删除残留，部分快照记录 PARTIAL。
   **Inferred**：虽然生产端缺失是 P0，但该防半快照设计应保留，不应改成任何增量都清全量。
9. **Observed**：特定第三方令牌加密且不明文回显，营销导出有独立权限和按创建人读取。
   **Inferred**：这些安全边界应保留，并在其上补持久审计，而不是照抄更宽但未知的页面入口。

## 10. 文件与行号证据

以下路径均位于 /Users/daishuaishuai/IdeaProjects。四份输入用于发现和去重；本节列当前代码、配置、迁移和测试源码证据。

### E01 用户归属与缓存

- **Observed**：armada/armada-api/src/main/resources/db/migration/V141__account_user_data_ownership.sql:1-42、66-98、108-122。
- **Observed**：armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml:326-356、662-729、739-785。
- **Observed**：armada/armada-api/src/main/java/com/armada/account/service/impl/AccountServiceImpl.java:36-44、92-117。
- **Observed**：armada/armada-api/src/main/resources/db/migration/V143__marketing_task_user_data_ownership.sql:1-23；V144__group_creation_marketing_task_user_data_ownership.sql:1-23；V146__pull_task_user_data_ownership.sql:1-23；V148__group_user_data_ownership.sql:1-168；V149__normal_group_creation_user_data_ownership.sql:1-49；V150__historical_group_pull_user_data_ownership.sql:1-45。
- **Observed**：wheel-saas-pure-web/src/utils/auth.ts:118-123；wheel-saas-pure-web/src/views/task/pull-task/composables/useStandardPullTaskCreate.ts:158-183；useCommonGroupCreate.ts:260-351。
- **Observed**：armada-user-data-isolation/armada-api/src/main/java/com/armada/shared/security/DataScope.java:43-91；armada-user-data-isolation/armada-api/src/main/resources/mapper/account/AccountMapper.xml:5-56、500-506。

### E02 方法级权限

- **Observed**：armada/armada-api/src/main/java/com/armada/account/controller/AccountController.java:51-52、99-100、139-171、240-325。
- **Observed**：armada/armada-api/src/main/java/com/armada/account/controller/AccountGroupController.java:29-30、73-110。
- **Observed**：armada/armada-api/src/main/java/com/armada/resource/controller/IpProxyController.java:29-30、68-113。
- **Observed**：armada/armada-api/src/main/java/com/armada/group/controller/GroupController.java:17-33；GroupLinkController.java:59-60、126-384；HistoricalGroupController.java:27-28、69-119。
- **Observed**：armada/armada-api/src/main/java/com/armada/marketing/controller/MarketingTaskController.java:32-33、73-189；GroupCreationMarketingTaskController.java:36-37、86-112。

### E03 协议管理面鉴权

- **Observed**：armada-protocol/protocol-layer/src/routes/index.ts:26-33、47-68；armada-protocol/protocol-layer/src/config.ts:24-43；armada-protocol/protocol-layer/src/routes/admin.ts:17-140。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/coordinator/admin_api.go:63-73、95-107、132-164。
- **Observed**：whatsapp-server-feature-android-zhuan/cmd/coordinator/main.go:93-121。
- **Observed**：whatsapp-server-feature-android-zhuan/deploy/coordinator/docker-compose.yml:17-20；deploy/multinode/docker-compose.yml:29-30。

### E04 通讯录快照

- **Observed**：armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/contact/ProtocolAccountContactEventConsumer.java:22-121。
- **Observed**：armada/armada-api/src/main/java/com/armada/account/contact/service/impl/AccountContactSnapshotSink.java:22-44、72-106、127-181。
- **Observed**：armada/armada-api/src/main/resources/mapper/account/AccountContactMapper.xml:5-69；AccountContactSyncMapper.xml:29-48。
- **Observed**：armada-protocol/protocol-layer/src/events/subjects.ts:7-50、107-129；armada-protocol/protocol-layer/src/worker/account-manager.ts:1775-1869。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/service/app/appstate.go:515-534；internal/service/axolotl/store/store.go:396-407、476-518。
- **Observed**：armada/armada-api/src/main/resources/db/migration/V162__account_contact_sync.sql:1-49；V166__account_contact_partial_status.sql:1-9。

### E05 通讯录消息跨仓契约

- **Observed**：armada/armada-api/src/main/java/com/armada/platform/protocol/backend/web/WebMessageSendBackend.java:53-97、130-158。
- **Observed**：armada-protocol/protocol-layer/src/commands/worker-consumer.ts:1057-1148、1297-1416、1536-1559。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java:219-264、314-345。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/message_command.go:34-60、171-233、341-377；internal/armada/message_event.go:30-57、89-115。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java:137-211。

### E06 建群关闭发言

- **Observed**：armada-protocol/protocol-layer/src/routes/groups.ts:373-425。
- **Observed**：armada-protocol/protocol-layer/src/routes/groups-create-announcement.test.ts:33-89。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidNativeGroupCreateAdapter.java:58-120。

### E07 建群营销重试

- **Observed**：armada/armada-api/src/main/java/com/armada/marketing/service/impl/MarketingSendResultServiceImpl.java:163-216。
- **Observed**：armada/armada-api/src/main/resources/mapper/marketing/GroupCreationMarketingTaskMapper.xml:336-359。

### E08 标准拉群群资料

- **Observed**：armada/armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupProfileDispatcher.java:62-126。
- **Observed**：armada/armada-api/src/main/java/com/armada/task/scheduler/PullTaskGroupCreateTransactionService.java:267-315；PullTaskManagerPullerContactTransactionService.java:139-149；PullTaskPullWaveSettlementTransactionService.java:161-178。
- **Observed**：armada/armada-api/src/main/java/com/armada/task/service/impl/PullTaskGroupSettingsResultServiceImpl.java:207-262。
- **Observed**：armada/armada-api/src/main/java/com/armada/task/service/impl/ProtocolGroupActionResultAdapter.java:89-99；platform/kafka/consumer/group/ProtocolGroupActionResultReportedEvent.java:25-44。
- **Observed**：armada/armada-api/src/main/resources/db/migration/V136__pull_task_new_group_mode_setting.sql:1-16；其“只写不读”注释是历史背景，已被上述当前运行代码取代。

### E09 账号、分组、画像、分配与诊断

- **Observed**：armada/armada-api/src/main/java/com/armada/account/controller/AccountController.java:85-327。
- **Observed**：armada/armada-api/src/main/java/com/armada/account/controller/AccountImportController.java:58-128；account/job/AccountImportOnlineDispatchScheduler.java:12-45。
- **Observed**：armada/armada-api/src/main/java/com/armada/account/controller/AccountGroupController.java:28-114。
- **Observed**：armada/armada-api/src/main/resources/db/migration/V159__account_profile.sql:3-36；V030__account_online_attempt_log.sql:1-28。
- **Observed**：armada/armada-api/src/main/java/com/armada/account/converter/AccountConverter.java:65-66；account/model/vo/AccountStatusVO.java:3-29。
- **Observed**：armada/armada-api/src/main/resources/db/migration/V005__account.sql:14-24；armada/armada-api/src/main/resources/mapper/account/AccountMapper.xml:326-358、744-750、888-908。
- **Observed**：wheel-saas-pure-web/src/api/account.ts:310-332；wheel-saas-pure-web/src/views/account/index/index.vue:311-325。

### E10 代理资源

- **Observed**：armada/armada-api/src/main/java/com/armada/resource/controller/IpProxyController.java:25-117；IpProxyStatsController.java:29-88。
- **Observed**：armada/armada-api/src/main/java/com/armada/resource/service/impl/IpProxyOptimisticAllocator.java:39-153。
- **Observed**：armada/armada-api/src/main/java/com/armada/account/recovery/ProxyFailedRecoveryCoordinator.java:34-72；ProxyFailedRecoveryDispatcher.java:39-71。
- **Observed**：armada/armada-api/src/main/resources/db/migration/V002__ip_proxy.sql:4-15；armada/armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml:446-518。

### E11 协议节点

- **Observed**：armada-protocol/protocol-layer/src/registry/registry.ts:34-52、197-230、316-365、418-500；registry/failover.ts:30-151；observability/health.ts:1-49。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/fleet/registry.go:40-59、304-387；internal/fleet/allocator.go:86-150；internal/fleet/heartbeat_runner.go:80-178。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/coordinator/upgrade_preflight.go:10-32。

### E12 群管理

- **Observed**：wheel-saas-pure-web/src/views/group/list/components/GroupListTable.vue:116-257；GroupMemberDrawer.vue:252-452、489-729。
- **Observed**：armada/armada-api/src/main/java/com/armada/group/model/vo/GroupLinkVO.java:10-135；HistoricalGroupDetailVO.java:9-68。
- **Observed**：armada/armada-api/src/main/java/com/armada/group/model/enums/GroupPermissionKey.java:3-19；platform/protocol/http/group/HttpGroupSettingsAdapter.java:40-55、155-209。

### E13 群任务与营销

- **Observed**：armada/armada-api/src/main/java/com/armada/group/service/impl/GroupOperationServiceImpl.java:39-89。
- **Observed**：armada/armada-api/src/main/java/com/armada/group/normalcreation/service/impl/NormalGroupCreationServiceImpl.java:43-177、269-317、432-500；platform/dispatch/mapper/NormalGroupCreationDispatchMapper.java:9-26。
- **Observed**：armada/armada-api/src/main/java/com/armada/task/controller/PullTaskStandardController.java:38-306；task/model/dto/PullTaskStandardCreateDTO.java:13-55。
- **Observed**：armada/armada-api/src/main/java/com/armada/marketing/grouppull/service/GroupPullMarketingExecutionWorker.java:134-218、330-390、432-549、839-873。
- **Observed**：armada/armada-api/src/main/java/com/armada/marketing/scheduler/MarketingRoundScheduler.java:23-100；MarketingNewGroupDelayScheduler.java:18-69。
- **Observed**：wheel-saas-pure-web/src/views/task/pull-task/create/index.vue:131-148、176-184；create/create-interactions.ts:47-52、83-85。

### E14 通讯录任务

- **Observed**：armada/armada-api/src/main/resources/db/migration/V163__contact_friend_task.sql:1-86；V165__contact_task_engine.sql:1-30。
- **Observed**：armada/armada-api/src/main/java/com/armada/contact/task/controller/ContactTaskController.java:34-168。
- **Observed**：armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskExpansionService.java:96-162；ContactTaskMessageCommandFactory.java:54-79、94-154。
- **Observed**：armada/armada-api/src/main/resources/mapper/account/AccountContactMapper.xml:52-69。

### E15 通讯录恢复、抑制与统计

- **Observed**：armada/armada-api/src/main/resources/mapper/contact/ContactFriendTaskRecipientMapper.xml:67-125。
- **Observed**：armada/armada-api/src/main/java/com/armada/contact/task/scheduler/ContactTaskRoundWorker.java:124-189、192-296；ContactTaskLifecycleWorker.java:72-93。
- **Observed**：armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskSendResultSink.java:60-120。
- **Observed**：armada/armada-api/src/main/java/com/armada/contact/task/service/impl/ContactTaskServiceImpl.java:89-93；contact/task/controller/ContactTaskController.java:139-168。

### E16 IAM 与审计

- **Observed**：armada/armada-api/src/main/resources/db/migration/V071__system_management_rbac.sql:3-76、102-157。
- **Observed**：armada/armada-api/src/main/java/com/armada/admin/controller/UserManagementController.java:21-68；RoleManagementController.java:21-68；MenuManagementController.java:20-54。
- **Observed**：armada/armada-api/src/main/java/com/armada/boot/config/MyBatisConfig.java:13-55；boot/security/TokenAuthenticationFilter.java:82-100。
- **Observed**：armada/armada-api/src/main/java/com/armada/admin/service/impl/UserManagementServiceImpl.java:63-148；RoleManagementServiceImpl.java:110-138；MenuManagementServiceImpl.java:101-151。
- **Observed**：armada-protocol/protocol-layer/src/routes/audit-log.ts:4-18。

### E17 域内运营与统一中心边界

- **Observed**：armada/armada-api/src/main/resources/db/migration/V075__restore_task_center_menu_structure.sql:1-62；V083__marketing_task_export_job.sql:1-56。
- **Observed**：armada/armada-api/src/main/java/com/armada/marketing/export/controller/MarketingTaskExportController.java:27-90。
- **Observed**：armada/armada-api/src/main/java/com/armada/promotion/stats/BuyerChannelStatsController.java:27-105；promotion/stats/BuyerChannelStatsModels.java:20-49。

### E18 产品边界

- **Observed**：wheel-saas-pure-web/AGENTS.md:5-8。
- **Observed**：wheel-saas-pure-web/.harness/agents/owner.md:7-11。
- **Observed**：当前审计范围的 Controller、Flyway 和前端清单未发现通用 L1/L2、财务、开放平台实现。

### E19 参考构建与竞品边界

- **Observed**：hylbuiaxykfrontendsource/README.md:1-16 仅说明存档是无 sourcemap 的编译后前端产物，作为证据边界背景。
- **Observed**：hylbuiaxykfrontendsource/readable/assets/router-CPQmbuR9.js:45613-45955、46898-49530、58649-58678。
- **Observed**：hylbuiaxykfrontendsource/readable/assets/manage-MZBfmYTV.js:1818-2483；grouping-B0ze363t.js:355-1539；transfer-Dn17o8OS.js:423-1459；stats-wHYTp86A.js:136-1266。
- **Unknown**：这些前端入口背后的服务端能力、数据质量和环境可用性。

### E20 页面与占位

- **Observed**：wheel-saas-pure-web/src/views/contact/script/index.vue:1-16。
- **Observed**：wheel-saas-pure-web/src/views/account/index/components/AccountListTable.vue:80-125。
- **Observed**：armada/armada-api/src/main/resources/db/migration/V075__restore_task_center_menu_structure.sql:1-62。

### E21 C3 媒体、ACK 与会话

- **Observed**：armada-protocol/protocol-layer/src/routes/messages.ts:73-120；worker/event-bridge.ts:123-269。
- **Observed**：armada/armada-api/src/main/java/com/armada/platform/kafka/consumer/message/ProtocolMessageEventConsumer.java:78-123、214-242。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/armada/message_ack.go:23-75、122-168。
- **Observed**：armada/armada-api/src/main/java/com/armada/contact/task/service/ContactTaskMessageCommandFactory.java:54-79、136-154。

### E22 特定第三方集成

- **Observed**：armada/armada-api/src/main/java/com/armada/promotion/channel/controller/PromotionChannelController.java:27-130。
- **Observed**：armada/armada-api/src/main/java/com/armada/promotion/channel/security/PromotionTokenCipher.java:20-145。
- **Observed**：whatsapp-server-feature-android-zhuan/internal/service/fcm/constants.go:15；internal/service/fcm/fcm.go:62-71、220-229。这里只记录位置，不回显任何值。

## 11. 最终结论

- **Observed**：本次有 7 个能力簇达到“已实现但只有本地静态证据”，但没有任何“本地运行已验证”或“环境已验证”能力；所有运行可用性仍为 Unknown。
- **Observed**：Armada 已有大量实质代码，不是只有菜单；但 7 个 P0 中有多个是当前源码直接证明的确定性断链或安全边界缺口。
- **Observed**：标准拉群设置时机已经接通；当前真正缺口是失败分类与恢复，不应继续沿用旧迁移注释判定“只写不读”。
- **Inferred**：先关闭 P0，再交付 P1-A～P1-D 四条最小纵切，价值高于继续增加竞品页面同名模块。
- **Inferred**：分阶段持久化、稳定操作标识、资源锁、受理/真实结果分离、代理恢复和完整快照保护是 Armada 应保留并扩展的内部优势。
- **Unknown**：跨租户转移、群采集/脚本、统一中心、L1/L2 商业能力是否值得做，必须先拿真实客户需求或竞品后端证据。
- **Observed**：本报告没有把菜单数量作为完成度，没有输出敏感信息，没有修改业务代码，也没有连接任何外部环境。
