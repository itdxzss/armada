# 变更记录：Android 建群营销完整协议路由

- 日期 / 分支 / 工作区: 2026-07-21 / `1.0.1-snapshot` / 当前工作区
- 需求来源: 用户要求 Android 与 Web 完整对齐；设计见 `docs/superpowers/specs/2026-07-21-android-group-creation-marketing-routing-design.md`
- 状态: 代码实现完成，待测试环境真实联调

## 目标（一句话）

按账号 `protocol_id` 将联系人预存、建群、关闭发言、成员快照和营销消息完整路由到 Web 或 Android。

## 缺口拆解 / 任务清单

- [x] 建群、联系人和群成员读取改为账号感知的统一命令与 Routing Port。
- [x] Web adapter 迁移且保持原 HTTP 契约。
- [x] Android 原生 HTTP client 接入联系人、建群和关闭发言接口。
- [x] Android 建群、成员和错误响应映射。
- [x] Android backends 与 Spring 装配。
- [x] Worker、直接建群和历史 Web-only 联系人调用迁移。
- [ ] Maven 全量测试。
- [ ] 明确测试环境后执行真实联调。

## 关键设计决策

- 使用持久化 `account.protocol_id`，不在运行时解析 JSON/六段导入格式。
- 群操作保持同步 HTTP；营销消息保持 outbox + Kafka。
- 关闭普通成员发言为 best effort，失败不推翻已经成功的建群。
- Android 建群响应与 Web 对齐：`GroupId` 有效即保留建群成功；成员数组缺失或单个成员身份无法识别时，
  只把对应成员降级为 `UNKNOWN` / 空身份占位，不触发换号重复建群。
- 不在 Worker 内写 Android 分支，不修改数据库、前端或 Android Go 服务。
- 在 `1.0.1-snapshot` 当前工作区完成实现并提交。

## 验证（evidence-before-done）

- 定向回归：在 `armada-api/` 执行计划中的 19 类协议与建群营销测试，69 个测试全部通过，0 failures / 0 errors / 0 skipped，Maven `BUILD SUCCESS`，总耗时 2.168 秒。
- Web 语义对齐修复按 TDD 验证：新增 5 个回归场景均先得到预期失败；最小修复后，未知成员占位、备用身份回退、建群成功保留和进群确认全部转绿。
- 扩大回归：`mvn -Dtest='GroupOperationServiceImplTest,HistoricalGroupPullWorkerImplTest,GroupCreationMarketingWorkerTest,Android*Test,ProtocolConfigurationTest,HttpContactAdapterTest,HttpGroupCreateAdapterTest,HttpGroupParticipantAdapterTest,HttpGroupMemberListAdapterTest,RoutingContactPortTest,RoutingGroupCreatePortTest,RoutingGroupMemberListPortTest' test`，111 个测试全部通过，0 failures / 0 errors / 0 skipped，Maven `BUILD SUCCESS`。
- Spring 与业务交接：`mvn -Dtest=ProtocolConfigurationTest,GroupCreationMarketingWorkerTest,GroupOperationServiceImplTest test`，23 个测试全部通过，Maven `BUILD SUCCESS`。
- 全量回归：`mvn test` 完成主代码与测试代码编译后，`EpochMillisSchemaDbTest` 因本地 MySQL 拒绝 `root@localhost` 无密码连接而失败（SQLState `28000`，错误码 `1045`）；`HarnessSmokeDbTest` 继承同一 Spring 上下文失败，后续 `TenantInterceptorIntegrationTest` 重复连接时手动停止，进程退出码 130。该失败与本次协议路由代码无关，全量测试项保持未勾选。
- 静态检查：`git diff --check` 通过；未发现 Worker 内 Android 条件分支、数据库迁移、Mapper SQL、前端、Android Go、凭据、部署或环境配置改动。

## 部署

- commit / 环境 / 部署后验证结果: 本次提交、未部署；远程操作前确认目标环境。

## 遗留 / 跟进

- 测试环境真实联调需要专用在线六段号、最小料子和明确环境授权。
