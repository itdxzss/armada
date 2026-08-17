# test1 验证任务：群变更事件直接投影（纯接口驱动）

## 你要做什么

把「群直投影」这套改动部署到 test1，验证「群里的人和群资料一变，控制台能不能自己更新」。
**这套代码从头到尾没跑过真实数据，你是第一个。**

test1 上**没有可操作的手机**，账号全是靠凭证登录的协议账号。所有触发动作都通过
**协议层 HTTP 接口**完成，本文档给了每一条的 curl。

**重要：你的产出是一份验证报告，不是修代码。** 发现问题先记录、定位根因，不要顺手改。
改与不改由人决定——除非部署本身卡住（比如配置缺项），那种可以就地修。

## 先读

- `armada/AGENTS.md` 与 `armada/.harness/rules/`（红线：真库/远程/部署前必须确认目标环境）
- `armada/.harness/changes/2026-08-17-group-event-direct-projection.md` —— 改动背景、
  12 条提交索引、10 条关键设计决策、已知遗留。**权威，先看完再动手。**
- 设计文档 `armada/docs/superpowers/specs/2026-08-16-group-event-direct-projection-design.md` §17

## 环境

第一套环境 `test1`，配置档 `armada/armada-deploy/envs/test1.conf`。pem 全在 `~/IdeaProjects/测试pem/`。

| 角色 | 连接 | 备注 |
|---|---|---|
| armada 后端 | `ssh -i ~/IdeaProjects/测试pem/dev-1.pem ubuntu@65.2.123.53` | `/home/app/armada-deploy`，容器 `armada-backend`，compose `docker-compose.rds.yml` |
| armada-protocol（Web） | `ssh -i ~/IdeaProjects/测试pem/protocol.pem ec2-user@65.2.122.109` | PM2，HTTP 端口 8080，`/home/ec2-user/armada-protocol`，env 文件是 **`protocol.env`**（不是 `.env`） |
| 安卓 fleet ×3 | `android-protocol-one.pem`→`ec2-user@3.110.158.204`；`armada-test01-proto-go-02.pem`→`ec2-user@13.234.76.66`；`armada-test01-proto-go-03.pem`→`ec2-user@13.232.214.247` | coordinator 9100 |

**协议层 API 鉴权**：请求头 `x-api-key`，值在 `protocol.env` 的 `API_KEYS`（逗号分隔，取第一个）。
**不要把 key 打印进报告或提交进仓库。** 建议在协议层机器上本地取值调用：

```bash
ssh -i ~/IdeaProjects/测试pem/protocol.pem ec2-user@65.2.122.109 'bash -s' <<'EOF'
KEY=$(grep -E "^API_KEYS=" /home/ec2-user/armada-protocol/protocol.env | cut -d= -f2- | cut -d, -f1)
curl -s -H "x-api-key: $KEY" http://127.0.0.1:8080/v1/health || true
EOF
```

**查 armada 库**（schema `armada`，RDS）：凭证不要往本地拷，ssh 进 1 号机就地跑：

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

```bash
cd ~/IdeaProjects/armada
./armada-deploy/deploy-test.sh --env test1 --check       # 先确认目标环境
./armada-deploy/deploy-test.sh --env test1 --be -y       # armada 后端
./armada-deploy/deploy-test.sh --env test1 --protocol -y # armada-protocol（Web）
./armada-deploy/deploy-test.sh --env test1 --zhuan -y    # 安卓 fleet
```

关键提交：armada `6b550f5f`（放行 add/remove）、`02ffc7cc`（放行 modify）、`95b1308d`（命令按后端分流）；
armada-protocol `32e2232`（Web 直接发群资料 patch + 删两处旧回查请求）；
android-zhuan `447b2f7`（接住群同步命令）、`96cbb95`（补报进群审批状态）。

部署后确认 Flyway 到 **V127**（`V126` 是并发会话的一次性身份归并迁移，也必须已执行）：

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
```

## 第二步：前置检查（这条错了后面全白做）

**账号群同步定时任务必须保持关闭。**

```bash
ssh -i ~/IdeaProjects/测试pem/dev-1.pem ubuntu@65.2.123.53 \
  'grep -i "ACCOUNT_GROUP_SYNC_ENABLED" /home/app/armada-deploy/.env || echo "(未配置=默认false=关闭)"'
