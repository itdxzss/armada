# Android 营销图片 Redis + 进程 LRU 设计

> 状态：已确认，待实施
> 日期：2026-07-19
> 范围：`armada/armada-api`、`whatsapp-server-feature-android-zhuan`
> 环境：当前只要求测试环境直接切换，不设计生产滚动发布顺序

## 1. 结论

Android 营销图片改为“原图 Redis 缓存 + Android 进程内规范化图片 LRU”：

1. MySQL 中的营销模板图片继续作为事实源。
2. Armada 在生成 Android 图片命令前，以租户和源图 SHA-256 为身份，把原始图片二进制写入共享 Redis；同一批次内相同图片只确保一次。
3. Kafka 只携带图片引用，不再为每个群重复携带 Base64 图片。
4. Android 第一次使用图片时从 Redis 读取原图，只执行一次像素解码、方向纠正、缩放和 JPEG 重编码。
5. Android 进程级 LRU 保存规范化主图和小缩略图；同一图片在缓存有效期内被任意账号、任务和群直接复用。
6. LRU 最后访问 20 分钟后失效，每次命中把失效时间续到新的 20 分钟；容量固定为 64MB。
7. 每个群不再做图片 Base64 编解码或像素重编码，但仍必须独立执行 WhatsApp 媒体加密、上传、群消息编码、发送和 ACK 等待。

本方案解决的是相同模板图片在群维度反复复制、Base64 编解码和像素处理的问题，不改变 WhatsApp 每群独立发送的协议要求。

## 2. 当前事实与问题

### 2.1 Armada 当前链路

- `MarketingTemplateFileServiceImpl` 把上传图片原始字节保存到 MySQL BLOB；图片大小上限已经在文件落入阶段校验，本次任务下发不重复校验 500KB。
- `MarketingRoundWorker`、建群营销和历史群营销把模板媒体组装为 `MessageSendCommand.MessageMedia(byte[], mimetype)`。
- `AndroidMessageSendBackend` 当前对每条命令调用 `Base64.getEncoder().encodeToString(media.bytes())`。
- 编码后的完整图片进入 outbox JSON，并最终进入 `protocol.android.message.commands.v1`。
- 相同模板图片发送 N 个群时，outbox 和 Kafka 中存在 N 份相同 Base64，体积比原二进制还约增加三分之一。
- Web backend 也使用 Base64，但本次不修改 Web 链路。

### 2.2 Android 当前链路

- `MessageCommand` 的图片字段为 `{base64, mimetype}`。
- `validateMessageImage` 先 Base64 解码一次，只用于验证，结果随即丢弃。
- `ZhuanMessageSender.Send` 在真正发送图片时再次 Base64 解码。
- 普通 `IMAGE` 当前没有执行像素级重新编码；完整原图还会被直接放入 protobuf `JPEGThumbnail`。
- 链接卡片和按钮卡片会在各自发送路径解码并重新编码 JPEG。
- 图片进入原生群发送后，每个群仍会重新获取设备信息、生成随机 media key、加密并上传媒体、构造群消息并等待 server ACK。

因此当前“两次解码”是分层造成的重复工作，并不是 WhatsApp 的硬性要求；像素重新编码是把压缩图片解成像素矩阵，再按尺寸、方向、格式和质量压缩成新图片。本设计把它移动到 Android LRU 首次未命中路径，每张图片每个进程只做一次。

## 3. 目标与非目标

### 3.1 目标

- 相同图片不按群、账号、任务或命令重复存入 Redis 和 Android LRU。
- 相同图片在一个 Android 进程的 LRU 有效期内只读取一次 Redis、只解码重编码一次。
- Kafka 和 outbox 不再携带 Android 图片 Base64。
- 把普通图片的完整原图缩略图改成真正的小 JPEG 缩略图。
- 保留 MySQL 事实源和现有营销发送结果、幂等、账号串行及群间隔语义。
- 通过租户隔离、内容哈希、大小和哈希校验阻止串图或错误引用。

### 3.2 非目标

- 不把 MySQL 图片事实源迁移到 Redis。
- 不让 Android 直接读取 Armada MySQL。
- 不修改 Web/Baileys 图片发送链路。
- 不复用每群的 WhatsApp 加密媒体或上传结果。
- 不保证同一图片在不同 Android 进程之间只重编码一次；LRU 是进程内缓存，每个进程最多各做一次。
- 不在本次引入 MinIO、S3 或 CDN。
- 不在本次处理生产滚动发布和灰度开关；测试环境允许 Armada 与 Android 直接切换。

