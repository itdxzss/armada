# 变更记录：账号触达受限（463）按操作分流与展示修正

- 日期 / 分支 / worktree: 2026-10-08 / 每个仓库各建 `codex/reachout-restriction-scope-20261008` / 见「工作规则」
- 需求来源: perf2 账号 2758（尾号 9592）无超链记录却显示「超链发送：受限」的排查结论，用户已确认修复方向
- 状态: B1–B4 / F1–F4 / A1 / W1–W2 已实现并提交；本地验证已执行，遗留失败与限制详见下文（四仓全量未全部通过）
- 涉及仓库（基线 HEAD，均为 `1.0.3-snapshot`）:
  - 后端 `armada/` f8f1c8cb
  - 前端 `wheel-saas-pure-web/` 934c8be4
  - Android 协议 `whatsapp-server-feature-android-zhuan/` 8d05bb1
  - Web 协议 `armada-protocol/protocol-layer/` 5f8a4e5

## 目标（一句话）

WhatsApp `463 account_reachout_restricted` 只按真实被拒的操作投影成对应能力限制：消息操作 → 消息发送受限，进群 → 进群拉人受限，其它操作只记事件；能拿到平台截止时间时用平台时间；页面文案与实际拦截范围一致；两个协议都能在登录后重新拿到平台触达限制事实。

## 工作规则（Codex 必须遵守）

1. 先读工作区 `AGENTS.md`，再读各仓库规则：
   - 后端：`armada/AGENTS.md`、`.harness/agents/owner.md`、`.harness/rules/编码规范.md`、`.harness/rules/工程结构.md`；改 Mapper SQL 时再读 `.harness/rules/数据模型规范.md`。
   - 前端：`wheel-saas-pure-web/AGENTS.md`、`.harness/rules/前端架构.md`、`.harness/rules/编码规范.md`。
   - Android：`whatsapp-server-feature-android-zhuan/AGENTS.md`（§1 强制自检命令）。
   - Web 协议：仓库无 AGENTS.md，按现有代码风格和测试方式。
2. 四个仓库主工作区都有其他会话的未提交改动，**不要在主工作区改**。每个仓库从上面的基线 HEAD 建 worktree + 分支：
   `git worktree add .worktrees/reachout-restriction-scope -b codex/reachout-restriction-scope-20261008 <基线HEAD>`
   - 注意：前端 `src/views/account/index/composables/useAccountListPage.ts` 在主工作区有他人在途修改，F4 会改到同一文件，最后汇报里要提示合并时协调。
3. 只改代码和测试。**不连远程、不用 PEM、不查或改真库、不部署、不推送**。不需要 Flyway（本次没有表结构变更）。
4. 按阶段分别提交（每个仓库一到多个 commit），提交信息结尾加仓库约定的署名；不 push。
5. 每个阶段跑对应的验证，**没有真实输出不能写“通过”**。把命令和结果摘要回填到本文件「验证」一节，并勾选任务清单。
6. 日志、测试数据、文档里不得出现完整手机号、群链接、邀请码。

## 背景与证据（已在 perf2 只读核实）

时间均为 Asia/Shanghai，账号 2758（ANDROID，尾号 9592）。

| 时间 | 事实 |
|---|---|
| 14:00:38 | WhatsApp 推送触达限制：`is_active=true`，`DEFAULT`，截止 20:00:37。风控事件 573522（`account.restricted`，raw 463） |
| 14:00:59 | 任务 73 拉人返回 463，每个目标成员各一条事件，共 25 条，不是重复回放 |
| 14:14、14:29… | 每次重新登录查询限制状态，返回的 Argo 二进制里有 `1791460837`、`DEFAULT`，但 Android 解析不了，没有上报（`safemex.go:383`） |
| 14:52:36 | 人工解除，清掉了平台截止时间和生效标记 |
| 17:36:08.420 | 任务 81 拉手点链接进群（动作 12425，命令 `cmd_3668ab83f6e84826b728930b0bfc26ce`），IQ 返回 `463 account_reachout_restricted` |
| 17:36:09.435 | 风控事件 577475（`GROUP_JOIN`）→ `restrictMessageSending` → 推断消息限制到 10-09 17:36:08.420（固定 24h），`mute_status=1`，页面显示「超链发送：受限」 |
| 19:09:26 / 20:13:54 | 第二次人工解除；之后平台通知已解除 |

该账号的超链收件人、用量、统计都是 0 行，命令发件箱里也没有任何消息发送命令。

查出的缺陷：

1. `ProtocolRiskEventSinkAdapter.java:68-72` 对任何操作类型的 `ACCOUNT_REACHOUT_RESTRICTED` 都写消息发送限制（固定 24h）。
2. 这和超链选号自己的设计相矛盾（`AccountMapper.xml:135-137`）：只有平台限制时不禁超链，但一条进群 463 推断出来的限制会禁超链。
3. 进群 463 不写拉人限制，账号仍会被选为拉手或管理员去进群、拉人，而平台实际拒绝的正是这两个动作。
4. 前端把 `mute_status` 1/3 显示成「超链发送」，实际挡的是全部营销发送、管理员角色和互加联系人。筛选项、导出、枚举注释写的都是「消息发送」。
5. Android 解析不了限制生效时的 Argo 结果；Web 登录后从不主动查询。手动解除后，两边都无法重新拿到平台事实。
6. Web 独有：普通建群遇到限流（429 / rate-overlimit）被标成 `ACCOUNT_REACHOUT_RESTRICTED`（`normal-group-creation-executor.ts:369`），同样会进入第 1 条的投影。

## 关键设计决策

- **按操作类型分流，不再一律写成消息限制。** 463 是账号级信号，但当前系统的能力模型是「消息发送」和「进群拉人」两种能力，按实际被拒的操作投影。
- **进群 463 写成进群拉人限制（`mute_status` 2/3），文案叫「进群拉人」，不叫「拉群业务」。** 证据只证明点链接进群和拉人被拒；`mute_status=2` 不挡超链、群内营销和群内已有账号提权，叫「拉群业务」会让人以为整个拉群任务都不能用这个号。
- **拉人（`PARTICIPANT_ADD`）463 在风控入口只记事件。** 拉群任务的拉人结果已经在 `PullTaskPullCallParticipantResultService.java:623` 写拉人限制，并负责使粘性拉手失效、发布拉手不可用事件。`ProtocolGroupEventConsumer.java:1078` 先调风控入口再调业务处理，入口里重复写会干扰业务路径的返回值判断。被否决的方案：入口也对拉人写限制。
- **建群、账号状态、群成员查询、群健康检查只记事件。** 平台权威事实走单独的 `account.restricted`（`handleAccountRestricted`），不受影响。
- **截止时间优先用平台。** 只对 `ACCOUNT_REACHOUT_RESTRICTED` 生效：有生效中且未过期的平台限制时用平台截止时间，否则仍用 24h。`RATE_LIMITED` 不变。
- **超链和消息发送的现有行为不变。** `MESSAGE_SEND` / `MESSAGE_ACK` 继续写消息限制；超链自己的 463 处理（`HyperlinkProtocolResultService.java:178`、`HyperlinkDispatchService.java:266`）不动。
- **Android 解析 Argo 不能靠猜字节布局。** 优先复用现有 `beeper/argo-go` 解码 + 回编码逐字节校验的做法（`internal/service/cloudcontacts/page.go:25-57`）。
- **Web 只做查询补全，不改建群错误码约定。** 建群 429 → `ACCOUNT_REACHOUT_RESTRICTED` 是和后端 `NormalGroupCreationErrorMessage.java:185`、`GroupCreateRestrictionClassifier` 的现有约定，改它是跨仓库约定变更，本次不做。B1 之后它已不会再写账号限制。

