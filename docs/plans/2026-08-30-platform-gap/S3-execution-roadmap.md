# S3 — Armada 可执行路线图（基于最终 S2 重建）

- 任务编号：`S3`
- 重建日期：2026-08-30（Asia/Shanghai）
- 文档性质：执行路线，不是实施结果或环境验收结果
- 当前状态：`ROADMAP_REBUILT_FROM_FINAL_S2`；`P0_IMPLEMENTATION_NOT_STARTED`；`LOCAL_RUNTIME_NOT_VERIFIED`；`ENVIRONMENT_NOT_VERIFIED`
- 事实输入：最终 S1、最终 S2、D1；独立验证报告不作为本路线的可变事实输入
- 明确排除：全部超链赶超、超链菜单、短链、超链数据包、超链模板、超链策略、超链分析及其竞品对齐工作

## 0. 标签、证据层级与硬边界

- **Observed**：由本次冻结的 S1/S2/D1 中所列当前源码、配置、测试源码、迁移和当前报告快照直接支持；测试源码存在仍不等于测试已运行。
- **Inferred**：由多项 Observed 事实形成的排序、依赖、实施切片、验收门或容量判断；尚未运行验证。
- **Unknown**：当前证据不能确认，或需要本地构建、test1、真实协议资源、产品决定或环境授权。
- **Observed**：本报告严格区分“代码已存在”“本地静态核对”“本地运行已验证”“环境已验证”“业务已可用”。
- **Observed**：最终 S2 明确有 38 个能力簇、7 个代码/发布 P0 和 4 个推断 P1 纵切；本路线不再保留过期的前置判断。证据：S2:33-43、94-249。
- **Observed**：本次只改写本报告；没有修改业务代码，没有提交、推送或部署，也没有连接远程、SSH、数据库、Kafka、Redis 或真实 WhatsApp 环境。
- **Inferred（停止规则）**：输入 hash、owner、合同、工作树、命令、制品或授权任一漂移，当前 work item 立即停止并输出 `BLOCKED/UNKNOWN`；不得用旧证据、默认值或静默 skip 放行。
- **Inferred（消息规则）**：阶段一不改 topic 名、payload、key、consumer group、ACK/commit/retry 语义；不合并任何 topic；replay 默认拒绝。证据：S1:20-27、423-450。

### 0.1 冻结输入快照

| 输入 | SHA-256 | 行数 | mtime（+0800） | 用途 | 标签 |
|---|---|---:|---|---|---|
| S1 | `8965e8cb53c6922768c89ccc091e680847c573355ce4462206424cfcdc0220c1` | 697 | 01:56:41 | Kafka/Redis/Runner 治理事实与约束 | Observed |
| S2 | `ff8300f14bb422e7fcebe74a389b0e722b33b440923fa40c8bdf0e4ba7792cf8` | 491 | 02:19:23 | 38 能力簇、7 P0、4 P1 的唯一业务矩阵 | Observed |
| D1 | `5f1ea2428132b62e734ce6d035e62c978147e61188a7307ab0ea8e97d943dcf8` | 409 | 01:11:11 | 交付链、Runner、状态和授权事实 | Observed |

- **Inferred**：实施启动前必须重新计算前三份事实输入的 hash；任一变化均触发路线复核，不继续执行旧排程。

## 1. 执行摘要

1. **Observed**：最终 S2 将能力去重为 38 簇：0 个有环境证据、7 个只有跨层静态实现证据、18 个部分实现、3 个页面/占位、6 个明确缺失、4 个产品边界未知；本轮没有任何“本地运行已验证”或“环境已验证”能力。证据：S2:33-43、69-92。
2. **Observed**：本周代码/发布 P0 必须覆盖七项：用户私有数据隔离、写操作最小权限、协议管理面强鉴权、通讯录快照生产与水位、通讯录消息跨仓关联契约、建群关闭发言成功后置条件、建群营销重试保留已建群事实。证据：S2:143-195。
3. **Observed**：四个 P1 候选已经形成：P1-A 账号可靠性与诊断、P1-B 群任务副作用安全、P1-C 通讯录安全私聊、P1-D 持久操作与敏感访问审计。其价值与顺序仍是 Inferred，实施前需要 owner 签认。证据：S2:196-249。
4. **Observed**：S1/D1 还证明两个横切基础没有闭合：canonical 消息 manifest/全量健康/Runner inventory，以及 `scope_hash → versions.lock → artifact → deployment/runtime manifest → Runner evidence → policy` 的交付证据链。证据：S1:18-27、543-564；D1:16-35、226-276。
5. **Inferred**：今天先完成输入冻结、owner/acceptance contract、失败夹具和两流容量锁；本周最多两个实现流，分别关闭“安全/交付”与“协议副作用/消息基础”。本周任一 P0 未达到本地出口，全部 P1 自动顺延。
6. **Inferred**：下周最多启动两个 P1。默认顺序是 P1-D 与 P1-A；P1-B、P1-C 进入候选队列，只有释放实现槽位或 owner 明确替换默认项后才能启动。四项均保留在路线中，不将候选写成已批准范围。
7. **Inferred**：P1-A/B/C 的本地 fake/fixture 工作可在 L1 完成，但进入 test1 前必须具备 candidate、Kafka manifest、健康和 Runner 基础；任何真实账号、代理、消息、联系人、建群、加人或风控 canary 都需要独立 L4 授权。
8. **Unknown**：两条实现流能否在五个工作日关闭全部七个 P0。当前估时接近双流容量上限；任一 4h 切片未闭合就拆分、顺延 P1，不降低断言或跳过验证。

## 2. 覆盖范围与未覆盖范围

### 2.1 已覆盖

| 范围 | 当前处理 | 标签 |
|---|---|---|
| 最终 S1 | canonical manifest、topic 独立性、DLT/replay、SLO、Runner inventory、授权任务 | Observed |
| 最终 S2 | 38 个能力簇、7 个 P0、4 个 P1、P2/P3、Unknown 和源码行号索引 | Observed |
| D1 | candidate/Runner/quick/soak/交付状态、owner、门禁和回滚 | Observed |
| 路线编排 | 今天、本周、下周；最多两实现流；逐项依赖、owner、验证、停止与证据 | Inferred |

### 2.2 未覆盖

- **Unknown**：四仓当前 HEAD 的测试、构建和候选 worktree 是否可复用；本轮没有运行任何会写构建产物的命令。
- **Unknown**：test1/perf2/生产的实际版本、迁移、topic、partition、retention、consumer lag、Runner 安装、quick/soak、告警与回滚状态。
- **Unknown**：仓库外 CI、分支保护、额外 producer/consumer、provisioner、DLQ/replay 服务或外部日志平台。
- **Unknown**：P1-A～D 的最终业务 owner、价值顺序、合规适用地区和验收合同；当前仅为可追溯候选。
- **Observed**：没有覆盖任何超链赶超工作；共享基础设施变化只允许既有超链回归，禁止增加超链需求或验收目标。

## 3. 38 个能力簇覆盖矩阵

“静态实现证据”是对 S2“已实现但只有本地证据”的收窄表达，仍不表示本地运行通过或业务可用。

- **Observed**：下表 M01～M38 的名称、分类、当前验证状态和 S2 引用直接来自最终 S2。
- **Inferred**：下表“本路线处置”是本报告的排程判断，不表示已实施或已批准。

