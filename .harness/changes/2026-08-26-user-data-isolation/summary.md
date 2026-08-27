# 变更记录：同租户用户数据隔离

- 日期 / 分支 / worktree: 2026-08-26 / `codex/user-data-isolation` / `/Users/daishuaishuai/IdeaProjects/armada-user-data-isolation`
- 需求来源: 用户确认“普通用户只看自己；租户管理员看全部；IP 平台共有；共享与转移后续再做；允许分阶段上线”
- 状态: 已合并 `1.0.3-snapshot` 最新代码并部署第一套测试环境；接口、运行期与 browser-skill 页面隔离验收通过

## 目标（一句话）

在保留现有 `tenant_id` 安全边界的基础上，为业务聚合增加可信用户归属：普通用户只能访问自己的数据，`TENANT_ADMIN` 可访问当前租户全部数据。

## 缺口拆解 / 任务清单

- [x] Phase 0：建立可信 `DataScope`（`SELF` / `ALL` / `SYSTEM`）及统一 fail-closed 规则。
- [x] Phase 1：账号、账号分组、账号导入批次完整隔离，覆盖列表、统计、详情、创建、修改、删除、批量操作、导出与上线诊断。
- [x] Phase 2：群链接运营句柄、WS 链接分组、群文件夹、链接导入/批处理、营销模板/图片与标准拉群头像已完成代码、迁移和 test1 验收；群 JID/协议事实继续保持租户级 canonical。
- [x] Phase 3：进群、标准拉群、普通营销、拉群营销、建群营销、新建普群和群批处理任务及其结果/导出/统计已完成 owner 继承链和 test1 读/越权矩阵。
- [x] Phase 4：推广渠道、文件、直接 SQL、Scheduler、Kafka、Outbox 和恢复链路完成收口；直接 JDBC、协议回调、异步导出、核心调度/恢复及群实时协议操作均已覆盖 owner 边界。
- [x] Phase 5：前端保存可信当前身份能力、隔离跨用户本地状态，并修正 401/403/404/409 错误处理；代码、类型检查、生产构建和 test1 前端制品发布完成。

## 关键设计决策

