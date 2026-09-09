# 超链任务 test1 真实环境执行结果

> 执行日期：2026-09-03
>
> 目标环境：第一套测试环境 `test1`
>
> 执行方式：真实环境探活、浏览器业务操作、数据库只读对账、Android 单账号加密回环业务 canary 与 Web 独立加密回环 canary
>
> 总体结论：`ANDROID_BUSINESS_CANARY_PASS_WITH_ENVIRONMENT_BLOCKERS / LOAD_TEST_BLOCKED`
>
> 安全边界：已创建并完成 1 条 test1 超链业务任务；只选中 1 个受控 Android 账号，账号的 WhatsApp TCP 外连由进程内回环硬切断，未向 WhatsApp 或真实收件人发送消息。任务仍真实经过 Armada、outbox、Kafka、Android 账号队列、Signal 出站加密、Noise 双向加解密、密文 Server ACK、结果事件、业务计数与零计费结算。

## 1. 结论

本轮已经在第一套 `test1` 环境真实创建并跑完 1 条超链任务，不再只是本地 `test` 测试类或独立加密脚本。任务 ID 为 `13`，1 个数据、1 个 Android 账号、即时执行、单账号上限 1；最终业务状态为已完成。

Android 第一阶段加密回环已经在协议仓完成本地实现和验证：真实 Noise XX 握手、双向 Noise 加解密、同 message id 的加密 Server ACK、回环计数和按账号 SHA-256 精确选择均已通过测试。共享节点不会整机切换；未命中的账号仍走原链路，配置残缺时服务启动失败。

Web 第一阶段也已补齐并在第一套真实 Web 协议机器运行。除最初 1000 条 smoke 外，又执行 1 万条基线、2 万条并发冲击和 10 万条持续校准；新增 13 万条全部收到密文 ACK、0 error，执行后 5 个 `/readyz` 端口和全部 PM2 进程仍健康。该结果是密码学与实例资源校准，不是 Armada 业务任务 E2E，也不能在正式 1× 尚未冻结时冒充容量结论。

单条链路的命令唯一性、协议提交、Server ACK、业务计数和零计费闭环已经通过；但它没有产生真实 `DELIVERED`、`READ` 或点击，也不能形成容量结论。继续放大到 1000 条之前，现场发现以下环境阻塞：

1. 发信账号维度统计接口 SQL 使用 MySQL 保留字 `usage` 作为未转义别名，HTTP 外层为 200，但业务返回“系统繁忙”。
2. 小时营销统计 SQL 读取不存在的 `hyperlink_task.task_type` 列，定时任务持续报错。
3. `protocol.message.events.v1.DLT` 与 `protocol.account.contact-sync.events.v1.DLT` 不存在；既有毒消息无法进入 DLT，相关分区持续 seek/retry。
4. test1 使用 `ZERO_TEST`，本轮只能验收零金额预约、结算和释放，不能代替真实钱包验收。
5. Web 在线账号和可用 `HIGH` 候选均为 0，Web 真号与蓝标 canary 仍不具备条件。
6. 页面把单钩描述为“已发送到对方手机”，而本轮只能证明 Server ACK；该文案会把提交成功误导成设备送达。

因此本轮判定为“Android 单条业务 canary 通过，但环境验收失败，禁止放量压测”。

## 2. 本轮实际执行范围

