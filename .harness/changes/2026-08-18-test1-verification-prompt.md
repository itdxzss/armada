# test1 验证任务：群变更事件直接投影

## 你要做什么

把「群直投影」这套改动部署到 test1，用真实 WhatsApp 账号跑一遍，验证「群里的人和群资料一变，
控制台能不能自己更新」。**这套代码从头到尾没跑过真实数据，你是第一个。**

**重要：你的产出是一份验证报告，不是修代码。** 发现问题先记录、定位到根因，不要顺手改。
改与不改由人决定——除非是部署本身卡住了（比如配置缺项），那种可以就地修。

## 先读

- `armada/AGENTS.md` 与 `armada/.harness/rules/` 下的规范（红线：真库/远程/部署前必须确认目标环境）
- `armada/.harness/changes/2026-08-17-group-event-direct-projection.md` —— 这次改动的完整背景、
  12 条提交索引、10 条关键设计决策、以及已知遗留。**这份是权威，先看完再动手。**
- 设计文档 `armada/docs/superpowers/specs/2026-08-16-group-event-direct-projection-design.md`
  的 §17（§17.8 替代原 §13）

## 环境

第一套环境 `test1`，配置档 `armada/armada-deploy/envs/test1.conf`。
**全部 pem 在 `~/IdeaProjects/测试pem/`**，权限已就绪。

| 角色 | 连接 | 备注 |
|---|---|---|
| armada 后端 + 前端 | `ssh -i ~/IdeaProjects/测试pem/dev-1.pem ubuntu@65.2.123.53` | 远端 `/home/app/armada-deploy`，容器 `armada-backend`，compose `docker-compose.rds.yml` |
| armada-protocol（Web/Baileys） | `ssh -i ~/IdeaProjects/测试pem/protocol.pem ec2-user@65.2.122.109` | PM2 起，健康端口 8080，远端 `/home/ec2-user/armada-protocol` |
| 安卓 fleet ×3 | `android-protocol-one.pem` → `ec2-user@3.110.158.204`；`armada-test01-proto-go-02.pem` → `ec2-user@13.234.76.66`；`armada-test01-proto-go-03.pem` → `ec2-user@13.232.214.247` | coordinator 端口 9100 |

**查 armada 库**（schema = `armada`，RDS，不是 wheel_tenant）：凭证不要往本地拷，
ssh 进 1 号机从 `/home/app/armada-deploy/.env` 读 `DB_URL/DB_USER/DB_PASSWORD` 就地跑 mysql：

```bash
ssh -i ~/IdeaProjects/测试pem/dev-1.pem ubuntu@65.2.123.53 'bash -s' <<'EOF'
cd /home/app/armada-deploy
DB_URL=$(sed -n 's/^DB_URL=//p' .env|tail -1|tr -d '\r'); DB_USER=$(sed -n 's/^DB_USER=//p' .env|tail -1|tr -d '\r'); DB_PASSWORD=$(sed -n 's/^DB_PASSWORD=//p' .env|tail -1|tr -d '\r')
t=${DB_URL#jdbc:mysql://}; auth=${t%%/*}; dbn=${t#*/}; dbn=${dbn%%\?*}; h=${auth%:*}; p=${auth##*:}
MYSQL_PWD=$DB_PASSWORD mysql --batch -h "$h" -P "$p" -u "$DB_USER" "$dbn" <<'SQL'
SELECT 1;
SQL
EOF
```

## 第一步：部署

三个仓都要部署，分支都是各自的当前开发分支。部署脚本一个就够：

```bash
cd ~/IdeaProjects/armada
./armada-deploy/deploy-test.sh --env test1 --check      # 先看目标环境对不对
./armada-deploy/deploy-test.sh --env test1 --be -y      # armada 后端
./armada-deploy/deploy-test.sh --env test1 --protocol -y # armada-protocol（Web）
./armada-deploy/deploy-test.sh --env test1 --zhuan -y   # 安卓 fleet
```

要上线的关键提交：