## 任务清单

### 后端 `armada/armada-api`

- [x] **B1 风控投影按操作分流**：`account/service/impl/ProtocolRiskEventSinkAdapter.java:68-72`
  - 规范化后的操作类型为 `MESSAGE_SEND` 或 `MESSAGE_ACK` → `restrictMessageSending`（行为不变）。
  - `GROUP_JOIN` → `restrictionService.restrictPulling(accountId, signal.name(), occurredAt, receivedAt)`。
  - 其它（`PARTICIPANT_ADD`、`GROUP_CREATE`、`CONTACT_PREPARE`、`ACCOUNT_STATE`、`GROUP_MEMBERS_QUERY`、`GROUP_HEALTH`、空值等）→ 只 `insertIdempotent`，不写账号限制。
  - `handleAccountRestricted` 不改。
  - 更新类注释，写明分流规则和原因。
- [x] **B2 进群拉人限制优先用平台截止**：`account/service/impl/AccountOperationRestrictionServiceImpl.java:42-51`
  - 仅当 `reasonCode` 规范化后等于 `ACCOUNT_REACHOUT_RESTRICTED` 时：先读账号状态（`AccountStateMapper.selectByAccountId`）；若平台限制生效中（`platform_message_restriction_active=1`）且截止时间晚于 `max(occurredAt, now)`，则 `candidateUntil` 取平台截止时间；否则仍是 `occurredAt + 24h`。
  - 其它原因码（如 `RATE_LIMITED`）行为不变。
  - `markPullingRestricted` 本身取较大值的语义不变。
  - 这个改动同时作用于拉人结果路径（`PullTaskPullCallParticipantResultService.java:623`），这是预期效果。
- [x] **B3 列表接口返回限制来源字段**：`resources/mapper/account/AccountMapper.xml:946-955` 的账号列表 SELECT，加上对应 VO（`AccountListVoRow` 及控制器实际返回的 VO / 转换链，自行追踪）
  - 新增 `platformMessageRestrictionUntil`：仅在平台限制生效中时返回平台截止时间，否则为 null。
  - 新增 `fallbackMessageRestrictionUntil`：返回推断消息限制的截止时间。
  - 现有 `messageRestrictionUntil`、`pullingRestrictionUntil` 保持不变，不要破坏现有字段。
- [x] **B4 文案一致**：
  - `account/model/enums/AccountOperationRestrictionStatus.java:6-13`：2 的注释改为「进群与拉人受限」，3 相应调整。
  - `resources/mapper/marketing/MarketingTaskExportMapper.xml:657-659`：导出文案 `拉人受限` → `进群拉人受限`，`消息发送和拉人受限` → `消息发送和进群拉人受限`。若有断言这些文案的测试，一并更新。

### 前端 `wheel-saas-pure-web`

- [x] **F1 能力文案**：`src/views/account/index/account-display.ts:48-62`
  - `超链发送` → `消息发送`，`拉手拉人` → `进群拉人`。
  - :73 `PULLING_RESTRICTED: "拉人受限"` → `进群拉人受限`。
  - 同步更新 `account-display.test.ts:49,54`。
- [x] **F2 显示原因和截止时间来源**：`src/api/account.ts`（类型约 :247-252、映射约 :369-380）接入 B3 的两个新字段；判断逻辑放在 `account-display.ts`（纯函数 + 单测）；`components/AccountListTable.vue:228-242` 只负责渲染。
  - 消息行：有平台截止时间，且不早于推断截止时间 → 「平台下发」，否则 →「系统推断」。
  - 进群拉人行：进群拉人截止时间等于平台截止时间 → 「平台下发」，否则 →「系统推断」。
  - 单元格下方显示一次原因：`accountRestrictionReasonLabel(restriction_reason_code)`。
  - 示例：`进群拉人：受限` / `预计 10-09 17:36 恢复（系统推断）` / `原因：账号触达受限`。
  - `.vue` 文件不得超过 600 行。
- [x] **F3 筛选项文案**：`account-status-filter.ts:14-15,29-30,48-49`
  - `拉人受限` → `进群拉人受限`，`消息和拉人受限` → `消息和进群拉人受限`，码值不变。
  - 同步更新 `account-status-filter.test.ts:24-25`。
  - 检查这些文案是否被持久化到地址栏或本地状态（如 `account-query-state.ts`）。如果有，旧值也要能映射到新值。
- [x] **F4 人工解除提示**：`composables/useAccountListPage.ts:757` 附近的确认文案改成「消息发送和进群拉人」。
  - 如果选中的账号里有平台限制未到期的（平台截止时间晚于当前时间），追加一句提示：「其中 N 个账号的 WhatsApp 平台触达限制尚未到期（最晚 X），解除只清本地状态，平台仍会拒绝进群、拉人和新会话」。
  - 同步修改 `src/api/account.ts:503` 的注释。
  - 这个文件主工作区有他人在途改动，见「工作规则」第 2 条。

### Android `whatsapp-server-feature-android-zhuan`

- [x] **A1 解析限制生效时的 Argo 结果**：`internal/service/app/safemex.go:383-406`（`parseReachoutTimelockArgo` / `decodeKnownReachoutTimelockArgo`），未限制常量在 :115。
  - 按 `internal/service/cloudcontacts/page.go:25-57` + `schema.go` 的方式，为 `xwa2_fetch_account_reachout_timelock` 定义 wire schema，用 argo-go 解码，再回编码逐字节比对。
  - schema 必须让下面两个真实样本都能逐字节回编码通过：
    - 未限制（现有常量）：`04 02 30 0c 00 00 02 01 03 03`
    - 限制生效（perf2 2026-10-08 14:14:34 日志，27 字节完整报文）：`04 14 31 37 39 31 34 36 30 38 33 37 0e 44 45 46 41 55 4c 54 0c 00 02 14 0e 03 03`，期望解出 `is_active=true`、`time_enforcement_ends="1791460837"`、`enforcement_type="DEFAULT"`
  - 解码或校验失败时保持现状：返回 nil，并记一条 WARN（长度 + 十六进制前缀）。**不得**用「截止时间晚于现在」去猜生效状态。
  - 如果找不到可信 schema，或两个样本无法同时回编码通过：停下，在本文件「遗留」里写明，不要提交手写的字节匹配。
  - 解出生效状态后，走现有的 `waapp.go:1575` → `notifyAccountRestriction` 上报路径，不另开新路径。
- [x] **A1 测试**：`internal/service/app` 下新增解析测试，覆盖两个样本、未知字节返回 nil、回编码不一致返回 nil。

### Web 协议 `armada-protocol/protocol-layer`

- [x] **W1 上线后查询一次触达限制**：`src/worker/account-manager.ts:2170` 的 `connection === 'open'` 分支，并且是 `transitionedOnline` 为真时。
  - 尽力调用 `sock.fetchAccountReachoutTimelock()`：失败只记 warn，不阻塞也不改变上线状态迁移。
  - Baileys 会发出 `connection.update { reachoutTimeLock }`，再由 `src/worker/event-bridge.ts:152-166` 发布 `account.restricted`（含 `isActive=false`），不要另写一套发布逻辑。
  - 后端 `ProtocolAccountEventConsumer.epochMillisAny` 已支持 ISO 格式的 `restrictedUntil`，不需要改后端。
