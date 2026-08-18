# test1 群变更事件直接投影验证报告

- 验证环境：第一套测试环境 `test1`
- 执行时间：2026-08-18 07:42 CST
- 验证范围：Armada 后端、前端、Web 协议、Android 协议 fleet
- 结论：**部署已校正；账号上线后复验仍发现阻断缺陷，当前不具备发布放行条件**

## 1. 总结

后端和前端最初不是当前构建，已重新部署并验活；Web 协议和 Android 协议远端源码与本地关键文件一致，进程/容器健康，因此没有为了“重复部署”而重启在线账号。

验证阶段遇到两个阻断条件：

1. test1 中符合条件的正常在线 Web 账号为 **0**，无法建立“A 号操作、B 号观察”的验证基线；现有后台登录凭据也不能通过认证，无法按正常运维链路重新上线账号。
2. Android 数据库记录有 179 个正常在线账号，coordinator 则持有 209 个账号归属；选定测试群的管理员群列表 IQ 连续出现 `iq processor closed` / timeout。幂等的群设置写请求可以成功，但当前代码不会记录未识别的 `w:gp2` 原始节点。若为抓样本部署临时日志，需要重启协议节点并影响大量在线会话，本次未在账号状态不一致时继续扩大扰动。

因此 A～F、G 的首次建档和 Android 原始通知样本均未形成有效验收闭环。H 观察窗口内计数为 0，但因为 A～F 没有成功触发，只能作为现状记录，不能据此判定通过。

2026-08-18 08:00 CST 后用户重新上线 Web 账号，已继续复验。双在线和实时管理员条件恢复，但两个独立测试群的群名修改、以及 announcement 开/关均被 WhatsApp 拒绝为 `423 locked`；首次建档还暴露出 `group.profile_reported` 协议发布成功、Armada 接收计数却为 0 的新问题。详见第 8 节。

## 2. 部署检查与处理

### 2.1 版本和制品

| 项目 | 本地版本 | 部署前 | 处理 | 当前结果 |
|---|---|---|---|---|
| Armada 后端 | `1.0.3-snapshot` / `5308d9f1` | 远端 JAR 缺少本次新消费者/协调代码 | 重新部署后端 | 远端 JAR SHA-256 与本地一致，容器正常启动 |
| 前端 | `1.0.3-snapshot` / `f65b6b3e` | 远端仍是旧 bundle `index-DcKCdiB5.js` | 重新部署前端 | 新 bundle `index-Dtg4CCGu.js`，Nginx 200 |
| Web 协议 | `1.0.3-snapshot` / `399a694` | 关键源码 checksum 与本地一致 | 未重复重启 | master + 4 workers 全部 online |
| Android 协议 | `1.0.3-snapshot` / `e0e9a1f` | coordinator、3 个节点关键源码 checksum 与本地一致 | 未重复重启 | coordinator 200，3/3 节点 online |

关键提交均存在：

- Armada：`6b550f5f`、`02ffc7cc`、`95b1308d`
- Web 协议：`32e2232`
- Android 协议：`447b2f7`、`96cbb95`

### 2.2 部署阻塞配置修正

首次 `--check` 发现 test1 环境档案要求 Android 地址为公网 `65.2.123.53:9100`，但后端与 coordinator 在同一台主机，实际运行配置使用私网 `172.31.13.65:9100`。公网地址从后端容器不可达，私网 `/healthz` 为 200。

已修正：

- `armada-deploy/envs/test1.conf` 的 `EXPECTED_ANDROID_BASE_URL`
- `armada-deploy/deploy-test.test.sh` 中对应环境档案断言

远端后端 `.env` 原先未显式声明三个 Android topic（运行时默认值正确）。已在不输出密钥的前提下补齐：

- `protocol.android.lifecycle.commands.v1`
- `protocol.android.message.commands.v1`
- `protocol.android.group-join.commands.v1`

原远端配置备份为 `/home/app/armada-deploy/.env.codex-backup-20260818-group-verify`。

### 2.3 部署验证

后端启动后：

```text
container: armada-backend Up
restart count: 0
application startup: 16.923s
GET /api/account-groups: HTTP 401（接口存活且鉴权生效）
```

前端和协议服务：

```text
frontend localhost: HTTP 200
Android coordinator /healthz: HTTP 200
Web protocol /readyz: HTTP 200
Web protocol /v1/health: HTTP 404
```

Web PM2：

```text
armada-protocol-master     online  Node 24.16.0
armada-protocol-worker-1   online  Node 24.16.0
armada-protocol-worker-2   online  Node 24.16.0
armada-protocol-worker-3   online  Node 24.16.0
armada-protocol-worker-4   online  Node 24.16.0
protocol-runtime-collector online
```

Android fleet：

