# 通讯录营销 交接状态

- 更新时间：2026-08-29（P3b 发送引擎落地后回填）
- 用途：**上下文清空后的唯一权威入口**。接手时先读本文，再读引用的设计与计划文档。

---

## 0. 一句话现状

**四块里做完四块的后三块半：协议层、通讯录采集、任务 CRUD、发送引擎全部落地并零回归；只剩前端一行代码没写。**

现在的实际效果：数据库有表、6 个接口能调、账号上线会自动同步通讯录、菜单和权限节点就位，
**任务启用后会真的圈号、展开收件人、按轮次把私聊消息投给协议层，并把三级回执写回计数直到任务自动完成。**

**唯一缺口是前端**：`wheel-saas-pure-web` 零改动，界面上还看不到「通讯录营销」菜单，
只能靠直接调接口驱动。另外发送链路只做过纯类测试，**没有在有库环境跑通过一次真实闭环**（见 §6.1）。

---

## 1. 分支与基线

四个仓库都在 `feat/contact-marketing`，均从各自 `1.0.3-snapshot` 切出。

| 仓库 | 路径 | 基线 commit | 领先 |
|---|---|---|---|
| `armada` | `/home/yanwenchao/ideaProject/armada` | `e1f5d195` | 35 |
| `armada-protocol` | `/home/yanwenchao/ideaProject/armada-protocol` | `60f40d9` | 5 |
| `whatsapp-server` | `/home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan` | `f1faa36` | 3 |
| `wheel-saas-pure-web` | `/home/yanwenchao/ideaProject/wheel-saas-pure-web` | `a9f039e` | **0（未动）** |

**尚未合并、尚未推送。** 集成方式（合回 `1.0.3-snapshot` / 开 PR / 继续挂着）用户还没定。

---

## 2. 文档地图

| 文档 | 作用 |
|---|---|
| `docs/superpowers/specs/2026-08-28-contact-marketing-replication-design.md` | **总设计**。竞品事实、数据模型、接口契约、分期。改方案先改它 |
| `docs/superpowers/plans/2026-08-28-contact-marketing-p0p1-protocol.md` | P0+P1 协议层计划 + **执行记录与偏离说明** |
| `docs/superpowers/plans/2026-08-28-contact-marketing-p2-contact-sync.md` | P2 通讯录采集计划 |
| `docs/superpowers/plans/2026-08-28-contact-marketing-p3a-task-crud.md` | P3a 任务 CRUD 计划 |
| `docs/superpowers/plans/2026-08-29-contact-marketing-p3b-send-engine.md` | P3b 发送引擎计划（15 任务 / 105 步，含三处对设计的有意偏离） |
| `docs/superpowers/plans/2026-08-29-contact-marketing-handoff.md` | 本文 |

竞品事实源：`/home/yanwenchao/hylbuiaxykfrontendsource/readable/assets/`
（`hyperlink-BVNnqLDE.js` = 通讯录超链任务页，`script-DcoFUVM0.js` = 剧本任务空占位，
`router-CPQmbuR9.js` = 路由与 6 个接口定义，`account-filter-modal-BXDIvipG.js` = 账号筛选弹窗）

---

## 3. 已完成

### P0+P1 协议层（3 仓，6 commits）

| 仓库 | commit | 内容 |
|---|---|---|
| armada-protocol | `92e1d84` | `worker/contact-store.ts` 联系人投影纯模块 |
| armada-protocol | `f24a91b` | 接进 `AccountManager` 生命周期 + `getContacts()` |
| armada-protocol | `21f697d` | `GET /v1/accounts/{id}/contacts` + OpenAPI |
| armada-protocol | `36ead18` | 私聊跳过 sendability 预检 + `contact_task` source 分支 |
| whatsapp-server | `1da633a` | `POST /ws/v1/contacts/list/{key}` |
| whatsapp-server | `ac5e583` | 安卓私聊发送路径（`isPeerTarget`） |
| armada | `4e99fb15` `55dd0406` | `ContactListPort` + Web/Android 两个 adapter + bean 注册 |
| armada | `dc172f2b` | `MessageTarget` 从 `groupJid` 中立化为 `jid` |