- [x] **W2 进群或拉人遇到 463 后补查（带节流）**：
  - 触发条件：进群结果原因码为 `ACCOUNT_REACHOUT_RESTRICTED`（`src/routes/group-join-error.ts:87`、`src/commands/group-join-executor.ts`），或拉人结果原因码为 `ACCOUNT_REACHOUT_RESTRICTED`（`src/commands/group-participants-executor.ts:622-626,686`）。
  - 按账号节流：同一账号同一时间最多一个查询在进行，5 分钟内最多查一次。
  - 不得延迟或改变进群、拉人结果事件本身。
  - **不要**由普通建群那条（429 映射成的）`ACCOUNT_REACHOUT_RESTRICTED` 触发。
- [x] **W 测试**：在现有的 `account-manager*.test.ts`、`group-join-executor.test.ts`、`group-participants-executor.test.ts`、`event-bridge.test.ts` 里补用例：
  - 上线只查一次，查询失败不影响上线；
  - 463 触发查询且节流生效；
  - 普通建群 429 不触发；
  - 查询结果会发布 `account.restricted`。

## 不在本次范围（需用户另行确认）

- Web 普通建群 429 改发 `RATE_LIMITED` 或专用原因码（跨仓库约定变更，需同时改 `NormalGroupCreationErrorMessage` 和 `GroupCreateRestrictionClassifier`）。
- 平台通知「已解除」时，同步清掉触达受限推断出来的限制（`AccountStateMapper.xml:396-419` 目前不碰推断限制）。
- 平台限制生效本身是否也直接写进群拉人限制（B1 之后，第一次进群 463 就会写入，并按 B2 取平台截止时间）。
- 进群任务的执行账号是否也要按 `mute_status` 过滤。
- 两个协议都把拉人 401 / 412 映射成触达受限是否正确（需协议侧确认）。
- 历史数据处理、部署、真库操作。

## 验收标准

1. 进群返回 463（Android 或 Web）：
   - 记录风控事件；
   - 写进群拉人限制（`mute_status` 2，原来是 1 则变 3）；
   - 推断消息限制不变；
   - 超链选号仍能选到该账号；
   - 页面显示「进群拉人：受限 …（系统推断）」。
2. 拉群任务里拉人返回 463：行为和现在一致（写拉人限制、粘性拉手失效、发布拉手不可用事件），且不写消息限制。
3. 消息发送返回 463：行为和现在一致。
4. 建群、账号状态、群成员查询、群健康检查携带 `ACCOUNT_REACHOUT_RESTRICTED`：只记事件，账号状态不变。
5. 平台限制生效中（截止时间 T）时，进群或拉人 463 写入的进群拉人限制截止时间是 T，不是 24h；`RATE_LIMITED` 仍是 24h。
6. Android 登录查询拿到限制生效样本时，上报 `account.restricted` 为生效，`restrictedUntil=1791460837000`；未限制样本的行为不变；未知报文返回 nil 并记 WARN。
7. Web 上线后查询一次并发布 `account.restricted`；查询失败不影响上线；463 补查有节流。
8. 人工解除时，若账号的平台限制未到期，会弹出提示。

## 验证（evidence-before-done）

> 每个阶段完成后回填：命令 + 真实输出摘要。

- 后端，先跑针对性测试：
  `cd armada-api && mvn -Dtest='ProtocolRiskEventSinkAdapterTest,AccountPullerRestrictionServiceH2Test,ProtocolGroupEventConsumerTest,ProtocolAccountEventConsumerTest,HyperlinkDispatchServiceTest,HyperlinkProtocolUnknownResultTest' test`
  - B1 需在 `ProtocolRiskEventSinkAdapterTest` 补：`GROUP_JOIN` → 写拉人限制、不写消息限制；`PARTICIPANT_ADD`、`GROUP_CREATE`、`ACCOUNT_STATE` → 只记事件；`MESSAGE_SEND` → 写消息限制（现有用例保留）。
  - B2 需在 H2 测试补：平台限制生效时取平台截止时间；没有平台限制或已过期时取 24h；`RATE_LIMITED` 不受影响。
  - B3 需补 H2 或 Mapper 测试，覆盖两个新字段。
  - 最后跑 `mvn test`。如果太慢或有与本次无关的已知失败，如实记录。
- 前端：`pnpm typecheck`、`pnpm test`、`pnpm lint`（注意 lint 会自动修复，提交前检查差异）。
- Android：按 AGENTS.md §1 执行 `gofmt -w <改动文件>`、`go vet ./...`、`go build ./...`、`go test ./...`。
- Web：`cd protocol-layer && npm run lint && npm run test:unit -- <相关测试路径>`，最后跑 `npm test`。

### 已完成阶段的实际执行记录（2026-10-08，Asia/Shanghai）

所有修改来自指定基线的独立 `.worktrees/reachout-restriction-scope`，分支均为 `codex/reachout-restriction-scope-20261008`。本文件只在后端 worktree 回填；原主工作区文档保持原样，避免覆盖在途文件。提交统一使用 `Co-authored-by: Codex <noreply@openai.com>`；四仓规则及近期历史未找到更具体的强制署名格式。

#### 后端 B1/B2 与 B3/B4（提交 `a73858c9`、`12d70b5a`）



以下 Maven 命令均在 `/Users/daishuaishuai/IdeaProjects/armada/.worktrees/reachout-restriction-scope/armada-api` 执行，`-o` 使用本地依赖缓存。

**TDD**

- `mvn -o -Dtest='ProtocolRiskEventSinkAdapterTest,AccountPullerRestrictionServiceH2Test' test`：exit1，45 tests，1 failure/24 errors，Mockito沙箱动态attach失败；日志 `/private/tmp/reachout-backend-b12-red.log`。
- `mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='ProtocolRiskEventSinkAdapterTest,AccountPullerRestrictionServiceH2Test' test`：exit1，45 tests，13 failures/0 errors；准确复现分流错误及平台截止20,000却写86,401,000。日志 `/private/tmp/reachout-backend-b12-red-agent.log`。
- 默认JDK27上的指定6类回归：135 tests，0 failures/24 errors，原有mock类受Byte Buddy不支持Java27影响；日志 `/private/tmp/reachout-backend-b12-green.log`。随后显式使用已安装Microsoft JDK17，无依赖升级。
- `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='ProtocolRiskEventSinkAdapterTest,AccountPullerRestrictionServiceH2Test,ProtocolGroupEventConsumerTest,ProtocolAccountEventConsumerTest,HyperlinkDispatchServiceTest,HyperlinkProtocolUnknownResultTest,AccountRestrictionListContractH2Test' test`：exit1，139 tests，4 failures/0 errors；指定6类135 tests全绿，B3新增4例因JSON缺字段失败。日志 `/private/tmp/reachout-backend-b12-green-b3-red.log`。
- `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='MarketingTaskExportMapperH2Test' test`：exit1，3 tests，1 failure/0 errors，实际H2表达式返回旧文案。日志 `/private/tmp/reachout-backend-b4-red.log`。

**阶段通过**

