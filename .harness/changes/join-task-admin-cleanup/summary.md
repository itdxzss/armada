# 进群任务：清空其他管理员并退出群组

## 状态与范围

2026-09-18，按用户授权直接修改 `armada` 和 `wheel-saas-pure-web` 主工作区，分支均为 `1.0.3-snapshot`。本地实现与定向验证完成，未 commit、push 或部署。两仓都有其他会话在途修改，未覆盖或提交这些改动。

## 最终行为

- 默认关闭新开关 `clearAdminsAndLeaveEnabled`，开启需同时启用已有 `setAdminEnabled`。
- 复用现有提权成功判定，不增加提权后的权限复核、每次踢人后的回读或退群前复核。
- 新管理员读取一次完整成员名单用于选择操作目标，只排除自身和最后退群的原执行账号；其他管理员全部踢出，包含平台受控账号，普通成员不动。
- 新号逐目标执行 REMOVE，全部成功后原号自行退群。失败或回执不明停止当前明细后续清理/退群；已成功的进群、提权和移除结果保留。其他明细沿用既有排期。
- 明细展示清理/退群阶段、已踢出数量和失败原因；汇总与下一条进群排期等待后处理完成。

## 实现与数据归属

- `JoinTaskAdminTransactions` 的成功完成分支接入后处理；关闭开关沿用原完成路径。
- `JoinTaskCleanupTransactions` 使用短事务保存单步意图和结果；`JoinTaskCleanupProtocol` 在事务外复用现有 Web / Android metadata、成员移除、退群端口，无新增协议 API。
- `JoinTaskCleanupScheduler` 独立工作线程推进，每次只操作一个目标；在途调用有截止时间，超时/重启结果未知即失败，不自动重发。重复或迟到结果不推进；回执落库时也检查截止时间，不依赖下一轮扫描先发现超时。
- 同租户同群用数据库唯一在途键互斥；任务删除或停止后仍扫描未终结清理记录以释放占用，不再发送操作。同群等待行不占扫描窗口，避免阻塞持有者推进。
- 账号身份沿用已有账号域和 PN/LID 字段。清理名单中的身份解析用于排除自身/原号，不是新增管理员权限核实。找不到原号记录或无法识别两个账号时停止并记录原因，不随机选原号退出。
- 已确认移除/主动退群通过群域 Service 写离群事实；不跨域直接操作群 Mapper。

Flyway `V203__join_task_clear_admins_and_leave.sql`：

1. `join_task.is_clear_admins_and_leave_enabled`：任务配置开关，历史默认 0。
2. `join_task_cleanup`：以 result_id 为主键的执行子记录，保存阶段、固定名单与完成游标、失败原因、下次执行/在途截止时间以及同群互斥键。

现有 `join_task_result` 已承载入群和提权；清理是后续执行关注点，单独子表避免继续扩展宽表。没有复制群成员当前事实，也没有把清理状态重复存入主明细；Java 的 cleanup 字段仅为 LEFT JOIN 查询投影。一次入群明细最多对应一条清理记录。

API 复用 `/api/join-tasks`：创建/编辑接收、列表/详情返回新开关，未传默认关闭；结果额外返回 cleanupStatus、cleanupReason、cleanupCompleted、cleanupTotal。Redis、Kafka topic 和协议契约均无新增。

## 验证

- 先添加清理名单测试，确认缺失实现时编译失败，再实现转绿。
- Java 全量生产及测试源码编译通过。使用临时 POM、`/tmp/join-cleanup-full-java-build` 避免污染并行会话的 target。
- 后端显式选择 17 个离线测试类，共 83 项，0 失败/错误/跳过。覆盖配置依赖/回填、旧行为、提权成功后接续、重复结果、双账号路由、单目标 REMOVE、第二项失败停止、空名单、退群失败、超时迟到、原号缺失、真实 Mapper 汇总、下一行闸门、租户隔离、事务回滚、两线程抢占和同群互斥。
- H2 使用生产 Mapper XML、MyBatis 租户插件、Spring 事务，并执行 V007 主表 DDL 和 V203 清理子表 DDL。MySQL information_schema / PREPARE 的开关增列部分做结构断言；H2 不能代表 InnoDB 全部锁行为。
- 前端进群相关测试 14 项通过；typecheck、相关 ESLint / Prettier、Vite 构建通过，产物在 `/tmp/join-cleanup-frontend-dist`。
- Mapper XML 校验通过。数据模型由 `refresh-schema.py` 读取当前文档及 V201/V203，调用正式 `gen_datamodel.py` 离线生成三个相关段落，保留其他会话文档。
- 扩展回归曾用过宽通配符选中旧 `JoinTaskDispatchMapperDbTest`，发现其启动数据库连接池后立即中断该轮；日志没有成功启动连接池，未计入通过结果。最终只显式列出离线单测/H2 测试，不再运行该测试。没有真实 WhatsApp 或数据库迁移验收。

复现离线验证：使用 Maven 显式 `-Dtest=JoinTaskCleanupTargetsTest,JoinTaskCleanupProtocolTest,JoinTaskCleanupMapperH2Test,JoinTaskAdminFactsTest,JoinTaskAdminAccessTest,JoinTaskAdminContractTest,JoinTaskAdminTransactionsTest,JoinTaskAdminPayloadHydratorTest,JoinTaskAdminMapperH2Test,JoinTaskCreateServiceTest,JoinTaskResultServiceTest,JoinTaskStartServiceTest,JoinTaskDispatchCoordinatorTest,JoinTaskDispatchSchedulerTest,JoinTaskDispatchTransactionServiceTest,JoinTaskIntervalPolicyTest,JoinTaskInviteCodeParserTest`，勿改成会匹配 DbTest 的通配符。

## 发布与回滚

发布前确认具体环境与提交集合，确认 V203 无撞号；后端执行 Flyway 后再开放前端新开关。基础协议端口已存在，本功能没有协议仓代码改动。

优先关闭入口和停止调度，保留执行记录；确认在途请求结束后再回退应用。`rollback.sql` 是审阅用破坏性结构逆向，未经环境确认不可执行。回滚不能恢复已经被踢出的成员或让退出账号自动回群。若 WhatsApp 拒绝移除群主等目标，按失败停止，原号不退群。

后续真实环境验收需分别覆盖 Web、Android 或混合账号、实际移除回执和原号退群，本轮未执行。
