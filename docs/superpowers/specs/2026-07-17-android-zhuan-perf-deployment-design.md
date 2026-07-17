# Android Zhuan 接入第二套性能环境设计

> 状态：设计已确认，尚未实施
> 日期：2026-07-17
> 范围：第二套 Armada、现有 Web 协议、Android Zhuan 新机、共享 MSK/RDS/Redis

## 1. 已确认目标

1. Android Zhuan 新机只接入第二套性能环境，不再连接第一套测试环境。
2. 第二套 Armada 同时接入 Web 协议和 Android Zhuan，按 `ProtocolBackend.WEB`、
   `ProtocolBackend.ANDROID` 路由。
3. 两套环境共用同一个 MSK 集群，但 topic 和 consumer group 必须严格隔离。
4. Android Zhuan 复用第二套 Redis Cluster，通过全局 key 前缀与 Web 协议隔离。
5. MySQL 复用第二套 RDS 实例，但 Android Zhuan 使用独立 schema。
6. 采用简化接入流程，不引入双写、双读或第一套兜底。

## 2. 当前事实

### 2.1 主机与网络

| 角色 | 私网地址 | 规格 | 安全组 | 当前状态 |
|---|---|---|---|---|
| 第二套 Armada | `172.31.5.135` | `c5a.large`，2C4G | `sg-0910d9bc97301ebd5` | Armada backend/nginx 运行中 |
| 第二套 Web 协议机 | `172.31.8.217` | `c6a.xlarge`，4C8G | `sg-08ece6402838b09f8` | 代码和压测配置存在，检查时无业务容器 |
| Android Zhuan 新机 | `172.31.40.84` | `c6a.xlarge`，4C8G | `sg-01abd69d3740db5d7` | Docker/Git/Compose 已安装，无业务容器 |

三台机器都位于 `vpc-0472041e4b0478092`。Android 新机已能访问第二套 RDS 3306；
检查时到 MSK 9094 尚未放通。安全组由用户负责开放，应用部署前必须重新验证。

### 2.2 Kafka

第一套和第二套使用同一个三 broker MSK 集群。共享集群中已经存在第一套 Android topic：

- `protocol.android.commands.v1`：3 分区、3 副本；
- `protocol.android.lifecycle.commands.v1`：4 分区、3 副本；
- `protocol.android.message.commands.v1`：4 分区、3 副本；
- `protocol.android.group-join.commands.v1`：4 分区、3 副本。

第二套 Armada 当前虽已支持三个 Android 命令 topic 配置，但容器仍使用上述无环境前缀的默认名称。
若直接启用新 Zhuan，会与第一套串线。

第二套现有 Web 命令和事件 topic 均为12分区、3副本、`min.insync.replicas=2`，包括：

- `armada.perf.protocol.master.commands.v1`；
- `armada.perf.protocol.account.events.v1`；
- `armada.perf.protocol.message.events.v1`；
- `armada.perf.protocol.group.events.v1`。

第二套 Armada 三类事件 consumer group 已使用 perf 专属名称：

- `perf-armada-api-account-events`；
- `perf-armada-api-message-events`；
- `perf-armada-api-group-events`。

### 2.3 Redis

第一套 Zhuan 使用本机独占的单机 `redis-zhuan`，`cluster_enabled=0`，通过 Docker 内网明文连接，
没有全局环境前缀。

第二套 Redis 是 TLS Redis Cluster，只支持 DB 0。Web 协议的 registry、keys、rate-limit、runtime
等连接均指向该集群，并统一使用 `armada-perf:` 前缀。

当前 Zhuan 只使用单节点 `redis.Client`，且 key 同时存在 `whatsapp:*`、`armada:zhuan:*`、
`<phone>_fcm` 等形式；`ClearByPattern` 还会执行宽泛 `KEYS`。因此当前代码不能直接安全复用第二套
Redis Cluster。

### 2.4 MySQL 与 HTTP

- 第二套 Armada 使用第二套 RDS 的 `armada_perf` schema。
- Android 新机到该 RDS 网络已连通。
- 第二套 Armada 尚未设置 `PROTOCOL_ANDROID_BASE_URL`。
- Zhuan 运行时必须同时连接 MySQL 和 Redis。
- Zhuan 迁移不会创建/导入 `wa_devices` 参考数据，该表需要单独处理。

## 3. 目标架构

```text
                           Web 命令 topic
第二套 Armada  -------------------------------->  Web 协议
      |
      |                    Android 三类命令 topic
      +---------------------------------------->  Android Zhuan

Web 协议 -----------+
                    +---- perf 账号/消息/群事件 topic ----> 第二套 Armada
Android Zhuan ------+
```

- Web 命令、Web URL 和 Web consumer group 保持不变。
- Android 使用三个独立命令 topic 和三个独立 consumer group。
- Web 与 Android 的账号、消息、群结果复用第二套现有 perf event topic。
- Web 协议和 Android Zhuan 不直接调用，也不共享 MySQL schema 或 Redis key 空间。
- Armada 通过独立的 Android base URL 访问 Zhuan 原生 HTTP 接口。