- `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='ProtocolRiskEventSinkAdapterTest,AccountPullerRestrictionServiceH2Test,ProtocolGroupEventConsumerTest,ProtocolAccountEventConsumerTest,HyperlinkDispatchServiceTest,HyperlinkProtocolUnknownResultTest' test`：exit0，135 tests，0 failures/0 errors/0 skipped，24.226s；日志 `/private/tmp/reachout-backend-b12-final.log`。
- `xmllint --noout --nonet src/main/resources/mapper/account/AccountMapper.xml src/main/resources/mapper/marketing/MarketingTaskExportMapper.xml`：exit0，无错误输出。
- `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dtest='AccountRestrictionListContractH2Test,AccountConverterTest,AccountOperationRestrictionListProjectionSqlTest,MarketingTaskExportMapperH2Test,MarketingTaskExportSqlContractTest' test`：exit0，21 tests，0 failures/0 errors/0 skipped，19.646s；日志 `/private/tmp/reachout-backend-b34-green.log`。包括完整列表链4例、转换4例、列表SQL1例、导出H2 3例、导出SQL9例。
- `git diff --check`：exit0。

**隔离全量（未通过，不得称无条件 mvn test 通过）**

- `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Dsurefire.excludesFile=/private/tmp/reachout-backend-safe-excludes.txt test`：exit1，1571 tests，4 failures/11 errors/0 skipped；fork在HistoricalGroupMaterialParserTest处abort134；日志 `/private/tmp/reachout-backend-full-safe.log`。
- `JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o '-DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true' -Dsurefire.excludesFile=/private/tmp/reachout-backend-safe-excludes.txt test`：exit1，5199 tests，20 failures/24 errors/0 skipped，1:14；headless消除了前述fork中断。日志 `/private/tmp/reachout-backend-full-headless.log`。
- 排除89个源码类（包括DbTestBase基类），理由是完整SpringBoot上下文/DbTestBase可能连真实库，或Testcontainers可能启动容器及下载镜像；准确清单在 `/private/tmp/reachout-backend-safe-excludes.txt` 和 `/private/tmp/reachout-backend-exclusions.md`。这些是排除，不计为JUnit skipped。
- 全量中本次相关类全部绿色：风控入口24、限制H2 21、列表全链4、原有超链候选H2 20、原有拉人结果业务50、营销导出H2 3。
- 9个errors为沙箱禁止本地监听（GrizzlySmsConfigurationTest5、HttpProtocolReadyProbeTest2、HttpFacebookCapiClientTest2）。
- MarketingGroupBanMapperH2Test有1个failure：原有XML解析尝试访问mybatis.org，DNS被沙箱阻止、未连接成功；发现后未再运行该类。此事实需记录，不能笼统宣称所有测试均无外连尝试。
- 其余19 failures/15 errors为现有断言、H2夹具缺列/缺表、SQL方言、状态口径问题，已按如下基线复测验证；未扩大修复范围。

**基线复测**

只在当前worktree临时将本次7个生产文件置换为f8f1c8cb原文；其余生产文件本来就与基线一致。Python finally恢复了7个提交版文件；`git diff --exit-code HEAD -- armada-api/src/main` exit0。没有切换分支、没有改主工作区。

`JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o '-DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true' -Dtest=com.armada.boot.config.GrizzlySmsConfigurationTest,com.armada.group.HistoricalGroupPreviewSchemaSqlTest,com.armada.group.mapper.GroupMembershipCountSemanticsMapperH2Test,com.armada.group.mapper.GroupParticipantRolePrecedenceSqlTest,com.armada.group.service.impl.HistoricalGroupPullWorkerImplTest,com.armada.marketing.grouppull.service.GroupPullMarketingMaterialEntryServiceTest,com.armada.marketing.mapper.GroupCreationMarketingTaskMapperSqlShapeTest,com.armada.platform.protocol.process.HttpProtocolReadyProbeTest,com.armada.promotion.channel.service.impl.HttpFacebookCapiClientTest,com.armada.security.BusinessControllerAuthorizationContractTest,com.armada.task.mapper.PullTaskGroupMarketingGroupMapperInMemoryTest,com.armada.task.mapper.PullTaskLifecycleMapperInMemoryTest,com.armada.task.mapper.PullTaskMapperBusinessConditionTest,com.armada.task.mapper.PullTaskNormalLinkSchemaSelfTest,com.armada.task.scheduler.PullTaskExecutionEndToEndIntegrationTest,com.armada.task.service.PullTaskStandardCreateServiceTest,com.armada.task.service.PullTaskStandardSettingWriterTest,com.armada.testsupport.MysqlModeMapperInMemoryTest test`

exit1，151 tests，19 failures/24 errors/0 skipped，32.796s，正好复现当前全量中除外部DTD那一例之外的失败/错误。日志 `/private/tmp/reachout-backend-baseline-failures.log`；原始逐参数命令 `/private/tmp/reachout-backend-baseline-command.txt`。GroupMembershipCountSemanticsMapperH2Test缺的是基线已查询的a.declared_account_type列和account_creator_deletion表，基线同样2 errors，不是本次B3新增字段导致。


**当前提交最终复核**

恢复7个生产文件后，重新编译当前功能提交并执行：

`JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home mvn -o '-DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -Djava.awt.headless=true' -Dtest='ProtocolRiskEventSinkAdapterTest,AccountPullerRestrictionServiceH2Test,ProtocolGroupEventConsumerTest,ProtocolAccountEventConsumerTest,HyperlinkDispatchServiceTest,HyperlinkProtocolUnknownResultTest,AccountRestrictionListContractH2Test,AccountConverterTest,AccountOperationRestrictionListProjectionSqlTest,MarketingTaskExportMapperH2Test,MarketingTaskExportSqlContractTest' test`

exit0，156 tests，0 failures/0 errors/0 skipped，21.125s，BUILD SUCCESS。日志 `/private/tmp/reachout-backend-final-feature.log` 已脱敏。`git diff --check`、`git diff --exit-code HEAD -- armada-api/src/main` 均exit0；验证时仅本实施记录尚未提交，代码和测试均已提交。

<details>
<summary>隔离全量排除的 89 个源码类（完整可重建 excludesFile）</summary>

以下内容原样保存为 `/private/tmp/reachout-backend-safe-excludes.txt` 即可重放上述隔离命令；排除原因逐类记录在 `/private/tmp/reachout-backend-exclusions.md`。DbTestBase / 完整 SpringBoot 上下文可能连接真实数据库，Testcontainers 类可能启动容器和下载镜像，均不在本次授权范围。

