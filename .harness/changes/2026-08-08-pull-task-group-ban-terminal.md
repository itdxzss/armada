# 变更记录：普通拉群任务遇群封禁后终止单群

- 日期 / 分支 / worktree: 2026-08-08 / `1.0.2-snapshot` / armada 主工作树
- 需求来源: WhatsApp 明确 suspended/terminated 后停止对应群执行，不影响同任务其他群
- 状态: 实现完成，未部署

## 目标（一句话）

将明确群封禁事实同步为 `FAILED/GROUP_BANNED` 执行终态，并停止该群后续调度与未知结果收敛。

## 缺口拆解 / 任务清单

- [x] 群健康落库后返回租户内解析出的 `groupLinkId`。
- [x] 只识别 `BANNED + CHAT_SUSPENDED/CHAT_TERMINATED` 明确信号。
- [x] 仅终止命中群执行行，取消该群未发布事实并释放该群拉手。
- [x] 其他群继续执行；全部群终态时复用父任务完成聚合。
- [x] 封禁终态不再进入未知结果收敛，迟到协议回报不能重新激活。
- [x] 补齐真实 Mapper XML、H2 事务和事件路由测试。

## 关键设计决策

- 不根据普通 403、临时失败、账号离线或代理失败推断群封禁。
- 执行行持久化为 `FAILED / GROUP_BANNED / 群已被封禁`，不新增表、字段、Topic 或依赖。
- 群健康落库和任务终止使用既有 Service 边界；Kafka 重投时，已终态执行行不会再次被选中。
- 健康恢复事件只更新群健康事实，不恢复已经失败的任务执行行。

## 验证（evidence-before-done）

- Java 17 聚焦回归通过：7 个测试类、61 个测试，0 失败、0 错误、0 跳过。
- 聚焦回归包含真实 H2 MySQL 模式、真实 Mapper XML、MyBatis-Plus 租户拦截器和 Spring 事务。
- `mvn -q -DskipTests compile` 退出码 0。
- `xmllint --noout armada-api/src/main/resources/mapper/task/PullTaskGroupExecutionMapper.xml` 退出码 0。
- `git diff --check` 退出码 0。
- 尝试运行 `armada-api` 全量 `mvn test`；套件进入既有
  `PromotionCapiEventOutboxSchemaDbTest` 后持续等待外部数据源。为遵守本地调整边界，约 1 分钟后主动终止，退出码 130；未将该环境阻塞记作业务测试失败。

## 部署

- 未提交、未部署；没有修改远程环境或测试数据。

## 遗留 / 跟进

- 如需全量门禁，应在已配置对应外部测试数据库的环境运行 `cd armada-api && mvn test`。