- `tenant_id` 继续作为不可跨越的租户边界；本次不修改登录租户选择。
- 权限根字段使用 `owner_user_id`；`created_by` / `updated_by` 只表示审计事实，禁止复用为权限边界。
- `DataScope` 必须来自服务端恢复的 `AuthPrincipal`。普通用户为 `SELF`，系统角色 `TENANT_ADMIN` 为 `ALL`。
- 管理员创建的数据仍归管理员本人；`ALL` 仅扩大读取与操作范围，不产生“无 owner”新数据。
- 用户请求缺少或未显式传递 `DataScope` 时 fail-closed，不能把 `null owner` 解释成管理员权限。
- 历史 `owner_user_id IS NULL` 数据不猜归属：普通用户不可见，管理员可见；共享/转移以后再做。管理员只可查看、停止或清理这类历史聚合，不能启动、恢复、重试、发送消息或调用群/账号实时协议操作。
- IP 资源保持平台/租户共享模型，不增加用户 owner；但账号绑定、解绑等操作必须校验账号 owner。
- 子表优先通过账号、任务、模板等聚合根继承归属，不机械复制 `owner_user_id`。
- 手机号、群 JID、协议句柄等物理唯一事实默认继续保持租户级唯一；名称类唯一键按所属聚合逐项调整。
- `group_link` 只是用户可编辑、可分组的运营句柄，同一 canonical 群可以有 U1/U2 两个 owner 句柄；`wa_group` / `wa_group_invite` 仍是租户共享协议事实。
- 群链接、文件夹、WS 链接分组、链接导入批次和群批处理任务以 owner 为权限根；导入、迁组、合并和拆分不允许跨 owner 引用。
- 账号系统默认分组改为每个 owner 一份；分组名称唯一范围调整为 `(tenant_id, owner_user_id, name, is_active)`。历史无 owner 分组仅管理员可见。
- 后台任务不隐式继承 HTTP ThreadLocal；跨租户扫描可使用 SYSTEM，但读取或操作用户私有数据前必须按持久化聚合根恢复 tenant 与 SELF/ALL，SYSTEM 不能直接访问或创建私有聚合。
- 管理员虽可访问所有 owner，但在共享/转移功能上线前，新建任务不得混合不同 owner 的账号或分组。
- 营销模板和模板图片分别保存 owner；模板只能引用同 owner 图片。模板名称按 owner 唯一，历史空 owner 名称仍单独保持唯一。
- 标准拉群头像的二进制继续保存在租户本地目录，`pull_task_group_avatar_file` 只保存随机 key 的可信 owner 元数据。新上传必须写操作者 owner；普通用户只可预览、删除或绑定本人头像，管理员可预览和删除全租户头像，但新任务仍只能绑定管理员本人上传的头像。
- 历史磁盘头像没有可靠创建人且无法安全回填元数据：普通用户不可通过 key 直接访问，管理员和已授权任务的协议执行链可继续读取；任务删除与过期清理由显式内部方法执行，不依赖 HTTP DataScope。
- 普通营销与拉群营销共用 `marketing_task.owner_user_id`；建群营销使用独立的 `group_creation_marketing_task.owner_user_id`。执行项、结果和同步导出通过任务根继承权限。
- 进群任务使用 `join_task.owner_user_id`；`join_task_result` 通过任务根继承。用户详情/明细/编辑/启动/批量删除先校验任务根，协议回调和调度器保留显式内部读取。
- 进群任务创建只接受操作者本人账号/分组；管理员编辑其他 owner 的既有任务时，新的账号与分组仍必须与原任务 owner 一致。
- 标准拉群任务使用 `pull_task.owner_user_id`；草稿、设置、执行行、账号行、动作和结果通过任务根继承。列表、详情、补号、启动/停止、草稿编辑和批量删除均先做任务根范围校验，协议回调与调度器保留显式内部读取。
- 标准拉群新建只接受当前操作者本人账号分组，并由服务端写入 owner；管理员可以管理当前租户已有任务，但不能借 `ALL` 代其他 owner 创建任务。混入不可见任务 ID 的批量删除整批拒绝。
- 新建普群使用 `normal_group_creation_task.owner_user_id`；幂等键按 owner 唯一，详情/重试先授权任务根，Kafka 回执从任务恢复 owner。管理员不能用他人账号分组或群文件夹代创建。
- 推广渠道沿用既有非空 `owner_user_id`：新增时忽略前端 owner 并写当前操作者，编辑永不改变 owner；普通用户的列表、详情、编辑、删除和 CAPI 探测均按 SELF 过滤，管理员可管理全租户渠道且审计字段记录管理员本人。
- 公开落地页和公开配对入口按不可预测渠道码与可信域名执行，不依赖 HTTP DataScope；CAPI Outbox 使用 V151 持久化的 owner 快照恢复 SELF，历史 NULL owner 事件永久终止且不调用 Facebook；正式投递和登录态探测共用 scoped 敏感配置查询，不再保留无 scope Token 查询。
- 营销异步导出使用 V152 冻结创建时的 SELF/ALL；Worker 恢复范围后重新校验任务 ID，并把 tenant/DataScope 显式传播到并发群查询线程。历史缺少范围快照的作业失败关闭，要求用户重新发起。
- 渠道统计的筛选项、汇总、每日明细、CSV 数据源和人工广告数据写入均先按渠道根 owner 授权；SELF 的账号解绑统计额外约束账号 owner，管理员写入他人渠道时 owner 不变且 `updated_by` 记录管理员本人。
- 用户批量删除或导出混入不可见任务 ID 时整批拒绝；管理员可管理当前租户全部已有任务，但不能代其他 owner 创建任务。
- 群实时预览、资料修改、成员管理、metadata 手工刷新、历史群实时读取/刷新、普通建群和群主退群均要求资源已有 owner；操作账号与群链接必须同 owner，管理员的 `ALL` 不允许跨 owner 拼接协议操作。
- HTTP 入口若同时接收认证主体与线程范围，必须验证当前 `DataScope` 与可信 `AuthPrincipal` 的 actor 和 SELF/ALL 模式完全一致；缺失或伪造范围返回 `ACCESS_DENIED`。
- 前端只把 HTTP 401、业务码 `40101` / `40104` 视为登录失效；HTTP/业务 403 保留登录态。账号上线冷却、拉群草稿/等待池和普群任务恢复状态均使用可信登录响应中的 tenant/user ID 分区，身份缺失时禁止缓存。
- 每个业务切片在完成全部读取与写入入口、H2 数据库测试和同租户 U1/U2/管理员越权测试后才允许上线。