```text
com/armada/account/AccountSchemaDbTest.class
com/armada/account/AccountTenantIsolationDbTest.class
com/armada/account/controller/AccountControllerDbTest.class
com/armada/account/controller/AccountImportControllerDbTest.class
com/armada/account/dispatch/AccountImportOnlineDispatcherDbTest.class
com/armada/account/mapper/AccountBatchTargetMapperDbTest.class
com/armada/account/mapper/AccountGroupMapperDbTest.class
com/armada/account/mapper/AccountImportListMapperDbTest.class
com/armada/account/mapper/AccountImportWriteMapperDbTest.class
com/armada/account/mapper/AccountListMapperDbTest.class
com/armada/account/mapper/AccountOnlineAttemptLogMapperDbTest.class
com/armada/account/mapper/AccountOnlineMapperDbTest.class
com/armada/account/mapper/AccountStatsMapperDbTest.class
com/armada/account/service/AccountImportServiceImplDbTest.class
com/armada/account/service/AccountMutationDbTest.class
com/armada/account/service/AccountOnlineCommandServiceImplDbTest.class
com/armada/account/service/AccountProtocolLookupServiceDbTest.class
com/armada/account/service/AccountStateEventServiceImplDbTest.class
com/armada/admin/SystemManagementSchemaDbTest.class
com/armada/admin/controller/CountryControllerDbTest.class
com/armada/admin/mapper/SystemManagementMapperDbTest.class
com/armada/group/AccountGroupMembershipStatusMigrationDbTest.class
com/armada/group/GroupCanonicalClassificationMigrationMySqlTest.class
com/armada/group/GroupDataModelFoundationMigrationMysqlTest.class
com/armada/group/mapper/GroupLinkImportDetailMapperDbTest.class
com/armada/group/mapper/GroupLinkLabelMapperDbTest.class
com/armada/group/mapper/GroupLinkMapperDbTest.class
com/armada/group/mapper/GroupListDataModelMigrationDbTest.class
com/armada/group/mapper/HistoricalGroupPullPersistenceDbTest.class
com/armada/group/service/AccountGroupMembershipStatusServiceDbTest.class
com/armada/group/service/GroupExecutionAccountSelectorDbTest.class
com/armada/group/service/GroupLinkRegistryServiceDbTest.class
com/armada/group/service/HistoricalGroupPullRecoveryDbTest.class
com/armada/group/service/impl/AccountGroupControlledBatchMySqlTest.class
com/armada/group/service/impl/AccountGroupCurrentSnapshotPersistenceMySqlTest.class
com/armada/group/service/impl/GroupCurrentLocalWriteMySqlTest.class
com/armada/group/service/impl/GroupLinkImportServiceDbTest.class
com/armada/group/service/impl/GroupLinkRegistryBatchMySqlTest.class
com/armada/group/service/impl/GroupLinkRegistryServiceImplTest.class
com/armada/group/service/impl/GroupListCurrentMapperMySqlTest.class
com/armada/group/service/impl/GroupMetadataPatchServiceMySqlTest.class
com/armada/group/service/impl/HistoricalGroupSendResultServiceImplDbTest.class
com/armada/hyperlink/task/HyperlinkRuntimeConcurrencyMySqlTest.class
com/armada/hyperlink/task/service/HyperlinkAccountStatQueryMySqlTest.class
com/armada/marketing/GroupCreationMarketingMigrationDbTest.class
com/armada/marketing/MarketingKafkaRoundSendMigrationDbTest.class
com/armada/marketing/MarketingTaskDataModelMigrationDbTest.class
com/armada/marketing/controller/MarketingTaskControllerDbTest.class
com/armada/marketing/grouppull/GroupPullMarketingRecoveryDbTest.class
com/armada/marketing/grouppull/GroupPullMarketingSchemaDbTest.class
com/armada/marketing/grouppull/GroupPullMarketingTenantIsolationDbTest.class
com/armada/marketing/grouppull/mapper/GroupPullMarketingMapperDbTest.class
com/armada/marketing/mapper/GroupCreationMarketingTaskMapperDbTest.class
com/armada/marketing/mapper/MarketingAccountOccupancyMapperDbTest.class
com/armada/marketing/mapper/MarketingRoundMapperDbTest.class
com/armada/marketing/mapper/MarketingTemplateFileMapperDbTest.class
com/armada/marketing/scheduler/MarketingRoundWorkerDbTest.class
com/armada/marketing/service/AccountDynamicNewGroupImmediateMarketingDbTest.class
com/armada/marketing/service/GroupCreationMarketingTaskServiceImplTest.class
com/armada/marketing/service/MarketingSendResultServiceImplDbTest.class
com/armada/marketing/service/MarketingTaskAccountTreeDbTest.class
com/armada/marketing/service/MarketingTaskCreateReadDbTest.class
com/armada/marketing/service/MarketingTaskMaterialUpdateDbTest.class
com/armada/marketing/service/MarketingTaskMutationDbTest.class
com/armada/marketing/service/MarketingTemplateDeletionDbTest.class
com/armada/platform/country/mapper/CountryMapperDbTest.class
com/armada/platform/protocol/mapper/ProtocolCommandOutboxMapperDbTest.class
com/armada/platform/protocol/mapper/ProtocolCommandOutboxSchemaDbTest.class
com/armada/platform/tenant/mapper/TenantMapperDbTest.class
com/armada/promotion/PromotionDataModelMigrationDbTest.class
com/armada/promotion/channel/mapper/PromotionChannelRuntimeMapperDbTest.class
com/armada/promotion/pairing/mapper/PromotionCapiEventOutboxMapperDbTest.class
com/armada/promotion/pairing/mapper/PromotionCapiEventOutboxSchemaDbTest.class
com/armada/promotion/template/mapper/PromotionTemplateMapperDbTest.class
com/armada/resource/mapper/IpProxyMapperDbTest.class
com/armada/resource/mapper/IpProxyStatsMapperDbTest.class
com/armada/task/PullTaskNormalLinkCollationDbTest.class
com/armada/task/controller/JoinTaskControllerDbTest.class
com/armada/task/mapper/JoinTaskDispatchMapperDbTest.class
com/armada/task/mapper/JoinTaskMapperDbTest.class
com/armada/task/mapper/JoinTaskMigrationDbTest.class
com/armada/task/mapper/JoinTaskResultMapperDbTest.class
com/armada/task/mapper/PullTaskGroupMarketingOccupancyMySqlTest.class
com/armada/task/service/JoinTaskCreateDbTest.class
com/armada/task/service/JoinTaskMutationDbTest.class
com/armada/task/service/JoinTaskReadDbTest.class
com/armada/testsupport/DbTestBase.class
com/armada/testsupport/EpochMillisSchemaDbTest.class
com/armada/testsupport/HarnessSmokeDbTest.class
```

</details>

#### 前端 F1–F4（提交 `0753fc3a`）

工作目录：`wheel-saas-pure-web/.worktrees/reachout-restriction-scope`。本机依赖经 `cp -cR ../../node_modules node_modules` 克隆复用，没有安装或下载。pnpm 11 默认的运行前依赖检查曾在 no-TTY 提示处中止，后续命令统一设 `pnpm_config_verify_deps_before_run=false`，阻止自动安装。

| 真实命令 | 真实结果 |
|---|---|
| `pnpm_config_verify_deps_before_run=false pnpm typecheck` | exit 0；`tsc --noEmit && vue-tsc --noEmit --skipLibCheck` 无诊断 |
| `pnpm_config_verify_deps_before_run=false pnpm test` | exit 1；790 tests / 715 pass / 75 fail；Node v23.11.0 加载 `nprogress.css` 报 `ERR_UNKNOWN_FILE_EXTENSION`，并有既有断言失败 |
| `pnpm_config_verify_deps_before_run=false pnpm lint` | exit 0；ESLint、Prettier、Stylelint 均执行；自动修复造成的 17 个无关文件差异已在本 worktree 恢复 |
| `pnpm_config_verify_deps_before_run=false pnpm build` | exit 0；`built in 46.13s`，bundle 5.82 MB |
| `TZ=UTC node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account.test.ts src/views/account/index/account-display.test.ts src/views/account/index/account-status-filter.test.ts src/views/account/index/components/AccountListTable.test.ts src/views/account/index/composables/useAccountListPage.test.ts` | exit 0；50 tests / 50 pass；包括平台与推断时间相差 1ms、时区差异和人工解除提示边界 |
| `node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test 'src/**/*.test.ts'` | exit 1；1143 tests / 1135 pass / 8 fail |