| ID | 能力簇 | 最终 S2 分类 | 本路线处置 | 当前验证层级 | 证据 |
|---|---|---|---|---|---|
| M01 | 用户私有数据隔离与前端缓存命名空间 | 部分实现 | 本周 P0-1 / Flow A | 代码与迁移静态存在；运行/环境 Unknown | S2:104、145-152 |
| M02 | 后端写操作最小权限 | 部分实现 | 本周 P0-2 / Flow A | 代码断链已观察；运行/环境 Unknown | S2:105、153-159 |
| M03 | Web/Android 协议管理面强鉴权 | 部分实现 | 本周 P0-3 / Flow A | fail-open/缺鉴权静态可见；网络边界 Unknown | S2:106、160-167 |
| M04 | 通讯录快照生产、分片、乱序与同步状态 | 部分实现 | 本周 P0-4 / Flow B | consumer 存在、producer 缺失；环境 Unknown | S2:107、168-175 |
| M05 | 通讯录消息命令、结果与收件人关联 | 部分实现 | 本周 P0-5 / Flow B | 跨仓契约断链；未运行 | S2:108、176-182 |
| M06 | 建群关闭普通成员发言的成功后置条件 | 部分实现 | 本周 P0-6 / Flow B | 双协议 best-effort；未环境验证 | S2:109、183-189 |
| M07 | 建群营销失败重试保留已建群事实 | 部分实现 | 本周 P0-7 / Flow B | 确定性代码缺口；未运行 | S2:110、190-195 |
| M08 | 标准拉群群资料设置时机、失败分类与恢复 | 部分实现 | P1-B | timing 已接通；失败恢复不完整 | S2:111 |
| M09 | 账号导入、批次、生命周期命令与状态收敛 | 部分实现 | P1-A | 跨层代码存在；最终闭环 Unknown | S2:112 |
| M10 | 账号分组、画像、设备事实、分配与诊断 | 部分实现 | P1-A | 多处代码存在；产品闭环不完整 | S2:113 |
| M11 | 代理导入、健康、地域分配与失败恢复 | 静态实现证据 | P1-A 保留并验证 | 未发现边界内静态断链；未运行 | S2:114 |
| M12 | Web 节点容量、心跳与故障迁移 | 静态实现证据 | P1-A 保留并验证 | 协议静态证据；未运行 | S2:115 |
| M13 | Android 节点版本、心跳、分配与容量 | 部分实现 | P1-A | 软均衡存在，硬容量缺失 | S2:116 |
| M14 | 群列表、详情、成员、管理员与群权限 | 静态实现证据 | P1-B 保留并做 contract test | 前后端/双协议静态证据；未运行 | S2:117 |
| M15 | 同步普通建群与批量普群 | 部分实现 | P1-B | 状态机存在；幂等/恢复有缺口 | S2:118 |
| M16 | 标准拉群与分阶段拉群营销 | 部分实现 | P1-B | 持久阶段存在；运行闭环 Unknown | S2:119 |
| M17 | 普通群营销、轮次与新群延迟发送 | 静态实现证据 | P1-B 保留并验证 | attempt/outbox 静态证据；未运行 | S2:120 |
| M18 | 通讯录任务 CRUD、调度、恢复、抑制与统计 | 部分实现 | P1-C | 产品外壳存在；P0-4/5 阻断 | S2:121 |
| M19 | 持久用户操作与敏感访问审计 | 部分实现 | P1-D | 技术日志/局部诊断存在；统一审计缺失 | S2:122 |
| M20 | L3 IAM、租户行隔离、用户/角色/菜单 | 静态实现证据 | P1-D；先过 P0-1/2 | 基础跨层静态证据；未运行 | S2:123 |
| M21 | 域内导入导出、渠道成本与局部运营报表 | 静态实现证据 | P2：先确认统一中心需求 | 未运行/未环境验证 | S2:124 |
| M22 | 账号/群资产转移与不可逆迁移交付 | 明确缺失 | P2：产品/合规 discovery | 当前范围无闭环 | S2:125 |
| M23 | 账号分组标签、融合搜索与密钥轮换 | 明确缺失 | P2：需求 discovery | 当前范围无闭环 | S2:126 |
| M24 | 账号通用 CSV、国家/趋势与质量画像 | 部分实现 | P2：先定字段语义 | schema/局部统计存在；展示不闭合 | S2:127 |
| M25 | 收群验群、群采集与群关注 | 明确缺失 | P2：先定产品/协议风险 | 参考行为与需求 Unknown | S2:128 |
| M26 | 群关键词、群脚本、独立群池/封号统计 | 明确缺失 | P2：先定收益/合规 | 当前范围无模块 | S2:129 |
| M27 | 群 QR、速度档与标准拉群未来定时启动 | 部分实现 | P2：先定真实场景 | 字段/路由/页面存在但语义未闭合 | S2:130 |
| M28 | 通讯录 delivery/read、会话、入站与回复 | 明确缺失 | P2：先定销售会话边界 | 通用事件局部存在；C3 未闭合 | S2:131 |
| M29 | 通讯录视频、音频和文件任务 | 明确缺失 | P2：先验证客户与协议限制 | 通用媒体路由未带任务关联 | S2:132 |
| M30 | 通讯录 AI、剧本与工作流 | 页面/占位 | P3：暂不跟进 | 页面建设中；无业务引擎 | S2:133 |
| M31 | 账号 IP 分组与已分配服务筛选 | 页面/占位 | P2：先验证检索痛点 | 控件禁用；不能计能力 | S2:134 |
| M32 | 统一任务、通知与导入导出中心 | 页面/占位 | P2：先定义跨域 owner | 菜单归组不是统一中心 | S2:135 |
| M33 | 平台共享 IP 池、供应商、采购与自动补货 | 产品边界未知 | P2：确认 L3/L1-L2 边界 | 预留 schema 不等于运行语义 | S2:136 |
| M34 | 统一协议版本、灰度升级与回滚控制 | 部分实现 | P2：先确认外部编排职责 | 版本/前置检查存在；执行器缺失 | S2:137 |
| M35 | 平台租户生命周期、部门、席位与组织树 | 产品边界未知 | P3：暂不跟进 | 当前 L3 未承诺 | S2:138 |
| M36 | 钱包、订单、套餐、账单与平台 P&L | 产品边界未知 | P3：暂不跟进 | 当前范围无通用财务闭环 | S2:139 |
| M37 | 开放平台、租户 API key、Webhook 与连接器 | 产品边界未知 | P3：暂不跟进 | 内部 key 不等同开放平台 | S2:140 |
| M38 | 特定第三方渠道集成与令牌保护 | 静态实现证据 | P2：只核分类/轮换责任 | 点对点静态证据；未运行 | S2:141 |

### 3.1 数量核对

| 分类 | 数量 | 本路线核对 | 标签 |
|---|---:|---|---|
| 有环境证据 | 0 | 不作任何环境通过声明 | Observed |
| 静态实现证据 | 7 | M11、M12、M14、M17、M20、M21、M38 | Observed |
| 部分实现 | 18 | M01–M10、M13、M15、M16、M18、M19、M24、M27、M34 | Observed |
| 页面/占位 | 3 | M30、M31、M32 | Observed |
| 明确缺失 | 6 | M22、M23、M25、M26、M28、M29 | Observed |
| 产品边界未知 | 4 | M33、M35、M36、M37 | Observed |

## 4. 横切事实与能力矩阵

| 能力 | 代码/设计已存在 | 本地运行已验证 | 环境已验证 | 当前安全结论 | 证据 |
|---|---|---|---|---|---|
| 当前 Kafka/Redis/outbox 路径 | 是，静态实现可见 | 否 | Unknown | 不能推出消息闭环或业务可用 | S1:18-27、29-46 |
| canonical topic manifest | 否；S1 只有设计 | 否 | Unknown | 本周基础 P0 | S1:20、452-487 |
| topic 合并 | 无候选通过四硬门 | 否 | Unknown | 当前全部保持独立 | S1:25、423-450 |
| DLT/replay 闭环 | 多种失败出口存在 | 否 | Unknown | replay 默认拒绝 | S1:351-389 |
| Runner 持久执行/证据 | 是 | 本轮未运行 | Unknown | `RUN_EXECUTION_PASS` 不等于验收 | D1:19-25、142-224 |
| quick/被动 soak | 是 | 本轮未运行 | Unknown | 只能按 candidate/profile 声明 | D1:19、25-27、94-101 |
| 通用 integration/canary | 未发现完整执行器 | 否 | Unknown | `CAPABILITY_MISSING/BLOCKED` | D1:20、26、98-102 |
| candidate/部署/Runner 绑定 | 设计与局部校验器存在；统一链缺失 | 否 | Unknown | 本周基础 P0 | D1:22、228-243 |
| 38 个业务能力簇 | 当前静态矩阵存在 | 0 个运行验证 | 0 个环境验证 | 7 P0 优先；4 P1 为候选 | S2:33-43、94-249 |