```text
node 01  online  version 1.0.1  accountCount 94
node 02  online  version 1.0.1  accountCount 58
node 03  online  version 1.0.1  accountCount 57
```

Flyway：

```text
127  group profile field versions       success=1
126  group participant identity merge   success=1
125  group participant phone index      success=1
124  group member link mode              success=1
123  group link canonical references     success=1
```

`ACCOUNT_GROUP_SYNC_ENABLED` 未配置，按默认值 `false` 保持关闭。

## 3. 前置账号检查

首轮按当前真实状态码修正查询条件为 `account_state=2`（正常）后：

```text
Web 正常在线账号：0
Android 正常在线账号：179
Android coordinator 账号归属合计：209
```

Web 侧找到了历史上满足双号条件的测试群，但其中所有 Web 号现在都离线。协议 owner 端点返回 `PROXY_REQUIRED`；最近的自动重上线记录反复复用同一失败代理，最终仍离线。一个原管理员账号在 Web 协议部署/PM2 reload 时出现 `manual_offline`，部署结束后没有自动恢复。

通过 Armada 后台的正常登录接口尝试环境现有开发密码和默认测试密码，均返回密码错误；没有重置密码、伪造 JWT 或直接读取账号凭据绕过后台。

Android 侧选用名称明确为测试用途的群，基线已脱敏：

```text
group_id=379
group_jid=120363***@g.us
subject=Armada Test Group 001
description=NULL
member_count=65
announce_only=0
admin_only_edit_info=0
member_add_mode=1
join_approval_mode=NULL
metadata_observed_at=1786949931583
subject_source=NULL
description_source=NULL
announce_only_source=NULL
member_snapshot_version=legacy:13292:1786763626394
```

数据库管理员账号一度被 coordinator 状态接口判定 Online，但群列表调用先后返回：

```text
Code=1003, Msg="iq processor closed"
Code=1003, Msg="iq BuildIqCreateGroup time out id:<redacted>"
```

另一名实时 Online 的测试群管理员也在群列表请求中 25 秒无响应。作为无状态改变的探针，对原测试群发送与当前状态相同的 announcement 设置（`state=false`），返回：

```json
{"Code":0,"Data":"","Msg":""}
```

这说明 coordinator HTTP 路由和部分群写 IQ 可用，但完整群资料查询链路不稳定，数据库在线状态与协议运行态也未收敛。

## 4. A～I 验证结果

| 项目 | 触发/观察 | 期望 | 结果 |
|---|---|---|---|
| A 改群名 | 需要在线 Web 管理员 A 与观察者 B | B 收到 `group.metadata_updated`，Armada 落字段级水位，整行水位不推进 | **复验失败**：两个实时管理员、两个群均返回 WhatsApp `423 locked` |
| B 六类群资料开/关 | 同上，尤其验证 false/0/NULL | 六字段能开也能关，0 不被跳过 | **B1 复验失败**：announcement 开/关均 `423 locked`；B2～B6 未触发 |
| C 有人进群 | Web 管理员加测试成员 | presence=1、last_joined_at 有值、role 保持 0 | **阻塞**：无可用 Web 管理员 |
| D 踢人/主动退群 | 管理员踢、观察者自退、可选批量踢 | REMOVED / LEFT / UNKNOWN 正确区分 | **阻塞**：无可用 Web 双号 |
| E 受控账号关系收敛 | 观察者退群后查 binding 对应 participant | 账号群关系变为不在群 | **阻塞**；且文档 SQL 与当前表结构不符，见第 6 节 |
| F promote/demote | 管理员升降测试成员 | role 2/1 切换，role_source 更新 | **阻塞**：无可用 Web 管理员 |
| G Android 进退群 | 已选测试群并完成基线；群列表 IQ 不稳定；幂等设置写探针成功 | Android 老链路回归通过 | **未完成**：没有安全完成加人/踢人闭环 |
| G Web 首次建档 | Web 号重新上线 | `group.profile_reported`、完整资料/成员、性能数据 | **失败**：性能日志已取得，但协议发布成功 6236、Armada 接收 0 |
| H 不再全量回查 | Web PM2 最近 3000 行统计 | A～F 后为 0 | **观察值 0，不能验收**：A～F 未成功触发 |
| I modify 合并 | Web/Armada 日志查 modify 与无双身份 | 低频，触发不了可记录 | **未触发**：两侧计数均 0 |

新部署后近一小时 Armada 日志计数：

```text
协议群资料上报事件收到：0
协议群资料事件收到：0
协议群成员事件收到：0
modify：0
没有可合并的双身份：0
```

Web PM2 最近 3000 行：

```text
account.group_metadata_sync_requested：0
group.metadata_updated：0
group.profile_reported：0
modify：0
没有可合并的双身份：0
```

