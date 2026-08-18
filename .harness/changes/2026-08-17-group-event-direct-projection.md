# 变更记录：群变更事件直接投影（增量维护）

- 日期 / 分支 / worktree: 2026-08-17 ~ 08-18 / `1.0.3-group` / 主 checkout（未用 worktree）
- 需求来源: `docs/superpowers/specs/2026-08-16-group-event-direct-projection-design.md`
  （核实结论与修订后的任务顺序在 §17；§17.8 替代原 §13）
- 状态: 进行中（**未部署，全程未跑真实数据**）

## 目标（一句话）

群里的人和群资料一变，控端靠 WhatsApp 事件直接更新，不再每次回头查一遍全量 metadata。

## 缺口拆解 / 任务清单

- [x] 基线策略：Web 首次上线拉完整 metadata + 并发闸（`80d170a`，armada-protocol）
- [x] 基线策略：安卓上报 announce/locked/memberAddMode（`f84ea4c`，android-zhuan）
- [x] armada 接住全部 7 字段（`189567a2`）
- [x] V127 逐字段版本 + `GroupMetadataPatchService`（`2b120a61`）
- [x] `group.metadata_updated` consumer（`de192af5`）
- [x] `group.profile_reported` consumer（`399d0613`）
- [x] 放行 `group.participant_changed` 的 add/remove（`6b550f5f`）
- [x] Web producer：7 字段映射 + 移除两处旧 metadata 同步请求（`32e2232`，armada-protocol）
- [x] 放行 modify（PN/LID 身份合并，含真库用例）（`02ffc7cc`）
- [x] 群同步命令按 backend 分流（`95b1308d`）
- [x] 安卓接住群同步命令，恢复定时对账（`447b2f7`，android-zhuan）
- [x] 安卓补报 joinApprovalMode，基线 5/7 → 6/7（`96cbb95`，android-zhuan）
- [ ] **安卓 WGP2 群资料变更通知解析**（让安卓也做到即时）— 卡在缺真实样本，见「遗留」
- [ ] **监控与开关**（§17.8 第 7 项）— 不卡样本，随时可做
- [ ] **契约 fixtures**（§17.8 第 1 项）— 建议拿 test1 真实事件反向固化
- [ ] 摘除已无生产者的 `account.group_metadata_sync_requested`（跨仓）
- [ ] `upsertProfiles` 快照路径补版本比较（§7.2 要求）
- [ ] test1 集成验收

## 当前能力矩阵

| | 有人进群 / 退群 | 改群名、改群设置 |
|---|---|---|
| Web 号 | 即时 | 即时 |
| 安卓号 | 即时（走旧事件名 `account.group_participant_joined/departed`） | **等 WhatsApp 下一次 `dirty(groups)` 通知**，时机不由我们控制 |

安卓群资料字段覆盖 6/7：群名、全员禁言、仅管理员改资料、成员可否加人、进群审批、管理员身份。
`description` 与 `ephemeralDuration` **不在 `GetAllGroup` 的 IQ 响应里**，安卓基线永远为空（§17.4）。

## ⚠️ 定时任务一直是关的（0818 实测，推翻早先前提）

`ACCOUNT_GROUP_SYNC_ENABLED` 默认 false（`armada-api/src/main/resources/application.yml:218`）；
test1 机器 `/home/app/armada-deploy/.env` 没有这个开关（只有同前缀的
`PROTOCOL_ACCOUNT_GROUP_SYNC_EVENTS_CONCURRENCY=1`，那是 Kafka 消费者并发数，不是本任务）；
容器日志近 20 分钟 `account_group.sync.job` 零条。间隔 3 分钟（`fixed-delay-ms:180000`）属实。

推翻两条早先结论：

1. 「定时任务不能砍，砍了就没人更新了」—— 前提不成立，它本来就没在跑。
2. 「安卓群资料靠定时刷新兜住，慢 3 分钟」—— **错的**。安卓群资料实际靠自身的事件刷新：
   WhatsApp 发 `dirty(groups)` 通知 → `GroupSnapshotCoordinator` 防抖 1s 后重拉全量群列表。
   该链路不经过 armada，但**通知发不发由 WhatsApp 决定，我们不控制**。

**由此浮出的真实风险**：定时任务既然关着，改动前 Web 群资料**唯一在跑**的更新路径就是
`account.group_metadata_sync_requested`（改群名 → 协议层请求 → armada 回查全量 metadata）。
`32e2232` 把它删了、换成直接发字段级 patch。方向是设计要求的，但意味着
**新 patch 链路若在真机不生效，Web 群资料会从"能更新"直接变成"完全不更新"，且无任何兜底**。