## 5. P0 / P1 / P2 问题与路线映射

### 5.1 最终 S2 的 7 个 P0

| ID | 问题 | 关键依赖 | 本周关闭路径 | 本地关闭标准 | 标签/证据 |
|---|---|---|---|---|---|
| P0-1 | 用户私有数据隔离未进入活动运行时 | owner/scope 规则、历史空 owner 口径 | A-02、A-03 | 同租户双用户 H2 + cache namespace 负向测试通过 | Observed + Inferred；S2:145-152、325-332 |
| P0-2 | 多组写操作继承只读权限 | 权限矩阵、edit 权限键 | A-04、A-05 | view/list 主体对所有写端点 403；edit/admin 按合同通过 | Observed + Inferred；S2:153-159、334-340 |
| P0-3 | 协议管理面鉴权 fail-open 或缺失 | 强身份配置、授权枚举、审计 | A-06、A-07 | 无身份配置启动/请求 fail-closed；状态变更均鉴权 | Observed + Inferred；S2:160-167、342-347 |
| P0-4 | 通讯录快照只有消费者 | snapshot schema、水位、完整/部分/删除语义 | B-03、B-04 | Web/Android 合成 producer + backend 水位/FAILED contract tests | Observed + Inferred；S2:168-175、349-356 |
| P0-5 | 通讯录消息跨仓关联契约不兼容 | P0-4、共享匿名 fixture、terminal 关联 | B-05～B-07 | 两协议 parser/result 保留 `taskId`/`taskAccountId`/`recipientId` + `roundNo`；失败可回写同 recipient | Observed + Inferred；S2:176-182、358-364 |
| P0-6 | 建群关闭发言不是成功后置条件 | 双协议成功定义、backend gate | B-08 | 设置未确认时不得进入营销；失败进入可恢复/人工态 | Observed + Inferred；S2:183-189、366-370 |
| P0-7 | 发送失败重试遗忘已建群事实 | P0-6、外部事实/消息 attempt 分离 | B-09 | 失败重试不再次建群，原群事实与 attempt 可追踪 | Observed + Inferred；S2:190-195、372-375 |

### 5.2 不增加 S2 数量的横切基础 P0

这些是 S1/D1 的发布与验收门，不重复计入“7 个 S2 P0”。

| ID | 问题 | 本周路径 | 出口 | 标签/证据 |
|---|---|---|---|---|
| F0-K1 | 无 canonical manifest、deterministic provisioning 和全量 inventory | B-01、B-02、B-10 | manifest/lint/shadow fixture 完整；Unknown 不得通过 | Observed + Inferred；S1:20-27、545-553 |
| F0-K2 | 单边 topic、group-action 漏项、失败出口分裂 | B-01、B-02、B-10 | exception 有 owner/到期；DLT/file/PEL 分开 | Observed + Inferred；S1:21-22、548-552 |
| F0-K3 | 物理 topic 合同和 replay eligibility 未闭合 | B-01、B-02 | 本地只生成 dry-run；replay 仍 deny | Observed + Inferred；S1:26、351-389、488-499 |
| F0-D1 | scope/candidate/artifact/deployment/Runner 无机器绑定 | A-01、A-08、A-09 | 同一 candidate 的 fake deploy/runtime/Runner 漂移 fail-closed | Observed + Inferred；D1:22、228-234 |
| F0-D2 | Runner PASS 可形成业务假绿 | A-09 | required stages + wrapper 逻辑 outcome 进入核心模型 | Observed + Inferred；D1:24-25、235-243 |
| F0-D3 | 缺通用 integration/canary/交付聚合 | A-10 | 缺能力机械 `BLOCKED`，不伪造 profile | Observed + Inferred；D1:244-249 |

### 5.3 四个 P1 候选

| ID | 最小纵切 | 覆盖能力 | 必须先关闭 | Kafka/健康/Runner | 真实资源授权 | 标签/证据 |
|---|---|---|---|---|---|---|
| P1-A | 账号上线、代理恢复、分配与诊断闭环 | M09–M13 | P0-1/2/3、F0-D1/D2 | 本地 fake 可先做；test1 前必须 | 真实账号/代理/上线/风控需 L4 | Inferred；S2:200-212 |
| P1-B | 群任务副作用安全、资料恢复与统一风险预算 | M08、M14–M17 | P0-1/2/6/7、F0-K1/K2、F0-D1/D2 | test1 前必须 | 真实建群/加人/权限/消息/风控需 L4 | Inferred；S2:213-225 |
| P1-C | 通讯录安全私聊最小闭环 | M18；依赖 M04/M05 | P0-1/4/5、F0-K1/K2、F0-D1/D2 | test1 前必须 | 真实联系人/账号/代理/消息需 L4 | Inferred；S2:226-238 |
| P1-D | 持久操作与敏感访问审计 | M19/M20 | P0-1/2/3、F0-D1 | 本地无需 Kafka；test1 查询需 Runner/candidate | 不应触发真实协议；若覆盖真实动作仍需 L4 | Inferred；S2:239-249 |

### 5.4 P2/P3

- **Inferred**：M21–M29、M31–M34、M38 进入 P2 discovery；先验证需求、产品边界、合规、外部编排责任或协议限制，不占本周两个实现流。
- **Inferred**：M30、M35–M37 维持 P3；没有正式版本范围依据时不实施。
- **Observed**：页面、路由、字段或 API 字符串不构成业务可用证据。证据：S2:251-276、483-491。

## 6. 依赖门、并行和授权

### 6.1 依赖门

| Gate | 进入条件 | 解锁 | 未通过状态 | 标签 |
|---|---|---|---|---|
| G0 输入冻结 | S1/S2/D1 hash 与本报告一致；最终 S2 仍为 38/7/4 | R0 决策任务 | `INPUT_DRIFT_BLOCKED` | Observed + Inferred |
| G1 本地实施授权 | 用户明确允许本地代码/测试产物；干净 worktree；owner/合同已锁 | Flow A/B | `LOCAL_WRITE_AUTH_PENDING` | Inferred |
| G2 P0 合同 | 七个 P0 均有 Given/When/Then、非目标、owner、失败态 | P0 实现 | `CONTRACT_BLOCKED` | Inferred |
| G3 消息基础 | canonical manifest、shadow diff、全量 inventory fixture；不合并 topic | B-03～B-10 与协议类 test1 | `MESSAGING_FOUNDATION_BLOCKED` | Inferred |
| G4 交付基础 | candidate/versions/fake deployment/runtime/Runner policy 本地通过 | test1 candidate | `DELIVERY_FOUNDATION_BLOCKED` | Inferred |
| G5 P0 本地出口 | 七 P0 + F0 本地证据齐全，独立 verifier 通过 | P1 合同与 test1 授权申请 | `P0_LOCAL_OPEN` | Inferred |
| G6 P1 选择 | 四候选中最多选两条；价值、合规、acceptance contract 签认 | 下周两个 P1 流 | `P1_SELECTION_BLOCKED` | Inferred |
| G7 test1 授权 | 环境、candidate、窗口、动作、owner、回滚明确 | L2/L3 work item | `TEST1_AUTH_PENDING` | Inferred |
| G8 真实协议授权 | 资源、动作、速率、时长、abort、cleanup 独立批准 | L4 最小 canary | `REAL_PROTOCOL_AUTH_PENDING` | Inferred |

### 6.2 最多两个实现流