## 验证（evidence-before-done）

> 推进中记录命令、退出码、测试数和失败/跳过情况。禁止连接真库作为本地完成门禁。

- `mvn -DskipTests test-compile`：1528 个主源文件、667 个测试源文件编译成功。
- V141/Flyway/SQL 契约和 H2 真 Mapper 用例覆盖 U1、U2、管理员、历史空 owner、跨租户、缺失 scope 和 SYSTEM。
- 账号主链路定向测试覆盖列表/统计、批量上下线、迁组/删除、WS 号码导出、分组 CRUD/拆分/合并、导入及后台自动上线。
- 手工授权边界审计额外收口了普通营销、建群营销、拉群营销、历史群查询/刷新/成员操作、普通建群和标准拉群对账号/分组的跨域引用。
- 综合定向回归 356 tests 全部通过（失败 0、错误 0、跳过 0）；导入后台调度 H2 真 XML 用例单独 1 test 通过。
- 营销模板/图片切片定向回归 67 tests 全部通过（失败 0、错误 0、跳过 0），包含 3 个 H2 真 Mapper XML 用例。
- 普通营销/拉群营销任务切片定向回归 49 tests 通过；追加 owner、整批拒绝和 H2 真 Mapper 边界后 54 tests 通过，失败 0。
- 建群营销任务切片非真库回归 42 tests 通过，另有 3 个 H2 真 Mapper XML 用例；V141-V144 与 Flyway 版本/历史/SQL 契约 8 tests 通过。
- 严格排除 `DbTestBase`、`@SpringBootTest` 与已知 H2/MySQL 方言不兼容用例后的安全回归 396 tests 通过，失败 0、错误 0、跳过 0。
- 进群任务 V145 切片非真库回归 52 tests 通过，包含 3 个生产 Mapper XML H2 隔离用例、Service 整批拒绝/根授权/可信 scope、调度/回调状态机及 Flyway 版本契约。
- 标准拉群任务 V146 定向回归 171 tests 全部通过（失败 0、错误 0、跳过 0），覆盖生产 Mapper XML、SELF/ALL/SYSTEM、历史空 owner、列表/详情/草稿/创建/生命周期/补号/批量删除、控制器权限契约与 Flyway 版本契约。
- 标准拉群头像 V147 先红后绿，定向回归 34 tests 全部通过（失败 0、错误 0、跳过 0）；其中 2 个 H2 用例加载生产 Mapper XML 与租户插件，覆盖 U1/U2、管理员、缺 scope、SYSTEM、跨租户、历史无元数据文件、并发绑定、删除回收和后台清理。
- 推广渠道切片先以“伪造 owner 被原样写入”的失败用例确认缺口，再完成管理面隔离；49 tests 全部通过（34 个 Service、12 个 SQL 契约、3 个生产 Mapper XML H2 用例），覆盖 U1/U2、管理员、缺 scope、SYSTEM、跨租户、管理员编辑不转移 owner，以及公开/内部调用不依赖登录态范围。
- 推广渠道相关非真库完整回归 69 tests 通过；统计直接 JDBC 旁路另以 3 个 H2 用例覆盖 SELF/ALL、缺 scope、SYSTEM、越权写入拒绝和管理员审计。
- 群域 V148 切片完成列表/详情、分类、文件夹、WS 链接分组、导入/失败导出、迁组、注册、批量任务及健康/元数据事件关联的 owner 收口；定向 Service 回归 101 tests 和生产 Mapper XML H2 边界 11 tests 通过，覆盖 U1/U2、管理员、历史 NULL owner、跨租户、缺 scope、SYSTEM 及直接 IDOR。
- 新建普群 V149 切片 67 tests 全部通过，包含 17 个生产 Mapper XML H2 用例，覆盖 owner 级幂等、SELF/ALL、历史 NULL owner、跨租户、缺 scope/SYSTEM、详情/重试 IDOR、管理员禁止代创建和 Kafka 任务 owner 恢复。
- 历史群 V150、CAPI Outbox V151 与营销导出 V152 均新增独立 SQL 契约；Flyway 版本契约通过，迁移不根据 `created_by`、渠道或历史角色猜 owner/scope。
- 协议结果与后台调度继续收口：营销发送结果、历史群发送结果、进群/拉群协议路由、JoinTask 结果/派发、PullTask 未知结果/执行派发均从可信任务根恢复 owner；JoinTask 混合 owner 候选按 owner 拆分 outbox 批次。
- CAPI/推广渠道定向回归 58 tests、营销异步导出及并发上下文回归 28 tests、SYSTEM 特例移除后的账号导入/新建普群/账号命令回归 90 tests 全部通过。
- 本轮核心隔离组合回归 203 tests 全部通过（失败 0、错误 0、跳过 0），同时通过 4 份相关生产 Mapper XML 校验和 `git diff --check`。
- 最终迁移/认证/真实 Mapper 隔离组合回归 109 tests 全部通过（失败 0、错误 0、跳过 0），覆盖 V141-V152、SELF/ALL/SYSTEM、历史空 owner、跨租户与缺失 scope。
- 历史空 owner 执行门禁及群协议入口组合回归 167 tests 全部通过（失败 0、错误 0、跳过 0）；覆盖进群、标准拉群、普通营销、拉群营销、新建普群、群实时资料/成员、历史群刷新和群主退群。
- 最终核心隔离组合回归 309 tests 全部通过（失败 0、错误 0、跳过 0），包含账号生命周期、直接 JDBC、营销异步物料/导出、调度器 owner 恢复和本轮全部执行门禁。
- 静态收口审计确认生产代码只有 `PullTaskController` 与 `BuyerChannelStatsService` 直接使用 `JdbcTemplate`，两者均有 H2 越权测试；定时任务、Kafka/Outbox 回调要么从持久化聚合恢复 SELF，要么对历史空 owner 失败关闭，canonical 群资料事件仅写租户共享事实。
- 前端 Phase 5 焦点回归 51 tests 全部通过，`pnpm typecheck` 与 `pnpm build` 通过；全量 Node 回归仅保留既有 5 个未修改测试套件失败，本次无新增失败。记录见前端 worktree 的 `.harness/changes/2026-08-27-user-data-isolation/summary.md`。
- `mvn -DskipTests test-compile` 成功；`PullTaskMapper.xml` 的 `xmllint --noout`、用户服务未使用无 scope 生命周期查询的源码扫描以及 `git diff --check` 均通过。
- 扩展执行 786 个 PullTask 相关测试时，修复了本切片引入的 4 个旧测试假设；剩余 2 类基线问题与本分支生产差异无关：1 个测试正则把 `= NULL` 误识别为业务条件，另 8 个 Spring 上下文错误源于测试配置缺少 `GroupFolderService` bean。涉及 V146 的定向回归全部通过。
- 2026-08-27 合并 `origin/1.0.3-snapshot@6ef4e1b491eb` 后，后端候选提交为 `e37e1bd4313f`；前端候选提交为 `caf5f5f0`。合并后后端 186 个焦点测试、前端 33 个测试全部通过，后端 `test-compile`、前端 `typecheck/build`、Mapper XML 和 `git diff --check` 均通过。
- 部署前在 test1 建立完整数据库与后端/前端制品回滚点：`/home/app/armada-deploy/backups/user-data-isolation-20260827T032953Z`；数据库压缩备份通过 `gzip -t`，SHA-256 为 `9886c49f55467dfad1770f72c872cf09f628235fa57dfa5ab9c5e3946f108240`。
- 使用仓库部署脚本只发布 Armada 后端和前端，Baileys/Zhuan 均未发布；V141-V152 共 12 个迁移全部成功，Flyway 最新版本 152、失败数 0，20 个 `owner_user_id` 列已生效。首次后端制品 SHA-256 为 `c4b45e9c4cd06cc06933018357b0fa700683a3ff88c6a3de123c97140a28f734`，前端目录树 SHA-256 为 `b8dec45c336b1bec4f0f389c77dad7ce339ac80b97f7190baa7991bf6ac5a3f5`。
- test1 使用 U1、U2 和 `TENANT_ADMIN` 三个专用临时验收身份完成真实接口矩阵：账号、账号导入、账号分组、群链接/标签/文件夹/导入、营销模板/任务、进群、标准拉群、拉群营销、推广渠道等 SELF 列表只返回本人数据，管理员返回全租户数据；跨 owner 详情、编辑、复制、导出、迁组、合并和混合 ID 批删均在写入前整批拒绝。
- 真实创建了 U1/U2 同名营销模板、失败账号导入批次、独立账号/群句柄，并完成账号分组拆分继承 owner、同 owner 合并和跨 owner 管理员合并拒绝；核心 owner 关联不一致计数全部为 0。
- 历史 NULL owner 账号对普通用户表现为不存在；管理员发起上线、刷新和 probe 均返回 `40302`，`protocol_command_outbox` 前后保持 594 条，未产生协议命令。IP 维持租户共享：U1/U2/管理员列表均为 3010，统计响应 SHA-256 均为 `76b9f5cf5530d53cba1408331584aa45bf9fab1bff749aa9d3e6cd3c78c26e2b`。
- 真实运行发现历史 NULL owner 拉群未知结果每分钟产生约 3500 条重复跳过日志；已在候选 SQL 从源头排除并保留 Java fail-closed 兜底，新增回归测试后以提交 `7325cfb93784` 只重发后端。新 JAR 本地/远端 SHA-256 均为 `6d37259cdf44ba12e65f9ccbb79ba34046a6701bfb2e4582e402ade2b36fdc82`；容器 `running`、重启 0，启动后该日志为 0，crash/Flyway/死锁/锁等待错误为 0。
- 部署后再次执行 `deploy-test.sh --env test1 --check`，Armada、Baileys、Zhuan 与跨组件检查全部通过；test1 未配置精确 Kafka 元数据期望，因此该子项按环境档案跳过。
- Playwright 页面冒烟（2026-08-27）：隔离浏览器登录 U1 成功；登录首页快照约 5.2s，账号列表快照约 5.4s，页面显示“总账号数 2”且只出现 U1 的两个 canary 账号。首次进入 `#/account/index` 的导航在 10s 超时阈值后约 14.4s 返回超时，但随后快照已显示账号列表；再次进入 `#/account/group` 约 8s 超时并回到登录页，改用站内 hash 切换仍约 5s 后出现登录弹层。按浏览器自动化规则停止重试，未将该现象误判为业务数据泄漏；测试账号已禁用，U1/U2/admin canary 记录均已精确软删除。

