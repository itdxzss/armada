# 变更记录：超链策略模板

- 日期 / 主仓分支：2026-08-30 / `1.0.3-snapshot`
- 状态：已同步主仓，保持未暂存、未提交；未部署、未连接真实数据库

## 目标

新增租户级超链策略模板管理，并把模板与任务发送策略统一收敛到 `hyperlink_strategy`。任务只关联一条独占
`TASK_SNAPSHOT`，后续模板修改或删除不反向影响已保存任务，任务表不再重复保存六个策略字段。

## 影响模块

- `hyperlink/strategy`：新增列表、详情、创建、更新、软删除和启用策略选项接口。
- `hyperlink/strategy`：新增不依赖钱包的账号筛选上下文与匹配数量接口。
- `hyperlink/task`：创建、编辑、查询、报价和运行统一读取任务策略快照；保存接口新增可空
  `sourceStrategyId`，报价接口显式携带 `maxUseAccounts`。
- `account/group`：用 `HYPERLINK_PUBLIC`、`HYPERLINK_MARKETING` 稳定编码迁移并懒创建“公共组、超链组”。
- `admin/menu`：把 `hyperlink/strategy/index` 加入页面组件白名单，使 V168 菜单可由菜单管理服务正常校验。
- `db/migration/V168__hyperlink_strategy.sql`：新增统一策略表、回填存量任务快照、删除任务表六个重复列，
  新增稳定系统组编码以及策略菜单和权限。

## API

- `GET /api/hyperlink-strategies`：分页列表，支持名称、任务模式和启用状态筛选。
- `GET /api/hyperlink-strategies/{id}`：租户内有效策略详情。
- `POST /api/hyperlink-strategies`：创建策略。
- `PUT /api/hyperlink-strategies/{id}`：携带 `version` 更新全部策略字段；启停也通过该接口完成。
- `DELETE /api/hyperlink-strategies/{id}`：软删除；策略为弱引用，不做任务引用保护。
- `GET /api/hyperlink-strategies/options`：仅返回启用策略，允许策略查看权限或超链任务创建/编辑权限消费。
- `GET /api/hyperlink-strategies/account-context`：返回分组、国家、渠道和协议选项，不读取钱包。
- `POST /api/hyperlink-strategies/account-match-count`：按任务相同筛选及容量规则返回可用账号数量，不读取钱包。

`account-match-count` 的请求体就是 raw `HyperlinkAccountFilterDTO`，不包
`{ "accountFilter": ... }`；包装或其他未知筛选字段按 fail-closed 返回 `40001`。

## 数据与业务约束

- 同一租户内有效策略名称唯一；删除后允许复用名称。
- 所有查询、写入、选项和辅助接口均按当前租户隔离。
- `taskMode` 仅允许 `instant | rolling | cycle`。
- `maxExecutingAccounts` 为 0～100；`0` 按竞品语义持久化为 AUTO/均分，不在保存时改写为固定数。
- AUTO 报价按协议容量、100 上限和 `maxUseAccounts` 解析本次有效计价并发；周期任务每轮重新解析容量。
- `maxUseAccounts`、`maxSendPerAccount` 为非负整数；固定并发时，非零的 `maxUseAccounts` 不得小于
  `maxExecutingAccounts`。
- 周期任务要求 `maxUseAccounts >= 1` 且 `cycleIntervalMinutes >= 30`；非周期任务把周期间隔归一化为 0。
- `accountFilter` 复用任务归一化器后以 JSON 快照保存；API 不暴露独立 `concurrentAccounts`，数据库 `concurrent_num` 唯一映射为 `maxExecutingAccounts`。
- 更新使用 `version` 比较并原子递增，陈旧版本返回稳定冲突错误；软删除也递增版本，但按冻结 API 不额外接收版本参数。
- `strategy_scope=1` 是可复用模板；`strategy_scope=2` 是任务独占快照。
- `hyperlink_task.hyperlink_strategy_id` 强关联任务快照；`owner_task_id` 保证一任务一快照；
  `source_strategy_id` 只弱追溯最初模板。
- 模板列表、详情和选项只读取 `TEMPLATE`，绝不把任务快照暴露为可复用模板。
- 现有租户由 V168 创建或升级默认业务组；只认领 `owner_user_id IS NULL` 的租户公共分组，避免
  V141 用户级同名分组导致唯一键冲突；未来租户首次进入超链上下文时按稳定编码幂等创建。

## 权限与菜单

- 页面：`tenant:hyperlink_strategy:view`，菜单 key `HyperlinkStrategy`，路由 `/hyperlink/strategy`，组件 `hyperlink/strategy/index`，排序 40。
- 按钮：`tenant:hyperlink_strategy:create`、`tenant:hyperlink_strategy:edit`、`tenant:hyperlink_strategy:delete`。
- 迁移只创建菜单/权限节点，不自动授予角色；由现有 RBAC 管理流程分配。

## 验证

- 新增静态迁移合同测试，覆盖表约束、V168 菜单与权限语义。
- 新增 API 形状与 Controller 权限合同测试。
- 新增真实 Mapper XML + H2 MySQL 模式 + MyBatis 租户插件测试，覆盖租户隔离、筛选、启用选项、乐观锁、软删除和名称复用。
- 新增 Service 测试，覆盖筛选归一化、0～100/AUTO 边界、周期约束、重复名称/陈旧版本冲突与选项限制。
- 任务 H2 Mapper 测试已切换到统一策略 JOIN；报价恢复、任务详情、列表、草稿生命周期和轮次选号均覆盖快照读取。
- 账号组 H2 测试覆盖稳定编码认领已有同名组、插入缺失系统组和固定顺序返回。
- 新增 MockMvc raw body 合同测试、账号上下文无钱包依赖测试及菜单组件白名单合同测试。
- `mvn -DskipTests test-compile`：通过。
- 主仓策略、任务快照、任务查询/详情、报价恢复、AUTO 容量和默认组定向回归 103 项全部通过；
  V141 同名用户分组兼容修订的迁移与 Mapper 回归 6 项通过。
- 超链全域 301 个测试中 295 通过、4 个 MySQL 环境测试跳过；6 个失败全部集中在未修改的
  `HyperlinkBillingSagaH2Test` 既有基线，与本变更文件无 diff。
- `xmllint --noout`：账号组、策略和任务三个 Mapper XML 均通过。

## 未执行与后续

- 未成功连接或执行真实 MySQL/Flyway，未部署，未修改任何真实环境。
- 尝试运行全量 `mvn test` 时，首个既有 `PromotionCapiEventOutboxSchemaDbTest` 持续重连本机 MySQL；在未建立连接、未进入业务断言时主动中止，避免越过真实数据库边界。
- `.harness/wiki/数据模型.md` 必须在 V168 应用到确认的 MySQL 环境后按 `information_schema` 生成流程刷新，本次不手工修改生成物。
- 未执行真实 MySQL/Flyway，V168 的 ALTER/回填仍需在明确环境先做备份和迁移演练。

## 回滚

- 先回退依赖本接口的前端与后端版本，再按 `rollback.sql` 把任务快照回填回旧六列并删除菜单、策略表和系统组编码列。
- AUTO 的 `0` 回滚到旧结构时只能降级为固定 `1`；模板与来源追溯会永久丢失，执行前必须备份并单独审批。