- **Inferred**：同时最多两个生产实现流：Flow A 与 Flow B；流内按表格依赖串行。
- **Inferred**：额外 Agent 只做独立复核、测试生成、故障注入设计和证据检查，不提交生产实现，不形成第三实现流。
- **Inferred**：跨仓 work item 仍属于其所在 WIP 流；不能通过换仓库名规避并行上限。
- **Inferred**：任一 work item 超过 4h 仍未闭合，立即拆成后续切片并重新计算容量；不得把未完成切片标记通过。
- **Inferred**：本周未完成 G5 时，下周继续 P0，P1 启动数量为 0。

### 6.3 授权层级

- **Observed**：当前任务实际只执行 L0，L1～L5 均未执行。
- **Inferred**：下表 L1～L5 的允许/禁止边界是后续执行门，不是本报告授予的权限。

| 层级 | 范围 | 本报告是否授权 | 典型任务 | 明确禁止 |
|---|---|---|---|---|
| L0 | 本地只读 | 是，仅本次报告 | hash、mtime、`rg`、状态/规则读取 | 写业务代码、构建产物、环境访问 |
| L1 | 本地隔离写入/构建 | 否；需新授权 | worktree 实现、H2、fake Kafka/Redis/sender、测试生成 | 真实凭据、真实 broker/账号/代理 |
| L2 | test1 只读 | 否；需明确授权 | runtime manifest、topic/group/config/lag、Runner summary | 读 payload、业务原始数据、环境变更 |
| L3 | test1 变更 | 否；需明确授权 | 精确 candidate deploy、隔离数据、create-only provisioning | 删除 topic、缩 partition/retention、无回滚变更 |
| L4 | 真实协议资源 | 否；必须单独授权 | 真实账号/代理/消息/联系人/群/风控 canary | 继承 L2/L3 授权、自动扩大样本、自动重试外部错误 |
| L5 | 生产 | 否；本路线不执行 | 发布、观察、回滚 | 任何未经再次确认的生产动作 |

证据：D1:297-318；S1:488-511、592-604。

## 7. 验证命令注册表

以下命令是未来 L1 实施的精确入口或拟建入口；本轮均未执行，当前结果为 **Unknown**。若脚本名或测试类尚不存在，创建该受审查入口是对应 work item 的交付部分；命令非零即停止。

### CMD-V0 — 输入与路线只读校验

```bash
shasum -a 256 /private/tmp/armada-audit-2026-08-30/S1-kafka-topic-governance-design.md /private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md /private/tmp/armada-audit-2026-08-30/D1-delivery-state-audit.md
stat -f '%N|%z|%m' /private/tmp/armada-audit-2026-08-30/S1-kafka-topic-governance-design.md /private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md /private/tmp/armada-audit-2026-08-30/D1-delivery-state-audit.md
```

### CMD-V1 — 四仓只读基线

```bash
git -C /Users/daishuaishuai/IdeaProjects/armada status --short
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web status --short
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol status --short
git -C /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan status --short
git -C /Users/daishuaishuai/IdeaProjects/armada rev-parse HEAD
git -C /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web rev-parse HEAD
git -C /Users/daishuaishuai/IdeaProjects/armada-protocol rev-parse HEAD
git -C /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan rev-parse HEAD
```

### CMD-V2 — Armada 后端 P0 合同

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn -Dtest='UserDataIsolationContractTest' test
mvn -Dtest='WritePermissionContractTest' test
mvn -Dtest='AccountContactSnapshotContractTest,ContactTaskWireContractTest' test
mvn -Dtest='GroupCreationAnnouncementGateTest,GroupCreationMarketingRecoveryContractTest' test
```

### CMD-V3 — 前端归属与缓存

```bash
cd /Users/daishuaishuai/IdeaProjects/wheel-saas-pure-web
pnpm run test
pnpm run typecheck
pnpm run build
```

### CMD-V4 — Web 协议合同

```bash
cd /Users/daishuaishuai/IdeaProjects/armada-protocol/protocol-layer
npm test
npm run build
npm run lint
```

### CMD-V5 — Android 协议合同

```bash
cd /Users/daishuaishuai/IdeaProjects/whatsapp-server-feature-android-zhuan
go vet ./...
go build ./...
go test ./...
```

### CMD-V6 — 交付脚本与 fake deployment

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
bash armada-deploy/deploy-test.test.sh
```

### CMD-V7 — Runner 核心

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-deploy/staging-accept
go test ./...
```

### CMD-VB1 — acceptance bootstrap linter（R0-B1 创建后首次使用）

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
node armada-deploy/contracts/acceptance/check.mjs --fixtures armada-deploy/contracts/acceptance/fixtures
```