## 4. 方案比较

### 4.1 Redis 原图 + Android 进程 LRU（采用）

Redis 负责跨 Armada/Android 进程传递和短期缓存原图，Android LRU 负责复用规范化输出。它同时降低 Kafka、outbox、Redis 热读和 CPU 重编码压力，且不改变 MySQL 事实源。

### 4.2 只使用 Redis、不使用 Android LRU（不采用）

Kafka 可以变小，但同一图片发送 N 个群仍产生 N 次约 500KB Redis GET 和 N 次解码重编码，热点模板的网络与 CPU 问题仍然存在。

### 4.3 Android LRU 保存解码像素（不采用）

解码像素占用远大于压缩字节。例如 2000×2000 RGBA 约占 16MB，少量图片就会挤满内存。缓存规范化 JPEG 能直接用于发送且内存可控。

### 4.4 对象存储（后续选项）

MinIO/S3 更适合长期、大规模媒体资产，但当前会引入存储服务、授权 URL 和生命周期管理。若每日活跃唯一图片造成 Redis 容量压力，再单独迁移对象存储。

## 5. 总体数据流

```text
MySQL 营销模板图片（事实源）
        |
        v
Armada AndroidMessageSendBackend
  - 当前批次按 tenantId + SHA-256 去重
  - Redis 已存在：只续期 24h
  - Redis 不存在：写原始二进制并设置 24h TTL
        |
        +------> Kafka N 条群命令，全部引用同一 image asset
                         |
                         v
Android 进程级 ImageAssetCache
  - LRU 命中：续 20 分钟，直接取得规范化主图和缩略图
  - LRU 未命中：singleflight 合并并发加载
                         |
                         v
共享 Redis GET 原图 -> 校验 -> 解码/重编码一次 -> 写入 64MB LRU
                         |
                         v
每群独立 WhatsApp 加密、上传、群消息发送和 ACK
```

同一租户同一源图在 Redis 只有一个 value。Android LRU 也不包含群 ID、任务 ID、命令 ID或账号 ID。

## 6. 图片身份与 Redis 模型

### 6.1 内容身份

图片身份固定为：

```text
tenantId + sourceSha256
```

- `sourceSha256` 是 Armada 对 MySQL 原始图片字节计算的 64 位小写十六进制 SHA-256。
- 不按模板 ID 建 Key，使同一租户内相同图片跨模板、跨任务复用。
- 不跨租户去重，避免租户之间形成可观察的共享资产关系，也便于 ACL 和问题定位。

### 6.2 Redis Key

逻辑 Key 固定为：

```text
marketing:image:v1:<tenantId>:<sourceSha256>
```

Redis 物理 Key 还要包含测试环境实际使用的全局 namespace。Armada 与 Android 必须配置成生成完全相同的物理 Key；Kafka 不携带物理 Key，也不允许消费者执行命令中给出的任意 Redis Key。

Android 现有 `db.KeyPrefix()` 只允许加一次。Armada 增加图片缓存物理前缀配置，用于对齐 Android 当前 namespace。两端各自的启动校验必须拒绝空白或缺少末尾冒号的前缀；测试环境集成检查负责确认双方最终物理 Key 完全一致。

### 6.3 Redis Value 与 TTL

- Value：原始图片二进制，禁止 Base64、JSON 包装或 Java 序列化。
- TTL：24 小时。
- Armada 每次真正生成包含该图片的 Android 命令批次时续期。
- Android GET 不续 Redis TTL，避免无人继续下发的图片被消费端永久保活。
- 已存在时先执行 `EXPIRE`，成功后不再发送 500KB value；不存在时才执行带 TTL 的 `SET NX`。并发 miss 的失败方只补一次 `EXPIRE`。
- Redis 写成功但业务事务回滚产生的孤儿 Key 可以接受，由 TTL 自动回收。

Armada 不在任务下发时重新校验 500KB；该限制已经在图片落入 MySQL 时完成。Android 仍校验命令声明的大小和 SHA，这是跨进程传输完整性校验，不是重复业务大小校验。

