# 账号分组双向保存联系人

2026-09-18，四仓主工作区 `1.0.3-snapshot` 本地实现；用户授权主仓库开发，保留其他 agent 在途修改。未提交、推送、部署或对真实账号保存联系人。

## 行为与约束

- 账号列表“互相添加好友”打开抽屉，选择两个不同分组，预览后创建。只纳入在线正常、可访问、协议身份完整的账号；WS 号码去重，禁止自己保存自己。
- A×B 全量配对，每对生成两条方向事实。2×3 = 6 对 / 12 次；只两方向成功才计双向成功。使用对方号码作为联系人名称，允许覆盖已有备注。
- 创建时冻结范围；重复创建使用请求幂等键，预览范围变化要求重新预览。权限和当前账号身份在派发前复核。
- 同账号跨本功能任务串行，不同账号并发。业务人员配置整数 0–3600 秒，默认 0，从上次结果处理后计算；0 仍有调度及协议耗时。
- 单任务最多 20000 条方向、8 条在途；每 500ms 扫描最多 32 个执行方。限流原因至少退避 60 秒。
- 300 秒无结果转“待确认”；该账号在本功能后续动作阻塞，晚到同 commandId 明确结果可收敛。没有自动重复执行或人工强制覆盖 UNKNOWN 的入口。
- 停止只取消未提交方向，已提交仍接收回执；失败重试仅重排可重试的明确失败方向。成功事实不会被矛盾/旧命令回执覆盖。
- 普通用户只访问自己的任务，租户管理员可访问本租户任务；账号权限按 owner_user_id 校验，历史未分配账号沿用租户共享语义。

## 模块与链路

`Vue HTTP → account 域持久任务/方向 → 现有 Outbox → Kafka → Web/Android 保存 → group.action_result_reported → 任务事实 → HTTP 查询`

命令仍为 `contact.save.requested`，新增 `source=account_group_mutual_contact`、`aggregateType=ACCOUNT_MUTUAL_CONTACT_ITEM`、taskId/itemId/attemptNo。不伪造拉群关联。Web 的无 owner 失败分支单独回传本来源；Android 复用等待实际 AppState/IQ/patch 结果的异步 sender，不走提前返回的旧 HTTP 保存。回执接入既有账号风控结果入口。

共享文件仅局部修改：前端 index/AccountListTable；后端 ProtocolGroupEventConsumer 及其构造测试；Web contact-save-executor/master-consumer；Android group_action_command/group_action_event。功能主体、DTO/VO、MapStruct、SQL 和测试放独立文件。开工快照 `/private/tmp/mutual-contact-baseline-20260918` 用于区分已有修改。

## 数据 / API / Redis

Flyway `V202__account_mutual_contacts.sql` 增加 account 聚合任务表和定向事实表；复用 protocol_command_outbox，成功数现场聚合，无通讯录或好友数镜像。所有前台和业务 SQL 受租户插件保护，后台跨租户扫描仅返回调度 ID；派发先锁任务再锁账号。同账号锁后首次一致性读避免 MySQL REPEATABLE READ 旧快照。

接口前缀 `/api/accounts/mutual-contact-tasks`：POST preview / 创建；GET 分页、`/{id}`、`/{id}/items`；POST `/{id}/stop`、`/{id}/retry-failed`。查询需 `tenant:account:view`，写入需 `tenant:account:edit`。

没有新增 Redis 业务事实或新 topic，复用两协议既有幂等执行存储/账号操作闸门。`armada.mutual-contact.enabled=false` 仅停调度，不关闭创建 API 和既有 Outbox；不能当成全面撤回在途动作。

## 验证与限制

- 后端针对性 64 项通过：Policy 2、H2 生命周期 7、Kafka group consumer 37、metadata 10、profile 8。H2 真跑 V202、真实 Mapper、租户插件和 Spring 事务，包含两线程竞争同账号、冻结预览、重复创建、租户/归属、回滚、分页、停止/晚到、UNKNOWN、失败重试和 payload 补全。
- Web 48 项通过，`npm run build` 通过，覆盖旧联系人来源与新来源、无 owner 回执及强关联拒绝。
- 前端 12 项通过（4 项本功能、8 项账号列表回归）；`npm run typecheck`、本功能 ESLint、生产构建通过。覆盖默认零间隔、非法输入、创建响应丢失仍复用请求键、修改配置清空预览。
- Android `go vet ./...`、`go build ./...`、`go test ./internal/armada`、`go test -race ./internal/armada` 通过。`go test ./...` 未全绿：仅 pkg/noise 的既有 8 项加密测试失败；已从 Git HEAD 单独导出 pkg/noise + go.mod/go.sum 到临时目录复现相同 8 项失败，确认不由本功能改动引入。
- Mapper `xmllint --noout` 通过，四仓 `git diff --check` 通过。未声称覆盖率达标，未运行真实 MySQL/Kafka 端到端和真号保存验收。
- 数据模型两表段落由 `generate-model.py` 读取本次迁移、调用 `gen_datamodel.py` 离线生成，仅更新两表；不是已部署数据库事实。

## 发布、验收与回滚

必须明确目标环境后单独发布；先部署两协议来源兼容，再部署后端 Flyway/结果处理，最后开放前端。上线前复核 V202 无撞号及当前四仓提交集合。

验收：使用明确授权测试账号 A=2/B=3，分别覆盖纯 Web、纯 Android、混合协议；核对 12 条方向、6 对结果和协议联系人读取；单方向失败后仅重试失败方向；停止及超时晚到不重复保存。

回滚优先收起创建入口、停止任务并保留回执处理。停调度不会撤销已经进入 Outbox/Kafka 的动作。确认在途已清理、保留审计备份后方可执行附带 schema 回滚；回滚不能撤销 WhatsApp 已保存的联系人。未知结果暂只能等待晚到回执或后续单独对账，本版本不提供强制解锁。

## 自查结论

依照 expert-reviewer 自查了业务方向唯一性、租户与归属、同账号行锁顺序、Outbox 同事务及 afterCommit、强关联回执、停止/重试和共享文件增量。修正 H2 保留字别名、MySQL 首次一致性读时机、抽屉关闭重开轮询重复、实体 VO MapStruct 转换及本来源风控回执接入。未发现本功能剩余阻断项；上述远程验收、UNKNOWN 对账能力和原有 Noise 测试失败仍保留为边界。