```

开着会每 3 分钟拉一次全量群列表把资料刷一遍，**盖住事件链路的问题，让你误判「事件通了」**，
实际是定时刷新在干活。0818 实测它一直是关的，保持原样，不要打开。

## 第三步：准备两个号 + 一个群（验证方案的基础）

**为什么要两个号**：如果用 A 号改群名，WhatsApp 不一定会把这个变更再推回 A 号自己
（它已经知道了）。所以必须 **A 号动手、B 号观察**，我们验的是 **B 号有没有收到事件**。
这样无论 WhatsApp 回不回推给操作者，验证都成立；同时这也更贴近真实场景（别人改了群）。

找出当前在线、且在同一个群里的两个 Web 号：

```sql
SELECT a.id AS account_id, a.protocol_account_id, a.ws_phone,
       g.group_jid, p.subject,
       CASE WHEN c.cred_format = 1 THEN 'ANDROID'
            WHEN c.cred_format IN (2,3) THEN 'WEB'
            WHEN UPPER(a.protocol_id) = 'ANDROID' THEN 'ANDROID' ELSE 'WEB' END AS backend
FROM account a
JOIN account_state s ON s.account_id = a.id AND s.tenant_id = a.tenant_id
LEFT JOIN account_credential c ON c.account_id = a.id AND c.tenant_id = a.tenant_id
JOIN wa_account_group_binding b ON b.account_id = a.id AND b.tenant_id = a.tenant_id
JOIN wa_group g ON g.id = b.group_id
LEFT JOIN wa_group_profile p ON p.group_id = g.id
WHERE a.deleted_at IS NULL AND s.login_state = 1 AND s.account_state = 1
ORDER BY g.group_jid, a.id;
```

挑一个**至少有两个在线号**的群，其中 **A 号必须是管理员**（改群设置需要管理员权限，
接口里有 `runGroupAdminMutation` 兜底，不是管理员会报错）。记下：

- `GROUP_JID`、`ACTOR`（A 的 protocol_account_id）、`OBSERVER`（B 的 protocol_account_id）
- 另外准备 1~2 个可被拉进群的号码（料子号），用于成员增减

记录基线：

```sql
SELECT g.id, g.group_jid, p.subject, p.description, p.announce_only, p.admin_only_edit_info,
       p.member_add_mode, p.join_approval_mode, p.ephemeral_duration_seconds,
       p.subject_source, p.subject_observed_at, p.metadata_observed_at
FROM wa_group g JOIN wa_group_profile p ON p.group_id = g.id
WHERE g.group_jid = '<GROUP_JID>';
```

## 第四步：验证清单

统一的调用骨架（在协议层机器上跑，`$KEY` 按前面的方式取）：

```bash
call() {  # call <path> <json>
  curl -s -X POST "http://127.0.0.1:8080$1" \
    -H "x-api-key: $KEY" -H 'Content-Type: application/json' -d "$2"
  echo
}
```

每项都按「触发 → 查协议层 → 查 armada → 查库」执行，逐项记录真实输出。
**每次只改一项，改完等 5~10 秒再查**，避免多个事件混在一起分不清。

### A. 改群名

```bash
call "/v1/groups/$GROUP_JID/subject" '{"accountId":"'"$ACTOR"'","subject":"验证-群名-0818-1"}'
```

- **协议层**：`pm2 logs --lines 200 | grep group.metadata_updated`，
  确认有一条 `accountId` 是 **OBSERVER**、`data.fieldMask` 含 `subject`、
  `data.subject` 是新群名、`data.source=wa_groups_update`
- **armada**：`docker compose -f docker-compose.rds.yml logs armada-backend | grep "协议群资料事件收到"`，看 `fields=[subject]`
- **库**：基线那条 SQL，`subject` 变成新名字，`subject_source` 有值，`subject_observed_at` 是刚才
- **额外确认**：`metadata_observed_at`（整行水位）**不应被推进**——字段级 patch 只推字段自己的水位，这是设计要求

### B. 改群设置（六项，每项都要开和关各来一次）

**「关」的那一半比「开」重要。** 本次改动最容易错的地方就是：值为 `false`/`0` 时被误当成
「没观察到这个字段」而保持原值，表现为**只能开不能关**。逐项确认库里真的变回去了。

```bash
# 1. 仅管理员可发消息
call "/v1/groups/$GROUP_JID/settings/announcement" '{"accountId":"'"$ACTOR"'","mode":"announcement"}'
call "/v1/groups/$GROUP_JID/settings/announcement" '{"accountId":"'"$ACTOR"'","mode":"not_announcement"}'