## 部署

- 最新 `1.0.3-snapshot` 已占用 V140（canonical 群分类）；用户隔离迁移顺延为 V141-V152。
- Phase 1 的 V141 会把账号分组名称唯一范围从租户级改为 owner 级，不允许旧、新应用滚动混跑。
- Phase 2 的 V142 会为营销模板和模板图片增加 owner，并把活跃模板名称唯一范围改为 owner 级；V147 会新增拉群头像 owner 元数据表；V148 会为群运营句柄/文件夹/分组/导入批次/批处理任务增加 owner，并改写 URL、名称和 request_id 唯一键。不允许旧、新应用滚动混跑。
- Phase 3 的 V143/V144/V145/V146/V149 分别给公共营销、建群营销、进群、标准拉群和新建普群任务根增加 owner；V149 还会把新建普群幂等键改为 owner 级。旧应用产生的 NULL owner 新任务只对管理员可见，因此也不能与新应用混跑。
- V150 为历史群一次性执行增加 owner 并把幂等键改到 owner 范围；V151 为 CAPI Outbox 增加 owner 快照；V152 为营销导出作业增加创建时 SELF/ALL 快照。V151/V152 之前的历史异步记录不猜权限并失败关闭。
- 前端发布后建议存量用户重新登录一次，以保存可信 tenant/user ID；未重新登录的旧会话对私有浏览器缓存失败关闭，只影响页面状态恢复，不会放宽服务端数据权限。
- 上线必须使用维护窗口：停旧版本写流量 → 执行迁移并原子切换 owner-aware 应用 → 健康检查和越权冒烟通过 → 恢复流量。
- 新版本承接流量后禁止直接降级到不识别 owner 的旧应用；首选前向修复。结构回滚需停止写入，并先通过同名、同 URL 和同 request_id 冲突守卫。
- commit / 环境 / 部署后验证结果: 后端 `7325cfb93784`、前端 `caf5f5f0` 已部署 `test1`；后端、前端、Flyway、真实 U1/U2/管理员隔离矩阵、历史执行门禁和只读深检通过。未部署生产环境，未部署 Baileys/Zhuan。

