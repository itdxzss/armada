# 变更记录：app-2 MySQL 并发与租户锁查询修复

- 日期 / 分支 / worktree: 2026-07-25 / `fix/account-migration-conditional-update` / `/Users/yanwc/IdeaProjects/armada`
- 需求来源: 用户要求合并修复 app-2 部署后持续出现的 MySQL 死锁、锁查询语法错误和群链接并发重复键，且不得改变现有业务逻辑和语义。
- 状态: 代码实现与 H2 内存数据库验证完成

## 目标（一句话）

保持账号群快照、即时营销差量、营销结果计数、分组锁定和租户隔离语义不变，移除运行期非法锁 SQL、首次群登记竞态和群快照与营销回执之间的交叉锁链。

## 缺口拆解 / 任务清单

- [x] Mapper 内封装当前租户，锁查询显式限定 `tenant_id` 并绕过会破坏 MySQL 尾句的租户 SQL 改写。
- [x] 账号观察到的新群使用单条原子 upsert 返回新建或既有 `group_link.id`，消除先查后插竞态。
- [x] 营销结果回填保留原字段优先级，按 target 单行锁、共享群表非锁定快照查询、target 单表更新的顺序执行。
- [x] 补租户委托、SQL 锁形状、原子 upsert、并发登记和结果回填语义回归测试。
- [x] 运行影响链纯单元测试、编译、测试编译、XML 与差异静态检查。
- [x] 使用测试专属 Spring/MyBatis-Plus/H2 配置完成 Mapper、租户隔离、事务和并发行锁回归；部署仍需单独确认目标环境。

## 关键设计决策

- 保留 `AccountGroupMembershipReportServiceImpl.applyGroupsReported` 的现有事务边界，不拆成分组级提交；否则中途失败重试可能改变 `addedGroups` 差量与即时营销触发语义。
- `selectByIdsForUpdate(groupIds)` 保持现有 Service 调用签名，由 Mapper 默认方法从 `TenantContext` 取当前租户并调用显式租户 SQL；禁止让 Controller/Service 接收可覆盖的 tenantId。
- 不修改全局 MyBatis-Plus/JSQLParser，也不做 SQL 字符串重排；全局补丁影响面不可控。
- `group_link` upsert 只在账号同步派生的 `wa://group/<jid>` 分支使用；已通过 preview 匹配到既有入口时继续复用并 touch，保留导入来源、分组归属和自建群关系态语义。
- 营销 target 回填继续使用 `attempt > preview > target` 的群 ID/JID 优先级，以及 `attempt > group_link > preview > target` 的群名优先级；先锁定 target 并读取锁内最新兜底值，再把带群表 JOIN 的 UPDATE 改为共享群表普通 SELECT 和 target 单表 UPDATE，避免锁等待后用旧快照覆盖并发结果。
- H2 仅作为 test scope 依赖；内存库用例使用测试类专属 Spring/MyBatis-Plus 配置，接入真实 Mapper XML、生产租户插件、`SqlSessionTemplate` 和 `DataSourceTransactionManager`，不进入生产运行时。
- 不调整 Kafka 重试次数、DLT、任务/attempt/target 状态码和累计计数口径。

## 影响分析

- API: 无变更。
- 数据模型/Flyway: 无变更。
- 租户隔离: 锁查询由运行期自动注入改为 Mapper 内从当前上下文取得并使用 JDBC 参数显式限定，缺失上下文时失败关闭。
- 状态流转: 无变更。
- 协议/Kafka: 无跨仓修改，无 Topic 或消息契约变更。
- 回滚: 回退本变更代码即可；无数据库结构和数据回滚。

## 验证（evidence-before-done）

- TDD 红灯：首次执行聚焦测试时，因 `GroupLinkMapper.upsertAccountObservedGroup` 尚不存在而编译失败；补生产实现后转绿。
- Java 17 影响链测试：`AccountGroupServiceImplTest`、`GroupLinkRegistryServiceImplUnitTest`、`AccountGroupMembershipSnapshotServiceImplTest`、`MarketingTaskMapperSqlShapeTest`、`MarketingSendResultServiceImplTest`、`MarketingImmediateRetryServiceTest`、`MarketingNewGroupImmediateSendServiceImplTest`、`MarketingRoundWorkerTest`，共 98 个测试，0 失败、0 错误、0 跳过。
- 聚焦修复测试：`MysqlModeMapperInMemoryTest`、`AccountGroupMapperUnitTest`、`AccountGroupMarketingLockGuardTest`、`GroupLinkMapperSqlShapeTest`、`GroupLinkRegistryServiceImplUnitTest`、`MarketingTaskMapperSqlShapeTest`、`MarketingResultTransactionBoundaryTest`，共 35 个测试，0 失败、0 错误、0 跳过。
- `MysqlModeMapperInMemoryTest` 5 个用例通过：真实执行三份 Mapper XML 和生产租户插件；两个 Spring 事务验证 target 行锁会阻塞并发结果回填，持锁事务提交后回填继续执行且保留最新 target 群快照。H2 MySQL 模式是本次数据访问改动的默认自动化门禁；MySQL InnoDB 特有差异作为剩余风险记录。
- SQL 结构测试已直接调用 `TenantLineInnerInterceptor`，确认营销 target 锁查询、共享群快照三表租户条件和账号群 upsert 的 `tenant_id` 注入均可由当前 JSQLParser 正常生成。
- `mvn -q -DskipTests compile`：通过。
- `mvn -q -DskipTests test-compile`：通过。
- `xmllint --noout` 校验三份改动 Mapper XML：通过。
- `git diff --check`：通过。
- 未执行全量 `mvn test`：测试集中 `TenantInterceptorIntegrationTest` 也会连接数据库；发现其连接重试后已主动终止，避免误连未确认环境。
- 未执行真库 DbTest：按当前规范不再要求连接外部数据库，真库仅作用户明确授权后的可选补充验证；本记录不声称 MySQL InnoDB 真库验证通过。

## 部署

- commit / 环境 / 部署后验证结果: 未部署；本次不执行部署。

## 遗留 / 跟进

- 修复灰度后检查 app-2 的 MySQL deadlock、重复键、SQLSyntaxError、Kafka lag 和 DLT 增量；确认稳定后再补偿失败事件。