| 检查面 | 是否连接真实 test1 | 是否有业务写入 | 结果 |
|---|---:|---:|---|
| 公网入口与服务探活 | 是 | 否 | 已完成 |
| 环境只读深检 | 是 | 否 | 已完成；运行版本证据另行阻塞 |
| Runner UI smoke | 是 | 仅写 Runner 自有证据 | `PASS`，run ID `20260903T062332Z-25867866` |
| Browser skill 登录与业务操作 | 是 | 是 | 登录成功，创建并完成任务 13 |
| 超链任务 API | 是 | 是 | GET、quote 和 create 均成功；账号统计接口暴露 SQL 错误 |
| 业务数据库对账 | 是 | 应用正常写入；人工只读 | 已完成任务、recipient、round、账号、outbox 和账务对账 |
| 任务报价、创建、启用与执行 | 是 | 是 | 已完成 1 条；预计冻结 0 USD，最终完成 |
| Android 加密回环业务闭环 | 是 | 是 | 已完成；真实业务任务、Signal 加密、Noise 双向加解密和密文 ACK 通过 |
| Web 加密回环 test1 canary | 是 | 仅同步独立测试脚本 | 已完成；1000 ACK、0 error，未创建 WASocket |
| Web/Android 真实 WhatsApp 触达 | 否 | 否 | 明确禁止；Android 账号 WhatsApp 外连已切断 |
| 1×、2×、soak 压测 | 否 | **未执行** | 容量合同与前置层未通过 |

Runner 的 `ui-smoke` 会写入自己的状态和证据目录，但不会写超链业务数据；该写入不等同于任务创建或真实消息发送。

## 3. 真实环境证据

### 3.1 只读探活与深检

- 已对真实 `test1` 入口和运行服务执行只读探活，而不是本地 Stub。
- 已执行环境深检范围内的服务、路由与协议健康核对，没有触发部署、重启、配置修改或账号上下线。
- 深检中的“运行制品版本一致性”不能通过：现有运行清单证据已超过允许的新鲜度，不能据此确认四仓候选版本。
- 该结果说明当前环境具备继续诊断的基础，不说明有状态业务链路已经通过。

### 3.2 UI smoke Runner

| 字段 | 实际值 |
|---|---|
| 环境 | `test1` |
| 运行 ID | `20260903T062332Z-25867866` |
| 安全级别 | `read-only` |
| 执行项 | UI smoke |
| 结果 | `PASS` |

本次 Runner 记录只证明 test1 的登录页面及固定页面 smoke 可执行；它不是超链发送 Runner，也没有生成任务负载。

### 3.3 浏览器真实页面检查

- 使用 browser skill 进入真实 test1 页面。
- 使用环境已有的受控登录方式完成登录；报告不保存用户名、密码、Cookie、Token 或其他认证材料。
- 成功进入“超链任务”页面。
- 首次进入时页面展示 `9` 条已有任务，证明前端路由、登录态、API 代理和任务列表渲染已形成真实环境闭环。
- 获得授权后，页面完成数据包、报价、最终核对和任务创建；刷新后任务 `13` 显示“已完成”、单钩 1、失败 0。

### 3.4 真实 API 核对

已在真实登录态下核对以下两个只读接口：

| 方法与接口 | 实际结果 | 本轮证明范围 |
|---|---|---|
| `GET /api/hyperlink-tasks` | HTTP 200 | 任务列表接口可访问并返回当前租户数据 |
| `GET /api/hyperlink-tasks/create-context` | HTTP 200 | 创建页只读上下文接口可访问 |

没有执行任何 `POST`、`PUT` 或任务动作接口。HTTP 200 只代表请求成功，不代表任务状态机、协议发送或账务已经验收。

补充发现：远端固定 UI smoke 的专用账号目前不含超链菜单权限；直接访问超链接口时返回业务码 `50000`，而具备权限的登录账号可正常返回列表。后续应为 smoke 账号补齐最小只读 RBAC，并把权限拒绝收敛为明确的 403 类错误，避免被误判为系统繁忙。

### 3.5 数据库只读核对

本轮通过获准的 test1 数据库只读路径核对超链任务基线，未执行 `INSERT`、`UPDATE`、`DELETE`、DDL 或手工迁移。