## 遗留 / 跟进

- 共享、转移、代创建和团队/部门范围不在本次首轮范围。
- 历史无 owner 数据的管理员分配能力留待共享/转移阶段。
- 登录时选择租户属于独立认证改造，不与本次数据隔离混做。
- 当前用户私有业务域的代码隔离已完成并在 test1 通过接口、运行期和 browser-skill 页面验收：账号/分组/导入、群运营句柄/分组/文件夹/导入/批处理、营销模板与全部任务类型、推广渠道/统计/CAPI、导出、协议回调和恢复链均已收口；IP、canonical 群协议事实、国家等按需求继续租户/平台共享。生产发布、历史 owner 分配以及共享/转移仍不在本轮范围。
- browser-skill 全面页面验收（2026-08-27）：U1 登录后账号 2、账号分组仅 U1、账号导入批次 52、导入链接批次 79、营销模板 36、IP 3010；U1 搜索 U2 账号返回 0。U2 对称显示账号 1、分组/批次/链接/模板均仅 U2、IP 3010。管理员显示账号 316、分组 40、导入 53、导入链接 6、模板 36，并可打开 U2 分组账号详情；任务列表管理员总量为标准拉群 181、进群 36、普通营销 5、拉群营销 6、建群营销 0；管理员 IP 统计 3010，与 U1/U2 一致。站内路由切换保持登录态，未发现跨 owner 数据混入。
- 页面耗时记录：本轮大多数登录/切换/快照约 3–8 秒；一次拉群营销路由约 10.7 秒、管理员登录提交约 12.1 秒、管理员分组大列表快照约 11.9 秒，均最终成功且无超时。按约定对这些慢点即时告警，未重复盲试。验收结束后临时账号均禁用，所有精确 canary 活跃记录均为 0。
- 真实业务协议验收（2026-08-27）：在 test1 管理员会话中选取“测试全参号”分组内单个历史 Android 账号，浏览器提交下线后约 1.4 秒收到 `OFFLINE` Kafka 事件并回写 DB；随后提交上线，页面约 28 秒倒计时，Android coordinator 节点 03 在约 3 秒内产生 `ONLINE` 事件，后端消费后 DB 登录态为 ONLINE，手动刷新页面显示在线。该账号原始状态为在线，收尾未改变其最终状态。对同一账号执行 coordinator 只读当前群列表查询，HTTP 200、业务 Code 0、返回 31 个群，秒级耗时约 2 秒；未读取群名/成员、未发送消息、未加群。历史 NULL owner 的后台“测试全参号”分组刷新请求本身返回 HTTP 200，但业务门禁拒绝执行，未产生群同步日志，属于预期 fail-closed。coordinator `/ws/v1/auth/status/<key>` 对该账号返回“account not online”，与节点 accountCount=1 和 Armada ONLINE 事件不一致，已记录为协议状态接口口径差异，不能用该接口单独判定最终状态。临时用户恢复仅用于本轮并已再次禁用，browser-skill 活动会话为 0。
- `.harness/wiki/数据模型.md` 由真实 `information_schema` 转储自动生成且禁止手改；本地未连接真库，V140-V152 应在获准迁移后重跑 `gen_datamodel.py` 刷新该文档。
