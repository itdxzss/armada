# 变更记录：Android 营销图片 Redis + 进程 LRU

- 日期 / 分支 / worktree: 2026-07-19 / `1.0.1-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户本次确认；`docs/superpowers/specs/2026-07-19-android-marketing-image-redis-lru-design.md`
- 状态: 本地编码与聚焦验证完成，待测试环境集成验收

## 目标（一句话）

把 Android 营销图片从“每群 Base64 随 Kafka 传输并重复处理”改为“Armada Redis 原图引用 + Android 进程级规范化图片 LRU”。

## 缺口拆解 / 任务清单

- [x] 核对 Armada -> outbox -> Kafka -> Android -> WhatsApp 当前图片链路。
- [x] 确认 Redis、Kafka 引用、LRU、图片规范化、TTL、错误和验收口径。
- [x] 输出跨仓设计文档。
- [x] 输出分步骤实施计划：
  - `docs/superpowers/plans/2026-07-19-android-marketing-image-redis-lru-armada.md`
  - `docs/superpowers/plans/2026-07-19-android-marketing-image-redis-lru-zhuan.md`
- [x] Armada 增加 Redis 原图缓存和 Android 图片引用契约。
- [x] Android 增加引用加载、规范化、64MB/20 分钟访问续期 LRU 和 singleflight。
- [x] 调整普通图片及卡片发送，复用规范化字节和小缩略图。
- [x] 补齐 Java/Go 聚焦单测和并发测试。
- [ ] 在测试环境完成同模板图片发送 100 群的集成验收。

## 关键设计决策

- MySQL 保持事实源；Redis 只保存 24 小时原图二进制。
- Redis/LRU 身份使用 `tenantId + sourceSha256`，不包含任务、群、账号或命令。
- Kafka 不携带 Redis 物理 Key，只携带 SHA、大小、MIME 和处理规则版本。
- Armada 同批去重；已存在图片只续 TTL，不重复覆盖 value。
- 图片 500KB 上限已在入库校验，下发不重复校验。
- Android LRU 为进程全局、固定 64MB、expire-after-access 20 分钟，命中续期。
- LRU 缓存规范化 JPEG 主图和小缩略图，不缓存占用巨大的解码像素。
- `marketing-image-v1`：主图最长边 1600/q85，缩略图最长边 320/q70，透明背景转白色，最大 25MP。
- 每群不再 Base64 或像素重编码，但 WhatsApp 媒体加密、上传和 ACK 仍逐群执行。
- Redis 临时故障发生在发送前时用 CAS 释放 PROCESSING 领取权再重试；发送后幂等状态语义不变。
- 只要求测试环境直接切换，不增加 Armada 功能开关或生产滚动发布设计。

否决方案：

- 只使用 Redis：仍会每群 GET 和重编码。
- LRU 缓存解码像素：内存放大明显。
- 当前直接引入对象存储：近期复杂度高，作为容量增长后的后续方案。

## 验证（evidence-before-done）

- Armada 聚焦测试：25 个测试通过，覆盖 Redis 配置、原图资源、Redis ensure、Android backend 和 Web backend 回归。
- Armada `mvn -DskipTests package` 通过；`armada-deploy/verify-config.mjs` 通过。
- 用户确认本次不执行 Armada MySQL 真库测试；本变更也没有表、SQL、Mapper 或 Flyway 修改。
- Android 图片解析、LRU、singleflight、规范化、sender、executor、生命周期聚焦测试通过；缓存与加载并发测试使用 `go test -race` 通过。
- Android `go build ./...` 通过；任务相关包 `go vet` 通过。
- Android 全量 `go vet ./...` / `go test ./...` 未全绿：未修改的旧 `appstate_test.go` 仍向当前 `decodeSnapshot` 传 `[]byte`，另有既有 vet 告警、Noise 向量测试问题；当前沙箱还禁止 miniredis/httptest 监听本地端口。
- 尚未执行真实 Redis、Kafka、WhatsApp 或测试环境 100 群同图验收。

## 部署

- commit / 环境 / 部署后验证结果: 代码已纳入本次 Git 提交，未部署；用户确认测试环境无需特殊发布顺序。

## 遗留 / 跟进

- 实施前核对共享 Redis 地址、TLS、ACL、双方最终物理 namespace 和可用内存。
- 如果每日活跃唯一图片导致 Redis 水位或 `evicted_keys` 异常，单独设计 MinIO/S3 迁移。