后一个命令使用仓库已有测试 loader，8 项失败集中于未改的 `resource-asset`（3）、`useGroupPermissions`（3）、`MarketingTemplateDrawer`（1）、`GroupMarketingCreateDrawer`（1）。基线 `934c8be4` 经 `git archive` 导出到 worktree 内忽略的 `node_modules/.cache/reachout-baseline`，基线及当前树均执行：

```bash
node --import tsx --import ./src/api/__tests__/node-test-alias.mjs --test src/api/resource-asset.test.ts src/views/group/list/composables/useGroupPermissions.test.ts src/views/material/marketing-template/components/MarketingTemplateDrawer.test.ts src/views/task/group-marketing/components/GroupMarketingCreateDrawer.test.ts
```

两边均 exit 1，31 tests / 23 pass / 相同 8 fail。`AccountListTable.vue` 为 441 行。完整记录 `/private/tmp/reachout-frontend-evidence.md`，日志 `/private/tmp/reachout-frontend-*.log`。

#### Android A1（提交 `a80cde6`）

工作目录：`whatsapp-server-feature-android-zhuan/.worktrees/reachout-restriction-scope`。Go 本地已装版本 `go1.26.5`；`go.mod` 的 1.25.1 和全部依赖保持不变。下列 Go 命令统一前置：

```bash
GOPROXY=off GOSUMDB=off GOTOOLCHAIN=local GOCACHE=/private/tmp/reachout-go-cache
```

schema 直接用现有 `argo-go v1.1.2` 的 `wirecodec.DecodeWireTypeStoreFile` 从本地 Android 2.26.36.73 客户端资源提取，操作 `FetchReachoutTimelockQuery`。资源为 `whatsapp-registration-helper/apk-analysis/2.26.36.73/apktool/assets/whatsapp-android-mex_argo_wire_types.argo`，SHA256：`81dd65853bfe694c0cd5440f63534961386d22f5f6d59e30c34344a718c62d4f`。无手写字节匹配、无 query ID 修改。

提取验证真实命令（执行时 probe 位于 worktree；之后案件移到 `/private/tmp/reachout-android-case/`，不提交临时工具）：

```bash
go run ./work/reachout-argo-20261008/probe.go /Users/daishuaishuai/IdeaProjects/whatsapp-registration-helper/apk-analysis/2.26.36.73/apktool/assets/whatsapp-android-mex_argo_wire_types.argo
```

exit 0；`entries=223`；`sample_length=10 roundtrip_exact=true` 和 `sample_length=27 roundtrip_exact=true`。生效样本明确解出 `is_active=true`、`time_enforcement_ends="1791460837"`、`enforcement_type="DEFAULT"`。采用现有 cloudcontacts 的头 flags 4→8 适配，解码后重新编码并比对完整报文。新测试在旧实现下曾真实失败，active/past-deadline 两项返回 nil。

| 真实命令 | 真实结果 |
|---|---|
| `gofmt -w internal/service/app/safemex.go internal/service/app/reachout_timelock_argo.go internal/service/app/reachout_timelock_schema.go internal/service/app/reachout_timelock_argo_test.go` | exit 0；后续 `gofmt -l` 无输出 |
| `go vet ./...` | exit 0，无输出 |
| `go build ./...` | exit 0，无输出 |
| `go test ./...` | exit 1；miniredis / httptest 回环监听被沙箱拒绝，另有 `pkg/noise` 8 项失败 |
| `go test -v ./internal/service/app -run '^(TestDecodeKnownReachoutTimelockArgo|TestParseReachoutTimelockArgoRejectsUnknownWithWarning|TestDecodeKnownReachoutTimelockArgoRejectsRoundtripMismatch)$' -count=1` | exit 0；3 个顶层测试（含 3 个样本子测试）PASS，`ok ws-go/internal/service/app 0.022s` |

审查追加执行新增解析及原有通知/事件转换测试：

```bash
go test -v ./internal/service/app ./internal/armada -run '^(TestDecodeKnownReachoutTimelockArgo|TestParseReachoutTimelockArgoRejectsUnknownWithWarning|TestDecodeKnownReachoutTimelockArgoRejectsRoundtripMismatch|TestHandleAccountRestrictedPublishesActiveAndClearedTransitions|TestBuildAccountRestrictedEventCarriesObservedAndroidFacts|TestBuildAccountRestrictedEventPreservesActiveFalse|TestBuildAccountRestrictedEventRejectsMissingCorrelationOrData)$' -count=1
```

exit 0，7 个顶层测试 PASS；app 0.016s，armada 0.026s。既有通知测试有空 URL `/event` 的 RESTY WARN，没有连接远程。现有 `waapp.go` 登录查询→`notifyAccountRestriction` 和 `BuildAccountRestrictedEvent` 链路保留，既有秒转毫秒对应样本 `restrictedUntil=1791460837000`。未做实际账号在线验收。

基线用 `git archive 8d05bb1 go.mod go.sum pkg/noise` 导出到 `/private/tmp/reachout-android-baseline`，在该目录及当前树分别执行 `go test ./pkg/noise`，均 exit 1，`6 passed, 8 FAILED`；相同失败为 `TestHandshakeRollback`、`TestNN`、`TestVectors`、`TestXX`、`Test_NNpsk0`、`Test_Npsk0`、`Test_XXpsk0`、`Test_Xpsk0`。其中 `TestVectors` 缺 `vectors.txt`。

申请解除沙箱运行未过滤全量 Go 测试被自动审批拒绝：未逐项排除真库、容器或外部服务访问可能性。没有重试被拒绝的全量动作；后续仅做沙箱内定向验证和基线复现。不能把 Go 全量标为通过。完整记录 `/private/tmp/reachout-android-evidence.md`，日志 `/private/tmp/reachout-android-*.log`。

#### Web W1 / W2（提交 `bc5d7ee`、`51c4714`，日志复核 `a3731c8`）

工作目录：`armada-protocol/.worktrees/reachout-restriction-scope/protocol-layer`。通过 worktree 内临时 node_modules 软链接复用本机依赖，未下载，验证后只移除软链接。本地 Baileys 7.0.0-rc13 已确认 `fetchAccountReachoutTimelock()` 会 emit `connection.update { reachoutTimeLock }`，true/false 均交给既有 event bridge。

| 真实命令 | 真实结果 |
|---|---|
| `npm run lint`（W1/W2 各运行一次） | 均 exit 0；`tsc --noEmit` 无诊断 |
| `npm run test:unit -- --runInBand src/worker/account-manager.heartbeat.test.ts src/worker/event-bridge.test.ts` | exit 0；2 suites / 113 tests 全过，3.148s |
| `npm run test:unit -- --runInBand src/worker/account-manager.heartbeat.test.ts src/worker/event-bridge.test.ts src/commands/group-join-executor.test.ts src/commands/group-participants-executor.test.ts src/commands/normal-group-creation-executor.test.ts src/commands/worker-consumer.test.ts src/routes/groups-join.test.ts src/routes/group-join-error.test.ts src/routes/groups-participants-mutation.test.ts` | exit 0；9 suites / 329 tests 全过，6.425s |
| `npm test`（首轮沙箱内） | exit 1；124 suites pass / 1 fail；1507 tests pass / 6 fail，失败均为 dashboard `127.0.0.1` 监听 EPERM |
| `npm test`（自动审批允许的本机测试重跑） | exit 0；125 suites / 1513 unit tests 全过，11.161s；CLI 9 pass / 0 fail；crypto-loopback 7 pass / 0 fail |