## 4. Kafka 设计

### 4.1 Android 命令 topic

| 命令族 | 第二套专属 topic | consumer group | 分区 |
|---|---|---|---:|
| 上线/下线 | `armada.perf.protocol.android.lifecycle.commands.v1` | `armada-perf-android-zhuan-lifecycle-v1` | 12 |
| 发消息 | `armada.perf.protocol.android.message.commands.v1` | `armada-perf-android-zhuan-message-v1` | 12 |
| 进群 | `armada.perf.protocol.android.group-join.commands.v1` | `armada-perf-android-zhuan-group-join-v1` | 12 |

三个 topic 统一使用：

- replication factor：3；
- `min.insync.replicas=2`；
- `unclean.leader.election.enable=false`；
- `cleanup.policy=delete`；
- `retention.ms=604800000`（7天）；
- `max.message.bytes=1048576`；
- 禁止依赖自动创建 topic。

Zhuan 初期为每个命令族启动4个 consumer。12分区为后续最多3台同规格 Zhuan 横向扩容预留，
避免运行中增加分区改变同一 key 的分区映射。

### 4.2 分区键与顺序

Armada 所有 Android 命令都以 `protocolAccountId` 作为 Kafka key。Zhuan 的账号、消息和进群结果也
以 `protocolAccountId` 作为 key 写入对应 event topic。

只保证同一 topic 内同一账号的顺序。lifecycle、message、group-join 三个 topic 之间不提供总顺序；
如果消息或进群先于上线执行，沿用现有业务语义返回 `ACCOUNT_OFFLINE` 或 `ACCOUNT_NOT_ONLINE`，
不在协议层增加跨 topic 等待。

### 4.3 创建与防串线

创建前必须逐一确认三个 perf Android topic 不存在。意外存在时停止操作并核对分区和来源，不能把
`--if-not-exists` 的成功退出当作创建成功。

创建后必须 `describe` 并断言名称、12分区、3副本、ISR 和关键配置。Zhuan 启动后，三个 consumer group
必须各自只分配到一个对应 perf topic。

Armada 切换前必须查询 `armada_perf.protocol_command_outbox`，确认不存在指向以下第一套 topic 的
`PENDING`/`LOCKED` Android 记录：

- `protocol.android.commands.v1`；
- `protocol.android.lifecycle.commands.v1`；
- `protocol.android.message.commands.v1`；
- `protocol.android.group-join.commands.v1`。

如存在遗留记录，停止切换并单独决定取消或重新生成；禁止直接改写其 topic。

## 5. Redis 复用设计

### 5.1 命名空间

- Web 协议：`armada-perf:*`；
- Android Zhuan：`android-zhuan-perf:*`。

Zhuan 在 Cluster 模式下必须配置非空全局 prefix。prefix 为空、等于 `armada-perf:`，或不是
`android-zhuan-perf:` 时启动失败。第一套 standalone 模式继续允许使用原配置，避免本次改造影响第一套。

### 5.2 客户端与 key 操作

Zhuan Redis 配置和初始化需要支持：

- `standalone` 与 `cluster` 两种模式；
- Redis Cluster configuration endpoint；
- TLS；
- ACL username/password；
- 全局 key prefix。

公共 GET/SET、Hash、pipeline、Lua script、Armada 命令幂等状态、LID/AppState 和 FCM key 必须使用同一个
key builder。Cluster 模式下禁止跨 slot 的多 key 原子操作；需要删除多个 key 时按 slot 或逐 key 执行。

`ClearByPattern` 必须移除 `KEYS`，改为在各 master 上对以 `android-zhuan-perf:` 开头、由调用方限定的
完整匹配模式执行游标 `SCAN`，且删除前再次校验每个 key 的 namespace。

首期允许复用现有 Redis 认证；为 Zhuan 建立仅能访问 `~android-zhuan-perf:*` 的独立 ACL 用户作为后续安全加固，
不阻塞本次性能环境接入。

### 5.3 容量与故障边界

接入前检查 Redis Cluster 的内存使用、连接数、延迟、碎片率和 `evicted_keys`。内存使用超过70%或已经发生
key 淘汰时暂停接入。共享 Redis 故障会同时影响 Web 和 Android，这是复用方案接受的故障域扩大。

Android 新机不启动本地 `redis-zhuan`，也不创建本地 Redis volume。

## 6. MySQL 与 callback

第二套 RDS 新建 `whatsapp_android_zhuan_perf` schema。Zhuan 使用独立数据库账号，只授权该 schema，
不得访问 `armada_perf`，也不得复用 Armada DB 用户。

先执行 Zhuan 自带迁移，校验 Signal identity、32张 prekey 分表、32张 session 分表、sender-key 等表。
`wa_devices` 作为非账号参考数据单独从第一套现有 Zhuan schema 复制表结构和数据，并核对行数；不得复制第一套
账号、登录凭据、Signal identity、session、prekey、sender-key 或 Redis 数据。

新机保留本地 `callback-zhuan` 兼容接收器，但不暴露公网。它只用于诊断，Armada 状态仍以 Kafka event 为准。
callback 日志需要脱敏和轮转。

