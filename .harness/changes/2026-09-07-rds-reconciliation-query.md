# 变更记录：RDS 拉群收敛查询与诊断统计优化

- 日期 / 分支 / worktree: 2026-09-07 / codex/rds-reconciliation-query-20260907 / /Users/daishuaishuai/IdeaProjects/.codex-worktrees/armada-rds-reconciliation-20260907
- 基线: 3a5e298b；主工作区已有其他业务修改，本次隔离实现。
- 需求来源: 用户“代码、索引开始改吧”；前序已取消独立 mysql 客户端的 97 小时统计 SQL，并完成分表聚合与执行行范围方案验证。
- 状态: 已完成（本地实现与验证，未部署）

## 目标（一句话）

减少后台未知结果判断的扫描范围，为常用统计提供有超时约束的诊断入口。

## 缺口拆解 / 任务清单

- [x] 两个事实 Mapper 改为带 groupExecutionId / pullCallId 的 EXISTS，删除旧 COUNT 接口并更新调用方。
- [x] 保留状态写回后的数据库读取，保留 UNKNOWN 与迟到结果收敛语义。
- [x] 将各明细独立聚合的统计接入现有 pull-task-diagnose.sh，增加单条 SELECT 5 秒上限与会话只读约束。
- [x] H2 真实 Mapper / 生产租户插件、Service 行为与原始统计 SQL 回归。
- [x] 真实测试库验证两表现有索引与旧、新布尔结果一致性。
- [x] 完成扩大回归、仓库统计 SQL 真库复核与评审。

## 关键设计决策

- material 复用 idx_pull_task_material_pending，样例估算 43,719 → 2 行；group_account 复用 uq_pull_task_group_account_role，样例估算 1,687 → 8 行。均为执行计划估值，不是实测 rows examined。
- 两表各核对 22 条 UNKNOWN 调用，旧 COUNT > 0 与新 EXISTS 全部一致。因此无新增索引/Flyway、无共享库 DDL；新增按 callId 的索引方案已被现有索引支持的更小改法替代。
- 原 97 小时 SQL 来自独立 mysql 客户端，在仓库未找到同文来源。本次扩展既有诊断工具，避免另造执行入口。
- 各明细按 tenant_id/task_id 聚合，命令引用 UNION 去重后等值连接 Outbox；空任务返回零统计，过滤执行行时所有明细共同收窄。
- 不改变对外接口与任务状态机；调度退避、终态候选与 attempt 批量查询仍为后续设计范围。

## 验证（evidence-before-done）

- TDD：新范围契约测试在旧 API 上编译失败；旧脚本无法处理 FACTS 输出时回归失败。实现后聚焦测试通过。
- H2：实际执行两个 Mapper XML，使用生产 MyBatisConfig 租户插件与测试 DataSourceTransactionManager；覆盖租户、执行行、调用、状态以及 CAS 写回后查询。
- 原始统计 SQL：4 个 H2 MySQL 模式用例覆盖明细相乘、重复命令、NULL、执行行范围、外租户行、空集/删除/其他模式。
- 本机默认 JDK 23 的 Mockito 动态附加失败；切换项目要求的 JDK 17，并显式加载本地 Byte Buddy 测试代理后执行。
- 聚焦验证最终 49 用例通过：Material Mapper 14、GroupAccount Mapper 21、Reconciliation Service 10、统计 SQL 4；0 失败/错误/跳过。
- 本地扩大回归 3,786 用例：3,757 通过、9 断言失败、16 错误、4 跳过，退出码 1。排除依赖外部 MySQL 的 DbTest 和两个同类 Service DbTest；本机 Testcontainers MySQL 用例包含在本地回归内。
- 原始 `mvn test` 会自动启动外部数据库测试；在未提供数据库环境且持续等待连接时中止该轮，退出码 130，不能声称该命令通过。
- 10 个失败测试类在未修改的 3a5e298b 归档中复跑 104 个用例，同样为 9 失败 + 16 错误。25 个失败的用例名称、类型和首条消息完全一致，本次未新增失败。详见 [本地测试证据](../../docs/operations/evidence/rds-query-local-tests-2026-09-07.json)。
- `bash -n` 与 `bash armada-deploy/tools/pull-task-diagnose.test.sh` 退出码 0；7 个脚本测试通过，包括查询失败不输出成功诊断。`xmllint --noout`、`git diff --check` 均退出码 0。
- 最终仓库统计 SQL 在 test1 #197、#198 的客户端总用时分别为 0.0501 / 0.0538 秒，含连接开销。统计为动作 40/40，调用 37/32，料子各 427，未释放拉手均 0；Outbox 两次均使用 uk_command_id / eq_ref / 1 行估算。
- 两套测试库 armada、armada_perf 均已确认拥有本次复用的两个索引。只读 SSM、计划与一致性证据见 [RDS 验证证据](../../docs/operations/evidence/rds-query-optimization-2026-09-07.json)。

执行环境为 JDK 17；以下参数显式加载本机已有测试代理，未修改项目依赖：

```bash
export JAVA_HOME=/Users/daishuaishuai/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home
mvn -f armada-api/pom.xml \
  -DargLine=-javaagent:/Users/daishuaishuai/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar \
  -Dtest=PullTaskMaterialMemberMapperInMemoryTest,PullTaskGroupAccountMapperInMemoryTest,PullTaskUnknownResultReconciliationServiceTest,PullTaskFactStatisticsSqlTest test
# 本地扩大回归使用相同 JDK / argLine，将 -Dtest 改为：
# '*Test,!*DbTest,!GroupCreationMarketingTaskServiceImplTest,!GroupLinkRegistryServiceImplTest'
```

## 评审

- 按 expert-reviewer 核对完整 diff、调用方、SQL、测试：本次变更没有发现新增阻断项；旧 COUNT/ownerId 路径已移除。
- 真实 H2 用例确认 EXISTS 内层租户过滤生效；统计每层显式 tenant_id 关联，空集与外租户 Outbox 被隔离。
- 已有 25 项回归失败尚未修复；不将全量回归描述为通过。此次仅做独立工作区本地交付，后续发布需要核对当时基线与部署验收。

## 部署

- 初始实现阶段：独立 worktree 完成本地验证，未提交/推送/部署。
- 后续交付授权：用户要求合入主目录 `1.0.3-snapshot` 并 commit / push。本记录随本次代码提交，提交与远端同步状态以 Git 为准；本次不执行部署。
- 代码部署后才会改变后台查询负载；本地测试通过不代表线上 CPU 已进一步降低。
- 无数据库结构变更；回滚为应用回滚。

## 遗留 / 跟进

- 历史终态/暂停任务的收敛扫描仍存在。其退避策略涉及迟到成功与资源归属，需单独设计与验证。
- H2 不证明 MySQL 优化器性能；执行计划只读复核基于现有测试数据，不代表任意规模。