最后日志复核提交 `a3731c8` 将本次新增查询失败告警改为仅保留 `accountIdSuffix` 末四位和固定 `source`，不记录可能夹带完整账号/JID 的原始异常。真实执行 `npm run lint` exit 0；`npm run test:unit -- --runInBand src/worker/account-manager.heartbeat.test.ts` exit 0，1 suite / 92 tests 全过，2.779s。仅日志字段和断言变化，没有重复全量测试；上表全量结果对应 `51c4714`。

新增覆盖上线单次查询及失败不阻塞、同账号 join/add 共用五分钟冷却、跨五分钟仍 in-flight 抑制、失败冷却、不同账号独立、worker 装配、HTTP join/add、普通建群 429 及 promote/remove 不补查、查询 true/false 事件。完整记录 `/private/tmp/reachout-web-evidence.md`；两轮全量输出 `/private/tmp/reachout-web-npm-test.log` 和 `/private/tmp/reachout-web-npm-test-unrestricted.log`。

### 分支、提交与改动文件

四仓同名功能分支：`codex/reachout-restriction-scope-20261008`。以下文件路径均相对于各仓 worktree 根目录。后端另新增本实施记录。所有提交均仅保留本地。

**后端 `armada`**

```text
a73858c9 fix(account): scope reachout restrictions by rejected operation
12d70b5a fix(account): expose restriction sources and align capability labels
```

```text
armada-api/src/main/java/com/armada/account/model/enums/AccountOperationRestrictionStatus.java
armada-api/src/main/java/com/armada/account/model/vo/AccountListVO.java
armada-api/src/main/java/com/armada/account/model/vo/AccountListVoRow.java
armada-api/src/main/java/com/armada/account/service/impl/AccountOperationRestrictionServiceImpl.java
armada-api/src/main/java/com/armada/account/service/impl/ProtocolRiskEventSinkAdapter.java
armada-api/src/main/resources/mapper/account/AccountMapper.xml
armada-api/src/main/resources/mapper/marketing/MarketingTaskExportMapper.xml
armada-api/src/test/java/com/armada/account/mapper/AccountRestrictionListContractH2Test.java
armada-api/src/test/java/com/armada/account/service/impl/AccountPullerRestrictionServiceH2Test.java
armada-api/src/test/java/com/armada/account/service/impl/ProtocolRiskEventSinkAdapterTest.java
armada-api/src/test/java/com/armada/marketing/export/mapper/MarketingTaskExportMapperH2Test.java
```

**前端 `wheel-saas-pure-web`**

```text
0753fc3a fix(account): clarify reachout restriction scope and expiry sources
```

```text
src/api/account.test.ts
src/api/account.ts
src/views/account/index/account-display.test.ts
src/views/account/index/account-display.ts
src/views/account/index/account-status-filter.test.ts
src/views/account/index/account-status-filter.ts
src/views/account/index/components/AccountListTable.test.ts
src/views/account/index/components/AccountListTable.vue
src/views/account/index/composables/useAccountListPage.ts
```

**Android `whatsapp-server-feature-android-zhuan`**

```text
a80cde6 fix(account): decode verified reachout timelock Argo responses
```

```text
internal/service/app/reachout_timelock_argo.go
internal/service/app/reachout_timelock_argo_test.go
internal/service/app/reachout_timelock_schema.go
internal/service/app/safemex.go
```

**Web `armada-protocol`**

```text
bc5d7ee fix(accounts): refresh reachout restrictions after online transition
51c4714 fix(groups): refresh reachout facts after scoped restriction results
a3731c8 fix(accounts): redact reachout query failure logs
```

```text
protocol-layer/src/commands/group-join-executor.test.ts
protocol-layer/src/commands/group-join-executor.ts
protocol-layer/src/commands/group-participants-executor.test.ts
protocol-layer/src/commands/group-participants-executor.ts
protocol-layer/src/commands/normal-group-creation-executor.test.ts
protocol-layer/src/commands/worker-consumer.test.ts
protocol-layer/src/commands/worker-consumer.ts
protocol-layer/src/routes/group-join-error.ts
protocol-layer/src/routes/groups-join.test.ts
protocol-layer/src/routes/groups-participants-mutation.test.ts
protocol-layer/src/routes/groups-test-harness.ts
protocol-layer/src/routes/groups.ts
protocol-layer/src/worker/account-manager.heartbeat.test.ts
protocol-layer/src/worker/account-manager.ts
protocol-layer/src/worker/event-bridge.test.ts
```

## 部署
- 初始实施阶段不部署。2026-10-08 用户追加授权：合并主仓库、部署第二套环境（perf2）、删除本次 worktree。部署执行结果记录于下方追加章节。

## 遗留 / 跟进
- **验证遗留**：前端标准 `pnpm test` 未通过，现有 loader 下 8 个失败已在基线复现；Android `go test ./...` 未通过，含沙箱端口限制及基线同样的 8 个 Noise 失败；后端隔离全量 5199 tests / 20 failures / 24 errors（并排除 89 个源码类）；恢复基线生产代码的定向复测重现除外部 DTD 一例外的相同失败/错误。不能标注“四仓全量全绿”。
- **自动审批限制**：Android 未过滤全量的提权重跑被拒，原因是未逐项排除外连/真库/容器行为；没有绕过或再次执行被拒的动作。已采用沙箱内定向测试、事件链测试和基线核验。若后续要求全量通过，需要另行收敛获准的测试范围与本地环境。
- **合并提示**：前端主工作区 `src/views/account/index/composables/useAccountListPage.ts` 有他人在途修改。本分支只新增 `clearOperationRestrictionsConfirmMessage` import 并替换解除确认文案调用；合并时与对方协调这两处，不能整文件覆盖。其它三仓也保留原主工作区在途代码，不回拷、不自动合并。
- **文档细化 / 出入**：F2 内部保留非零毫秒，防止两个截止时间相差 1ms 却误判同源；既有整秒字符串和页面显示不变。F3 已查明筛选中文只在页面内存状态、地址栏是分组 ID、query-state 使用数字 muteStatus，故无需旧中文持久化迁移。
- **文档细化 / 出入**：W2 补充裸数值 `463` 的进群规范化（原先仅认错误文本），并覆盖 HTTP join/add 和 worker commands 两条入口；普通建群 429 原约定未改，promote/remove 不补查。
- **Android schema 不再阻塞**：可信本地客户端 wire store 与两个真实样本均完成逐字节往返；原登录通知链保留。无手写字节匹配、无截止时间猜 active、无协议 query ID 升级。
- **外部 DTD 事实**：后端旧 `MarketingGroupBanMapperH2Test` 解析 XML 时尝试解析 `mybatis.org`，DNS 失败、未连接成功；发现后从后续基线/定向执行中排除。不能宣称所有测试都没有外连尝试。
- **初始实施阶段范围保持**：未修改平台解除时清推断限制、平台事实直接写拉人限制、进群任务选号过滤、拉人 401/412 映射、历史数据、数据库结构或部署逻辑。初始实施阶段未使用 PEM、未查改真库、未部署、未推送、未执行远程命令；后续部署追加授权与执行结果见下方。没有线上账号业务验收。
- **回退**：仅按需 revert 本表相应仓库本次提交，不重置主工作区、不回滚他人在途改动。


## 追加授权与主仓合并（2026-10-08）

