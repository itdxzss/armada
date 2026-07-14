# 变更记录：Web / Android 多协议进群路由

- 日期 / 分支 / worktree: 2026-07-14 / detached HEAD / `armada-multi-protocol-routing`
- 需求来源: `docs/superpowers/plans/2026-07-11-multi-protocol-join-task-routing.md`
- 状态: 进行中

## 目标（一句话）

让进群任务按账号协议后端选择 Web/Baileys 或 Android Zhuan 原生能力，同时保持业务 Worker 不感知协议 HTTP 契约。

## 缺口拆解 / 任务清单

- [x] 增加按协议后端隔离的 HTTP 配置与执行器注册表。
- [x] 增加统一进群模型、路由端口与 Web 进群 backend。
- [x] 增加后端感知的账号运行态窄端口、路由实现与 Web 状态 backend。
- [x] 增加 Android 原生 HTTP client、响应 decoder 与错误映射。
- [x] 增加 Android 运行态 backend。
- [x] 增加 Android 邀请码规范化与进群成功响应解析。
- [ ] 增加 Android 群成员确认与进群 backend。
- [ ] 使用统一进群和运行态端口收口 `JoinTaskWorker`。

## 关键设计决策

- 运行态查询使用独立 `AccountRuntimeStatusPort`，不继续把同步热路径查询和账号上线、批量上线、探活混在同一个生命周期端口中。
- 路由只根据 `ProtocolAccountRef.backend` 选择 backend；业务调用方不额外传递协议类型。
- Task 3 先注册 Web 状态 backend；Task 5 补齐 Android 状态 backend 后，统一端口可按账号 backend 路由两种实现。
- 现有 `AccountLifecyclePort.status` 暂时保留给存量调用方，待最终 Worker 收口和全仓调用审计后再评估移除，避免本切片扩大范围。
- Android 原生 client 复用按 backend 隔离的 `ProtocolHttpExecutorRegistry`，不新增并行 HTTP 配置路径。
- Android 业务失败通常使用 HTTP 200，因此先解码 `Code/Data/Msg/error` envelope，再由操作级 mapper 映射错误；原始消息不进入异常文本。
- Android 完整邀请链接只接受无 userinfo、端口、query、fragment 和额外路径的 `https://chat.whatsapp.com/{code}`；原生成功文本必须解析出群 ID 才能继续。

## 影响与外部变更

- 影响模块: `armada-api/platform/protocol`。
- 数据库变更: 无。
- HTTP API 变更: 无。
- Kafka 变更: 无。
- Redis 变更: 无。

## 验证（evidence-before-done）

- Task 3 新增路由、Web adapter 与 Spring 装配测试，执行过程已分别观察预期 RED 和 GREEN。
- Task 4 新增 Android envelope decoder、错误 mapper、原生 HTTP 请求形状与 Spring 装配测试，执行过程已分别观察预期 RED 和 GREEN。
- Task 5 新增 Android ONLINE/OFFLINE 语义、未知失败、非法响应、网络异常上下文与双 backend Spring 装配测试，执行过程已分别观察预期 RED 和 GREEN。
- Task 6 新增严格邀请码、邀请 URI 边界、原生成功群 ID 提取和非法成功响应测试，执行过程已分别观察预期 RED 和 GREEN。
- 使用 JDK 17 并在当前沙箱预加载 Byte Buddy agent，执行以下聚焦回归：

```bash
mvn -DargLine=-javaagent:<byte-buddy-agent-1.14.19.jar> \
  -Dtest=ProtocolPropertiesTest,ProtocolConfigurationTest,ProtocolHttpExecutorRegistryTest,\
ProtocolExceptionTest,ProtocolHttpExecutorTest,HttpAccountLifecycleAdapterTest,\
AccountLifecyclePortContractTest,RoutingGroupJoinPortTest,WebNativeGroupJoinAdapterTest,\
ProtocolAccountRuntimeStatusTest,RoutingAccountRuntimeStatusPortTest,\
WebAccountRuntimeStatusAdapterTest,AndroidResponseDecoderTest,\
AndroidGroupJoinErrorMapperTest,HttpAndroidNativeClientTest,\
AndroidAccountRuntimeStatusAdapterTest,AndroidGroupJoinResponseMapperTest,\
JoinTaskWorkerTest test
```

关键输出：`Tests run: 66, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 尝试运行整个 `com.armada.platform.protocol` 测试包时命中既有真库
  `ProtocolCommandOutboxSchemaDbTest`；当前本地没有可用数据库凭据，因此停止该通配测试。
  Task 3 至 Task 6 不涉及数据库、Mapper 或迁移脚本。

## 部署

- commit / 环境 / 部署后验证结果: 未部署；本 worktree 仅进行开发提交。

## 回滚方案

- 回退本次 Task 3 提交，移除新增运行态模型、端口、路由、Web adapter 及 Spring 装配。
- 回退 Task 4 提交，移除 Android 原生响应模型、decoder、错误 mapper、HTTP client 及对应 Spring Bean。
- 回退 Task 5 提交，移除 Android 运行态 backend 及其 Spring 注册；Web 运行态路径仍可独立工作。
- 回退 Task 6 提交，移除 Android 邀请码与进群成功响应 mapper；不涉及数据回滚。
- 现有 `AccountLifecyclePort.status` 在本切片中未删除，回滚后存量状态查询路径不受影响。
- 本次没有数据库、对外 HTTP API、Kafka 或 Redis 变更，无需额外数据回滚。

## 遗留 / 跟进

- 按实施计划继续 Task 7：Android 群成员确认与进群 backend。
- 全功能完成前不合并 `1.0.1-snapshot`。