## 7. Kafka 图片引用契约

顶层 `tenantId` 继续来自现有消息 payload。普通图片、链接卡片缩略图和按钮卡片缩略图统一使用以下引用形状：

```json
{
  "image": {
    "assetRef": {
      "sha256": "e3b0c44298fc1c149afbf4c8996fb924...",
      "sizeBytes": 428312,
      "mimetype": "image/png",
      "transformProfile": "marketing-image-v1"
    }
  }
}
```

链接卡片和按钮卡片分别在现有 `linkCard.thumbnail`、`buttonCard.thumbnail` 位置使用相同的 `{assetRef}` 结构。

字段语义：

- `sha256`：源图内容身份和完整性摘要。
- `sizeBytes`：源图二进制长度，用于 Redis GET 后快速校验。
- `mimetype`：源图声明 MIME；Android 解码后以实际格式为准，规范化输出固定为 `image/jpeg`。
- `transformProfile`：处理规则版本；第一版只允许 `marketing-image-v1`，未知版本永久拒绝。

新产生的 Android 命令不再带 `base64`。测试环境不要求特殊发布顺序；Android 可保留旧 Base64 解析分支用于处理遗留命令，但新路径和验收不能依赖它。

## 8. Armada 设计

### 8.1 依赖与边界

Armada 当前没有 Redis 依赖。本次在 `armada-api` 增加 Spring Data Redis/Lettuce，并使用显式的 `RedisTemplate<String, byte[]>` 或等价原始字节序列化配置，禁止默认 Java 对象序列化。

建议边界：

```text
platform/protocol/media/AndroidImageAssetStore
  ensure(tenantId, sourceBytes, mimetype) -> AndroidImageAssetRef

platform/protocol/backend/android/AndroidMessageSendBackend
  - 收集当前批次所有 image/thumbnail
  - tenantId + SHA-256 去重
  - 每个唯一资产调用 ensure 一次
  - 用引用编码 Android wire payload
```

统一业务模型 `MessageSendCommand.MessageMedia` 和 Web backend 继续持有原始字节；Redis 引用只属于 Android adapter/wire 契约，不能泄漏到营销业务域或 Web backend。

### 8.2 批次去重

- 当前 `enqueue` 批次内，以 `tenantId + sourceSha256` 建 Map。
- 同图用于 100 个群只执行一次 Redis ensure，100 条 outbox 都引用同一个 SHA。
- 同图出现在后续批次或另一任务时只执行轻量 `EXPIRE`，不重复覆盖二进制。
- 普通图片、链接缩略图和按钮缩略图都参加同一源图去重。

### 8.3 事务边界

- 图片 Redis ensure 必须先于对应 Android outbox 写入。
- Redis 不可用或写入失败：抛出基础设施异常，本批 Android 图片命令不写 outbox，所在数据库事务回滚，由现有轮次调度重试。
- Redis 成功、数据库随后回滚：不补偿删除 Redis Key，避免误删其它任务共享资产；24 小时 TTL 清理孤儿。
- Web 命令路径不依赖本图片 Redis，保持现状。

## 9. Android 图片解析与规范化

### 9.1 获取和校验

Android 根据命令顶层 `tenantId`、引用 SHA 和本机 Redis namespace 生成逻辑 Key，不接受命令指定的物理 Key。

LRU miss 时按以下顺序执行：

1. Redis GET 原始二进制。
2. 比较 `len(raw)` 与 `sizeBytes`。
3. 计算原始字节 SHA-256 并与引用比较。
4. 通过 `DecodeConfig` 或等价方式先读取尺寸；总像素超过 25MP 时拒绝，避免压缩小文件解码后占用异常内存。
5. 完整解码并按 EXIF orientation 纠正方向。
6. 生成规范化主图和小缩略图。

### 9.2 `marketing-image-v1` 处理规则

主图：

- 保持宽高比。
- 最长边最大 1600px。
- 不放大小图。
- 输出 JPEG，质量 85。
- PNG 等带透明通道图片使用白色背景合成。

缩略图：

- 保持宽高比。
- 最长边最大 320px。
- 不放大。
- 输出 JPEG，质量 70。

规范化结果总大小超过 5MB 时不进入 LRU，并返回 `IMAGE_REENCODE_FAILED`。第一版缓存项为：