# 2. 仅管理员可编辑群信息
call "/v1/groups/$GROUP_JID/settings/locked" '{"accountId":"'"$ACTOR"'","mode":"locked"}'
call "/v1/groups/$GROUP_JID/settings/locked" '{"accountId":"'"$ACTOR"'","mode":"unlocked"}'

# 3. 成员可否添加成员
call "/v1/groups/$GROUP_JID/settings/member-add-mode" '{"accountId":"'"$ACTOR"'","mode":"all_member_add"}'
call "/v1/groups/$GROUP_JID/settings/member-add-mode" '{"accountId":"'"$ACTOR"'","mode":"admin_add"}'

# 4. 进群审批
call "/v1/groups/$GROUP_JID/settings/join-approval" '{"accountId":"'"$ACTOR"'","mode":"on"}'
call "/v1/groups/$GROUP_JID/settings/join-approval" '{"accountId":"'"$ACTOR"'","mode":"off"}'

# 5. 群简介（含清空）
call "/v1/groups/$GROUP_JID/description" '{"accountId":"'"$ACTOR"'","description":"验证简介-0818"}'
call "/v1/groups/$GROUP_JID/description" '{"accountId":"'"$ACTOR"'","description":null}'

# 6. 限时消息（mode 取值见 EphemeralMode，先 GET 一次群详情确认可用值）
call "/v1/groups/$GROUP_JID/settings/ephemeral" '{"accountId":"'"$ACTOR"'","mode":"24h"}'
call "/v1/groups/$GROUP_JID/settings/ephemeral" '{"accountId":"'"$ACTOR"'","mode":"off"}'
```

对照表：

| 接口 | fieldMask 里应出现 | 库里的列 | 关掉后期望 |
|---|---|---|---|
| settings/announcement | `announceOnly` | `announce_only` | 0，不是保持 1 |
| settings/locked | `adminOnlyEditInfo` | `admin_only_edit_info` | 0 |
| settings/member-add-mode | `memberAddMode` | `member_add_mode` | admin_add → 0 |
| settings/join-approval | `joinApprovalMode` | `join_approval_mode` | off → 0 |
| description | `description` | `description` | 清空后 **NULL**，不是保持旧简介 |
| settings/ephemeral | `ephemeralDurationSeconds` | `ephemeral_duration_seconds` | **0**，不是 NULL 也不是保持旧值 |

**`ephemeral` 关成 0 这条要特别盯**：0 是「明确关闭」，必须落库；如果代码把 0 当成假值跳过，
库里会保持旧的 86400，这就是漏洞。

### C. 有人进群

```bash
call "/v1/groups/$GROUP_JID/participants/add" \
  '{"accountId":"'"$ACTOR"'","participants":["<料子号>@s.whatsapp.net"]}'
```

```sql
SELECT pn_jid, lid_jid, phone, presence_status, presence_source, presence_observed_at,
       last_joined_at, role, role_source
FROM wa_group_participant
WHERE group_id = (SELECT id FROM wa_group WHERE group_jid = '<GROUP_JID>')
ORDER BY updated_at DESC LIMIT 5;
```

- **期望**：新成员 `presence_status=1`、`last_joined_at` 有值、
  **`role` 保持 0（未知），不能被写成 1** —— 加人事件没有观察到角色

### D. 踢人 vs 主动退群（这项最容易判反）

分两次做，**必须区分开**：

```bash
# D1 管理员踢人：ACTOR 把料子号踢出去
call "/v1/groups/$GROUP_JID/participants/remove" \
  '{"accountId":"'"$ACTOR"'","participants":["<料子号>@s.whatsapp.net"]}'

# D2 主动退群：让 OBSERVER 自己退群（退之前先确认群里还有别的我方号能观察到）
call "/v1/groups/$GROUP_JID/leave" '{"accountId":"'"$OBSERVER"'"}'

# D3 批量踢（可选）：一次踢两个
call "/v1/groups/$GROUP_JID/participants/remove" \
  '{"accountId":"'"$ACTOR"'","participants":["<号1>@s.whatsapp.net","<号2>@s.whatsapp.net"]}'