| 核对项 | 实际观察 | 判定边界 |
|---|---:|---|
| 本轮关键不变量异常计数 | 全部为 `0` | 当前静态基线未发现对应异常；不代表有负载时仍为 0 |
| Android 结构可选 / 在线 | `142 / 141` | 存在在线库存，但尚未分配独占租约和获准收件人，不能直接发送 |
| Web 在线账号 | `0` | 普通 Web 真号 E2E 不具备执行条件 |
| 当前可用 `HIGH` 候选账号 | `0` | 蓝标 canary 不具备执行条件 |
| 超链计费模式 | `ZERO_TEST` | 可验证零金额流程结构，不能验收真实余额、冻结、扣费、退款或释放 |

“关键不变量全 0”是执行前的只读基线。单条 canary 后又对任务 `13` 做了定向对账：1 个 recipient、1 个 command、1 个 outbox、1 次 dispatch、1 个 Server ACK、1 个 success、0 retry、0 failure，零计费账务已收尾。该结果证明单条路径，不代表有负载时仍能保持零漂移。

### 3.6 运行清单与版本门禁

- test1 当前可取得运行清单证据，但该证据已陈旧。
- 陈旧清单不能证明当前后端、前端、Web 协议和 Android 协议与[测试方案](./2026-09-03-hyperlink-task-test-plan.md)冻结的候选 SHA 一致。
- 在重新生成受信任、时间有效的四仓运行清单并完成制品身份核对前，本轮结果只能归属于“当前 test1 运行态”，不能归属于指定候选版本。
- 版本门禁必须 fail closed；不得拿计划文件里声明的 SHA 代替现场观测到的制品事实。

### 3.7 Android 加密回环与 test1 canary 预检

- Android 协议仓已实现进程内 `net.Pipe` 回环。账号仍走生产消息构造、Signal 出站加密和 `WASegment`，回环端解开 Noise、解析 XMPP，再用反向 Noise cipher state 返回 Server ACK。
- `ANDROID_CRYPTO_LOOPBACK_MODE=ack` 必须同时配置账号 User 的 SHA-256。共享节点只切换命中的账号；哈希错误、缺少选择器或孤立选择器都会失败关闭。
- 本地专项测试、竞态测试、静态检查和全量构建通过；全仓仍有既存部署夹具与旧 Noise 向量失败，未混入本次通过结论。
- test1 现场确认三个 Android 节点在线且均承载正常账号，因此禁止使用整节点 `*` 模式。当前页面快照为 272 个账号、137 个在线账号。
- 已获准自动选择并找到一组普通、在线、Android 且存在 Signal session 的发送/接收对；账号标识和号码不写入报告。
- node03 已记录原镜像 ID、标签、健康和资源基线，新增 canary 镜像已在服务器构建完成，源码同步未重启服务。
- 获得共享节点短时重启授权后已完成切换。仅选择器命中的账号进入回环，启动日志确认 WhatsApp TCP egress 被阻断；账号重新上线后才下发任务。

### 3.8 Web 加密回环 test1 canary

- 第一套 Web 协议机现场版本为 Node 24.16.0、Baileys 7.0.0-rc13；执行前 `/readyz` 正常，master、4 worker 和 traffic dashboard 全部 online。
- 只同步 `web-crypto-loopback.mjs` 与其测试文件，没有同步生产 `src`、`.env` 或 PM2 配置，也没有重启任何 Web 进程。
- 正确性测试 3/3 PASS，覆盖 Signal 原文回环、Noise 双向密文 ACK、参数失败关闭。
- 1000 条、8 lanes、512-byte payload 的低流量结果：1000 ACK、0 error、约 3236 msg/s；CPU user 397.772 ms、system 8.702 ms，等效 1.315 cores；进程峰值 RSS 152,834,048 bytes，峰值 heap 35,975,632 bytes。
- Signal 明文 515,000 bytes、密文 659,633 bytes；Noise 出站明文 701,523 bytes、线长 720,523 bytes；ACK 入站明文 16,890 bytes、线长 35,890 bytes。
- 在确认 test1 当时无人使用后，继续直接执行三档 512-byte payload 校准：

  | 档位 | lanes | ACK / error | 吞吐 | CPU 等效核数 | 峰值 RSS | 峰值 heap |
  |---|---:|---:|---:|---:|---:|---:|
  | 基线 10,000 条 | 8 | 10,000 / 0 | 3906.041 msg/s | 1.219 | 330,235,904 bytes | 96,614,568 bytes |
  | 冲击 20,000 条 | 32 | 20,000 / 0 | 4307.074 msg/s | 1.201 | 391,667,712 bytes | 58,958,560 bytes |
  | 持续 100,000 条 | 16 | 100,000 / 0 | 4756.459 msg/s | 1.124 | 389,222,400 bytes | 76,395,472 bytes |