### P2 通讯录采集（armada，6 commits）

`V157__account_contact_sync.sql`：`account_contact`、`account_contact_sync` 两张表，
`account_state` 加 `contact_named_num` / `contact_mutual_num` 两列。

- `AccountContactNormalizer` 归一化与计数
- `ContactSnapshotFreshness` TTL 判定 + `AccountContactProperties` 配置
- `AccountContactSyncService` 采集服务（整批替换 + 失败不动既有数据）
- `AccountContactOnlineHook` 账号 ONLINE 后自动同步，走独立线程池

### P3a 任务 CRUD（armada，6 commits）

`V158__contact_friend_task.sql`：`contact_friend_task`、`contact_friend_task_account`、
`contact_friend_task_recipient` 三张表。
`V159__contact_marketing_menu_rbac.sql`：菜单目录 + 两个页面 + 三个按钮权限节点。

- `ContactTaskStateMachine` 状态机（已停止/已完成为终态）
- `ContactTaskFormValidator` 表单校验
- `ContactAccountFilterNormalizer` 筛选白名单归一化
- `ContactTaskService` + `ContactTaskController` 6 个接口

接口清单（全部已实现）：

```
GET    /api/contact-tasks              tenant:contact_task:view
POST   /api/contact-tasks              tenant:contact_task:create
GET    /api/contact-tasks/{id}         tenant:contact_task:view
PUT    /api/contact-tasks/{id}         tenant:contact_task:edit
POST   /api/contact-tasks/{id}/action  tenant:contact_task:operate
GET    /api/contact-tasks/{id}/data    tenant:contact_task:view
```

**没有删除接口**（竞品也没有）。

---

### P3b 发送引擎（3 仓，14 commits）

`V160__contact_task_engine.sql`：`contact_friend_task.current_round_no`、
`contact_friend_task_recipient.round_no` / `command_id` 三列。

| 仓库 | commit | 内容 |
|---|---|---|
| armada | `05cb94cb` | V160 补列迁移 |
| armada | `1bd24f17` | `ContactFriendTaskRecipient` 实体 + Mapper + XML |
| armada | `cbd172e9` | `AccountFilterCriteria` 圈选条件解析 |
| armada | `02e36084` | `AccountFilterSelector` 共享圈号服务 + SQL |
| armada | `a8876803` | `ContactSendIntervalPicker` 逐条随机间隔 |
| armada | `6eeac164` | `MessageCorrelation` 加 `contactTask` + Web/Android 两个 payload 编码 |
| armada | `0fd09ba9` | 调度用查询、计数更新、终态收敛等数据访问语句 |
| armada | `c900b957` | `ContactTaskExpansionService` 圈号 → 同步 → 展开 |
| armada | `0c58e7cb` | 展开接进 `create` / `update` 的启用路径 |
| armada | `86bf1eb3` | `ContactTaskMessageCommandFactory` 命令组装 |
| armada | `7bfc58df` | `ContactTaskRoundScheduler` / `RoundWorker` / `LifecycleWorker` 三件套 |
| armada | `572737ab` | `ContactTaskSendResultSink` 三级回执回写 + 消费器 contact 分支 |
| armada-protocol | `a4c01d5` | `messageResultBase` / `messageSendLogFields` 补三个关联字段 |
| whatsapp-server | `7bb77ef` | payload/结果事件加三字段 + 解析层按来源放开私聊目标 |

**交接文档上一版没记、这次补掉的两个真实缺口：**

1. `armada-protocol` 的 `messageResultBase()` 只回填 marketing / groupCreation / historicalGroup，
   **成功与失败回执都不带通讯录关联**——只有 `invalidMessageResultBase()` 带。不补这一处，
   armada 侧永远收不到 `recipientId`，回执无法归属。
2. `whatsapp-server` 三处解析仍硬校验 `groupJid` 必须 `@g.us`
   （`validateMessageCommand` / `ParseMessageCommandRoute` / `ParseMessageCommandReference`）。
   P1 的 `ac5e583` 只改了 `message_sender.go` 的发送路径，没动解析，安卓号的私聊命令会在解析阶段被拒。