| 仓 | 提交 | 内容 |
|---|---|---|
| armada | `6b550f5f` | 放行成员 add/remove |
| armada | `02ffc7cc` | 放行 modify（PN/LID 身份合并） |
| armada | `95b1308d` | 群同步命令按后端分流 |
| armada-protocol | `32e2232` | Web 直接发群资料 patch + 删两处旧的回查请求 |
| android-zhuan | `447b2f7` | 安卓接住群同步命令 |
| android-zhuan | `96cbb95` | 安卓补报进群审批状态 |

部署前确认 Flyway 迁移已到 **V127**（`V126` 是并发会话的一次性身份归并迁移，也必须已执行）。

```sql
SELECT version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

## 第二步：前置检查（这条错了后面全白做）

**账号群同步定时任务必须保持关闭。**

```bash
ssh -i ~/IdeaProjects/测试pem/dev-1.pem ubuntu@65.2.123.53 \
  'grep -i "ACCOUNT_GROUP_SYNC_ENABLED" /home/app/armada-deploy/.env || echo "(未配置=默认 false=关闭)"'
```

为什么：这个任务开着会每 3 分钟拉一次全量群列表，把群资料刷新一遍，
**会盖住事件链路的问题，让你误判「事件通了」，实际是定时刷新在干活**。
0818 实测它一直是关的，保持原样即可，不要打开。

## 第三步：验证清单

每一项都按「怎么触发 → 查什么 → 期望」执行，逐项记录真实输出。
**Web 号和安卓号要分别做**，两条链路完全不同。

准备：挑一个测试群，记下它的群 JID，先查出基线：

```sql
SELECT g.id, g.group_jid, p.subject, p.description, p.announce_only, p.admin_only_edit_info,
       p.member_add_mode, p.join_approval_mode, p.ephemeral_duration_seconds,
       p.subject_source, p.subject_observed_at, p.metadata_observed_at
FROM wa_group g JOIN wa_group_profile p ON p.group_id = g.id
WHERE g.group_jid = '<群JID>';
```

### A. Web 号：改群名

- **触发**：用一个 Web 号所在的群，在手机上改群名
- **查协议层**：`ssh` 到 65.2.122.109，`pm2 logs` 找 `group.metadata_updated`；
  确认事件 `data.fieldMask` 含 `subject`、`data.subject` 是新群名、`source=wa_groups_update`
- **查 armada**：`docker compose logs armada-backend | grep "协议群资料事件收到"`，看 `fields=[subject]`
- **查库**：上面那条 SQL，`subject` 变成新名字，`subject_source` 有值，`subject_observed_at` 是刚才的时间
- **期望**：三处都对上。**特别注意 `metadata_observed_at`（整行水位）不应该被推进**——
  字段级 patch 只推字段自己的水位，这是设计要求

### B. Web 号：改群设置（逐项）

分别开/关这几项，每次只改一个，各查一遍：

| 手机上的操作 | fieldMask 里应出现 | 库里的列 |
|---|---|---|
| 「仅管理员可发消息」开 / 关 | `announceOnly` | `announce_only` |
| 「仅管理员可编辑群信息」开 / 关 | `adminOnlyEditInfo` | `admin_only_edit_info` |
| 「成员可添加其他成员」开 / 关 | `memberAddMode` | `member_add_mode` |
| 「新成员需管理员批准」开 / 关 | `joinApprovalMode` | `join_approval_mode` |
| 改群简介 | `description` | `description` |
| 开 / 关限时消息 | `ephemeralDurationSeconds` | `ephemeral_duration_seconds` |

**重点验「关」的那一半**：把某项从开改成关，库里必须变成 0/false，
**不能因为值是 false 就被当成「没观察到」而保持原值**。这是本次改动最容易错的地方，务必逐项确认。

清空群简介也要试：期望 `description` 变成 NULL，而不是保持旧简介。

### C. Web 号：有人进群

- **触发**：拉一个人进群
- **查**：`grep "协议群成员事件收到"`，`action=add`
- **查库**：

```sql
SELECT pn_jid, lid_jid, phone, presence_status, presence_source, presence_observed_at,
       last_joined_at, role, role_source
