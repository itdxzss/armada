# 账号分组互聊

2026-11-05。四仓均在 `1.0.3-snapshot` 原工作区开发，离线实现和专项评审完成；离线阶段没有部署、连接远程环境或发送真实消息。真实验收留待另行确认测试环境与账号。

需求：[开发任务书](../../../docs/handoff/2026-11-05-账号分组互聊开发任务书.md)，[分析方案](../../../docs/business/2026-11-05-account-group-mutual-chat-analysis.md)。开工快照及原始测试日志保存在本机 `/private/tmp/account-mutual-chat-20261105`。

## 行为与实现

- 两个分组按 A×B 冻结账号，双向各发一条固定纯文本，共 2×M×N 条；分组重叠归 A，最多 5000 条。两侧文本去除首尾空白后 1–1000 字符，间隔 1–3600 秒，页面默认 10 秒。
- 从互加好友抽取 `AccountMutualGroupPlanner`，共用账号选择、去重、权限/归属和预览令牌；互加回归通过。新任务/方向事实属于 account 域，不复用通讯录或互存事实表。
- 新增 Controller、Service、Mapper、调度器、派发事务和结果 Sink。创建幂等、批量写入 200 条；先锁任务再锁账号，同账号在途/冷却互斥，单任务在途上限 100。派发前重验权限、归属、资格与身份。
- 新来源 `account_group_mutual_chat` 经 `MessageSendPort` / Outbox 和两个协议后端发送 `PRIVATE + TEXT`；命令间隔及 notBefore 都为 0，间隔由控端负责。既有业务关联与互聊关联严格互斥。
- 停止仅取消待发送方向，已提交继续收结果；重试失败、待确认、已取消方向，每次生成新命令 ID，成功方向不重发，旧命令回执忽略。未知结果不自动重发、不永久阻塞同账号后续方向；超时转待确认并计算冷却，RATE/LIMIT 至少退避 60 秒。
- 结果校验租户、任务、明细、发送账号、协议账号和目标 JID；同命令晚到的明确结果可收敛待确认。互聊 ACK 由专属 Sink 空处理，不改业务事实、不造成缺少处理器的 Kafka 重试。

## 评审后补强

1. `ProtocolMessageSendResultReportedEvent` 除两项互聊关联外增加 `accountId`，保证发送账号核对有真实输入，保留原兼容构造器。
2. `POST /{id}/retry?confirmUnknown=true` 表示用户已确认可能重复发送。默认 false；任务锁内再次核对 UNKNOWN，封住页面读取统计后新出现待确认项的竞态窗口。前端只有弹窗确认后才传 true，取消不会请求重试。
3. Web 在实际发送异常与空消息 ID 时，对互聊来源上报 UNKNOWN；同命令状态重放不再次调用发送。Android 除任务书三处来源条件，还覆盖 `consumer_account_message.go` 的派发 panic 收敛路径。
4. V225、V226 已被同时开发的账号档位检测占用，因此本任务改用 **V227__account_mutual_chat.sql**，未改动该会话迁移。

后端、协议层、前端分别进行只读专项评审，所发现问题已修复；协议和前端复核无新阻断项。

## 数据与交付

- Flyway 新增 `account_mutual_chat_task` / `account_mutual_chat_item`，只主表存两段文本，明细存方向与协议消息 ID，没有 retryable 列；统计现场聚合。
- [db-migrations.sql](db-migrations.sql) 与 V227 内容一致；[rollback.sql](rollback.sql) 仅为回滚准备，未执行。
- [generate-model.py](generate-model.py) 从本次 Flyway DDL 生成离线元数据，再调用 `.harness/wiki/gen_datamodel.py`，仅合入两表段落到数据模型文档；没有读取或修改共享数据库。
- 接口 `/api/accounts/mutual-chat-tasks` 提供 preview/create/list/detail/items/stop/retry，权限沿用 `tenant:account:view` / `tenant:account:edit`。
- 没有增加 Redis 业务事实、topic、环境变量或 yml 配置。本次未改 `application.yml`、`ProtocolConfiguration`，其中工作区变化来自其他会话。

## 验证

详见 [verification.txt](verification.txt)。后端 14 个测试类、**215 项通过，0 失败/错误/跳过**，包括新功能 H2 22 项、策略 2 项、真实消费者/Sink 契约 3 项，以及互加、通讯录、动态、剧本、Outbox 和两个后端适配器回归。Mapper XML 校验和接口文档结构检查通过。

主工作区同时存在未完成的账号档位检测等代码，直接测试曾在无关 DTO 编译处失败。最终 Java 验证使用开工 HEAD `dd16d2f2bc4cb55909448066bbee48b609ed9b02` 的临时归档，加本任务 44 个源码/资源/测试文件覆盖，使用 JDK 21、本地 H2 与测试替身；未新建分支或切换工作区。该结果证明本任务与该 HEAD 的回归，不代表并行开发全部合并后的整仓集成通过。没有执行后端全部测试类。

Web：82 项测试、类型检查、构建通过。前端：互聊与互加共 11 项单测、typecheck、涉及文件 ESLint、生产构建通过。Android 的完整结果见[协议层变更记录](../../../../whatsapp-server-feature-android-zhuan/.harness/changes/account-group-mutual-chat/summary.md)。

## 上线和回滚边界

真实 Web、Android、混合协议的 A=2/B=3 验收没有执行。部署前须合并并行开发后重新确认迁移版本唯一性与整体编译；按任务书第 11 节另行确认环境和账号后验收。

若以后执行回滚，先停止任务，确认在途命令/回执已收敛并备份事实，再按部署流程处理代码和 Flyway 历史；删除表不能撤销已发送的消息。

## 提交交付范围

2026-11-05 用户授权本次提交并推送到 `origin/1.0.3-snapshot`；部署由用户稍后统一执行，本次不部署。提交仅包含互聊功能，其他会话的未提交修改保留在工作区。