- 新增三档共 130,000 条，Signal 和 Noise 加解密及密文 ACK 全部成功，没有真实网络请求。
- 执行后 `8080`～`8084` 的 `/readyz` 均返回 HTTP 200，master、4 worker、runtime collector 和 traffic dashboard 全部 online。
- 该脚本不创建 WASocket、不读取真实账号 creds/keys、不访问 Redis/MySQL/Kafka/代理/WhatsApp。它证明真实实例可以执行 Web 的 Signal+Noise 密码学热路径，不证明业务命令、真实账号状态或 WhatsApp 服务端行为。

### 3.9 Android test1 单条完整业务 canary

本轮在获得有状态执行授权后，先把 node03 切到只命中一个账号的回环镜像，再从真实 test1 页面创建并执行任务。账号和号码只以受控选择器参与运行，不写入报告。

| 层次 | 现场结果 |
|---|---|
| UI / API | 创建任务 `13` 成功；1 个数据、1 个匹配账号、即时启用，预计冻结 `0 USD` |
| 任务运行态 | `run_status=2`、`provision_status=2`、总数 1、提交 1、成功 1、失败 0、耗时 36 秒 |
| recipient | 仅 1 行；仅 1 个 command；`dispatch_attempt=1`；协议 messageId 已回写；状态为 Server ACK；无 DELIVERED/READ/失败 |
| 账号占用 | 仅 1 行；Android；成功上限 1；成功数 1；预留槽和 in-flight 均回到 0 |
| 轮次 | 仅 1 轮；已完成；分配 1、选中账号 1、提交 1、成功 1、失败 0 |
| outbox / Kafka | 仅 1 行 `message.send.requested`；topic 为 Android 消息命令 topic；状态已发送；`retry_count=0`、`last_error=NULL` |
| 协议回环 | 活跃连接 1；`message_acks` 从 0 增至 1；`errors` 始终为 0 |
| 计费 | provider=`ZERO_TEST`；报价、预约、结算、释放金额全部为 0；`settled_send_count=1`；无待处理操作和失败码 |

回环前后诊断计数差值：

| 指标 | 前 | 后 | 差值 |
|---|---:|---:|---:|
| received frames | 70 | 105 | +35 |
| received plaintext bytes | 4,098 | 5,263 | +1,165 |
| received wire bytes | 5,428 | 7,258 | +1,830 |
| returned frames | 69 | 104 | +35 |
| returned plaintext bytes | 893 | 1,461 | +568 |
| returned wire bytes | 2,204 | 3,437 | +1,233 |
| message ACK | 0 | 1 | +1 |
| IQ result | 68 | 102 | +34 |
| error | 0 | 0 | 0 |

wire bytes 均大于 plaintext bytes，说明真实 Noise 帧头与 AEAD 密文路径被执行；消息在实例内还经过真实 Signal 出站加密。回环端当前解开的是 Noise 外层并返回 Noise 密文 ACK，不解开 message node 内部的 Signal 密文。

完成后的容器瞬时快照为 `0.87% CPU / 69.95 MiB`。该值包含连接维持和周期 IQ，且只有 1 条消息，不能作为单消息成本或容量结论。容器网络命名空间内只观察到 MySQL、Redis 和 Kafka 端口连接，没有任何 443 已建立连接，符合“未触达 WhatsApp”的安全边界。