这把「监控与开关」从「上线出事能回退」提升为**上 test1 前的必要前置**：开关的具体作用是
新链路不灵时立刻切回旧的请求-回查模式，而不是眼看着群资料停止更新。

**验证期间定时任务保持关闭**：开着会盖住事件链路的问题，让人误判事件已通、实际是定时刷新在干活。
确认事件链路可用后再决定是否作为生产安全网打开；打开前必须确认 `95b1308d` + `447b2f7`
已随之上线，否则安卓号的命令仍会被静默丢弃。

## 关键设计决策

> 含被否决的方案与原因。

1. **add/remove 走安卓既有的进群/退群事实链路，不走 observation 链路**（用户在三个方案里选定）。
   两端因此落到同一批列（presence + `last_joined_at`/`last_exited_at`/`last_exit_type`），
   且 role 传 null → SQL 里 `COALESCE(role,0)=0` 表示未观察不覆盖，不会把没看到的角色写成"普通成员"。
   **否决**：走 observation 链路（需把 `admin` 改成三态），白得受控账号收敛但不写进退群时间，
   两端反而不统一。

2. **`reconcileControlledMemberships` 提升为公开入口**。进群/退群事实链路不写受控账号群关系
   （`applyParticipantJoins` javadoc 明说"不创建账号群关系"），落库后必须再对齐一次，
   否则受控号自己进退群后选号仍按旧关系派活。传 PN + LID 双候选，因为
   `selectStatesByParticipantJids` 按 `COALESCE(pn_jid, lid_jid)` 匹配，只传 LID 会漏掉
   已合并出 `pn_jid` 的行——而受控号恰恰是最可能有 `pn_jid` 的那批。

3. **remove 的退出方式按 §6.1 判定**：唯一目标 + 操作人与目标同形态可比才给 LEFT/REMOVED，
   批量 remove 或跨 PN/LID 一律 UNKNOWN。宁可 UNKNOWN 也不猜——猜错会把"被踢出群"记成"主动退群"。

4. **新增 `WEB_NOTIFICATION` 这个 sourceType**。沿用 `WGP2_NOTIFICATION` 会触发
   `departurePresenceSource` 里给安卓准备的降级规则（安卓的 REMOVED 因拿不到可靠证据要降成
   UNKNOWN_EXIT_EVENT），而 Web 的 REMOVED 自带 operator 比较证据，不该被降。

5. **modify 用独立的 `mergeParticipantIdentities` 语句，不复用 `upsertParticipantFacts`**。
   后者的 presence 没有"未观察"档（role 有 `role=0` 守卫，presence 没有），会把已知的在群态
   覆盖成未知。新行按 `presence_status=0`、`role=0` 落地。
   **同一个人已分裂成 PN 行与 LID 行时跳过并告警**——这不是洁癖：一次写入同时命中 PN 与 LID
   两个唯一键，MySQL 直接报重复键错误把消费卡住。跨行归并要决定哪行留下、账号群关系怎么搬，
   不在本次范围。

6. **`group.metadata_updated` 从 BEST_EFFORT 移入 CRITICAL**。群资料只靠它增量维护，
   留在可降级里丢一条那个字段就一直是旧值。

7. **群资料 patch 按属性存在性判断，不按真值**。`announce: false` 是"明确没开全员禁言"、
   `ephemeralDuration: 0` 是"明确关掉限时消息"，都必须进 fieldMask；用真值判断会把这两种
   关闭状态当成未观察，控端永远改不回来。一个字段都没观察到时不发事件（空 fieldMask 会被整条拒绝）。

8. **群同步命令的 topic 分流单独写，不复用 `onlineCommandTopic`**。Web 上线命令走
   `protocol.account.commands.v1`，而群列表同步一直走 `protocol.master.commands.v1`，
   两者不是同一个 topic，复用会把 Web 的同步命令悄悄挪走。

9. **安卓侧不做 RouteGuard 校验**。`GroupSnapshotCoordinator` 只认自己持有在线状态的账号，
   这本身就是最准确的归属判定，且不需要命令携带手机号（该命令 payload 没有手机号也没有凭证）。
   执行器不返回错误也不发状态事件：返回错误会让 Scheduler 当失败重试，而 Armada 每轮都会重发。

10. **安卓对账刷新必须读 participant 明细**（新来源 `android_group_sync_command`，
    合并优先级排最前）。常规刷新不读明细，但对账不读就判不了退群，等于没兜住；
    优先级低会被别的来源盖掉后退化成不读明细。

## 纠正的历史结论

**§17.3 第 1 条的说法不对**（0818 实测）。原文称"Android 命令白名单不含群列表同步命令 →
命令进永久失败处理 → 每轮污染失败指标与 DLQ"。实际链路是：