FROM wa_group_participant
WHERE group_id = (SELECT id FROM wa_group WHERE group_jid = '<群JID>')
ORDER BY updated_at DESC LIMIT 5;
```

- **期望**：新成员 `presence_status=1`（在群），`last_joined_at` 有值，
  **`role` 保持 0（未知），不能被写成 1**——加人事件没有观察到角色

### D. Web 号：踢人 vs 主动退群（这项最容易出错）

分两次做，**必须区分开**：

1. **管理员踢人**：期望 `presence_status=2`、`last_exit_type='REMOVED'`、`last_exit_source_type='WEB_NOTIFICATION'`
2. **成员自己退群**：期望 `last_exit_type='LEFT'`
3. **一次踢多个人**（如果能操作）：期望全部 `last_exit_type='UNKNOWN'`——批量时无法把操作人对应到具体某个目标，宁可不判

**如果 1 和 2 判反了，是严重问题**，把事件原文（`data.operator` 和 `data.participants`）抓下来一起报。

### E. 受控账号自己进退群 → 账号群关系

- **触发**：把我们自己的一个受控号拉进群 / 踢出群
- **查库**：

```sql
SELECT b.account_id, b.membership_status, b.updated_at
FROM wa_account_group_binding b
WHERE b.group_id = (SELECT id FROM wa_group WHERE group_jid = '<群JID>');
```

- **期望**：受控号的关系跟着变。这条链路是本次新接的（落库后额外做一次收敛），
  没接对的话成员表变了但这张表不变

### F. 安卓号：进退群回归

安卓走的是另一条老链路（`account.group_participant_joined/departed`），本次没改它，
但要确认没被改坏。用安卓号所在的群做一次进群、一次退群，查同样的表。

### G. 首次建档：新号上线拉全量

**这一半从提交到现在也没验过。**

- **触发**：让一个新的 Web 号上线（或把某个号下线再上线）
- **查协议层**：日志找 `group.profile_reported`，确认按群逐条发、每条带 7 个资料字段和成员列表
- **查 armada**：`grep "协议群资料上报事件收到"`，看 `memberCount` 和 `membersComplete`
- **期望**：`wa_group_profile` 里 7 个字段都有值（不只是群名），`wa_group_participant` 有成员
- **同时观察**：上线过程中协议层机器的内存。这次改成了拉完整 metadata（比以前重），
  有并发闸（默认 2，环境变量 `MAX_CONCURRENT_FULL_GROUP_SYNC`）。
  记录 groupCount / 查询耗时 / heap，作为并发闸是否够用的证据

### H. 负向验证：确认不再回查全量

设计的核心目标就是「少查 metadata」。做完 A~E 之后：

```bash
# 协议层：这个事件应该一条都没有了（32e2232 已删除其生产者）
pm2 logs --lines 2000 | grep -c "account.group_metadata_sync_requested"
```

- **期望**：**0 条**。改群名、进退群都不应再触发全量 metadata 回查
- 首次建档（G）那一次的完整查询属于允许，不算违反

### I. modify（身份合并）—— 碰到就看，触发不了不强求

`modify` 是低频事件（同一个人的身份编号变化）。有一条**已知的实测结论要验**：

V126 迁移里记着「Web 与 Android 的群成员列表均只返回 LID 身份，test1 抽样 4 个群 188 名成员，
PN 形式 0 个」。而我们的 modify 处理**要求同时拿到 LID 和号码两种身份才会合并**，
只有一种时会跳过并记日志。

- **要查的**：`grep "协议群成员事件收到.*action=modify"`，以及
  `grep "没有可合并的双身份"`。如果一直是后者，说明协议层没给号码，这个分支实际形同虚设，
  **这是个需要上报的发现**，不要放过

## 第四步：必须抓的样本（这次去 test1 最重要的产出之一）

安卓侧「改群名/改群设置」的即时上报**还没做**，卡在没有真实样本。你这次去，请务必抓到：

- **操作**：用**安卓号**所在的群，改一次群名、开一次「仅管理员可发消息」、改一次群简介
- **抓什么**：安卓 fleet 机器上，WhatsApp 下发的 `w:gp2` notification **原始节点**。
  相关解析代码在 `internal/service/node/processor/group_notification.go` 的 `parseWGP2GroupEvent`，
  它目前只认 9 种动作（create/invite/add/remove/leave/promote/demote/suspended/terminated），
  群资料类的通知会走到「返回 nil」的默认分支。
  **建议在那个默认分支加一条临时的 debug 日志，把整个节点打出来**（脱敏：去掉手机号），
  抓完样本后这条日志可以留着也可以去掉，在报告里说明
- **要拿到的信息**：动作的 tag 叫什么（`subject`？`description`？`announce`/`not_announce`？
  `locked`/`unlocked`？`ephemeral`？），新值放在属性里还是子节点里，叫什么名字
- **顺便确认**：改群简介的通知里**到底带不带简介正文**。这个很关键——
  安卓查群列表时拿不到简介（协议 IQ 响应里就没这个字段），
  如果通知里带得到，那安卓的简介就还有救；带不到就是永久空

把抓到的原始节点脱敏后贴进报告，这是解锁最后一块的钥匙。

## 预存失败清单（这些红的不是你弄坏的，别去修）

- armada `AccountGroupCurrentSnapshotPersistenceMySqlTest`：3 个
- armada `AccountGroupCurrentSnapshotPersistenceImplTest`：2 个（`BaselineEvidence.capturedAt()` 为 null 的 NPE）
- armada `HistoricalGroupPullWorkerImplTest`：2 个；`ProtocolCommandPublisherTest`：1 个
- armada 全部 `*DbTest`：本机无真库时整类起不来，属环境
- armada-protocol heartbeat `suppresses groups.update caused by explicit metadata reads`：1 个，
  自 `90c7bc0` 起坏（那个提交把 metadata 读取换成自行发 IQ，没同步测试 mock）
- android-zhuan `pkg/noise`：8 个（加密库，与本次无关）

## 环境上的坑（会浪费你时间的）

- **armada-protocol 本地 node_modules 可能是坏的**：`typescript` 和 `@types/node` 没装、
  `.bin` 全是断链，而 `npx tsc` 会去装一个错的包（`tsc@2.0.4`）。
  修法：`npm install`，然后用 `npm run lint` 而不是 `npx tsc`。
  注意它会把 `package-lock.json` 的 `engines.node` 从 >=20 改成 >=24，与本次无关，别提交
- armada 没有根 pom，跑测试要 `mvn -f armada-api/pom.xml`；多个测试类用**逗号**分隔
- Testcontainers 需要 OrbStack：`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`
  + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` + `TESTCONTAINERS_RYUK_DISABLED=true`