### CMD-VB2 — messaging bootstrap linter（R0-B2 创建后首次使用）

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
node armada-deploy/contracts/messaging/check.mjs --fixtures armada-deploy/contracts/messaging/fixtures --mode bootstrap
```

### CMD-V8 — canonical manifest / shadow / contract fixtures（B-01 扩展 bootstrap 入口）

```bash
cd /Users/daishuaishuai/IdeaProjects/armada
node armada-deploy/contracts/messaging/check.mjs --manifest armada-deploy/contracts/messaging/topic-manifest.v1.yaml --mode shadow --fixtures armada-deploy/contracts/messaging/fixtures
bash armada-deploy/contracts/messaging/run-contract-tests.sh
```

### CMD-V9 — P0 总出口

```bash
cd /Users/daishuaishuai/IdeaProjects/armada/armada-api
mvn test
cd /Users/daishuaishuai/IdeaProjects/armada
bash armada-deploy/deploy-test.test.sh
cd /Users/daishuaishuai/IdeaProjects/armada/armada-deploy/staging-accept
go test ./...
```

### CMD-V10 — test1 与真实协议

- **Unknown**：当前未冻结 Runner 二进制、state dir、candidate、环境、资源与绝对 argv，因此安全状态是 `NO_COMMAND/TEST1_AUTH_PENDING` 或 `NO_COMMAND/REAL_PROTOCOL_AUTH_PENDING`。
- **Inferred**：E-01/W-01 只能在 L2/L4 合同签认后生成精确 argv；在此之前不得提供可误执行的环境命令。

## 8. 今天可以启动的只读/本地任务

当前任务只授权 R0-01；其余写入/测试任务需要 L1。所有 work item 均不超过 4h。

- **Observed**：R0-01 的输入冻结已在本报告收尾核验中执行。
- **Inferred**：R0-02～R0-08 与 R0-B1/R0-B2 是获得相应授权后的计划任务，尚未执行。

| ID | 时长 | Owner 仓库 | 依赖与并行 | 动作 | 验证 | 停止条件 | 交付证据 / 授权 |
|---|---:|---|---|---|---|---|---|
| R0-01 | 1h | `armada`（delivery owner） | 无；先行 | 冻结 S1/S2/D1 hash、mtime、行号索引和 38/7/4 计数 | CMD-V0 | 任一输入变化或计数不符 | 本报告 §0.1、只读命令输出与引用语义清单；L0，不另写文件 |
| R0-02 | 2h | `armada` | 依赖：R0-01；并行：R0-03 | 指定 delivery、manifest governance、producer、consumer、operations、security owner 角色；在本任务内先创建 `owner-decision.schema.json` 与 `owner-decision/check.mjs`，再校验正反 fixture | CMD-V0 + 本任务创建的 owner linter | 任一 P0 无 accountable owner，或 linter 路径/反例不可机械执行 | `owner-decision.md`、schema/linter/fixtures；L1 文档写入 |
| R0-03 | 2h | 四个 owner 仓库 | 依赖：R0-01；并行：R0-02 | 固定四仓 commit/dirty、验证脚本名、worktree 与候选分支边界 | CMD-V1 | dirty 变更与计划重叠或命令入口不确定 | `baseline.json`；L0 读取，建 worktree 需 L1 |
| R0-B1 | 4h | `armada` | 依赖：R0-02；并行：R0-B2 | 先建最小 acceptance schema/linter 与一组必败/必过 fixture；该任务内首次调用真实脚本 | CMD-VB1 | 脚本路径不存在、反例返回 0 或正例非 0 | acceptance bootstrap 脚本/schema/fixtures；L1 |
| R0-B2 | 4h | `armada` | 依赖：R0-02；并行：R0-B1/R0-06 | 先建最小 messaging schema/linter 与一组必败/必过 fixture；该任务内首次调用真实脚本 | CMD-VB2 | 脚本路径不存在、反例返回 0 或正例非 0 | messaging bootstrap 脚本/schema/fixtures；L1 |
| R0-04 | 4h | `armada` | R0-B1 | 为七 P0 写 Given/When/Then、负向、恢复、非目标和授权级别 | CMD-VB1 | 任一 P0 只有“有类/有路由”验收 | `p0-acceptance-contract.yaml`；L1 |
| R0-05 | 4h | `armada` | R0-B2；可与 R0-06 并行 | 写 manifest 失败夹具：缺 owner、单边 topic、缺 key/order/SLO、错误 DLT、Unknown 无到期 | CMD-VB2 | fixture 可静默通过 | messaging fixtures；L1 |
| R0-06 | 4h | `armada` | 依赖：R0-02；并行：R0-B1/R0-B2 | 写 candidate/Runner 失败夹具：scope/commit/digest 漂移、required stage 缺失、逻辑 FAIL 但进程 0 | CMD-V6、CMD-V7 | 旧 PASS 可复用或缺 profile 仍 PASS | delivery fixtures；L1 |
| R0-07 | 2h | `armada` + 产品/工程 owner | R0-04；不占实现流 | 对 P1-A～D 确认价值、合规、owner 和默认候选顺序；最多预留两条 | CMD-VB1 | 把 Inferred 排序写成 Observed，或选出三条以上 | `p1-selection-decision.md`；L1 文档写入 |
| R0-08 | 1h | `armada` | R0-B1 | 固定 L2/L3/L4 授权模板的 environment/candidate/action/rate/duration/abort/cleanup 字段 | CMD-VB1 | test1 授权可继承为真实协议授权 | `authorization-contract.yaml`；L1 |

## 9. 本周必须完成的 P0：两条实现流

### 9.1 Flow A — 数据/权限/管理面与交付证据链

本流顺序执行；估时合计接近 40h，任何切片超时都触发 P1 顺延。所有计划均为 **Inferred**。

| ID | 时长 | Owner 仓库 | 依赖 | 实现切片 | 验证命令 | 停止条件 | 交付证据 |
|---|---:|---|---|---|---|---|---|
| A-01 | 4h | `armada` | R0-04/R0-06 | 扩展 R0-B1 的 acceptance linter，并建 candidate schema linter，绑定 change、scope、四仓适用性、required profiles | CMD-VB1、CMD-V6、CMD-V7 | 缺 owner/case/profile 仍可生成 candidate | schema、正反 fixtures |
| A-02 | 4h | `armada` | A-01 | P0-1：将 owner 写入与 SELF/ALL/SYSTEM 过滤接入活动 runtime；历史 NULL fail-closed | CMD-V2 `UserDataIsolationContractTest` | 候选 worktree 无法与当前 HEAD 对账，或任一域仍 tenant-only | patch、H2 双用户证据 |
| A-03 | 4h | `wheel-saas-pure-web` | A-02 | P0-1：用户维度缓存 namespace、切换身份清理；先创建 `src/utils/user-data-isolation.test.ts` 负向/正向用例，再运行仓库现有 `pnpm run test` | CMD-V3 | 固定业务缓存键仍可跨用户复用 | unit/typecheck/build 证据 |
| A-04 | 4h | `armada` | A-01；可在 A-03 后 | P0-2：生成账号/分组/代理/群/成员/营销写端点权限矩阵与参数化测试 | CMD-V2 `WritePermissionContractTest` | 任一变更端点无 edit/admin 语义 | endpoint-authority matrix、红测 |
| A-05 | 4h | `armada` | A-04 | P0-2：逐方法替换写权限并闭合 view/edit/admin 用例 | CMD-V2 `WritePermissionContractTest` | 只读主体仍能触发数据或协议副作用 | 绿测、权限 diff |
| A-06 | 4h | `armada-protocol` | A-01 | P0-3：Web 管理面无 key/身份配置时 fail-closed，状态变更细分授权并写脱敏审计 | CMD-V4 | 空配置仍放行，或日志含敏感值 | auth tests、route matrix |
| A-07 | 4h | `whatsapp-server-feature-android-zhuan` | A-06 的共享合同 | P0-3：coordinator 状态变更路由统一应用鉴权与授权测试 | CMD-V5 | drain/online/delete 任一路径绕过鉴权 | middleware tests、route matrix |
| A-08 | 4h | `armada` | A-01、A-02～A-07 | 生成 `versions.lock` 与四仓 verification report hash；源码/锁/配置漂移即失效 | CMD-V6 | 手填 commit、dirty 未记录、旧证据可复用 | candidate bundle、drift fixtures |
| A-09 | 4h | `armada` | A-08 | fake deploy 原子产出 deployment/runtime manifest；Runner required stages 与逻辑 outcome fail-closed | CMD-V6、CMD-V7 | 需要 SSH/test1 才能测试，或短 plan 可产验收 PASS | fake transcript、Runner tests |
| A-10 | 2h | `armada`（独立 verifier） | A-09 | 复核 P0-1/2/3 与交付链证据，不由实现者自证 | CMD-V1、CMD-V2、CMD-V3、CMD-V4、CMD-V5、CMD-V6、CMD-V7 | 证据无 hash、commit 不同或存在第三实现流 | `flow-a-verdict.json` |

### 9.2 Flow B — 通讯录/群副作用与 Kafka/健康/Runner 基础

本流顺序执行；跨仓工作仍算一个实现流。所有计划均为 **Inferred**。

| ID | 时长 | Owner 仓库 | 依赖 | 实现切片 | 验证命令 | 停止条件 | 交付证据 |
|---|---:|---|---|---|---|---|---|
| B-01 | 4h | `armada` | R0-05 | 扩展 R0-B2 的 messaging linter，建 canonical manifest/schema，登记 Kafka、DLT、Redis Stream、outbox、owner、key/order/PII/SLO | CMD-V8 | Unknown 无 owner/到期进入 enforce；任何 topic 被合并 | manifest、schema、fixtures |
| B-02 | 4h | `armada` | B-01 | 建 deterministic generator + shadow diff + base/dynamic/DLT 全量 inventory | CMD-V8 | 未采集序列化为 0，或 broker default 进入 enforce | generated inventory、hash、diff report |
| B-03 | 4h | `armada` | B-01、R0-04 | P0-4：冻结 full/partial/delete/page/interrupted/out-of-order schema、水位和 FAILED 状态 | CMD-V2 `AccountContactSnapshotContractTest`、CMD-V8 | history/named count 被当完整通讯录 | schema、匿名 fixtures、backend tests |
| B-04 | 4h | `armada-protocol` + `whatsapp-server-feature-android-zhuan` | B-03 | P0-4：两协议各实现合成 snapshot producer 与相同 schema contract tests | CMD-V4、CMD-V5、CMD-V8 | 任一协议只能从真实账号取样才可测试，或字段语义分叉 | 两协议 producer tests |
| B-05 | 4h | `armada` | B-03、R0-04 | P0-5：冻结 `taskId`/`taskAccountId`/`recipientId` + `roundNo`、正/负结果、terminal reason 的匿名 wire fixture | CMD-V2 `ContactTaskWireContractTest`、CMD-V8 | adapter-only 测试替代 parser 测试 | canonical fixture、backend tests |
| B-06 | 4h | `armada-protocol` | B-05 | P0-5：Web parser/validator/result 保留关联，失败可持久回写 | CMD-V4、CMD-V8 | 普通营销校验吞掉关联或失败无 terminal | Web contract tests |
| B-07 | 4h | `whatsapp-server-feature-android-zhuan` | B-05 | P0-5：Android parser/result 保留关联并与同一 fixture 兼容 | CMD-V5、CMD-V8 | 两协议 fixture 结果不一致 | Android contract tests |
| B-08 | 4h | `armada-protocol` + `whatsapp-server-feature-android-zhuan` + `armada` | B-01、R0-04 | P0-6：关闭发言确认成为营销前置；失败进入可恢复/人工态 | CMD-V2、CMD-V4、CMD-V5 | 任一协议设置失败仍返回完整成功 | 双协议 + backend gate tests |
| B-09 | 4h | `armada` | B-08 | P0-7：保留已建群事实，只独立重试消息 attempt；不确定结果转人工 | CMD-V2 `GroupCreationMarketingRecoveryContractTest` | SQL 清空群关联或重试再次建群 | mapper/service tests、state trace |
| B-10 | 4h | `armada` | B-02、B-03～B-09、A-09 | Runner/health 从 manifest 生成全量 topic/group/DLT/Stream/outbox inventory；缺失为 Unknown/Fail | CMD-V6、CMD-V7、CMD-V8 | subset PASS 提升为全局健康；replay 自动开放 | collector fixtures、health report、flow-b verdict |

### 9.3 本周 P0 出口

只有以下条件全部满足，独立 verifier 才能产生 `P0_LOCAL_CLOSED`：

以下第 1～7 项均为 **Inferred（验收门）**；第 8 项为 **Unknown（验证层级限制）**。

1. 七个 S2 P0 均有当前 commit、失败先红后绿的本地测试证据和明确非目标。
2. Flow A/B 的验证证据由同一 candidate 绑定；任何 commit、migration、config、lock 或合同变化都会使证据失效。
3. manifest shadow 覆盖所有已知 topic/group/DLT/Stream/outbox；单边合同有 owner/到期，未采集保持 Unknown/Fail。
4. topic 名、payload、key、group、ACK/commit/retry 语义未因治理工作改变；没有 topic 合并。
5. replay 仍默认拒绝；只允许 fake sender 验证原 ID/state/window，不触达真实 WhatsApp。
6. Runner 的执行 PASS、profile PASS、P0 本地关闭和环境验证四种状态分开。
7. 独立复核确认没有超链功能改动、敏感值、第三实现流或静默 skip。
8. **Unknown**：即使全部满足，也只表示本地 P0 风险闭合，不表示 test1 已部署、环境通过或业务可用。

## 10. 下周可以启动的 P1 业务纵切

### 10.1 启动规则

- **Observed**：最终 S2 已给出 P1-A～D 四个候选。证据：S2:196-249。
- **Inferred**：G5、G6 未通过时启动数为 0；通过后同时最多两条。
- **Inferred（默认建议）**：Slot 1 启动 P1-D，先建立跨高风险动作的可追责事实；Slot 2 启动 P1-A，复用现有 outbox、代理恢复和节点 registry。P1-B/C 排队，或由 owner 在 R0-07 中替换 P1-A。
- **Unknown**：默认顺序是否符合真实客户价值和地区合规；必须由产品/工程 owner 签认，不由本报告冒充决定。
- **Inferred**：下列 PA/PB/PC/PD work item 均为条件式计划，尚未执行；每项最多 4h。

### 10.2 P1-A — 账号可靠性与诊断

| ID | 时长 | Owner 仓库 | 依赖/并行 | 切片 | 验证 | 停止条件 | 证据/授权 |
|---|---:|---|---|---|---|---|---|
| PA-01 | 3h | `armada` | 依赖：G5/G6；并行：PD-01 | 冻结 accepted/terminal、一次换代理、硬容量、分配一致性合同 | CMD-VB1 | value/owner/非目标任一 Unknown | acceptance contract；L1 |
| PA-02 | 4h | `armada` + `wheel-saas-pure-web` | PA-01 | fake outbox：重复/乱序结果不重复改终态；扩展现有 `src/views/account/index/account-display.test.ts`，断言 accepted 仅表示命令受理、不显示为 ONLINE | CMD-V2、CMD-V3 | 需要真实账号证明本地状态机 | backend state tests + frontend display test；L1 |
| PA-03 | 4h | `armada` | PA-02 | 代理失败只做一次原子换绑并写脱敏诊断时间线 | CMD-V2 | 读取/输出真实代理值或自动循环换绑 | recovery tests；L1 |
| PA-04 | 4h | Android 仓 | PA-01 | Android 硬容量拒绝与无容量明确失败 | CMD-V5 | 容量默认值无 owner 或只靠环境试错 | allocator tests；L1 |
| PA-05 | 4h | `armada` + 前端 | PA-02 | 分配写入、列表筛选、统计、取消分配口径一致 | CMD-V2、CMD-V3 | 任一读模型无法由持久事实解释 | cross-layer evidence；L1 |
| PA-06 | 4h | `armada`（独立 verifier） | PA-03～PA-05 | 合成纵切、故障注入与证据检查 | CMD-V2、CMD-V3、CMD-V5、CMD-V9 | 缺一项负向/恢复证据 | local vertical verdict；L1 |

### 10.3 P1-B — 群任务副作用安全

| ID | 时长 | Owner 仓库 | 依赖/并行 | 切片 | 验证 | 停止条件 | 证据/授权 |
|---|---:|---|---|---|---|---|---|
| PB-01 | 3h | `armada` | G5/G6；等待实现槽位 | 冻结幂等、RESULT_UNKNOWN、过期恢复、设置失败项与风险预算合同 | CMD-VB1 | “统一风险预算”无 owner/阈值来源 | acceptance contract；L1 |
| PB-02 | 4h | `armada` | PB-01 | 同步/批量建群请求幂等与 expired-processing 恢复 | CMD-V2 | 随机 operation ID 仍可重复副作用 | idempotency/recovery tests；L1 |
| PB-03 | 4h | `armada` | PB-01 | 群资料失败准确记录项目，有限重试不重复成功项 | CMD-V2 | 协议漏失败项仍固定归为群名失败 | result classification tests；L1 |
| PB-04 | 4h | `armada` + 两协议仓 | PB-01 | 合成限制信号映射到一致退避/暂停/人工态 | CMD-V2、CMD-V4、CMD-V5 | 需要真实封号信号才能验证映射 | fake risk fixtures；L1 |
| PB-05 | 4h | 独立 verifier | PB-02～PB-04 | fake client 验证不重复建群、关闭发言前置与中断恢复 | CMD-V2、CMD-V4、CMD-V5、CMD-V9 | 任一外部事实无法追踪 | local vertical verdict；L1 |

### 10.4 P1-C — 通讯录安全私聊

| ID | 时长 | Owner 仓库 | 依赖/并行 | 切片 | 验证 | 停止条件 | 证据/授权 |
|---|---:|---|---|---|---|---|---|
| PC-01 | 3h | `armada` | G5/G6；P0-4/5；等待槽位 | 冻结完整性、试算、退订、冷却、SENDING 恢复、retryMax 以及 `taskId`/`taskAccountId`/`recipientId` + `roundNo` 四关联合同 | CMD-VB1 | 把 named contacts 写成好友/双向好友 | acceptance contract；L1 |
| PC-02 | 4h | `armada` | PC-01 | 试算同时返回可用账号、预计收件人数与零结果原因 | CMD-V2 | 快照缺失被静默忽略 | preview tests；L1 |
| PC-03 | 4h | `armada` | PC-01 | 租户级退订与跨任务/跨账号冷却 | CMD-V2 | 合规适用范围未签认或跨租户读写 | suppression tests；L1 |
| PC-04 | 4h | `armada` | PC-01 | SENDING 超时有限恢复；retryMax 文案与总尝试口径一致 | CMD-V2 | 无 terminal 的无限重试或永久卡住 | recovery tests；L1 |
| PC-05 | 4h | `armada` + 两协议仓 | PC-02～PC-04 | 用同一匿名 fixture 做 snapshot→recipient→command→result 合成 E2E，成功/失败均断言 `taskId`/`taskAccountId`/`recipientId` + `roundNo` | CMD-V2、CMD-V4、CMD-V5、CMD-V8 | 需要主动拉真实通讯录或真实发送才能通过 | local vertical verdict；L1 |

### 10.5 P1-D — 持久操作与敏感访问审计

| ID | 时长 | Owner 仓库 | 依赖/并行 | 切片 | 验证 | 停止条件 | 证据/授权 |
|---|---:|---|---|---|---|---|---|
| PD-01 | 4h | `armada` | 依赖：G5/G6；并行：PA-01 | 定义 append-only actor/action/target/result/trace/retention schema 与迁移 | CMD-V2 | 保存正文、联系人、凭据或原始协议数据 | schema/migration tests；L1 |
| PD-02 | 4h | `armada` | PD-01 | 高风险动作成功/失败/拒绝恰好写一条，不可关闭/采样 | CMD-V2 | 技术日志被当审计事实源 | service tests；L1 |
| PD-03 | 4h/域 | `armada`、Web 或 Android owner | PD-02；每次只选一个域 | 依次接账号、群、代理、IAM、协议节点、敏感导出 | CMD-V2/CMD-V4/CMD-V5 | 单切片跨两个域或形成第三流 | per-domain tests；L1 |
| PD-04 | 4h | `armada` | PD-02 | 租户/用户隔离查询 API 与管理员访问审计 | CMD-V2 | 普通用户可越权查询或管理员读取无留痕 | API/H2 tests；L1 |
| PD-05 | 4h | 前端 | PD-04 | 最小只读审计页面，页面只调 API client | CMD-V3 | 生产 mock、页面直连 axios、敏感字段展示 | unit/typecheck/build；L1 |
| PD-06 | 4h | 独立 verifier | PD-03～PD-05 | 故障注入：写审计失败必须按合同 fail-closed/fail-safe，不丢高风险动作事实 | CMD-V2、CMD-V3、CMD-V4、CMD-V5 | 失败策略未由 owner 签认 | local vertical verdict；L1 |

## 11. Kafka/健康/Runner 前置关系

| 任务 | 本地实现前是否必须 | test1 前是否必须 | 原因 | 标签 |
|---|---|---|---|---|
| P0-1 数据隔离 | Kafka 否；H2/前端测试即可 | candidate/Runner 必须；Kafka按用例适用 | 先证明 owner/scope，不需环境试错 | Inferred |
| P0-2 写权限 | Kafka 否；MockMvc 即可 | candidate/Runner 必须 | 环境角色验证不能替代代码权限 | Inferred |
| P0-3 管理面鉴权 | Kafka 否 | health/candidate/Runner 必须 | test1 只读验证监听/鉴权组合 | Inferred |
| P0-4 快照 | manifest/schema fixture 必须；真实 broker 否 | Kafka/health/Runner 必须 | producer/consumer/topic/watermark 是跨仓合同 | Inferred |
| P0-5 消息关联 | manifest + parser fixture 必须；真实 broker 否 | Kafka/health/Runner 必须 | terminal 关联需完整消息 inventory | Inferred |
| P0-6/7 群副作用 | fake client + manifest 必须；真实 broker 否 | Kafka/health/Runner 必须 | group-action/normal-group 通道不能漏采 | Inferred |
| P1-A | fake outbox/allocator 可先做 | Kafka/health/Runner 必须 | accepted/terminal 与节点状态需 candidate 绑定 | Inferred |
| P1-B | fake client 可先做 | Kafka/health/Runner 必须 | 多阶段外部副作用和结果链需全量观测 | Inferred |
| P1-C | 合成 snapshot/wire 可先做 | Kafka/health/Runner 必须 | 快照、command/result、恢复链跨 topic | Inferred |
| P1-D | 本地 schema/API 不需要 Kafka | candidate/Runner 必须；Kafka按审计来源适用 | 先闭合 append-only 与隔离 | Inferred |

## 12. test1 与真实协议后续任务

- **Inferred**：E-01～E-07 与 W-01～W-03 均为授权后的条件式计划。
- **Unknown**：当前没有任何 test1 或真实协议运行结果。

### 12.1 test1：只有 L2/L3 授权后执行

| ID | 时长 | Owner 仓库 | 授权 | 依赖 | 动作 | 验证 | 停止条件 | 交付证据 |
|---|---:|---|---|---|---|---|---|---|
| E-01 | 2h | `armada` | L2 | G5/G7 | Runner self-check：binary/service/wrapper/disk/retention/process-group | CMD-V10：先冻结 argv | 任一 Unknown/Fail | 脱敏 self-check summary |
| E-02 | 2h | `armada` | L2 | E-01 | 只读核对 runtime manifest 与 candidate/roles/digest | CMD-V10 | 版本或角色漂移 | runtime-match report |
| E-03 | 3h | `armada` | L2 | E-02、B-10 | 只读 describe topic/group/config/lag/DLT/Stream/outbox 聚合 | CMD-V10 | 需要读 payload、凭据或原始数据 | manifest drift/health report |
| E-04 | 4h | `armada` | L3 | E-03 | 精确 candidate 有界部署；原子写 deployment/runtime manifest | CMD-V10 | 无窗口/owner/回滚或部分版本不匹配 | deployment evidence |
| E-05 | 4h | `armada` | L3 | E-04 | 隔离数据 quick/integration；required case 无静默 skip | CMD-V10 | 通用 integration 能力缺失 | profile/case evidence |
| E-06 | 2h | `armada` | L3 | E-03 | create-only provisioning（仅缺失资源）；先 dry-run | CMD-V10 | 删除、缩 partition/retention、broker default | plan/apply diff |
| E-07 | 2h | 独立 verifier | L2/L3 | E-04/E-05 | 聚合 candidate/profile/findings；不产生 DELIVERABLE 除非 policy 完整 | CMD-V10 | 实现者自证、缺证据 hash | test1 verdict |

### 12.2 真实 WhatsApp、代理、账号与风控：只有 L4 单独授权后执行

| ID | 时长 | Owner 仓库 | 依赖 | 动作 | 验证 | 立即停止条件 | 交付证据 |
|---|---:|---|---|---|---|---|---|
| W-01 | 2h | `armada` + 选定协议仓 | G8、E-05 | 签认资源租约、单一动作、速率/时长、abort/cleanup 和精确 argv | CMD-V10 | 资源/环境/owner 任一不明 | 不含真实标识的授权合同 |
| W-02 | 2h | P1 对应 owner | W-01 | 只执行一个批准动作；不得自动扩大样本 | CMD-V10 | 外部异常、风控信号、版本漂移、未预期副作用 | 脱敏聚合结果 |
| W-03 | 2h | 独立 verifier | W-02 | cleanup、状态/副作用对账、冷却与复核 | CMD-V10 | 清理/对账不完整 | canary verdict、cleanup proof |

- **Inferred**：真实代理探测、账号上下线、联系人同步/拉取、消息发送、建群、加人、群权限变更和任何风险预算样本都属于 L4；test1 部署授权不能代替。
- **Inferred**：真实协议错误不自动重试，不换账号、不换代理、不扩大联系人或群样本。

## 13. Unknown 与最便宜的下一步验证

| Unknown | 最便宜且安全的下一步 | 需要授权 | 通过标准 | 标签 |
|---|---|---|---|---|
| canonical 仓与实际 owner | R0-02 的 2h 决策表 | L1 文档 | owner/审批边界无空项 | Unknown |
| 七 P0 是否能在本周双流完成 | R0-04 后按每个红测切片重新估时 | L1 | 每片≤4h且双流容量不超；否则 P1 顺延 | Unknown |
| 隔离候选 worktree 是否可复用 | R0-03 对当前 HEAD 做只读 commit/diff/依赖闭包核对 | L0；移植需 L1 | 无基线漂移、合同一致、测试可重放 | Unknown |
| 当前四仓测试/构建是否通过 | 在干净 worktree 运行 CMD-V2–CMD-V9 | L1 | 实际 exit 0 + commit/hash + 结果文件 | Unknown |
| account-command 是否有仓库外 consumer | 先查本地冻结 IaC；仍不明再做 E-03 | 本地 L0；环境 L2 | consumer/group 闭环或退役 ADR | Unknown |
| contact snapshot 是否有仓库外 producer | 核对部署构件清单/SBOM，不连接服务 | L2 或离线导出 | producer 归属/版本明确，否则按 P0-4 实施 | Unknown |
| 环境 topic/partition/retention/minISR | B-01/B-02 后执行 E-03 | L2 | 与 manifest 一致；未采集保持 Fail/Unknown | Unknown |
| Runner 是否安装且稳定 | E-01 | L2 | binary/service/wrapper/disk/process-group 全通过 | Unknown |
| test1 实际运行版本 | E-02 | L2 | 与 candidate/roles/digest 完全匹配 | Unknown |
| 通用 integration/canary 是否在仓库外 | 只读导出外部任务定义；无则明确缺能力 | L2 或平台只读 | 工具位置/owner/contract，或 `CAPABILITY_MISSING` | Unknown |
| P1-A～D 的最终价值顺序 | R0-07 一页 owner 决策 | L1 文档 | 同时最多两条，含用户/价值/合规/非目标 | Unknown |
| 真实账号/代理/群/联系人风险 | 先完成所有 fake/fixture；再 W-01 | L4 | 单动作、上限、abort/cleanup 明确 | Unknown |
| 外部日志平台是否补审计 | 只读核对脱敏字段/路由/留存配置 | L2 或离线导出 | actor/action/target/result/trace/retention 覆盖 | Unknown |
| P2/P3 是否进入版本范围 | 产品 owner 签边界表，不看菜单数量 | L1 文档 | 本版本/后续/外部系统/不做四分法 | Unknown |

## 14. Evidence → Finding → Path

### 14.1 Evidence

| ID | 标签 | 事实 | 输入证据 |
|---|---|---|---|
| E-S3-01 | Observed | 最终 S2 有 38 个能力簇、7 P0、4 P1；运行/环境均未验证 | S2:33-43、69-92、94-249 |
| E-S3-02 | Observed | 七 P0 是归属、权限、管理鉴权、通讯录两断链、群两副作用问题 | S2:143-195 |
| E-S3-03 | Observed + Inferred | S2 已列四 P1；其启动顺序仍是推断 | S2:196-249 |
| E-S3-04 | Observed | 消息治理缺 canonical、全量 inventory；不合并 topic，replay deny | S1:18-27、423-450、543-564 |
| E-S3-05 | Observed | candidate/Runner/DELIVERABLE 机械链不完整，Runner PASS 语义有限 | D1:16-35、142-249 |
| E-S3-06 | Observed | 事实输入的 hash、mtime、行数与用途已在本报告冻结 | 本报告 §0.1 |

### 14.2 Findings

| Finding | 严重度 | 结论标签 | 证据 | 路径 |
|---|---|---|---|---|
| F-S3-01 七个业务/安全 P0 未本地关闭 | P0 | Observed + Inferred | E-S3-01/E-S3-02 | Flow A/B → G5 |
| F-S3-02 消息与交付基础会让局部 PASS 冒充闭环 | P0 | Observed + Inferred | E-S3-04/E-S3-05 | F0-K/F0-D → G3/G4 |
| F-S3-03 四 P1 可执行但价值顺序未签认 | P1 门 | Inferred + Unknown | E-S3-03 | R0-07 → G6 → 最多两条 |
| F-S3-04 环境与真实协议无证据且无授权 | 阻断门 | Unknown | E-S3-01/E-S3-05 | L2/L3 → L4 分级 |
| F-S3-05 事实输入漂移风险 | 报告门 | Inferred | E-S3-06 | G0 hash + 引用语义校验 |

### 14.3 Paths

| Path | 步骤 | 当前出口 | 残余风险 | 标签 |
|---|---|---|---|---|
| P-S3-01 本地 P0 | G0/G1/G2 → Flow A + Flow B → 独立复核 → G5 | `P0_LOCAL_CLOSED` 或精确 BLOCKED | 环境仍 Unknown | Inferred |
| P-S3-02 下周 P1 | G5 → R0-07/G6 → P1-D + 最多一个 A/B/C → 本地纵切证据 | `P1_LOCAL_VERTICAL_PASSED` | 价值/合规/环境仍需验证 | Inferred |
| P-S3-03 test1 | G3/G4/G5 → L2/L3 → runtime/health/deploy/integration | profile-scoped verdict | 不等于真实协议或可交付 | Inferred |
| P-S3-04 真实协议 | test1 通过 → G8/L4 → 单动作 canary → cleanup | canary-scoped verdict | 封号率/长期行为仍 Unknown | Inferred |

## 15. 文件与行号证据索引

- **Observed**：`/private/tmp/armada-audit-2026-08-30/S1-kafka-topic-governance-design.md:18-27` — canonical、单边合同、不合并 topic、replay 默认拒绝。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S1-kafka-topic-governance-design.md:351-389` — DLT 分层与人工 replay 门禁。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S1-kafka-topic-governance-design.md:423-450` — 保持独立与合并硬门。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S1-kafka-topic-governance-design.md:452-511` — manifest/shadow/运维实施切片。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S1-kafka-topic-governance-design.md:543-589` — S1 P0/P1/P2 与 Unknown。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md:33-43` — 38/7/4 执行摘要。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md:69-92` — 分类数量与验证层级。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md:94-141` — M01～M38 全矩阵。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md:143-195` — 七个 P0。
- **Observed/Inferred**：`/private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md:196-249` — 四个 P1 最小纵切。
- **Observed/Inferred**：`/private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md:251-296` — P2/P3 与最便宜验证。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/S2-non-hyperlink-gap-matrix.md:321-481` — 当前源码、配置、迁移与测试源码行号证据。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/D1-delivery-state-audit.md:16-35` — 当前工具岛、Runner 和交付链结论。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/D1-delivery-state-audit.md:142-224` — 状态模型与 candidate/证据关系。
- **Observed**：`/private/tmp/armada-audit-2026-08-30/D1-delivery-state-audit.md:226-295` — D1 P0/P1/P2。
- **Observed/Inferred**：`/private/tmp/armada-audit-2026-08-30/D1-delivery-state-audit.md:297-333` — 阶段 owner、门禁、回滚与 Unknown。