用户追加要求：“速度合并进入到主仓库，然后部署到第二套环境，这个worktree删掉”。本节是初始不部署约束之后的新授权和执行记录；只针对 perf2，未推送远程 Git，未操作 test1 / Athena。

- 四仓主工作区均为 `1.0.3-snapshot`，执行 `git merge --ff-only codex/reachout-restriction-scope-20261008`，全部成功：后端 `6004cb5e`、前端 `0753fc3a`、Android `a80cde6`、Web `a3731c8`。
- 主目录原有未提交文件按 SHA-256 逐一核对未改。前端仅临时 stash 冲突路径 `useAccountListPage.ts`，fast-forward 后恢复；恢复前后的原补丁增删行完全一致，仅行号偏移。本次没有覆盖对方文件；只删除本任务创建的临时 stash。
- 原主目录未跟踪的任务文档已有更完整的分支版本；原文件保留在 `/private/tmp/reachout-merge-backup-20261008/armada-overlap-backup`。其他未跟踪工作保留。
- 发布源码：后端/前端/Web 使用本次干净 worktree；Android 使用本地 Git clone 的 detached `a80cde6`，避免 rsync 把 worktree 的 `.git` 指针文件带到远端。四份实际发布源码状态均为 `clean`，没有发布主目录中他人未提交的改动。

### 部署前真实命令与输出

以下本地日志目录为 `/private/tmp/reachout-release-20261008`。`run-deploy.sh` 只设置四仓源码目录、对应 perf2 SSH key 路径和构建环境变量，再调用本次后端 worktree 的 `armada-deploy/deploy-test.sh --env perf2`；没有硬编码凭据内容。

| 命令 | 真实结果 |
| --- | --- |
| `bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh` | exit 0 |
| `bash armada-deploy/deploy-test.test.sh` | exit 0；`OK deploy-test.sh protocol and zhuan tests passed` |
| `bash armada-deploy/package-prod.test.sh` | exit 1；`FAIL expected file to exist: .../armada-deploy/prod/scripts/inspect-production-host.sh`；缺少生产离线包脚本，未修改该无关范围，不能标为通过 |
| `bash /private/tmp/reachout-release-20261008/run-deploy.sh --full --dry-run` | exit 0；四份 clean 源码和 perf2 目标一致；`OK dry-run 完成` |
| `bash /private/tmp/reachout-release-20261008/run-deploy.sh --check` | exit 0；Armada / Baileys / Kafka / Zhuan / Cross-component 均 OK；`OK 只读深度检查通过` |

部署前留存回滚：Armada 远端 `/home/app/reachout-backup-20261008/artifacts.tgz`；Web 远端 `/home/ec2-user/reachout-backup-20261008/protocol.tgz`；Android 远端同目录 `android-source.tgz`。保留镜像标签 `reachout-rollback/armada-backend:20261008`、`reachout-rollback/armada-nginx:20261008`、`reachout-rollback/android-zhuan:20261008`。Android 初次源码归档因只读权限不可访问 `deploy/isolated` 失败，按正式同步本就排除的相同边界排除后重跑 exit 0；未读取或变更 isolated 环境。

### perf2 发布与补验结果

执行：`bash /private/tmp/reachout-release-20261008/run-deploy.sh --full --yes`。

- 本地构建与四项远端更新均完成；Web `SUCCESS`、Android `SUCCESS`。后端/前端容器已成功重建启动，但最终验活读取运行 JAR 时 SSH 输出 `Connection closed by 3.110.124.52 port 22`，部署脚本因此 **exit 1**，摘要 Backend / Frontend 为 FAILED。没有将该脚本写为 exit 0，也没有为这次短暂断连重复重启服务。
- 随后独立执行 `python3 /private/tmp/reachout-release-20261008/verify-perf2.py`：**exit 0**，`ARTIFACT AND RUNTIME VERIFICATION PASSED`。Web 本次 15 个改动文件、Android 4 个改动文件远端 SHA-256 均与提交内容一致。Web 5 个协议进程与看板 online、Node `24.16.0`，`/readyz` 返回 `{"ok":true}`。
- `python3 /private/tmp/reachout-release-20261008/verify-backend.py`：**exit 0**，运行 JAR 与本地构建匹配；HTTP 前端内容与容器/local index 哈希一致；API `/api/account-groups` 返回预期鉴权码 `40104`；环境标题“第二套环境”；后端启动成功 marker 1，启动/迁移失败 marker 0。两次容器核对中 backend/nginx 均 running、restarts=0。
- `bash /private/tmp/reachout-release-20261008/run-deploy.sh --check` 再次执行：**exit 0**。`Armada / Baileys / Kafka / Zhuan / Cross-component` 全部 OK，`OK 只读深度检查通过`。Kafka 10 个环境主题及 8 个消费组验证通过，消费组均 Stable。
- Android 三容器 `callback-zhuan`、`whatsapp-android-zhuan`、`traffic-dashboard-zhuan` 均 running/healthy、restarts=0；Swagger HTTP 200。原迁移检查两项都输出“不需要执行”，没有新增迁移。Compose 提示的既有 device-ingest orphan 容器未删除。

| 运行制品 | SHA-256 |
| --- | --- |
| 后端 `/app/app.jar`，与本地构建和上传文件相同 | `c902bf2a084812e6c7676486fbfaec0ea53b4eb241a6816d8caf3aee2de663fe` |
| 前端 `index.html`，本地、nginx 容器与 HTTP 响应相同 | `7b748c8fda44849ae74dee024fad45ed0adf4619147089bfdd0c61a622e0d7a4` |
| Android 新镜像 | `77eead0b40c137a327b8ea85869a32c027e8724e436b25407f6d781ab4962c56` |
| Android 运行二进制 `/app/whatsapp-server` | `8cbafac58a3ccf68ed475545276139aa9d47b2ebe30a2da4a6c35df98b157a8d` |

结论：perf2 四项新制品已生效，部署后独立核验通过；原部署脚本最后的 SSH 断连仍按失败保留。原先全量测试的既有失败没有因此消失；没有新做真实 WhatsApp 账号操作或限制事件业务验收，不能将健康检查等同于业务验收。未 push。

完整命令输出：`/private/tmp/reachout-release-20261008/{dry-run,precheck,backup,backup-android,deploy,verify,verify-backend,postcheck}.log`。部署脚本测试日志：`/private/tmp/reachout-deploy-script-tests.log`、`/private/tmp/reachout-package-script-tests.log`。回滚文件仅留在对应 perf2 主机，不包含本地 PEM 外发。

### Worktree 清理结果

部署记录提交 `a6b0bc1b` 已 fast-forward 合入后端主分支。随后在每个主仓执行 `git worktree remove <仓库>/.worktrees/reachout-restriction-scope`，四条命令均 **exit 0**，无需 force。删除前每个 worktree 均干净，HEAD 都已被对应主分支包含；删除后目录不存在且 `git worktree list --porcelain` 不再列出它们。临时 Android 本地 clone/archive 源码目录一并清理，日志与回滚副本保留；功能分支仍保留供追溯。

清理后重新核验主目录：原脏文件哈希保持一致，`useAccountListPage.ts` 原未提交补丁增删行完全一致。证据：`/private/tmp/reachout-release-20261008/cleanup.log`、`/private/tmp/reachout-release-20261008/main-preservation.log`。仅此清理回执在 worktree 删除后单独提交到主分支，未暂存他人代码。
