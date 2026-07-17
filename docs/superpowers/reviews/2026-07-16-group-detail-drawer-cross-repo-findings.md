# 群详情抽屉跨仓复核问题清单

> 状态：待修复，阻断项未关闭前不建议合并或部署
>
> 复核日期：2026-07-16
>
> 工作分支：`1.0.1-snapshot`
>
> 涉及仓库：`armada`、`armada-protocol`、`wheel-saas-pure-web`
>
> 本文用途：记录问题和后续验收口径；本次复核未修改功能代码、未连接数据库、未操作远程环境或 WhatsApp 真群

## 1. 复核范围与事实源

本次复核以用户逐项确认的群详情抽屉需求为最高口径，并对照以下设计和实施记录：

- `docs/superpowers/specs/2026-07-15-group-detail-drawer-completion-design.md`
- `.harness/changes/2026-07-15-group-detail-drawer-completion.md`
- `armada` 当前未提交代码、Mapper XML、单元测试和待执行 DbTest
- `armada-protocol` 当前未提交群路由、OpenAPI、master gateway 和 Jest 测试
- `wheel-saas-pure-web` 当前未提交抽屉、API、composable 和前端测试

已确认的产品边界保持不变：

- 前端不选择执行账号，Armada 自动选择在线、仍在群内、优先管理员的账号。
- “添加其他成员”只是群权限设置，不是真正添加成员。
- “通过链接邀请”是独立权限；当前协议能力未验证时禁用，不映射成添加成员或入群审批。
- 群名称和头像修改真实 WhatsApp 后同步本地镜像；群备注只更新 Armada 本地数据。
- 群主不可被降级或踢出；成员批量操作保留逐 JID 结果，成功项不回滚。

## 2. 复核结论

当前实现存在 2 个阻断级功能问题、5 个重要问题和 1 个结构性建议。其中超时恢复链路和前端旧请求串群可能直接破坏真实状态语义，必须先解决；数据库真库测试与 WhatsApp 真群验收尚未执行，因此当前只能认定本地编译和 mock 测试已通过，不能认定跨仓功能可合并或部署。

## 3. 阻断问题

### B-01 群操作超时后的同账号回读链路没有真实接通

状态：`OPEN`

涉及位置：

- `../armada-protocol/protocol-layer/src/routes/groups.ts:145`
- `../armada-protocol/protocol-layer/src/routes/groups.ts:181`
- `../armada-protocol/protocol-layer/src/error/error-handler.ts:121`
- `armada-api/src/main/java/com/armada/platform/protocol/http/group/HttpGroupParticipantAdapter.java:111`
- `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java:470`

触发场景：

1. 成员升管理员、降管理员或踢人超过协议请求的 `timeoutMs`。
2. 群名称、头像、限时消息或权限设置由 Baileys 抛出查询超时 Boom。

当前行为：

- 协议层成员操作超时后返回 HTTP 200、`partial=true` 和空结果，不抛 `TIMEOUT`。
- Armada Adapter 把该响应当作普通成功结果返回。
- Armada Service 只在捕获 `ProtocolException.TIMEOUT` 时执行同账号回读，没有消费协议返回的 `partial` 超时语义。
- Baileys Boom 的 HTTP 状态位于 `error.output.statusCode`；协议层统一错误处理器只检查顶层 `error.statusCode`。当前依赖中的 Boom 408 没有顶层 `statusCode`，会落入 500，而不是稳定 `TIMEOUT`。

影响：

- 成员操作发生真实协议超时时不会按设计执行同账号 metadata 回读，已成功的动作也可能统一显示为 `UNKNOWN`。
- 群名称、头像、限时消息和权限设置的超时确认分支依赖 HTTP 客户端与协议端超时的竞争结果，行为不稳定。
- 当前后端测试直接 mock `ProtocolException.TIMEOUT`，没有覆盖协议 HTTP 真实返回，因此测试通过不能证明该链路已接通。

最小修复方向：