### 15.1 S2 P0 的底层文件证据入口

- **Observed**：P0-1：S2:325-332；`V141__account_user_data_ownership.sql`、`AccountMapper.xml`、`AccountServiceImpl.java`、前端 `auth.ts`。
- **Observed**：P0-2：S2:334-340；账号、分组、代理、群、成员、营销 Controller 权限行。
- **Observed**：P0-3：S2:342-347；Web `config.ts/routes` 与 Android `admin_api.go/main.go`。
- **Observed**：P0-4：S2:349-356；backend contact consumer/sink/mappers 与两协议当前联系人路径。
- **Observed**：P0-5：S2:358-364；backend Web/Android adapter、两协议 command/result、backend event consumer。
- **Observed**：P0-6：S2:366-370；Web group route/test 与 Android group adapter。
- **Observed**：P0-7：S2:372-375；marketing result service 与 mapper retry SQL。

## 16. 超链排除、状态声明与停止条件

- **Observed**：本路线没有任何超链赶超 work item。
- **Inferred**：共享基础设施若触及超链路径，只运行既有回归；不得增加超链 acceptance case、页面、服务、协议语义、指标或迁移。
- **Inferred（停止条件）**：任一 work item 若以补齐超链竞品能力为必要条件，立即移出本路线并单独立项。
- **Observed**：当前“代码已存在”仅表示最终输入中列出的静态实现可见。
- **Unknown**：当前 HEAD 是否本地通过；本轮未运行测试/构建。
- **Unknown**：任何 test1、Kafka、Runner、真实 WhatsApp、代理、账号或风控行为是否环境通过；本轮未访问。
- **Inferred**：本 S3 只表示路线已按最终 S2 重建，并纳入 38 个能力簇、7 个 P0 与 4 个 P1；不表示 P0 已实现、P1 已批准、test1 已验、真实协议已验或业务已可用。