**放开私聊目标时按来源收口，没有一刀切**：只有 `source=contact_task` 才允许 `@s.whatsapp.net`，
群营销命令带私聊目标仍然拒绝——既有测试 `TestParseMessageCommandRejectsUnsafeInvalidValues`
就钉着这条，一刀切放开会把它打挂。

一轮的固定顺序（照 `MarketingRoundWorker` 的关闸顺序）：
读任务 → 未到点则退回等待 → 取有 PENDING 的账号 → 排干则收尾完成 / 在途则只推迟 →
积压闸门 → `claimDueRound` 抢轮次 → 复查账号协议事实 → 逐条 `claimForSend` 抢批 → 分批写 outbox。
`claimDueRound` 与 `claimForSend` 是两道并发闸门，缺一就会重复投递。

---

## 4. 未完成

### P4 前端（wheel-saas-pure-web）— 计划未写，仓库零改动

```
src/views/contact/
  hyperlink/index.vue + components/ + composables/ + domain/
  script/index.vue        ← Result 空占位，逐字复刻竞品
```

菜单与路由接入 `src/router/`，补 `contact-route.test.ts`（对齐 `hyperlink-route.test.ts`）。

---

## 5. 硬约束与已冻结的契约（**改之前先读这里**）

### 5.1 P0 冻结的下游契约

`source = 'contact_task'` 的 Kafka payload **必须携带**四个数值字段，缺一即被协议层判为
`invalid message send payload` 丢弃：

```
contactTaskId / taskAccountId / recipientId / roundNo
```

P3b 的 `ContactTaskCorrelation` 与 Web/Android backend 编码这四个字段时**字段名必须逐字一致**。
出处：`armada-protocol/protocol-layer/src/commands/worker-consumer.ts`。

另：`invalidMessageResultBase` 也已加 `contact_task` 分支——四个字段齐全但内容非法时，
失败回执能带回关联信息；四个字段缺任一则**重抛不 ack**，交上游重投。

### 5.2 Kafka 线上字段名没动

`MessageTarget` 的 Java record 组件改名是**纯内部重构**。
`WebMessagePayload` / `AndroidMessagePayload` 的字段仍叫 `groupJid`，
两个协议消费者不需要同步发版。私聊时该字段值是 `<phone>@s.whatsapp.net`。

### 5.3 双向好友暂时拿不到

两套协议**都不暴露**双向好友标记（安卓 `ContactEntry` 只有 JID/LID/FirstName/FullName，
Baileys `Contact` 只有 name/notify/verifiedName）。因此：

- `account_contact.is_mutual`、`account_contact_sync.mutual_num`、
  `account_state.contact_mutual_num` **当前恒为 0**
- 补齐后**只需改 `AccountContactNormalizer` 一处**
- **在此之前前端不得渲染「双向好友数 ≥/≤」筛选控件**

### 5.4 竞品口径要点

- 消息类型只有 `0 链接消息` / `1 图文消息`，**没有按钮**
- 发送间隔是**带一位小数的秒**（`DECIMAL(4,1)`，最快 0.1s），落成整数会把「最快」这档做没
- 双状态字段：`is_enabled`(0停用/1启用) + `run_status`(0未开始/1进行中/2已完成/3已暂停/4已停止)
- `scheduled + 延迟 0` **只在启用时才拒绝**，存草稿允许
- 竞品「新建任务」按钮在当前构建里是 `disabled`（灰度中），**我们开放**
- 竞品有「单价 USDT/条」badge，**我们不复刻**（armada 无计费体系）
- `通讯录剧本任务` 竞品自己就是「敬请期待」空占位，**逐字复刻，不编造功能**

---

## 6. 环境限制（会影响你怎么验证）

### 6.1 本机跑不了 `*DbTest`

缺 `armada-api/.env`（gitignore，本机不存在）里的库凭据，93 个 DbTest 会因
`Unknown database 'armada'`（SQL 1049）秒挂，Spring 上下文起不来。