`account.groups_sync.requested` 在 `ProtocolCommandOutboxServiceImpl.java` 写死发
`masterCommandProperties.getTopic()`（只有上线/下线按 backend 分 topic），安卓 fleet 根本收不到；
Web master 查不到该号的 owner worker → `OWNER_NOT_FOUND` → `publishOwnerMissingFallbacks`
对下线/进群/健康检查等都有兜底回执，**唯独这条命令类型没有** → 落进 `failures` →
`server.ts` 记一条 warn 就丢。**无 DLQ、无 armada 侧永久失败**。

真实后果是静默失效：安卓号的群列表从来没被定时刷新过，事件漏投永远补不回来。已由
`95b1308d` + `447b2f7` 修复。

## 验证（evidence-before-done）

```bash
# armada：改动涉及的类
mvn -f armada-api/pom.xml -o test -Dtest='ProtocolGroupParticipantChangedSinkAdapterTest,\
ProtocolGroupEventConsumerTest,GroupParticipantObservationServiceImplTest,\
WhatsappGroupMemberCacheServiceImplTest'
# → Tests run: 49, Failures: 0, Errors: 0

mvn -f armada-api/pom.xml -o test -Dtest='AccountGroupSyncCommandServiceTest,\
AccountGroupSyncJobTest,ProtocolCommandOutboxServiceImplTest'
# → Tests run: 39, Failures: 0, Errors: 0

# armada 真库（Testcontainers，需 OrbStack 环境变量，见下）
mvn -f armada-api/pom.xml -o test -Dtest='AccountGroupCurrentSnapshotPersistenceMySqlTest'
# → Tests run: 22, Failures: 2, Errors: 1
#   改动前为 20 / 2 / 1：新增 2 个用例全绿，3 个红的是预存失败

# armada-protocol
npm run lint          # tsc --noEmit，干净
node --experimental-vm-modules ./node_modules/.bin/jest \
  src/events/subjects.test.ts src/worker/account-manager.heartbeat.test.ts \
  src/routes/groups-announcement-text.test.ts
# → Tests: 63 passed, 1 failed
#   唯一的红是预存失败：heartbeat「suppresses groups.update caused by explicit metadata reads」，
#   栈在 readGroupMetadataWithMemberLinkMode，自 90c7bc0 起坏（该提交把 metadata 读取换成
#   自行发 IQ，未同步测试 mock），与本次改动无关

# android-zhuan（AGENTS.md §1 四条自检）
gofmt -l <改动文件>   # 空
go vet ./...          # 干净
go build ./...        # 干净
go test ./...         # 仅 pkg/noise 8 个红，预存失败（加密库，未触碰）
```

**没有任何一条链路跑过真实数据。** 首次建档那半自 `80d170a` 起就没验过。

## 部署

- commit / 环境 / 部署后验证结果: **未部署**

## 重连时哪些数据有兜底（0818 对齐后）

事件是主路径，但事件会漏投、账号离线期间也收不到。所以「重连时那一次查询能纠正什么」
决定了每个字段有没有安全网。

| 数据 | 安卓 | Web（对齐前） | Web（对齐后 `661bae1`） |
|---|---|---|---|
| 账号在哪些群（群关系） | ✅ 全量对账 + 差集删除 | ✅ | ✅ |
| 群名 | ✅ | ✅ | ✅ |
| 群简介 | ✅ | ❌ | ✅ |
| 四个群设置 | ✅ | ❌ | ✅ |
| 群成员明细 | ❌ | ❌ | ❌ |
| 群人数 | ❌ 设计禁止盲加盲减 | ❌ | ❌ |

差集删除必须要全集：库里记着在 50 个群，只有拿到「当前实际在的 48 个」才能算出少了哪两个。
单靠增量事件永远做不到——「没收到退群事件」既可能是真没退、也可能是事件丢了，
从事件流里看一模一样。所以事件里的 `snapshotComplete` 是授权标记，协议层不声明完整就不敢删。

**成员明细与群人数至今没有兜底**，这是当前最大的敞口。安卓侧的显式对账命令（`447b2f7`）
会读成员明细，Web 侧没有等价物；而那条命令由定时任务下发，任务是关的。

## 轻量 vs 完整查询：同一个接口，差在选择器

两者都是发给 `@g.us`、命名空间 `w:g2` 的 `get` IQ，区别只在 `<participating>` 的子节点：

```xml
<participating/>                              <!-- 对齐前的 Web：什么都不点名 -->
<participating><description/></participating> <!-- 安卓 & 对齐后的 Web -->
<participating><participants/><description/></participating>  <!-- 完整 -->
```