## 5. 必须交付项状态

### 5.1 Android `w:gp2` 原始群资料通知

**未取得。** 三台 Android 节点近 48 小时日志中没有 `w:gp2` 或可复用的原始群通知样本；当前 `parseWGP2GroupEvent` 未识别群资料动作时直接返回 `nil`，且生产配置 `debug=false`。

本次没有为添加临时日志而重启 fleet。当前 fleet 持有 209 个账号，部署脚本会并行停止三个 node/callback、释放 lease 后再切换；在数据库/协议在线状态已经不一致的情况下继续做该操作，可能扩大账号离线范围。

因此以下结论仍未知：

- 群名/简介/announcement 的真实 action tag
- 新值位于属性还是子节点
- Android 群简介通知是否包含正文

### 5.2 首次建档性能

**部分取得。** 账号重新上线后已取得 groupCount、查询耗时、并发延期次数和 worker 当前 heap；环境没有保留 heap 峰值时序，因此峰值仍缺失。详见第 8.4 节。

### 5.3 全量回查计数

观察值：`account.group_metadata_sync_requested=0`（Web PM2 最近 3000 行）。由于 A～F 没有成功触发，此值不构成通过证据。

### 5.4 操作者能否收到自己的改动

**未知。** 双号前置条件已恢复，但两个群的写请求均在 WhatsApp `423 locked` 阶段失败，没有产生可归因的 `groups.update`。

## 6. 新发现与文档偏差

1. 文档选账号 SQL 使用 `s.account_state=1`，但当前定义是 `1=新增、2=正常`。按原 SQL 会漏掉正常账号，应使用 `account_state=2`。
2. `wa_account_group_binding` 没有 `membership_status` 列。当前关系状态需通过 `participant_id` 关联 `wa_group_participant.presence_status` 判断；文档 E 的 SQL 不能执行。
3. Web 协议健康端点应为 `/readyz`；文档给出的 `/v1/health` 在当前版本返回 404。
4. Android 群 API 不是 Web 的 `/v1/groups/...` 契约。当前入口是 coordinator `/ws/v1/groups/.../:key`，请求体和布尔语义也不同，文档“同样接口”不能直接照抄。
5. Web 协议远端同时存在根目录 `protocol.env` 和 `protocol-layer/.env`；API key 来自前者，部署运行配置读取后者。仅写“env 文件是 protocol.env”不完整。
6. test1 深度检查仍会从后端探测 Web 协议公网地址、从本地探测前端公网 nip.io；这两条路径超时，但实际运行私网后端到 Web `/readyz` 为 200、后端到 Android `/healthz` 为 200、服务器本机前端为 200。属于检查工具网络路径与实际运行路径不一致。
7. test1 的 Android 期望地址原配置为同机公网地址，实际必须走私网 `172.31.13.65:9100`；已修正环境档案与测试。
8. Web 协议 PM2 reload 会把已有 Web 账号标记为 `manual_offline`，部署完成后不会自动恢复；本次因此丢失验证所需的双在线号。
9. Web 自动重上线曾连续复用同一个失败代理，最终 `proxy_failed_reonline`；代理轮换/选择没有形成有效恢复。
10. Android 数据库正常在线数 179，与 coordinator 账号归属 209 不一致；单账号还出现“状态接口 Online、群列表 IQ closed/timeout、写设置成功”的混合状态，当前在线判定不足以代表群 IQ 可用。
11. `package-prod.test.sh` 因仓库缺少 `armada-deploy/prod/scripts/inspect-production-host.sh` 失败；与 test1 部署和本次群投影逻辑无关，本次未处理。

建议把第 1～10 条补入权威变更记录；其中第 8～10 条应视为后续重新验证前的环境治理项。

## 7. 下一步解阻条件

重新执行 A～I 前至少需要：

1. 当前 Web 双在线和实时管理员条件已经恢复；先解释或解除两个独立群都返回的 WhatsApp `423 locked`，否则 A/B/C/F 的管理操作无法触发。
2. 定位 `group.profile_reported` 协议发布成功 6236 条、Armada 接收 0 条的问题，确认 topic、consumer group、partition 和真实 envelope。
3. 确认 Android 测试群管理员可以稳定完成群列表 IQ；同时收敛 DB 在线数与 coordinator owner 数。
4. 若仍要抓 Android 原始样本，安排可接受的节点重启窗口，在一个承载测试观察账号的 node 上加脱敏日志并单节点部署；不要在当前账号状态不一致时直接全 fleet 重启。

满足上述条件后，应从 A 开始重新逐项执行，尤其完整跑完 B 的“开→关”六组用例；当前报告不能作为上线放行依据。

## 8. Web 账号上线后的复验（2026-08-18 08:00～08:33 CST）

### 8.1 三层在线状态