## 7. HTTP 与安全组

| 来源 | 目标 | 端口 | 用途 |
|---|---|---:|---|
| Android SG `sg-01abd69d3740db5d7` | MSK SG | 9094 | Kafka SSL |
| Android SG | 第二套 Redis SG | 6379 | Redis TLS |
| Android SG | 第二套 RDS SG | 3306 | Zhuan MySQL |
| Armada SG `sg-0910d9bc97301ebd5` | Android SG | 8001 | Android 原生 HTTP |
| 管理员固定 IP | Android SG | 22 | SSH |

Zhuan 监听 `0.0.0.0:8001`，但安全组只允许第二套 Armada 访问。8001、Redis 和 callback 均不得向公网开放。
Web 协议机不需要访问 Android 新机，Android 也不需要反向访问 Armada HTTP。

第二套 Armada 增加：

```text
PROTOCOL_ANDROID_BASE_URL=http://172.31.40.84:8001
```

现有 Web `ARMADA_PROTOCOL_BASE_URL` 保持不变。Redis、Kafka、MySQL 凭据只进入新机权限为0600的受保护配置，
不得进入 Git、镜像、部署日志或设计文档。

## 8. 简化实施顺序

### 8.1 准备依赖

1. 用户开放 MSK、Redis 和 Android HTTP 所需安全组。
2. 完成 Zhuan Redis Cluster/TLS/prefix 兼容改造和自动化测试。
3. 创建独立 MySQL schema/账号，执行迁移并复制 `wa_devices` 参考表。
4. 创建并核对三个 perf Android command topic。

### 8.2 配置并部署 Zhuan

1. 配置第二套 RDS、Redis Cluster、三个 perf command topic、三个 perf group 和现有 perf event topic。
2. 在 Android 新机部署 Zhuan 与 callback，Kafka 直接启用。
3. 验证 HTTP 健康、Redis namespace、MySQL 表和三个 Kafka consumer group。

此时 Armada 尚未切换，三个新 command topic 应为空；出现非预期消息即停止 Zhuan 并排查串线。

### 8.3 切换 Armada

1. 暂停第二套 Android 账号操作并检查旧 Android outbox 遗留。
2. 在第二套 Armada `.env` 写入三个 perf Android topic 和 Android base URL。
3. 只重建 Armada backend；Web 协议机不重启。
4. 从运行中容器读取非敏感配置再次核对，不能只检查 `.env`。

### 8.4 冒烟与放量

先使用一个第二套专用 Android 账号依次验证上线、状态查询、发消息、进群和下线；再验证一个现有 Web 账号。
通过后逐步增加 Android 账号并观察 Kafka lag、Redis、RDS、CPU、内存和磁盘。

## 9. 错误处理与回退

- topic 缺失或拼错：禁止自动创建，Zhuan 启动失败。
- Redis Cluster/TLS 不通：Zhuan 启动失败，不启动 Kafka consumer。
- Kafka event 发布失败：沿用现有语义，不提前提交对应 source offset。
- Android HTTP 不通：Armada 明确返回失败，不切到 Web 或第一套环境兜底。
- 发现第一套 topic 消息：立即停止 Zhuan，检查 Armada 运行时环境和历史 outbox。

最小回退方式是停止新机 Zhuan。Armada 保留 perf Android topic 配置，未处理命令留在 perf topic，修复后继续消费；
绝不恢复到第一套 topic。新 topic、MySQL schema 和 Redis namespace 都保留，不在回退中删除。Web 配置和 Web topic
全程不变，因此 Web 协议可继续运行。

## 10. 验收标准

1. 三个 Android perf topic 都是12分区、3副本，关键 topic 配置符合本设计。
2. 三个 Zhuan consumer group 各自只订阅一个对应 perf topic。
3. Armada 容器实际使用三个 perf Android topic，Web 仍使用 perf master topic。
4. 切换后 Android outbox 的命令族到 topic 映射正确，第一套 Android topic 新增行数为0。
5. Zhuan 创建的 Redis key 都以 `android-zhuan-perf:` 开头；不存在无前缀 Zhuan key。
6. Android 上线、发消息、进群、下线结果通过现有 perf event topic 回到 Armada。
7. 一个 Web 账号完成上线/消息/进群回归，两套协议同时可用。
8. 测试期间 Redis 无 key 淘汰，Zhuan 无持续重启，Kafka consumer lag 可收敛。

## 11. 容量结论

Android 新机 `c6a.xlarge`（4C8G）和50G系统盘足够第一阶段使用。Redis 已外置，机器资源主要提供给 Zhuan；
首期不升级规格。保留 Docker 日志轮转，并根据 CPU、RSS、磁盘和 Kafka lag 决定是否扩容。

## 12. 尚未实施的外部动作

以下动作均未执行，必须在实施计划和目标环境复核后进行：

- 修改安全组；
- 创建 Kafka topic；
- 创建 RDS schema/用户或复制 `wa_devices`；
- 创建 Redis ACL 用户；
- 部署 Zhuan；
- 修改或重启第二套 Armada。