子节点是**选择器**，控制服务端返回什么。**已由线上日志证实服务端认这个选择器**：
`groupCount: 4, participantNodeCount: 0`，且补丁里「响应含成员节点就抛错」的断言从未触发。
所以省的是真实下行流量，不是解析。

`<participants/>` 是体量大头（群数 × 成员数）；简介是一段文本，四个群设置是 `<group>` 的子节点、
**根本不受选择器控制**。Web 对齐前为了省那几十字节，丢掉了群资料在重连时唯一的纠正机会。

**待验证**（代码证明不了）：不点名 participants 时服务端是否仍返回 `<announcement/>`、`<locked/>`
这些设置节点。安卓已按此假设在生产跑，但仍需 test1 抓真实响应确认。

## 遗留 / 跟进

### 0. 待实测：轻量 vs 完整群列表查询的真实流量差（账号全下线，暂时测不了）

**已确证的部分**（0818，test1 线上日志）：WhatsApp 认 `<participating>` 里的选择器子节点。
轻量查询（空 `<participating/>`）的真实观测是
`groupCount: 4, participantNodeCount: 0, subjectPresentCount: 4`，
且补丁里那句「响应含成员节点就抛错」的断言**一次都没触发过**。
所以不点名 `<participants/>`，服务端就真的不发成员列表——**省的是下行流量，不只是解析**。

**未确证的部分**：省多少。上面那条日志的号只有 4 个群，样本太小，
「50 群 × 100 人的号能省多少 KB」目前只有估算（约 100 KB vs 2 KB），**没有实测**。

**怎么量**（等有号在线时做）：挑一个群多的号，对同一个号分别跑一次轻量和一次完整查询，
打出两次响应的实际字节数。协议层有流量观测（`src/traffic-dashboard/`）可复用。
拿到数字后可折算：每次上线多耗流量 × 号数 × 每天重连次数 → 住宅代理 GB 费用。

**为什么这笔账重要**：Web 号走住宅代理，按 GB 计费（见记忆 `iproyal-session-freely-rotatable`），
而当前实现是**每次上线都全量拉**（不分首次/重连），这是真金白银。


### 1. 安卓 WGP2 群资料变更通知解析 —— 卡在缺真实样本

`internal/service/node/processor/group_notification.go` 的 `parseWGP2GroupEvent` 只接受 9 种动作：
`create / invite / add / remove / leave / promote / demote / suspended / terminated`。
改群名、改简介、开关全员禁言、开关仅管理员改资料这些 tag **一个都没接**，这是安卓群资料
无法即时更新的根因。

**为什么不能现在写**：仓里零 group fixture（§16.3 把"Android WGP2 真实脱敏样本"列为唯一
未解前置项，至今未解）。凭协议惯例猜 tag 名会写出「能编译、测试绿、上线不生效、且没样本
测不出来」的代码。

**正确做法**：在 test1 用安卓号改一次群名/群设置，抓下真实 notification 节点，照着写解析，
同时把样本固化成 fixture（一并解决 §17.8 第 1 项）。因此这一项天然属于「验证时顺手做」，
而不是「验证前做完」。

**在此之前的兜底**：安卓群资料靠定时对账刷新（默认 3 分钟一轮），已由 `447b2f7` 打通，
不是收不到，只是慢一拍。

### 2. `description` / `ephemeralDuration` 安卓口径未定

不在 `GetAllGroup` 的 IQ 响应内，安卓基线永远为空。通知里是否带得到同样要等样本才知道。
需要产品口径：认了这两栏对安卓号常空，还是另找来源。

### 3. 监控与开关（§17.8 第 7 项）—— 不卡样本

协议层零 feature switch、零专用 metric。上线后发现新逻辑有问题无法一键回退到旧的
「事件后回查全量 metadata」行为；也看不到 §14.3 验收要求的 metadata 查询避免量。
**建议在上 test1 之前做掉，否则出问题只能靠回滚发布。**

### 4. `account.group_metadata_sync_requested` 已无生产者

事件名仍挂在协议层 `subjects.ts` 注册表与 armada `ProtocolAccountEventConsumer`。
摘除是跨仓契约变更，另起一刀。

### 5. `upsertProfiles` 快照路径未走版本比较

§7.2 要求「完整快照也必须通过该 reducer 写字段」，现状是完整快照可能压过更新的增量事件。

### 6. 并发会话风险

同一 armada checkout 上另有活跃会话（PN/LID 归并方向），本任务已有两次改动被对方
`git add .` 卷走（`189567a2`、`2b120a61`）。**每刀做完立刻提交**缩短暴露窗口。