用户重新上线后：

```text
数据库正常在线 Web 账号：169
账号 200：account_state=2, login_state=1, source=STATE_CHANGED
账号 237：account_state=2, login_state=1, source=STATE_CHANGED
协议 master：账号 200/237 均为 ONLINE，worker=w1，connectionField=open
```

账号 237 与 200 的共同群中，数据库角色为管理员/成员；实时 Web metadata 也确认 237 为 `admin`。第一次 announcement 探针时 237 短暂转为 `VERIFYING`，因此另选了状态稳定、名称明确为 Testall 的群：

```text
group_id=15651
actor account_id=169（实时 metadata: admin）
observer account_id=1222（普通成员）
baseline subject=Testall-8-16-Batch-testall-8-16-admin-20260816T140000Z-1643-50
member_count=17
announce_only=0
admin_only_edit_info=0
member_add_mode=1
join_approval_mode=0
ephemeral_duration_seconds=0
```

### 8.2 A 改群名：失败

先后在两个不同群、两个经实时 metadata 确认的管理员账号上调用群名修改，均返回：

```json
{"code":"INTERNAL_ERROR","message":"internal server error","requestId":"<redacted>"}
```

worker 原始错误一致：

```text
Error: locked
data=423
groupUpdateSubject -> runGroupAdminMutation -> HTTP 500
```

两个群的 subject 均未改变，第二个 Testall 群的字段级和整行水位也未推进。因为请求在 WhatsApp 写入阶段失败，没有产生可归因的 actor/observer `groups.update`，因此无法判断操作者会不会收到自己的变更。

结论：**A 失败**。这不是数据库管理员角色误判；协议应至少把 WhatsApp `423 locked` 映射为可识别的业务错误，而不是统一 `INTERNAL_ERROR`。

### 8.3 B1 announcement 开/关：失败

对 Testall 群依次调用 `announcement`、`not_announcement`，两次均返回 `INTERNAL_ERROR`。worker 两次均为：

```text
Error: locked
data=423
groupSettingUpdate -> runGroupAdminMutation -> HTTP 500
```

数据库始终保持：

```text
announce_only=0
announce_only_source=METADATA_EVENT
announce_only_observed_at=1787011432322
metadata_observed_at=1787011052081
```

结论：**B1 开/关均失败**；不能据此验证 false/0 投影逻辑。B2～B6 和 C～F 尚未继续写入测试群。

### 8.4 G 首次建档与性能

本次批量上线确实触发 `online_open_group_sync`。从 00:12 UTC 起的完整同步样本：

```text
完成账号数：145
groupCount：min=0, max=94, avg=10.74
elapsedMs：min=376, max=9084, avg=1463.34
并发闸延期次数：296
```

本次 actor/observer 的具体数据：

```text
actor 169：8 群，profileCount=8，1200～1827ms
observer 1222：25 群，profileCount=25，1117～1948ms
```

四个 worker 采样时内存（不是峰值）：

```text
w1 RSS=508104704, heapUsed=109311320, heapTotal=248287232
w2 RSS=221540352, heapUsed=75990968,  heapTotal=101224448
w3 RSS=348266496, heapUsed=71966776,  heapTotal=140677120
w4 RSS=332357632, heapUsed=64434416,  heapTotal=197672960
```

当前没有保留 heap 峰值的时序指标，不能把采样值当成峰值。默认并发闸 2 确实产生了反压（296 次 deferred），145 个同步最终完成且未观察到 OOM，但尚不能仅凭当前 heap 判断默认值是否最优。

### 8.5 首次建档事件发布/消费不一致

协议 worker 指标显示：

```text
group.profile_reported Kafka publish success：6236
w1=6089, w2=144, w3=3, w4=0
```

同一观察窗口 Armada 后端日志：

```text
协议群资料上报事件收到（group.profile_reported）：0
协议群资料事件收到（group.metadata_updated）：5150
协议账号群列表事件收到（online_open_group_sync）：156
```

未发现 `group.profile_reported` 校验失败、DLQ 或明确消费异常日志。协议侧 `profileCount` 会在每个 `await publisher.publish(...)` 成功后增加，Kafka 成功计数也已增长，但 Armada 的 profile consumer 完全没有接收日志。这是首次建档链路的独立阻断问题，需继续检查 Kafka topic/consumer group/分区和实际消息 envelope；当前不能判定首次资料与成员建档通过。

### 8.6 H / I 复核

Web PM2 最近 3000 行：

```text
account.group_metadata_sync_requested=0
group.metadata_updated 日志计数=0
modify=0
没有可合并的双身份=0
```

H 的回查计数仍为 0，但 A/B 写入均在 WhatsApp 侧失败，所以仍不能作为成功触发后的验收证据。I 没有触发。