### 3.10 本轮暴露的环境缺陷

| 严重度 | 缺陷 | 直接证据 | 影响 |
|---|---|---|---|
| P0 | 两个 Kafka DLT topic 不存在 | producer 持续返回 `UNKNOWN_TOPIC_OR_PARTITION`；既有毒消息每分钟 seek/retry | 分区无法越过毒消息，压测时无法可靠证明事件无积压、无丢失 |
| P0 | 账号维度统计查询 SQL 语法错误 | `LEFT JOIN hyperlink_task_account_usage usage` 在 MySQL 报错 | 页面显示“系统繁忙”，无法从正式接口完成账号维度验收 |
| P1 | 小时营销统计 schema 漂移 | SQL 读取不存在的 `task.task_type` | 小时统计定时任务持续失败，营销聚合不可验收 |
| P1 | 单钩文案把 ACK 写成设备送达 | 页面图例称“已发送到对方手机” | 容易把 Server ACK 误当 DELIVERED，违反分层统计口径 |

数据库中的 `hyperlink_task_account_stat` 实际已有 1 条正确汇总行；账号统计页失败是查询 SQL 缺陷，不是本条消息没有形成账号统计事实。

## 4. 明确未执行的项目

### 4.1 有状态任务流程

本轮已经执行报价、创建、启用、首轮调度、协议命令、ACK 回写、任务完成和零计费收尾。以下动作仍未执行：

- 修改、复制、暂停、恢复、手工结束或删除任务；
- 多数据、多账号、多轮次任务；
- 验证无账号任务的等待时限与最终终态；
- 触发短链并产生真实点击归因。

任务 `13` 是本轮创建的受控测试夹具，保留用于后续审计和复测。

### 4.2 真号发送与 L3/L4

以下项目均未执行：

- 普通 Web 真号低流量 E2E；
- Android 到真实 WhatsApp 服务端的低流量 E2E；
- TEXT、LINK、IMAGE、LINK_CARD、BUTTON_CARD 五类真实发送；
- 真实 WhatsApp 的 DELIVERED、READ 和点击逐级对账；
- `HIGH` 蓝标账号小流量 canary；
- 任何未经明确 allowlist 授权的号码触达。

Android 受控账号到进程内加密回环的 Server ACK 已执行并通过；这不是对真实 WhatsApp 的触达证明。

### 4.3 压测

以下项目均未启动：

- L1 隔离全链 0.1× smoke；
- 1× 稳态负载；
- 2× 短时冲击及积压恢复；
- 长时间 soak；
- 公平性、owner 丢失、ACK/回调乱序、故障恢复和账务故障注入；
- 稳定容量搜索与 60%～70% 上线容量折算。

正式业务量 `CAP-01`～`CAP-19`、`CAP-21` 尚未全部冻结。本轮已得到单命令的 C2 协议接收和 C3 加密提交事实，但统计 SQL 与 DLT 环境门禁未通过；在修复并复测前不得扩大负载。

## 5. 当前门禁判定

