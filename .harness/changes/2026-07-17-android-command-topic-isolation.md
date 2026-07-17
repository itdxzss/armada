# 变更记录：Android 命令 Topic 隔离

- 日期 / 分支 / worktree: 2026-07-17 / `1.0.1-snapshot` / 当前 checkout
- 需求来源: 用户确认；`docs/superpowers/specs/2026-07-17-android-command-topic-isolation-design.md`
- 状态: dev-1 已完成停机切换和部署，待业务侧补发 lifecycle/group-join 实际命令快照

## 目标（一句话）

把 Android 生命周期、营销消息和进群命令拆成三个 Kafka topic 和三个独立 consumer pool，避免营销或进群积压拖慢批量上线、下线。

## 缺口拆解 / 任务清单

- [x] Armada 三 topic 配置与启动校验
- [x] Armada 四类 Android command type 精确路由
- [x] Zhuan 三组 TOML 配置与启动校验
- [x] Zhuan 三个独立 consumer pool 与错路由永久提交
- [x] 离线营销 `ACCOUNT_OFFLINE`、离线进群 `ACCOUNT_NOT_ONLINE` 回归
- [x] 本地 Java/Go/部署模板聚焦验证
- [x] dev-1 停机切换、部署与消费隔离验收

## 关键设计决策

- 停机切换，不双写、不双读、不迁移旧 topic 消息。
- 三个 topic 都以 `protocolAccountId` 为 key，每个默认 4 分区、Zhuan 每组 4 consumer。
- 营销发送不预查、不等待账号上线；账号实例不可用时回报 `ACCOUNT_OFFLINE` 后提交 source offset。
- 进群离线继续回报 `ACCOUNT_NOT_ONLINE`。
- event topic、outbox 状态机和 Web/master 路由不变。

## 执行前基线

- Armada: `mvn -Dtest='ProtocolCommandOutboxServiceImplTest,AndroidMessageSendBackendTest,ProtocolConfigurationTest,ProtocolAndroidCommandPropertiesTest,ProtocolKafkaConfigurationTest' test`，39 tests，0 failure，0 error，BUILD SUCCESS。
- Android Zhuan: `go test ./internal/configs ./internal/armada -count=1`，两个 package 均通过。
- 两个仓库均在 `1.0.1-snapshot`；Zhuan 按钮消息改动已按用户要求分别提交为 `114232f 修正安卓按钮消息版本号`、`0a3dbb9 调整安卓按钮消息顶层结构`、`cc11ac4 完善安卓按钮消息原生流参数`、`d395c86 调整安卓按钮消息协议参数`。

## 验证（evidence-before-done）

- Zhuan 聚焦：`go test ./internal/configs ./internal/armada -count=1`，两个 package 通过。
- Zhuan race：`go test -race ./internal/armada -count=1`，通过。
- Zhuan vet：`go vet ./internal/configs ./internal/armada`，通过。
- Zhuan build：`go build ./...`，通过。
- Zhuan 全量已知失败：`go vet ./...` 仍报 `tcpclient.go` IPv6 地址格式、`promise.go` 未调用 cancel，以及 `appstate_test.go` 的 `[]byte`/`*waproto.SyncdSnapshot` 类型不匹配；`go test ./...` 同样在 appstate 编译失败，并保留 `pkg/noise` 向量/缺少 `vectors.txt` 等既有失败。topic 涉及 package 均通过。
- Armada 聚焦：39 tests，0 failure，0 error，BUILD SUCCESS。
- Armada 全量：`mvn test` 因本机 MySQL `root@localhost` 无密码访问被拒绝而无法完成数据库集成测试；中止时统计为 154 tests、47 errors，根因均为 Spring context/Flyway 无法连接本机数据库。该环境问题不影响上述聚焦测试。
- 部署模板：`node armada-deploy/verify-config.mjs`、`bash armada-deploy/deploy-test.test.sh`、`bash armada-deploy/package-prod.test.sh` 均通过。
- 合同移除扫描：活跃代码和模板中 `protocol.android.commands.v1`、`PROTOCOL_ANDROID_COMMANDS_TOPIC`、旧 TOML 单通道键均无命中。
- 安全扫描：本次 topic 提交未发现私钥、AWS access key 或明文数据库密码模式。
- 独立代码审查及复核：无 Critical；发现的两个 Important 均位于运维文档——旧 topic 非终态 outbox 可能在重启后继续发送、旧 lifecycle 手册仍引用单 topic。已补充停服后全量取消旧 topic PENDING/LOCKED 行及零残留查询，并将旧手册标为废弃、链接新手册；复核确认两个 Important 均清零。剩余仅 writer 关闭计数、错 topic/commit/安全日志组合测试等 Minor 覆盖建议，未发现对应生产缺陷。

## 部署

- 目标: dev-1 (`65.2.123.53`)，用户已确认允许停机和丢弃旧 topic 未消费命令。
- 切换手册：`docs/operations/android-command-topic-isolation-cutover.md`。
- 部署时本地 commit：Armada `005ba0b`（topic 核心 `808cd42`）；Zhuan `d395c86`（topic 核心 `5c169ce`、`1dda337`）。
- 停机切换时间：`cutover_epoch_ms=1784261749000`；旧 topic 可发送 outbox 取消 `0` 行，切换后可发送残留为 `0`。
- 已创建 `protocol.android.lifecycle.commands.v1`、`protocol.android.message.commands.v1`、`protocol.android.group-join.commands.v1`，均为 4 分区；旧 `protocol.android.commands.v1` 保留。
- Zhuan 受保护 TOML 已从 3 个旧键迁移为 9 个新键，旧键计数为 `0`；启动日志确认三组 topic/group 与并发度 `4/4/4`。
- 三个 consumer group 都只分配到同名 topic，各有 4 个分区；验收时 message group 四分区 lag 均为 `0`。
- Armada 容器内三个 topic 环境变量均为新值；切换后真实 outbox 快照为 `message.send.requested -> protocol.android.message.commands.v1`，`SENT(2)` 共 91 条，旧 topic 新行数和全历史可发送行均为 `0`。
- 验收结束时 Armada API 返回 HTTP 200，Zhuan/Redis/callback 均 healthy；本地与远程 Armada JAR SHA-256 一致，Zhuan 核心源码组合 SHA-256 一致。
- 已保留回滚资源：Armada 旧镜像标签 `armada-backend:pre-topic-split-1784261625000`，Zhuan 旧源码和受保护配置备份 `/home/ubuntu/armada-deploy-backups/whatsapp-android-zhuan-pre-topic-split-1784261749000`。
- 部署异常处理：Zhuan 旧容器引用的镜像对象已被清理，普通 `up -d` 未替换容器，使用新镜像 `--force-recreate` 后健康；Armada 脚本在 Spring 完成启动前立即验 API 得到 502，约 10 秒启动完成后 API 稳定返回 200。

## 遗留 / 跟进

- 旧 `protocol.android.commands.v1` 保留；后续删除必须单独批准。
- 验收期间没有新的 lifecycle/group-join 业务命令；这两组已确认运行时配置、topic 和消费分区，但实际发送证据需在有授权登录态和明确测试账号/群链接后补齐。
