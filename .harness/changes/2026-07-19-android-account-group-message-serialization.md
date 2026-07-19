# 变更记录：Android 同账号群消息串行发送

- 日期 / 分支 / worktree: 2026-07-19 / `1.0.1-snapshot` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 用户确认的同账号 Android 群消息串行设计；跨仓设计见 `../whatsapp-server-feature-android-zhuan/docs/superpowers/specs/2026-07-19-android-account-group-message-serialization-design.md`
- 状态: 已完成相称验证并部署到第二套测试环境；未执行真实营销消息冒烟

## 目标（一句话）

Armada 把账号群发送间隔写入 Android 消息命令，使 Go 能按账号独立队列完成“发送结果回传后等待间隔，再处理下一条”。

## 缺口拆解 / 任务清单

- [x] 确认普通营销 `accountGroupSendIntervalMs` 当前仅用于 `notBeforeAt` 错峰投递。
- [x] 确认 Android wire payload 当前没有 `sendIntervalMs`。
- [x] 确认 Android 结果先写 Kafka、再提交输入 offset 的现有契约。
- [x] 编写跨仓实施计划：`../whatsapp-server-feature-android-zhuan/docs/superpowers/plans/2026-07-19-android-account-group-message-serialization.md`。
- [x] 为协议无关消息命令补充账号群发送间隔，并由各群消息来源显式赋值。
- [x] Android backend 把间隔编码为 `sendIntervalMs`，Web backend 保持不变。
- [x] 补充普通营销、建群营销、历史群营销和 Android payload 契约测试。
- [x] 完成 Go 账号动态队列、共享 dispatcher 和结果发布后提交 offset 的跨仓契约测试。
- [x] 补齐 Go 对 `historical_group_pull` execution/member 关联的解析、归因和结果事件透传。

## 关键设计决策

- 范围校验仍由 Armada 负责，Android 不重复校验；旧 payload 缺失字段时 Go 使用 `500ms`。
- `notBeforeAt` 继续作为 Armada outbox 内部投递时间，不复用为 Go 发送完成后的间隔。
- 普通营销传页面配置；没有该页面配置的其他群消息来源显式传 `500ms`。
- 同账号串行属于 Android Go 进程内执行模型；Armada 不使用 Kafka 节奏模拟发送完成，也不引入 Redis 锁。
- 发送成功或业务失败都必须先发布 `message.send_result_reported`，再提交输入 offset。
- 不增加数据库列、Redis schema、Kafka topic 或对外 API。

否决方案：

- 固定 worker 加账号锁：等待同账号锁的消息会占满 worker，拖慢其他账号。
- 小容量单账号队列：正常账号可能一次发送上百个群，会误丢业务消息。
- Kafka 分区串行或全局调度器：会阻塞无关账号或引入当前不需要的复杂调度。

## 验证（evidence-before-done）

设计阶段已核对：

- `MarketingRoundWorker` 使用 `accountGroupSendIntervalMs` 计算 `notBeforeAt`。
- `MessageSendCommand.notBeforeAt` 明确不进入协议 payload。
- `AndroidMessageSendBackend.AndroidMessagePayload` 当前没有发送间隔字段。
- Go `MessageCommandExecutor` 当前顺序为 StoreResult -> Publish result event -> MarkPublished；consumer 随后 Commit。

实施结果：

- Armada 红灯确认：新增 `sendIntervalMs()` 断言后，定向测试按预期因字段不存在编译失败。
- Armada 定向回归：`MarketingRoundWorkerTest`、`GroupCreationMarketingWorkerTest`、
  `HistoricalGroupMarketingServiceImplTest`、Android/Web backend、routing、outbox 共 77 个测试通过。
- 普通营销测试显式锁定非默认 `750ms` 页面配置透传，不只覆盖兼容默认值。
- Armada 全量 `mvn test` 会进入本地未配置的真库/集成测试并持续连接 MySQL，已中止；本次无数据库改动，
  全量 test compile 和上述受影响单测均通过。
- Go parser、dispatcher、account consumer、启动接线定向测试通过；同账号 500 条突发、多个 reader
  共用 dispatcher、冷却期间入队、panic、取消和退出竞态均有覆盖。