| 门禁 | 状态 | 依据 |
|---|---|---|
| 真实 test1 可访问 | `PASS` | 探活、UI、浏览器与 GET API 均已执行 |
| UI smoke | `PASS` | run ID `20260903T062332Z-25867866` |
| 超链任务列表与单条创建路径 | `PASS` | 登录后创建任务 13，并看到最终已完成 |
| 只读 API 基线 | `PASS` | 两个超链 GET API 均 HTTP 200 |
| 单条命令与计数对账 | `PASS` | 1 recipient、1 command、1 outbox、1 ACK、1 success、0 failure、0 retry |
| 四仓运行版本一致 | `BLOCKED` | 运行清单证据陈旧 |
| 普通 Web 真号前置 | `BLOCKED` | Web 在线账号为 0 |
| HIGH canary 前置 | `BLOCKED` | eligible HIGH 为 0 |
| 正式账务前置 | `BLOCKED` | 当前为 `ZERO_TEST`，不是真实钱包 |
| Android 回环本地闭环 | `PASS` | Noise XX、双向密文、ACK、计数和单账号门禁均通过 |
| Web test1 加密回环 canary | `PASS_WITH_LIMITATION` | 初始 1000 条及新增 10,000/20,000/100,000 三档均全量 ACK、0 error；是独立 crypto harness，不是业务 E2E |
| Android test1 单条业务 canary | `PASS_WITH_LIMITATION` | 完整业务链和密文 Server ACK 通过；没有真实 DELIVERED/READ |
| ZERO_TEST 账务闭环 | `PASS_WITH_LIMITATION` | 实际发送数 1；冻结、结算、释放均为 0；不能替代真实钱包 |
| 账号维度统计接口 | `FAIL` | MySQL 保留字别名导致查询失败 |
| 小时营销统计 | `FAIL` | 运行库缺少 SQL 引用的 `task_type` 列 |
| Kafka DLT 与毒消息隔离 | `FAIL` | 两个 DLT topic 不存在，旧毒消息持续重试 |
| L1/L3/L4 与容量验收 | `BLOCKED` | 环境 P0 未修复、业务量合同未齐 |

总体发布判定：`ANDROID_BUSINESS_CANARY_PASS_WITH_ENVIRONMENT_BLOCKERS / LOAD_TEST_BLOCKED`。

## 6. 下一次允许放量测试的条件

必须先同时满足：

1. 创建并验证两个缺失的 DLT topic，清掉或正确隔离当前毒消息，确认 consumer 不再循环 seek。
2. 修复账号统计查询的 `usage` 别名并从页面复测 1 条账号汇总。
3. 对齐小时营销统计 SQL 与 test1 schema，确认定时任务不再报 `task.task_type` 缺列。
4. 把单钩文案改成 Server ACK 口径，DELIVERED 和 READ 继续独立展示。
5. 重新采集 test1 四仓运行制品身份，生成时间有效的运行清单。
6. 冻结 1000 条校准用例的账号数、间隔、超时、观测窗口和停止阈值；继续使用零媒体、单账号回环，先验收积压恢复再扩账号。
7. 若执行正式账务或 L4，再单独准备真实测试钱包和可被服务端筛出的 `HIGH` 账号。

满足后按以下顺序推进：

```text
修复 DLT 和两处 SQL
  → 复跑任务 13 的账号统计与小时统计
  → 重新跑 1 条回环 canary
  → 对账 1 command / 1 outbox / 1 ACK / 1 success / 0 retry
  → 才启动 1000 条业务回环校准
  → 再评估 1×、2× 和 soak
```

## 7. 与其他交付物的关系

- [超链任务测试方案](./2026-09-03-hyperlink-task-test-plan.md)：定义 L0～L4、容量冻结与通用通过标准。
- [超链任务验收清单](./2026-09-03-hyperlink-task-acceptance-checklist.md)：后续逐项签署 `PASS / FAIL / BLOCKED`。
- [超链任务压测用例](./2026-09-03-hyperlink-task-load-test-cases.md)：待容量合同和 L1 环境具备后执行。
- [测试启动变更记录](../../../.harness/changes/2026-09-03-hyperlink-task-test-acceptance-load.md)：记录本地 L0/L2 测试及尚未关闭的实现缺口。

## 8. 证据与隐私约束

- 本报告不记录用户名、密码、Cookie、Token、API key、数据库连接、私钥路径、完整手机号、账号标识、群标识或消息正文。
- UI smoke 的 run ID 可用于在受控 Runner 状态目录内定位完整证据；不要把原始 trace、浏览器存储或环境文件复制进仓库。
- 后续有状态运行使用单独 run ID，并只记录脱敏别名、计数、时间、状态码和制品身份。
- 任何有状态执行发生后，都必须重新跑数据库不变量核对；本轮的静态零值不能沿用。