1. 在协议层统一识别 Baileys Boom 的 `isBoom`、`output.statusCode` 和结构化错误数据，将超时稳定映射为 `TIMEOUT`。
2. 为成员操作显式区分 `timedOut`、回执不完整和逐成员业务失败；不要只依赖一个含义混杂的 `partial`。
3. Armada Adapter/Service 消费真实协议超时字段，并使用本次已选账号完成同账号回读，不重新选号。
4. 增加经过 Fastify 路由、HTTP Adapter、Service 的跨层测试，禁止只 mock 最终 `ProtocolException.TIMEOUT`。

关闭条件：

- 四类写操作的协议超时均稳定进入 `GROUP_PROTOCOL_TIMEOUT` 或同账号回读确认路径。
- 成员接口返回 HTTP 200 超时回执时，Armada 能实际执行同账号回读并输出逐 JID 结果。
- 测试覆盖真实 Boom 408、成员 `timedOut`、回读成功和回读失败。

### B-02 抽屉旧异步请求可能覆盖另一个群的数据

状态：`OPEN`

涉及位置：

- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:143`
- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:168`
- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:206`
- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:290`

触发场景：

1. 打开群 A，详情请求尚未返回。
2. 关闭抽屉后打开群 B，或在请求完成前切换当前 `group`。
3. 群 B 请求先返回，群 A 请求随后返回。

当前行为：

- `loadDetail()` 捕获请求开始时的 `group`，但响应返回后不校验当前群 ID、抽屉打开周期或请求序号。
- 旧请求可以覆盖当前 `detail`、表单基线、权限、限时消息、头像和成员列表。
- 资料、头像、权限和成员操作完成后的异步回写也没有验证当前群是否仍是请求发起时的群。

影响：

- 页面可能在群 B 抽屉里展示群 A 的资料和成员。
- 用户基于被覆盖的表单继续编辑时，API 使用当前 `props.group.id`，存在把基于群 A 的内容提交给群 B 的数据完整性风险。

最小修复方向：

1. 为每次打开/切群生成请求代次，关闭抽屉和切群时使旧代次失效。
2. 所有异步响应落状态前同时校验抽屉仍打开、当前群 ID 等于请求发起群 ID、请求代次仍有效。
3. 能取消的查询使用取消信号；不能取消的写请求允许完成外部动作，但禁止回写到另一个群的页面状态。
4. 增加组件级异步乱序测试。

关闭条件：

- 详情请求乱序、关闭后重开、群 A 写请求晚于群 B 返回等场景均不能污染当前群状态。
- 组件测试证明旧响应被忽略，且不会把旧表单内容提交到新群 ID。

## 4. 重要问题

### I-01 管理员权限不足只依赖三种英文错误文案识别

状态：`OPEN`

涉及位置：

- `../armada-protocol/protocol-layer/src/routes/groups.ts:81`
- `../armada-protocol/protocol-layer/src/error/error-handler.ts:121`
- `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java:966`

触发场景：

- WhatsApp/Baileys 返回 401/403，但错误文案不是 `not-authorized`、`forbidden` 或 `not admin`。
- 错误节点只有结构化状态码，没有当前代码匹配的文本。

影响：

- 应返回的 `GROUP_PERMISSION_DENIED` 可能变成协议 500/`UNKNOWN`。
- Armada 默认分支进一步把它翻译成“群设置修改失败”或参数校验错误，无法满足“执行账号没有权限时明确提示”的需求。

最小修复方向：

- 复用统一协议错误归一化逻辑，优先判断 Boom/错误节点中的结构化 401/403，再以文本作为兼容兜底。
- 增加结构化 Boom 401/403、无文本错误和现有三种文本的测试。

### I-02 成员升降级和踢人提交期间可以重复点击

状态：`OPEN`

涉及位置：

- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:89`
- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:240`
- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:508`

触发场景：

- 成员操作请求尚未返回时，用户再次点击设置管理员、取消管理员或踢出。

影响：

- 前端可并发发送重复群管理请求。
- 协议操作锁可能返回 `ACCOUNT_BUSY`，但不能防止首个请求刚完成后第二个重复请求再次执行，页面提示也会混乱。
- 违反已确认设计中的“控件提交期间禁用重复点击”。

最小修复方向：

- 增加成员操作独立的提交状态和处理函数重入保护；请求开始时复制目标 JID 快照。
- 从请求发出到结果展示及详情回读完成前，统一禁用三个成员操作按钮。
- 增加连续双击和不同按钮交叉点击测试。

### I-03 `partial` 在协议层、Armada 和产品语义中不一致

状态：`OPEN`

涉及位置：

- `../armada-protocol/openapi/protocol-v1.yaml:3559`
- `../armada-protocol/protocol-layer/src/routes/groups.ts:122`
- `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java:665`

当前语义：

- 协议层：回执数量不足或操作等待超时才是 `partial=true`；回执完整但某个成员失败时为 false。
- Armada：只要不是全部成功就返回 `partial=true`，包括全部失败。
- 已确认产品设计：`partial` 表达一部分成功、一部分失败。

影响：

- 全部失败会被标记为“部分”。
- 协议层回执不完整和业务部分成功无法被调用方可靠区分。
- 后续审计、指标或其它调用方容易根据同名字段作出错误判断。

最小修复方向：

- 统一定义 `partial` 只表示成功与失败混合；全部成功和全部失败均为 false。
- 用独立字段表达 `timedOut` 或 `receiptIncomplete`，Armada 必须显式消费。
- 增加全成功、部分成功、全部失败、回执缺失和整体超时五类契约测试。

### I-04 头像返回 `applied=false` 时仍保留未生效的本地预览

状态：`OPEN`

涉及位置：

- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:206`
- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.vue:215`

触发场景：

- 用户选择新头像后，Armada 正常返回响应，但 `applied=false`。

当前行为：

- 页面在调用 API 前已经用 object URL 展示新图片。
- 收到 `applied=false` 后只提示“群头像未更新”并返回，没有恢复最近确认的头像，也没有立即释放失败预览 URL。

影响：

- WhatsApp 实际未更新，抽屉却继续显示新头像，形成假成功视觉状态。

最小修复方向：

- 保存上传前的已确认头像；`applied=false` 或异常时恢复该值并释放失败 object URL。
- `applied=true, mirrorSynced=false` 仍可保留本地图片预览，但必须继续显示“本地列表待刷新”的明确提示。
- 增加三种响应状态的组件测试。

### I-05 当前前端测试不能证明真实组件交互正确

状态：`OPEN`

涉及位置：

- `../wheel-saas-pure-web/src/views/group/list/components/GroupMemberDrawer.test.ts:1`
- `../wheel-saas-pure-web/src/views/group/list/composables/useGroupPermissions.test.ts:22`
- `../wheel-saas-pure-web/src/views/group/list/composables/useGroupTimedMessage.test.ts:14`

当前行为：

- 抽屉测试读取 `.vue` 源文件并做正则断言，没有挂载组件。
- composable 测试能验证单个 API 调用和回滚，但不能覆盖抽屉生命周期、异步乱序、按钮禁用、选择状态和头像预览。

影响：

- 测试数量全部通过仍会遗漏 B-02、I-02 和 I-04。

最小修复方向：

- 保留必要的范围静态断言，同时补充基于 Vue Test Utils 的组件交互测试。
- 使用可控 Promise 构造乱序响应，验证 loading、防重、关闭重开和失败回滚。

## 5. 验收阻断与剩余风险

### V-01 选号 SQL 真库测试和 WhatsApp 真群验收尚未执行

状态：`PENDING_ENVIRONMENT_CONFIRMATION`

涉及位置：

- `.harness/changes/2026-07-15-group-detail-drawer-completion.md:43`
- `armada-api/src/test/java/com/armada/group/service/GroupExecutionAccountSelectorDbTest.java:17`

未验证内容：

- 当前租户、在线、仍在群内、管理员优先和软删关系过滤是否在真实 MySQL/租户拦截器下正确生效。
- 真实 Baileys/WhatsApp 是否返回当前代码假定的管理员错误结构和逐成员状态。
- 四档限时消息、四项稳定权限、真实群名称、头像及成员升降级/踢人在真群中的最终状态。
- 协议超时、权限不足、部分成功和回读确认是否与 WhatsApp 实际一致。

执行约束：

- DbTest 前必须确认目标数据库环境。
- WhatsApp 验收前必须确认目标环境、测试账号、协议账号、测试群、管理员身份和本次操作授权。
- 未确认前不得连接远程、修改真库、部署或操作真群。

关闭条件：

- `GroupExecutionAccountSelectorDbTest` 在确认的测试数据库执行通过并保留真实输出。
- 按设计文档的真群验收清单逐项验证，并记录账号角色、预期、实际结果和必要日志证据；不得记录凭据、完整手机号或敏感材料。

## 6. 结构性建议

### S-01 `GroupDetailServiceImpl` 已接近单类硬限制，应在继续扩展前拆分职责

状态：`OPEN`

涉及位置：

- `armada-api/src/main/java/com/armada/group/service/impl/GroupDetailServiceImpl.java:46`
- `armada-api/src/main/java/com/armada/group/service/GroupDetailProtocolPorts.java:9`
- `.harness/rules/编码规范.md:130`

当前事实：

- `GroupDetailServiceImpl` 物理行数为 1108；排除注释和空行后复算为 704，当前尚未违反“单类除注释外不超过 800 行”的硬规则。
- 该类同时承担详情聚合、资料修改、群设置、成员操作、超时确认、输入校验和错误映射。
- `GroupDetailProtocolPorts` 把四个端口包装成一个构造器参数，解决了参数数量限制，但没有降低 Service 的业务职责复杂度。

风险：

- 继续增加群能力很容易突破类长度限制，并扩大回归影响面。
- 资料、设置、成员三类状态机和错误语义集中在同一类，不利于针对性测试和维护。

建议方向：

- 不在本轮问题修复中顺手大重构。
- 当后续继续增加群功能或本轮修复使类接近上限时，按真实职责拆为资料、设置、成员编排服务，详情聚合保留独立服务。
- 拆分必须删除旧路径，不保留转发型兼容 Service，也不得引入 Repository 或大而全协议客户端。

## 7. 建议修复顺序

1. 统一协议 Boom、超时和权限错误模型，先解决 B-01 与 I-01。
2. 明确成员 `partial/timedOut/receiptIncomplete` 契约，补齐 Armada 同账号回读，解决 I-03。
3. 增加抽屉请求代次和写请求回写保护，解决 B-02。
4. 增加成员操作防重和头像失败回滚，解决 I-02、I-04。
5. 补真实组件测试，解决 I-05，并把前四步回归场景锁住。
6. 确认测试环境后执行 DbTest 和 WhatsApp 真群验收，关闭 V-01。
7. 仅在职责继续增长或接近硬限制时处理 S-01，避免与功能修复混成一次大重构。

## 8. 状态维护规则

- `OPEN`：问题已确认，尚未修复或缺少验证证据。
- `IN_PROGRESS`：已开始修复，但不得视为关闭。
- `FIXED_PENDING_VERIFICATION`：代码已修复，本地自动化测试通过，仍缺 DbTest 或真群验证。
- `CLOSED`：代码、自动化测试和与风险相称的真实验证全部完成，并在本节下追加证据。
- `PENDING_ENVIRONMENT_CONFIRMATION`：需要数据库或远程环境，尚未取得明确目标和授权。

每次关闭问题必须补充：修复提交范围、关键测试命令与结果、DbTest/真群验证结果、剩余风险。不得仅因代码已修改或 mock 测试通过将问题标记为 `CLOSED`。