```

| 场景 | `last_exit_type` 期望 | 理由 |
|---|---|---|
| D1 管理员踢人 | `REMOVED` | 操作人与目标明确不是同一人 |
| D2 自己退群 | `LEFT` | 操作人就是目标本人 |
| D3 批量踢 | 全部 `UNKNOWN` | 一次多个目标，无法把操作人对应到具体某个，宁可不判 |

三种都应有 `presence_status=2`、`last_exit_source_type='WEB_NOTIFICATION'`。

**如果 D1 和 D2 判反了，是严重问题**——把事件原文里的 `data.operator` 和
`data.participants` 一起抓下来报上来。

**D2 做完记得把 OBSERVER 拉回群**，后面还要用。

### E. 受控账号自己进退群 → 账号群关系

D 组里 OBSERVER 是我方受控号，它退群后查：

```sql
SELECT b.account_id, b.membership_status, b.updated_at
FROM wa_account_group_binding b
WHERE b.group_id = (SELECT id FROM wa_group WHERE group_jid = '<GROUP_JID>');
```

- **期望**：OBSERVER 的关系跟着变成「不在群」。这条是本次新接的（成员事实落库后额外做一次收敛），
  没接对的话**成员表变了但这张表不变**——这正是要验的点

### F. 角色变更回归（本次没改，确认没弄坏）

```bash
call "/v1/groups/$GROUP_JID/participants/promote" '{"accountId":"'"$ACTOR"'","participants":["<某号>@s.whatsapp.net"]}'
call "/v1/groups/$GROUP_JID/participants/demote"  '{"accountId":"'"$ACTOR"'","participants":["<某号>@s.whatsapp.net"]}'
```

期望 `wa_group_participant.role` 在 2（管理员）和 1（成员）之间切换，`role_source` 有值。

### G. 安卓号回归 + 首次建档

- **安卓进退群**：找一个安卓号所在的群，用同样的接口做一次加人、一次踢人，查同样的表。
  安卓走的是另一条老链路，本次没改它，但要确认没被改坏
- **首次建档**（这一半也从没验过）：让一个 Web 号下线再上线

```bash
# 下线 / 上线走 armada 后台或协议层 lifecycle 接口，按现有运维方式操作
```

- **查协议层**：日志找 `group.profile_reported`，确认按群逐条发、每条带资料字段和成员列表
- **查 armada**：`grep "协议群资料上报事件收到"`，看 `memberCount` 和 `membersComplete`
- **期望**：`wa_group_profile` 里字段都有值（不只是群名），`wa_group_participant` 有成员
- **同时记录**：上线过程中协议层的 groupCount / 查询耗时 / heap 峰值。
  这次改成拉完整 metadata（比以前重），有并发闸（默认 2，环境变量 `MAX_CONCURRENT_FULL_GROUP_SYNC`），
  这组数据用来判断默认值够不够

### H. 负向验证：确认不再回查全量

设计的核心目标就是「少查 metadata」。做完 A~F 之后：

```bash
pm2 logs --lines 3000 | grep -c "account.group_metadata_sync_requested"
```

- **期望 0 条**。改群名、进退群都不应再触发全量 metadata 回查
- G 组首次建档那一次的完整查询属于允许，不算违反

### I. modify（身份合并）—— 碰到就看，触发不了不强求

`modify` 是低频事件。有一条**已知实测结论要复核**：V126 迁移里记着
「Web 与 Android 的群成员列表均只返回 LID 身份，test1 抽样 4 个群 188 名成员，PN 形式 0 个」。
而我们的 modify 处理**要求同时拿到 LID 和号码两种身份才会合并**，只有一种时跳过并记日志。

要查：`grep "协议群成员事件收到.*action=modify"` 以及 `grep "没有可合并的双身份"`。
**如果一直是后者，说明协议层没给号码，这个分支实际形同虚设——这是需要上报的发现，不要放过。**

## 第五步：必须抓的样本（这次最重要的产出之一）

安卓侧「改群名/改群设置」的即时上报**还没做**，卡在没有真实样本。请务必抓到：

- **操作**：对**安卓号**所在的群，用上面同样的接口改一次群名、开一次 announcement、改一次简介
  （注意：要用安卓号作为 ACTOR 还是只要安卓号在群里当观察者都行，关键是安卓号能收到通知）
- **抓什么**：安卓 fleet 机器上 WhatsApp 下发的 `w:gp2` notification **原始节点**。
  解析代码在 `internal/service/node/processor/group_notification.go` 的 `parseWGP2GroupEvent`，
  它只认 9 种动作（create/invite/add/remove/leave/promote/demote/suspended/terminated），
  群资料类通知会走到「返回 nil」的默认分支。
  **建议在那个默认分支加一条临时 debug 日志，把整个节点打出来**（脱敏：去掉手机号）。
  抓完后这条日志留不留都行，在报告里说明
- **要拿到**：动作 tag 叫什么（`subject`？`description`？`announce`/`not_announce`？
  `locked`/`unlocked`？`ephemeral`？），新值在属性里还是子节点里，字段名是什么
- **顺带确认（很关键）**：改群简介的通知里**到底带不带简介正文**。
  安卓查群列表时拿不到简介（协议 IQ 响应里就没这个字段），
  通知里带得到，安卓的简介就还有救；带不到就是永久空，需要产品定口径

把脱敏后的原始节点贴进报告，这是解锁最后一块的钥匙。

## 预存失败清单（这些红的不是你弄坏的，别去修）

- armada `AccountGroupCurrentSnapshotPersistenceMySqlTest`：3 个
- armada `AccountGroupCurrentSnapshotPersistenceImplTest`：2 个（`BaselineEvidence.capturedAt()` NPE）
- armada `HistoricalGroupPullWorkerImplTest`：2 个；`ProtocolCommandPublisherTest`：1 个
- armada 全部 `*DbTest`：本机无真库时整类起不来，属环境
- armada-protocol heartbeat `suppresses groups.update caused by explicit metadata reads`：1 个，
  自 `90c7bc0` 起坏（那次把 metadata 读取换成自行发 IQ，没同步测试 mock）
- android-zhuan `pkg/noise`：8 个（加密库，与本次无关）

## 环境上的坑（会浪费你时间的）

- 协议层 env 文件叫 **`protocol.env`**，不是 `.env`
- **armada-protocol 本地 node_modules 可能是坏的**：`typescript`、`@types/node` 没装、`.bin` 断链，
  而 `npx tsc` 会去装一个错的包（`tsc@2.0.4`）。修法：`npm install`，用 `npm run lint` 而不是 `npx tsc`。
  它会把 `package-lock.json` 的 `engines.node` 从 >=20 改成 >=24，与本次无关，别提交
- armada 没有根 pom，跑测试要 `mvn -f armada-api/pom.xml`；多个测试类用**逗号**分隔
- Testcontainers 需要 OrbStack：`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`
  + `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` + `TESTCONTAINERS_RYUK_DISABLED=true`
- 部署脚本里 `$VAR` 后紧跟全角符号一律写 `${VAR}`
- zsh 里 `grep --include='*.ts'` 的通配符必须加引号
- 改群设置需要 **ACTOR 是该群管理员**，否则接口报错

## 交付：验证报告

按清单逐项给出 **触发了什么 / 看到什么 / 期望什么 / 过没过**，附真实日志与 SQL 输出。
另外单独列出：

1. **失败项**：现象、涉及的事件原文、你定位到的根因（能定位的话）。**不要顺手改**
2. **安卓 `w:gp2` 群资料通知的原始节点样本**（脱敏），以及简介正文带不带得到的结论
3. **首次建档的性能数据**：groupCount、查询耗时、heap 峰值，用于判断并发闸默认值 2 够不够
4. **`account.group_metadata_sync_requested` 的实际计数**（期望 0）
5. **操作者自己能不能收到自己改动的事件**（A 号改群名，A 号自己有没有收到 `groups.update`）——
   这个结论决定以后还需不需要双号验证，值得单独记一笔
6. 你觉得该记进 `.harness/changes/2026-08-17-group-event-direct-projection.md` 的新事实

## 最后提醒

这套改动删掉了 Web 群资料**原来唯一在跑**的更新路径（改群名 → 请求 → 回查全量），
换成了新的字段级 patch。定时任务是关的，**没有兜底**。

所以 **B 组（改群设置，尤其是「关」的那一半）比其他任何一项都重要**——它不过，
就意味着 Web 群资料在生产上会从「能更新」变成「完全不更新」。