**因此所有可测逻辑都刻意放在纯类里**：迁移用 SQL 文本契约测试、Mapper 用 XML 静态契约测试、
Service 用 Mockito。累计新增约 130 个用例，全部不依赖数据库。

**代价**：以下几件事本地验证不了，必须在有库环境补——

- `V157`/`V158`/`V159`/`V160` 能否真正跑通 Flyway
- MyBatis-Plus 租户拦截器是否正确注入五张新表
- Spring 容器能否装配新增的 bean（`ContactListPort` 三个、`AccountContact*` 三个、
  `ContactTaskService`、`ContactTaskExpansionService`、`ContactTaskSendResultSink`、
  `AccountFilterSelector`，以及 `@Profile("kafka")` 下的 `ContactTaskRoundScheduler` /
  `RoundWorker` / `LifecycleWorker`）
- 列表分页 / 模糊查询 / 条件更新的真实 SQL 行为
- **P3b 新增、尤其需要真库验证的几条**：
  - `INSERT IGNORE`（收件人展开）与 `ON DUPLICATE KEY UPDATE`（任务账号行）的真实幂等行为
  - `settleDrainedAccounts` 的 `UPDATE ... NOT EXISTS(SELECT ... FROM 另一张表)` 在 MySQL 下能否执行
  - `completeDrainedTask` 里 `UPDATE t SET ... = (SELECT ... FROM 另一张表 WHERE a.task_id = t.id)`
    的相关子查询能否执行
  - `claimDueRound` / `claimForSend` 两道条件更新在真并发下的抢占行为
  - 圈号 SQL 里 `UNIX_TIMESTAMP() * 1000` 与 `created_at`（epoch 毫秒）的比较是否符合预期
- **`.harness/wiki/gen_datamodel.py` 本次没有重跑**：它读 `/tmp/wheel_*.tsv`（information_schema
  真库转储），本机无库拿不到输入。部署到有库环境后必须补跑，把 V160 三列同步进数据模型文档

### 6.2 跑测试的正确命令

```bash
# armada：根目录没有聚合 pom，mvn -pl armada-api 会失败
cd /home/yanwenchao/ideaProject/armada/armada-api && mvn -o test
# 全量超过 120 秒，后台跑

# armada-protocol：必须带 ESM flag，裸 npx jest 会让 28 个 suite 假失败
cd /home/yanwenchao/ideaProject/armada-protocol/protocol-layer
node --experimental-vm-modules ./node_modules/.bin/jest

# whatsapp-server
cd /home/yanwenchao/ideaProject/whatsapp-server-feature-android-zhuan && go test ./...
```

### 6.3 回归基线（**只要涨了就必须查清，不能归因于环境**）

| 范围 | 基线 |
|---|---|
| armada 全量 | `Failures: 7, Errors: 461`（当前 3532 tests） |
| armada-protocol | 1 个既有失败 suite `worker/baileys-participating-groups.test.ts`；`traffic/baileys-patch.test.ts` 也是既有失败 |
| whatsapp-server | `pkg/noise` 既有失败；`internal/armada` 全绿 |

**统计 armada 回归数字时不要用 `mvn 输出 | grep | tail`**——surefire 会打多段汇总，
`tail` 会抓到分段行给出错误数字（实测出现过 `2779 tests / 299 errors` 的假数据）。
正确做法是从 `target/surefire-reports/*.txt` 聚合：

```bash
cd armada-api/target/surefire-reports
echo -n "Tests: ";    grep -h "^Tests run:" *.txt | sed 's/Tests run: \([0-9]*\).*/\1/'     | paste -sd+ | bc
echo -n "Failures: "; grep -h "^Tests run:" *.txt | sed 's/.*Failures: \([0-9]*\).*/\1/'   | paste -sd+ | bc
echo -n "Errors: ";   grep -h "^Tests run:" *.txt | sed 's/.*Errors: \([0-9]*\).*/\1/'     | paste -sd+ | bc
```

---