```go
type CachedImageAsset struct {
    ImageBytes     []byte
    ThumbnailBytes []byte
    Mimetype       string // 固定 image/jpeg
    Width          int
    Height         int
    SizeBytes      int
}
```

缓存字节创建后视为不可变；下游媒体加密和 protobuf 组包只能读取，不能原地修改。

### 9.3 原生发送边界

普通 `IMAGE` 发送使用 `ImageBytes` 作为媒体明文、`ThumbnailBytes` 作为 protobuf `JPEGThumbnail`。链接卡片和按钮卡片直接使用已经准备好的缩略图，不在每群发送路径再次解码或 JPEG 编码。

每群仍必须独立执行：

- 获取目标账号和群参与者设备信息。
- 生成随机 media key。
- 媒体加密和上传。
- Signal/sender-key 与群消息 protobuf 编码。
- 发送并等待 server ACK。

因此同一规范化明文的 `FileSHA256` 可以相同，但随机 media key 会使每群的加密媒体、`FileEncSHA256` 和上传 token 不同。本次不缓存上传结果。

## 10. Android 进程内 LRU

### 10.1 Key 与共享范围

LRU 是 Android 进程级单例，所有账号、任务和群共享：

```text
tenantId + sourceSha256 + transformProfile
```

第一版只有 `marketing-image-v1`。将处理规则版本放入 Key，可在未来升级尺寸或质量规则时自然隔离旧缓存。

### 10.2 容量与失效

- 容量固定写死为 64MB，第一版不增加配置项。
- cost 为 `len(ImageBytes) + len(ThumbnailBytes)` 加少量固定元数据开销。
- 使用 LRU 淘汰，超限时从最久未访问项开始删除。
- expire-after-access 为 20 分钟；每次命中都更新 `expiresAt=now+20m` 并移动到 LRU 头部。
- 每分钟清理一次过期项，Get 时也惰性删除过期项。
- 缓存移除只删除索引引用；已经取得缓存项的发送仍由 Go 引用保持，不受淘汰影响。

### 10.3 并发

- 使用 `singleflight.Group` 按完整 LRU Key 合并并发首次加载。
- leader 在 singleflight 内再次检查 LRU，避免 miss 与 Set 的竞态。
- 同一进程同一图片并发发送 100 个群时只允许一次 Redis GET 和一次规范化。
- 不缓存 Redis、校验或重编码失败结果；并发调用共享本次错误，下一次命令可以重新尝试。
- LRU 的 Map、双向链表、字节计数和访问续期由同一互斥边界保护，并执行 `go test -race`。

## 11. 错误语义与命令状态

| 场景 | 处理 | 结果/offset |
| --- | --- | --- |
| Redis 超时、连接失败 | 发送前临时故障 | 释放本次发送领取权，不提交 offset，重试 |
| Redis Key 不存在 | 资产已过期或生产契约破坏 | `IMAGE_ASSET_NOT_FOUND`，保存失败结果并提交 |
| 大小或 SHA 不一致 | 资产损坏或引用错误 | `IMAGE_ASSET_INVALID`，保存失败结果并提交 |
| MIME/像素/方向/重编码失败 | 图片不可处理 | `IMAGE_REENCODE_FAILED`，保存失败结果并提交 |
| WhatsApp 加密、上传或 ACK 失败 | 已进入原生发送 | 沿用 `SEND_FAILED` |

现有状态阶段保持：

```text
PROCESSING -> RESULT_STORED -> PUBLISHED
```

Redis 图片临时故障发生在调用 WhatsApp 之前，不应在 `PROCESSING` 超时后被误判为可能已经发送。执行器需增加严格 CAS 的“发送前释放领取权”操作：只在状态仍是本 worker 本次创建的 `PROCESSING`、且尚未调用 WhatsApp 时删除状态，然后返回可重试错误。CAS 失败时不得盲目删除或重新发送，必须重读权威状态。

缺失、损坏或重编码失败属于确定的发送前业务失败，正常调用 `StoreResult` 和结果事件发布。进入 WhatsApp 调用后禁止释放领取权，继续沿用现有崩溃窗口的 `SEND_RESULT_UNKNOWN` 保护。

## 12. Redis 大 Key 与容量影响

