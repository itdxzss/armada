# 营销任务明细最近有效群状态

## 目标

账号离线导致协议无法识别群状态时，营销任务详情保留同账号、同群最近一次有效识别状态；最新一轮执行结果与账号离线原因仍独立展示。

## 已确认口径

- 群组状态取最近一次有效识别结果。
- `ACCOUNT_OFFLINE / UNCONFIRMED / STATUS_RESOLUTION_UNAVAILABLE` 不覆盖历史有效群状态。
- 没有历史有效群状态时继续展示 `UNCONFIRMED`。
- 执行结果和执行原因取最新已结束记录，不读取历史群状态记录的原因。
- 不增加账号在线检测，不改调度、暂停恢复或重试逻辑。

## 影响范围

- `MarketingTaskMapper.xml`：发送事实增加有效群状态证据标记，群状态窗口优先选择有效证据；最新执行窗口额外返回自己的原始群状态字段。
- `MarketingTaskAccountGroupStatRow`：分别承载群状态证据与最新执行证据。
- `MarketingTaskServiceImpl`：最新执行原因只使用最新执行记录自己的字段。
- `MarketingGroupExecutionNormalizer`：失败发送携带 `NORMAL / GROUP_SEND_ALLOWED` 时，群状态为 `NORMAL`，执行结果仍为失败。
- 后端测试：增加归一化、Service 解耦、SQL 结构和真库回归场景。

## 数据与接口

- 数据库结构：无变更。
- HTTP API：无字段变更。
- Kafka / 协议契约：无变更。
- Redis：无变更。
- 租户隔离：沿用原查询的 `tenant_id` CTE 与 MyBatis 租户拦截器。

## 实现约束

- SQL 中的有效信号集合与 `MarketingGroupExecutionNormalizer` 的明确状态保持一致。
- 业务跳过和已提交记录不参与群状态证据选择。
- 不为测试 helper 增加兼容重载；使用单一 `SendAttemptFixture` 记录表达完整发送事实。
- 详情读取路径不新增日志。该变更是确定性查询归一化，没有需要逐行记录的异常事件，避免批量详情查询产生噪声日志。

## TDD 与验证

- 基线：
  - `mvn -Dtest=MarketingGroupExecutionNormalizerTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest test`
  - 48 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- RED：
  - `MarketingGroupExecutionNormalizerTest#failedSendKeepsRecognizedNormalGroupStatusAndItsOwnFailureReason`
  - 预期 `NORMAL`、实际 `UNCONFIRMED`，1 failure，证明正常群检测在发送失败时被错误降级。
  - Service / SQL 新测试首次编译时缺少 `setExecutionGroupStatus` 与 `setExecutionGroupStatusReason`，证明行对象无法承载独立执行证据。
- GREEN：
  - `mvn -Dtest=MarketingGroupExecutionNormalizerTest,MarketingTaskServiceImplLifecycleTest,MarketingTaskMapperSqlShapeTest test`
  - 51 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- 真库 DbTest：
  - `.env` 的数据库目标经脱敏检查为本机地址，未输出连接串或凭据。
  - 现有本机 `armada` 库的 V055/V056 Flyway 校验和与当前分支不一致；仅对测试进程关闭 Flyway 后，旧 schema 又缺少当前分支启动组件依赖的表/列。未对该库执行 `flyway repair`、迁移或 schema 修改。
  - 创建本任务专用临时库 `armada_codex_marketing_status`，从零校验并执行当前分支全部 60 个迁移，schema 到达 v060。
  - `MarketingTaskCreateReadDbTest#getDetail_preservesLatestEffectiveGroupStatusWhenNewestAttemptIsOffline` 通过，进程退出码 0。
  - 相邻三个详情回归测试一起执行通过，进程退出码 0：最新轮次执行汇总、动态群执行汇总、离线后保留最近有效群状态。
  - 验证结束后已删除临时库，并再次查询确认不存在；其中仅包含本次迁移结构与测试数据，删除不可恢复。
- 全量普通测试尝试：
  - `mvn -Dtest='!*DbTest' test` 仍会选中依赖数据库的 `TenantInterceptorIntegrationTest`。
  - 该测试在沙箱内重试数据库连接时被主动中止，退出码 130，因此不把本次尝试记为全量测试结论。

## 第二套测试环境部署

- 目标：`perf2` 第二套测试环境，只部署 Armada 后端；前端、Baileys、Zhuan 均跳过。
- 来源：本地 `1.0.1-snapshot` 当前未提交工作树，基准 commit 为 `5276f65`；实现文件未提交、未暂存。
- 部署前：51 个聚焦测试通过，Mapper XML、`git diff --check`、部署脚本测试和生产离线包契约测试通过。
- `--env perf2 --check` 的 Armada 检查通过，随后在本次不部署的 Baileys 跳板链路超时，整体检查退出 255。
- 第一次后端部署按档案中的 Compose 项目 `armada-perf` 启动时，因现有 `armada-backend` 实际属于 `armada-deploy` 项目而发生固定容器名冲突；旧容器未被删除。
- 复核现有容器标签和健康响应后，仅对本次命令使用 `ARMADA_DEPLOY_PROJECT=armada-deploy`，原地重建同一 backend；部署脚本退出码 0，Backend=`SUCCESS`。
- 本地 JAR、远端 JAR、运行容器内 JAR 的 SHA-256 均为 `65eaeec1f02f79e36c0df9b2be16a333325e1d15959b3baf6bb9a7873b24ff00`。
- 容器状态 `running`、重启次数 0；Spring 在 8.46 秒启动，Flyway 使用第二套 `armada_perf` schema，未包含本任务数据库迁移。
- 回环与公网 API 冒烟均返回 HTTP 200；未认证回环响应为业务码 `40101`，证明请求已进入后端鉴权链路。
- 启动后消费积压消息时出现过 MySQL deadlock 重试；最终一分钟采样 deadlock 数为 0，营销发送结果仍持续成功回写。
- 环境仍有 `armada.perf.protocol.account.events.v1.DLT` 不存在的问题。最终一分钟采样得到 61 条 `UNKNOWN_TOPIC_OR_PARTITION` 警告和 4 条对应的死信发布 ERROR，失败事件的死信投递受影响，因此部署后的运行日志不能认定完全健康。本任务未改 Kafka 配置，也未擅自创建 Topic。
- 当前没有可用的已登录浏览器会话，因此未执行任务 62 的认证业务页面验收，不把服务级冒烟当作业务验收。

## 回滚

- 无数据库结构或数据迁移；现有本机 `armada` 库和远程数据库均未手工修改。
- 回滚时可恢复部署前后端镜像或重新部署上一审核版本，不需要数据库回滚。远端仍保留部署前约 6 小时生成的未标记镜像和更早的显式 rollback 镜像；未在本次任务中删除镜像。
- 按用户要求，所有实现修改继续保留在本地 `1.0.1-snapshot` 工作树中，不创建提交，等待人工复核。
