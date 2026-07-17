# 变更记录：Android Zhuan 接入第二套性能环境

- 日期 / 分支 / worktree：2026-07-17 / `1.0.1-snapshot` / 当前主 worktree
- 需求来源：用户要求 Android 新机不接第一套，改接第二套性能环境；Web 与 Android 同时接入一个 Armada
- 设计文档：`docs/superpowers/specs/2026-07-17-android-zhuan-perf-deployment-design.md`
- 状态：进行中（设计和实施计划完成，尚未编码或部署）

## 目标（一句话）

将 Android Zhuan 独立部署到新机，并通过环境专属 Kafka topic、Redis namespace 和 MySQL schema 安全接入
第二套 Armada，同时保持现有 Web 协议不变。

## 缺口拆解 / 任务清单

- [x] 核对第二套 Armada、Web 协议机、Android 新机的资源和网络拓扑。
- [x] 核对共享 MSK 中第一套 Android topic 与第二套 perf topic 的真实分区配置。
- [x] 确认 Web 与 Android 同时接入一个 Armada。
- [x] 确认 Android 三个命令 topic 使用 perf 专属名称，每个12分区。
- [x] 确认 Android 复用第二套 Redis Cluster，使用 `android-zhuan-perf:` 前缀。
- [x] 完成并确认部署设计。
- [ ] 为 Zhuan 增加 Redis standalone/cluster、TLS、ACL 和全局 prefix 支持及测试。
- [ ] 将共享 Redis 上的宽泛 `KEYS` 清理改为 namespace 限定的 Cluster SCAN。
- [ ] 准备新机外部 Redis Compose 配置和 perf Zhuan 受保护配置。
- [x] 编写并自审实施计划。
- [ ] 用户开放 MSK、Redis、Android HTTP 安全组并完成连通性复核。
- [ ] 创建独立 RDS schema/用户，执行迁移并复制 `wa_devices` 参考表。
- [ ] 创建并核对三个 perf Android command topic。
- [ ] 部署 Zhuan，验证 Redis/MySQL/Kafka/HTTP。
- [ ] 修改第二套 Armada 配置并完成 Web/Android 冒烟。

## 关键设计决策

- 第一套和第二套共用 MSK，因此第二套禁止使用无环境前缀的 `protocol.android.*` topic。
- 第二套 Android command topic 使用 `armada.perf.protocol.android.*`，三个 consumer group 同样带 perf 前缀。
- 三个 command topic 均为12分区、3副本；Zhuan 初期每组 consumer concurrency 为4。
- Web 与 Android 结果复用现有 `armada.perf.protocol.*.events.v1`，不新增 Android event topic。
- Redis 复用第二套 TLS Cluster DB 0，Web 使用 `armada-perf:*`，Android 使用 `android-zhuan-perf:*`。
- 第一套 standalone Redis 保持兼容；本次不强制迁移第一套。
- MySQL 复用第二套 RDS 实例但使用独立 `whatsapp_android_zhuan_perf` schema。
- 采用简化四步接入：准备依赖、配置并部署 Zhuan、重建 Armada backend、Web/Android 冒烟。
- 回退时停止新 Zhuan并保留 perf topic，禁止把 Armada 恢复到第一套 Android topic。

### 被否决方案

- 复用第一套无前缀 Android topic：同一 MSK 上会发生跨环境消费。
- 只更换 consumer group、不更换 topic：不同 group 会各自消费全量消息，造成跨环境重复执行。
- 使用 Redis logical DB 隔离：第二套为 Redis Cluster，只支持 DB 0。
- Android event 也新建独立 topic：需要 Armada 增加额外事件订阅，当前没有必要。
- 完整暗部署和双阶段 Kafka 开关：测试性能环境没有现存 Android 流量，用户选择简化流程。

## 验证（evidence-before-done）

已完成只读事实核对：

- 三台机器同属一个 VPC；Android 新机为4C8G，Docker/Git/Compose 可用。
- Android 新机到第二套 RDS 3306 可达；检查时 MSK 9094 尚未放通。
- 第二套现有 perf command/event topic 为12分区、3副本、`min.insync.replicas=2`。
- 第一套三个拆分后的 Android topic 已存在，均为4分区、3副本。
- 第二套 Armada 当前三个 Android topic 仍为无 perf 前缀默认值。
- 第二套 Redis 为 TLS Cluster、DB 0，Web key prefix 为 `armada-perf:`。
- 第一套 Zhuan Redis 为本地 standalone，`cluster_enabled=0`。

尚未运行代码测试、共享 Redis 集成测试或部署验证；实施阶段必须补充真实命令与输出。

## 部署

- commit / 环境 / 部署后验证结果：尚未部署。

## 遗留 / 跟进

- 用户需先开放第二套 MSK、Redis 与 Android HTTP 的安全组规则。
- Redis ACL 专属用户属于安全加固项，不阻塞首期接入。
- 实施前必须再次确认目标为第二套性能环境，并检查 Android outbox 无第一套 topic 遗留。