单个原始图片最多 500KB，相比普通 Redis 状态 Key 属于较大的 value，但本设计不会随群数量复制：1000 个群使用同图仍只有一个 Redis Key。主要容量变量是 24 小时内活跃的唯一图片数量：

```text
原图内存上界约等于：活跃唯一图片数 × 平均原图大小 + Redis 元数据
```

例如 1000 张都接近 500KB 时，纯 value 约 488MiB；这说明风险由“唯一图片数量”决定，不由“群数量”决定。

控制措施：

- 入库已有 500KB 上限。
- Redis value 使用二进制，避免 Base64 额外膨胀。
- 24 小时 TTL 自动回收。
- Armada 已存在时只发 `EXPIRE`，Android 同图热发送只 GET 一次/进程。
- 禁止 `KEYS` 扫描图片 namespace。
- 监控 Redis 内存、网络、命令延迟、`evicted_keys` 和活跃图片数量。
- 如果共享 Redis 已接近内存水位或出现 key 淘汰，应暂停扩量并评估对象存储，而不是继续提高 TTL。

共享 Redis 故障会同时影响既有 Android 状态和图片获取，这是用户确认复用同一套 Redis 所接受的故障域。

## 13. 租户、安全与日志

- Redis Key 必须包含 `tenantId`，Android 只能使用命令顶层已校验 tenantId。
- Kafka 不携带 Redis 地址、账号、密码或任意物理 Key。
- Redis 连接、TLS、ACL 和凭据只来自环境配置，禁止写入仓库或日志。
- Armada 与 Android 的 Redis ACL 都必须允许访问约定的图片 namespace。
- 日志不记录图片字节、Base64、完整 Redis Key或完整 SHA；可记录 SHA 前 8 位、tenantId、commandId、大小、耗时和低基数错误类型。
- LRU 不写磁盘，进程退出后由内存回收。

## 14. 可观测性

Armada 至少记录：

- 图片 cache ensure 的 `write/touch/fail` 计数。
- Redis ensure 耗时和首次写入字节数。
- 当前批次唯一图片数与引用命令数。

Android 至少记录：

- LRU hit、miss、eviction、expired 计数。
- 当前 entry 数和当前 cost bytes。
- singleflight 共享加载次数。
- Redis GET 耗时和读取字节数。
- 图片校验、规范化耗时及输入/输出字节数。
- WhatsApp 媒体上传耗时、ACK 耗时。
- `IMAGE_ASSET_NOT_FOUND/INVALID/REENCODE_FAILED/SEND_FAILED` 分类计数。

日志只用于定位单次命令，聚合指标不得使用 SHA、commandId、群 JID 等高基数字段作为 label。

## 15. 测试设计

### 15.1 Armada

- 相同租户相同图片的 100 条 Android 命令只 ensure 一个 Redis Key，引用 SHA 完全相同。
- 相同图片跨任务复用同一 Key；不同租户使用不同 Key。
- Redis miss 写入原始二进制并设置 24 小时 TTL，不出现 Base64/JSON/Java 序列化头。
- Redis hit 只续期，不重复发送 value。
- 同批普通图片、link thumbnail、button thumbnail 正确去重。
- Redis 失败不写 Android outbox，事务回滚。
- Android payload 不含 `base64`，只含完整引用字段。
- Web backend payload 保持现状。
- 文件下发不重复执行 500KB 业务校验。

### 15.2 Android

- 引用 parser 校验 SHA 格式、正数大小、MIME 和唯一支持的 profile。
- 首次 miss：一次 Redis GET、一次主图编码、一次缩略图编码。
- 同图 100 个并发群发送通过 singleflight 只加载一次。
- LRU 命中不发生 Base64、图片解码或 JPEG 编码。
- 每次访问把失效时间续到新的 20 分钟；20 分钟无访问后重新加载。
- 64MB 超限按 LRU 淘汰，字节计数准确；淘汰不影响正在使用的项。
- 25MP 上限、EXIF 方向、1600px 主图、320px 缩略图、白色透明背景和 JPEG 质量规则正确。
- Redis missing、大小不符、SHA 不符、解码失败和输出超过 5MB 返回稳定错误码。
- Redis 临时故障在 WhatsApp 调用前 CAS 释放 PROCESSING，Kafka 重试不会产生 `SEND_RESULT_UNKNOWN`。
- 已进入 WhatsApp 发送后不允许释放 PROCESSING。
- 普通图片 protobuf 使用小缩略图，不再使用完整原图。
- 链接/按钮卡片使用缓存缩略图，不按群重复重编码。
- 执行 `gofmt`、`go vet ./...`、`go build ./...`、`go test ./...` 和相关 `go test -race`。