- 部署脚本里 `$VAR` 后紧跟全角符号一律要写 `${VAR}`
- zsh 里 `grep --include='*.ts'` 的通配符必须加引号

## 交付：验证报告

按验证清单逐项给出 **触发了什么 / 看到什么 / 期望什么 / 过没过**，附真实日志与 SQL 输出。
另外单独列出：

1. **失败项**：每项写清楚现象、涉及的事件原文、你定位到的根因（能定位的话）。**不要顺手改**
2. **安卓 WGP2 群资料通知的原始节点样本**（脱敏），以及简介正文带不带得到的结论
3. **首次建档的性能数据**：groupCount、查询耗时、heap 峰值，用于判断并发闸默认值 2 够不够
4. **`account.group_metadata_sync_requested` 的实际计数**（期望 0）
5. 你觉得该记进 `.harness/changes/2026-08-17-group-event-direct-projection.md` 的新事实

## 最后提醒

这套改动删掉了 Web 群资料**原来唯一在跑**的更新路径（改群名 → 请求 → 回查全量），
换成了新的字段级 patch。定时任务是关的，**没有兜底**。
所以「B 组（改群设置）全过」这件事，比其他任何一项都重要——它不过，
就意味着 Web 群资料在生产上会从「能更新」变成「完全不更新」。