- 历史群消息通过真实 account consumer/executor 发布带 execution/member 的结果事件后再提交 offset；
  可归因的非法历史群 payload 也能保留关联并发布失败结果。
- dispatcher 的账号日志使用现有末四位脱敏；panic 只记录固定 `panic_recovered` 分类和安全元数据，
  不记录 panic value、消息正文或完整账号 ID。
- `go test -race ./internal/armada` 通过；`go build ./...` 通过。
- `go test ./...` 中本次相关 `internal/armada` 通过；全仓仍有未改动区域的既有失败：
  `internal/service/appstate` 测试参数类型不匹配，`pkg/noise` 缺少 `vectors.txt` 且固定向量不一致。
- `go vet ./...` 同样被既有 `appstate` 测试编译错误阻断，并报告未改文件中的 IPv6 地址格式和
  `context.WithTimeout` cancel 警告。

## 部署

- 目标环境：第二套测试环境；Android Go 主机 `ec2-3-111-245-182.ap-south-1.compute.amazonaws.com`，
  Armada 主机 `ec2-3-110-124-52.ap-south-1.compute.amazonaws.com`。本次未创建 commit，也未修改受保护的
  `.env`、Android TOML、证书或凭据。
- 部署源码：Armada `2d6d95b28dace542252a4c24b6ae7756fc7cb192`；Android Go
  `8bf1bcceae3e8c40aeddb87ea2a805dd4695682a`，均为部署时当前 `1.0.1-snapshot` HEAD。
- 部署前复验：Go `go test -race ./internal/armada -count=1` 和 `go build ./...` 通过；Armada 受影响的
  77 个测试全部通过，0 failure、0 error。
- Android 部署目录为 `/home/ec2-user/whatsapp-android-zhuan`，使用
  `deploy/docker-compose.perf.yml`。远端 Compose `v5.1.2` 与 Buildx `0.12.1` 不兼容，标准
  `compose build` 在切换容器前失败；确认根因后使用现有 Docker 25 legacy builder 按同一 Dockerfile
  构建同名 perf 镜像，未升级或改写系统 Docker 组件。幂等迁移完成，应用和 callback 均 healthy。
- Android 新镜像 `sha256:69b9e10a9ec8cf9a6d5e5344a3bc465702b36548016393eadb61d4b50dc07b0e`；
  核心源码本地/远端组合 SHA-256 均为
  `e2c368b4218114875f1756bf59fc2d21a9d8b529714a32df1e00eaeb8ed149eb`。HTTP 200、重启次数 0，
  三组 perf command topic/group 按既有配置启动，启动后严重错误计数为 0。
- Armada 使用 `armada-deploy/deploy-test.sh --be -y` 和第二套环境显式参数部署到
  `/home/app/armada-deploy`。新镜像
  `sha256:ea1a7fa7d85088625877fddc34d9a8c8ad6fb82b924fc6ef25b556aa348ade11`；本地/远端 JAR
  SHA-256 均为 `a99d9f919738aceb5c0e5aa57bc9a57b05d2682dd89d678d46dab1de3afa043b`。
  Spring 启动完成，API 代理校验通过，重启次数 0，启动后严重错误计数为 0；Armada 主机访问 Android
  Swagger 返回 HTTP 200。
- 回滚资源：Android 旧镜像标签
  `whatsapp-server-feature-android-zhuan:pre-account-serial-20260719-142716`（镜像
  `sha256:c901ca1626d0df4aad1d03528cf77458577252b89efe7fd14a4971a9c872f59c`）；Armada 旧镜像标签
  `armada-deploy-backend:pre-account-serial-20260719-142716`（镜像
  `sha256:072eb10a8a445835381fc3e49f8065c54f66af0f663b64058c97dd0290f2c6d6`），旧 JAR 备份后缀为
  `.pre-account-serial-20260719-142716`。
- 本次只验证部署、启动、配置和跨机 HTTP 链路；未主动创建或重发营销任务，账号级串行的真实群发送时序
  需由后续业务消息验证。

## 遗留 / 跟进

- Go 内存队列不设业务容量上限；上线后观察账号队列深度和进程总排队量。
- 若后续要求全仓 Go 测试全绿，需另行处理 `appstate` 和 `pkg/noise` 既有测试问题；不纳入本次功能改动。