### 15.3 集成验收

在测试环境用同一模板图片发送至少 100 个群，验证：

1. Redis 只有一个该租户图片 Key。
2. 100 条新 Kafka 命令引用相同 SHA，均不含 Base64。
3. 每个 Android 进程只有一次 Redis GET 和一次规范化；后续全部 LRU hit。
4. LRU 命中持续发生时图片不会在 20 分钟内失效。
5. 每个群仍分别产生媒体上传和发送 ACK，结果事件正常收敛。
6. Redis 图片 TTL 接近 24 小时；Android GET 不改变它，Armada 新任务使用会续期。

## 16. 代码影响范围

Armada 预期修改：

```text
armada-api/pom.xml
armada-api/src/main/resources/application.yml
armada-api/src/main/java/com/armada/platform/protocol/backend/android/AndroidMessageSendBackend.java
armada-api/src/main/java/com/armada/platform/protocol/media/*
armada-api/src/test/java/com/armada/platform/protocol/backend/android/*
armada-api/src/test/java/com/armada/platform/protocol/media/*
armada-deploy/相关测试环境 Redis 配置模板
```

Android 预期修改：

```text
internal/armada/message_command.go
internal/armada/message_sender.go
internal/armada/message_executor.go
internal/armada/message_state.go
internal/armada/image_asset_cache.go（新增）
internal/armada/image_asset_loader.go（新增）
internal/armada/card_thumbnail.go
internal/service/app/group.go
internal/service/node/message_payload.go
对应测试文件
```

具体文件可在实施计划中根据现有分层进一步收敛，不为方便缓存而让 `internal/service` 反向依赖 `internal/armada`。

## 17. 测试环境切换与回退

用户已确认测试环境无需纠结部署顺序，本次不增加 Armada 图片引用功能开关。Armada 与 Android 修改完成并通过各自测试后可直接更新测试环境。

回退时回退本次两个仓库的业务代码即可；不删除 Redis 图片 Key，不清理 Kafka topic，不修改 MySQL 图片。遗留图片 Key 由 24 小时 TTL 自动清理。若 Android 暂时保留旧 Base64 解析分支，它只作为遗留消息兼容，不影响新路径验收。

## 18. 验收标准

1. 同一租户同一模板图片不按群、账号、任务或命令重复建立 Redis/LRU 缓存项。
2. 100 个群的新 Android Kafka 命令不携带图片 Base64。
3. 同一 Android 进程、20 分钟访问有效期内，同图只 Redis GET 和解码重编码一次。
4. LRU 固定 64MB，按字节 LRU 淘汰，访问续期 20 分钟。
5. Redis 原图二进制 TTL 为 24 小时，只由 Armada 实际使用续期。
6. 主图、缩略图和透明背景符合 `marketing-image-v1`。
7. 每群不做图片 Base64 或像素重编码，但仍独立完成 WhatsApp 加密、上传和 ACK。
8. Redis 临时故障可安全重试；缺失、损坏和重编码错误稳定终结且不阻塞账号队列。
9. Web 图片发送行为不变，MySQL 仍是图片事实源。

## 19. 事实、推断与未确认项

### 已确认事实

- 图片入库时已有 500KB 上限，Armada 下发时不重复校验。
- Armada 与 Android 使用同一套 Redis。
- Redis TTL 为 24 小时，仅 Armada 使用时续期。
- Android LRU 访问续期 20 分钟，容量先写死 64MB。
- LRU 缓存规范化后的压缩图片，不缓存解码像素。
- 测试环境不要求设计发布顺序。

### 设计推断

- 500KB value 可用于近期测试方案，但容量必须按每日活跃唯一图片数观测；若唯一图片规模持续增大，应迁移对象存储。
- 同一图片的规范化明文可跨群复用；WhatsApp 随机 media key 仍保证各群加密上传结果独立。

### 未确认项

无。Redis 实际地址、TLS、ACL、namespace 和测试环境容量属于实施/部署前配置核对，不改变本设计业务口径。