## 7. 踩过的坑（别再踩一遍）

1. **改既有类的构造签名后，要同时 grep `new <类名>` 和 `@InjectMocks`。**
   P2 只查了前者，漏掉 `AccountStateChangedSinkAdapterTest` 用 `@InjectMocks` 注入 null，
   导致既有测试挂了 3 个。

2. **Java record 组件不能叫 `notify`**（与 `Object.notify()` 冲突，编译期报
   `illegal record component name`）。Web 联系人响应用
   `@JsonProperty("notify") String notifyName` 承接。

3. **`disposeCaches` 有早返回**，附属清理要放在早返回**之前**，否则缓存已清的账号会漏清。

4. **`ORDER BY` 是 MyBatis 挡不住的注入口**，排序列必须走 `<choose>` 白名单 +
   Service 层抹除非白名单值。

5. **空批次不能调 `upsertBatch`**（`<foreach>` 会生成空 VALUES 导致语法错），
   但扫尾删除和计数归零仍必须发生。

6. **回执有两条独立路径**：`messageResultBase()`（正常成功/失败）与
   `invalidMessageResultBase()`（payload 非法）。加关联字段要**两处都改**，
   只改一处会出现「失败带关联、成功不带」这种极难查的现象。

7. **Go 侧目标校验散在三个解析函数里**：`validateMessageCommand`、`ParseMessageCommandRoute`、
   `ParseMessageCommandReference`。改发送路径（`message_sender.go`）不等于改了解析路径，
   P1 就是只改了前者。

8. **放开目标类型要按来源收口，不能一刀切**。把 `@g.us` 硬校验直接改成「群或私聊都行」，
   会打挂既有的 `TestParseMessageCommandRejectsUnsafeInvalidValues`——它钉着
   「群营销命令带私聊目标必须拒绝」。正确做法是 `isMessageTargetJID(jid, source)`，
   只对 `source=contact_task` 放行私聊。

9. **Java record 加组件会打挂所有构造点**。本期 `MessageCorrelation` 加第 6 个组件牵动 10 处、
   `ProtocolMessageSendResultReportedEvent` 加 3 个组件牵动 9 个测试文件。改之前先
   `grep -rn "new <类名>(" src` 数清楚。

10. **断言 Jackson 转出来的 Map 时注意装箱类型**：`Long` 字段断言成 `containsEntry("k", 77)`
    会因为 `Integer != Long` 挂掉，必须写 `77L`。

---

## 8. 依赖真机验证的三件事（**只有用户能做**）

| # | 验证项 | 影响 |
|---|---|---|
| V1 | Baileys contact store 冷启动后是否**全量**（app-state 是增量 patch，重连后可能只有本次会话变更） | 若只拿到增量，`contact_num` 偏小，P3b 的「计划发送总数」失真；Web 侧需追加主动 resync |
| V2 | 双向好友标记两侧是否可得 | 决定 `is_mutual` 系列列能否有真值、筛选控件能否渲染 |
| V3 | 私聊群发的风控表现（0.1s / 0.5~1s / 1~3s 三档各发 50 条，记封号率） | 决定默认间隔取值，可能推翻竞品的「最快 0.1s」预设 |

验证结论请写进 `docs/superpowers/reviews/2026-08-28-contact-protocol-verification.md`，
并回填到总设计的 §5.1 与 §11。

---

## 9. 建议的下一步

1. **写 P4 前端计划并落地**——现在唯一的功能缺口，做完整条链路就能在界面上跑通
2. **部署到有库环境跑一次全量**，补齐 §6.1 列出的验证项，重点是发送闭环：
   建一个启用态任务 → 看 `contact_friend_task_recipient` 是否展开 →
   看 outbox 是否产生 `source=contact_task` 的命令 → 看回执是否把三级计数写回 →
   看任务是否自动进入「已完成」
3. 补跑 `.harness/wiki/gen_datamodel.py`
4. 安排 V1/V2/V3 真机验证（§8）

顺序不是硬约束；2 可以先于 1 做，能更早暴露 SQL 层面的问题。
